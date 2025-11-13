package chipyard.base.arty100t

import chisel3._
import chisel3.util._
import freechips.rocketchip.diplomacy._
import org.chipsalliance.cde.config.{Parameters}
import freechips.rocketchip.tilelink._
import freechips.rocketchip.prci._
import freechips.rocketchip.subsystem.{SystemBusKey}

import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.shell._
import sifive.fpgashells.clocks._
import sifive.fpgashells.ip.xilinx.{IBUF, PowerOnResetFPGAOnly}

import sifive.blocks.devices.uart._

import chipyard._
import chipyard.harness._

//============================================================================
/* CUSTOM IMPORT FOR BASE RISC-V SYSTEM ON CHIP ON ARTY100T */
//============================================================================
import sifive.blocks.devices.uart.{PeripheryUARTKey, UARTPortIO}
import sifive.blocks.devices.spi.{PeripherySPIKey, SPIPortIO}


class Arty100THarness(override implicit val p: Parameters) extends Arty100TShell {
  def dp = designParameters

  val clockOverlay = dp(ClockInputOverlayKey).map(_.place(ClockInputDesignInput())).head
  val harnessSysPLL = dp(PLLFactoryKey)
  val harnessSysPLLNode = harnessSysPLL()
  val dutFreqMHz = (dp(SystemBusKey).dtsFrequency.get / (1000 * 1000)).toInt
  val dutClock = ClockSinkNode(freqMHz = dutFreqMHz)
  println(s"Arty100T FPGA Base Clock Freq: ${dutFreqMHz} MHz")
  val dutWrangler = LazyModule(new ResetWrangler())
  val dutGroup = ClockGroup()
  dutClock := dutWrangler.node := dutGroup := harnessSysPLLNode

  harnessSysPLLNode := clockOverlay.overlayOutput.node

  val (ddrOverlay, ddrClient, ddrBlockDuringReset) =
    if (dp(ExtTLMem) == None) {
      println(s"ExtTLMem is not defined")
      (null, null, null)
    } else {
      println(s"ExtTLMem is defined")
      val overlay = dp(DDROverlayKey).head.place(
        DDRDesignInput(dp(ExtTLMem).get.master.base, dutWrangler.node, harnessSysPLLNode)
      ).asInstanceOf[DDRArtyPlacedOverlay]
      val client = TLClientNode(Seq(TLMasterPortParameters.v1(Seq(TLMasterParameters.v1(
        name = "chip_ddr",
        sourceId = IdRange(0, 1 << dp(ExtTLMem).get.master.idBits)
      )))))
      val blockDuringReset = LazyModule(new TLBlockDuringReset(4))
      overlay.overlayOutput.ddr := blockDuringReset.node := client
      (overlay, client, blockDuringReset)
    }


  val ledOverlays = dp(LEDOverlayKey).map(_.place(LEDDesignInput()))
  val all_leds = ledOverlays.map(_.overlayOutput.led)
  val status_leds = all_leds.take(3)
  val other_leds = all_leds.drop(3)

  // CUSTOM CODE 
  /*** SDIO ***/
  val io_spi_bb = BundleBridgeSource(() => (new SPIPortIO(dp(PeripherySPIKey).head)))
  dp(SPIOverlayKey).head.place(SPIDesignInput(dp(PeripherySPIKey).head, io_spi_bb))
  // CUSTOM CODE END

  override lazy val module = new HarnessLikeImpl

  class HarnessLikeImpl extends Impl with HasHarnessInstantiators {
    all_leds.foreach(_ := DontCare)
    clockOverlay.overlayOutput.node.out(0)._1.reset := ~resetPin

    val clk_100mhz = clockOverlay.overlayOutput.node.out.head._1.clock

    // Blink the status LEDs for sanity
    withClockAndReset(clk_100mhz, dutClock.in.head._1.reset) {
      val period = (BigInt(100) << 20) / status_leds.size
      val counter = RegInit(0.U(log2Ceil(period).W))
      val on = RegInit(0.U(log2Ceil(status_leds.size).W))
      status_leds.zipWithIndex.map { case (o,s) => o := on === s.U }
      counter := Mux(counter === (period-1).U, 0.U, counter + 1.U)
      when (counter === 0.U) {
        on := Mux(on === (status_leds.size-1).U, 0.U, on + 1.U)
      }
    }

    other_leds(0) := resetPin

    harnessSysPLL.plls.foreach(_._1.getReset.get := pllReset)

    def referenceClockFreqMHz = dutFreqMHz
    def referenceClock = dutClock.in.head._1.clock
    def referenceReset = dutClock.in.head._1.reset
    def success = { require(false, "Unused"); false.B }

    childClock := harnessBinderClock
    childReset := harnessBinderReset

    if(ddrOverlay != null) {
      // Connect MIG and DDR block clocks and resets
      ddrOverlay.mig.module.clock := harnessBinderClock
      ddrOverlay.mig.module.reset := harnessBinderReset
      ddrBlockDuringReset.module.clock := harnessBinderClock
      ddrBlockDuringReset.module.reset := harnessBinderReset.asBool || !ddrOverlay.mig.module.io.port.init_calib_complete

      other_leds(6) := ddrOverlay.mig.module.io.port.init_calib_complete
    }else{
      other_leds(6) := true.B // If no DDR, just turn on the LED
    }



    instantiateChipTops()
  }
}
