package chipyard.base.kr260

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
/* CUSTOM IMPORT FOR BASE RISC-V SYSTEM ON CHIP ON KR260 */
//============================================================================
import sifive.blocks.devices.uart.{PeripheryUARTKey, UARTPortIO}
import sifive.blocks.devices.spi.{PeripherySPIKey, SPIPortIO}

// NOTE: This harness assumes a KR260Shell exists in base-fpga-shells
// You will need to create a KR260Shell.scala file similar to Arty100TShell.scala
// For now, this is a template that extends a generic shell
class KR260Harness(override implicit val p: Parameters) extends KR260Shell {
  def dp = designParameters

  val clockOverlay = dp(ClockInputOverlayKey).map(_.place(ClockInputDesignInput())).head
  val harnessSysPLL = dp(PLLFactoryKey)
  val harnessSysPLLNode = harnessSysPLL()
  val dutFreqMHz = (dp(SystemBusKey).dtsFrequency.get / (1000 * 1000)).toInt
  val dutClock = ClockSinkNode(freqMHz = dutFreqMHz)
  println(s"KR260 FPGA Base Clock Freq: ${dutFreqMHz} MHz")
  val dutWrangler = LazyModule(new ResetWrangler())
  val dutGroup = ClockGroup()
  dutClock := dutWrangler.node := dutGroup := harnessSysPLLNode

  harnessSysPLLNode := clockOverlay.overlayOutput.node

  // DDR support removed - KR260's DDR4 is connected to PS (Processing System), not PL
  // All configurations use on-chip scratchpad memory
  // To access the 4GB DDR4, you need to integrate Zynq UltraScale+ MPSoC IP

  val ledOverlays = dp(LEDOverlayKey).map(_.place(LEDDesignInput()))
  val all_leds = ledOverlays.map(_.overlayOutput.led)
  // KR260 has only 2 LEDs, so use 1 for status and 1 for reset indicator
  val status_leds = all_leds.take(1)
  val other_leds = all_leds.drop(1)

  // CUSTOM CODE 
  /*** SPI (SDIO) ***/
  val io_spi_bb = BundleBridgeSource(() => (new SPIPortIO(dp(PeripherySPIKey).head)))
  dp(SPIOverlayKey).head.place(SPIDesignInput(dp(PeripherySPIKey).head, io_spi_bb))
  // CUSTOM CODE END

  override lazy val module = new HarnessLikeImpl

  class HarnessLikeImpl extends Impl with HasHarnessInstantiators {
    all_leds.foreach(_ := DontCare)
    clockOverlay.overlayOutput.node.out(0)._1.reset := ~resetPin

    val clk_25mhz = clockOverlay.overlayOutput.node.out.head._1.clock

    // Blink the status LED for sanity (KR260 has 2 LEDs: 1 for blink status, 1 for reset indicator)
    withClockAndReset(clk_25mhz, dutClock.in.head._1.reset) {
      val period = (BigInt(25) << 20) / status_leds.size  // KR260 has 25MHz input clock on pin C3
      val counter = RegInit(0.U(log2Ceil(period).W))
      val on = RegInit(0.U(log2Ceil(status_leds.size).W))
      status_leds.zipWithIndex.map { case (o,s) => o := on === s.U }
      counter := Mux(counter === (period-1).U, 0.U, counter + 1.U)
      when (counter === 0.U) {
        on := Mux(on === (status_leds.size-1).U, 0.U, on + 1.U)
      }
    }

    // Use the second LED to show reset status
    other_leds(0) := resetPin

    harnessSysPLL.plls.foreach(_._1.getReset.get := pllReset)

    def referenceClockFreqMHz = dutFreqMHz
    def referenceClock = dutClock.in.head._1.clock
    def referenceReset = dutClock.in.head._1.reset
    def success = { require(false, "Unused"); false.B }

    childClock := harnessBinderClock
    childReset := harnessBinderReset

    instantiateChipTops()
  }
}
