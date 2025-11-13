package chipyard.base.arty100t

import chisel3._

import freechips.rocketchip.jtag.{JTAGIO}
import freechips.rocketchip.subsystem.{PeripheryBusKey}
import freechips.rocketchip.tilelink.{TLBundle}
import freechips.rocketchip.diplomacy.{LazyRawModuleImp}
import org.chipsalliance.diplomacy.nodes.{HeterogeneousBag}
import sifive.blocks.devices.uart.{UARTPortIO, UARTParams}
import sifive.blocks.devices.jtag.{JTAGPins, JTAGPinsFromPort}
import sifive.blocks.devices.pinctrl.{BasePin}
import sifive.fpgashells.shell._
import sifive.fpgashells.ip.xilinx._
import sifive.fpgashells.shell.xilinx._
import sifive.fpgashells.clocks._
import chipyard._
import chipyard.harness._
import chipyard.iobinders._
import testchipip.serdes._

//============================================================================
/* CUSTOM IMPORT FOR BASE RISC-V SYSTEM ON CHIP ON ARTY100T */
//============================================================================
import sifive.blocks.devices.spi._

class WithArty100TUARTTSI extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: UARTTSIPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val harnessIO = IO(new UARTPortIO(port.io.uartParams)).suggestName("uart_tsi")
    harnessIO <> port.io.uart
    val packagePinsWithPackageIOs = Seq(
      ("A9" , IOPin(harnessIO.rxd)),
      ("D10", IOPin(harnessIO.txd)))
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      ath.xdc.addPackagePin(io, pin)
      ath.xdc.addIOStandard(io, "LVCMOS33")
      ath.xdc.addIOB(io)
    } }

    ath.other_leds(1) := port.io.dropped
    ath.other_leds(9) := port.io.tsi2tl_state(0)
    ath.other_leds(10) := port.io.tsi2tl_state(1)
    ath.other_leds(11) := port.io.tsi2tl_state(2)
    ath.other_leds(12) := port.io.tsi2tl_state(3)
  }
})

class WithArty100TDDRTL extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: TLMemPort, chipId: Int) => {
    val artyTh = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    if(artyTh.ddrClient == null) {
      println("DDR client is null. DDR HarnessBinder is no longer needed.")
    }else{
      println("DDR client is present. Connecting DDR client to TLMemPort.")
      val bundles = artyTh.ddrClient.out.map(_._1)
      val ddrClientBundle = Wire(new HeterogeneousBag(bundles.map(_.cloneType)))
      bundles.zip(ddrClientBundle).foreach { case (bundle, io) => bundle <> io }
      ddrClientBundle <> port.io
    }
  }
})


// Uses PMOD JA/JB
class WithArty100TSerialTLToGPIO extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: SerialTLPort, chipId: Int) => {
    val artyTh = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName("serial_tl")
    harnessIO <> port.io

    harnessIO match {
      case io: DecoupledPhitIO => {
        val clkIO = io match {
          case io: InternalSyncPhitIO => IOPin(io.clock_out)
          case io: ExternalSyncPhitIO => IOPin(io.clock_in)
        }
        val packagePinsWithPackageIOs = Seq(
          ("G13", clkIO),
          ("B11", IOPin(io.out.valid)),
          ("A11", IOPin(io.out.ready)),
          ("D12", IOPin(io.in.valid)),
          ("D13", IOPin(io.in.ready)),
          ("B18", IOPin(io.out.bits.phit, 0)),
          ("A18", IOPin(io.out.bits.phit, 1)),
          ("K16", IOPin(io.out.bits.phit, 2)),
          ("E15", IOPin(io.out.bits.phit, 3)),
          ("E16", IOPin(io.in.bits.phit, 0)),
          ("D15", IOPin(io.in.bits.phit, 1)),
          ("C15", IOPin(io.in.bits.phit, 2)),
          ("J17", IOPin(io.in.bits.phit, 3))
        )
        packagePinsWithPackageIOs foreach { case (pin, io) => {
          artyTh.xdc.addPackagePin(io, pin)
          artyTh.xdc.addIOStandard(io, "LVCMOS33")
        }}

        // Don't add IOB to the clock, if its an input
        io match {
          case io: InternalSyncPhitIO => packagePinsWithPackageIOs foreach { case (pin, io) => {
            artyTh.xdc.addIOB(io)
          }}
          case io: ExternalSyncPhitIO => packagePinsWithPackageIOs.drop(1).foreach { case (pin, io) => {
            artyTh.xdc.addIOB(io)
          }}
        }

        artyTh.sdc.addClock("ser_tl_clock", clkIO, 100)
        artyTh.sdc.addGroup(pins = Seq(clkIO))
        artyTh.xdc.clockDedicatedRouteFalse(clkIO)
      }
    }
  }
})

// Maps the UART device to the on-board USB-UART
class WithArty100TUART(rxdPin: String = "A9", txdPin: String = "D10") extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: UARTPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName("uart")
    harnessIO <> port.io
    val packagePinsWithPackageIOs = Seq(
      (rxdPin, IOPin(harnessIO.rxd)),
      (txdPin, IOPin(harnessIO.txd)))
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      ath.xdc.addPackagePin(io, pin)
      ath.xdc.addIOStandard(io, "LVCMOS33")
      ath.xdc.addIOB(io)
    } }
  }
})

// Maps the UART device to PMOD JD pins 3/7
class WithArty100TPMODUART extends WithArty100TUART("G2", "F3")

class WithArty100TJTAG extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: JTAGPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName("jtag")
    harnessIO <> port.io

    ath.sdc.addClock("JTCK", IOPin(harnessIO.TCK), 10)
    ath.sdc.addGroup(clocks = Seq("JTCK"))
    ath.xdc.clockDedicatedRouteFalse(IOPin(harnessIO.TCK))
    val packagePinsWithPackageIOs = Seq(
      ("F4", IOPin(harnessIO.TCK)),
      ("D2", IOPin(harnessIO.TMS)),
      ("E2", IOPin(harnessIO.TDI)),
      ("D4", IOPin(harnessIO.TDO))
    )
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      ath.xdc.addPackagePin(io, pin)
      ath.xdc.addIOStandard(io, "LVCMOS33")
      ath.xdc.addPullup(io)
    } }
  }
})

//============================================================================
/* CUSTOM CONFIGURATIONS FOR BASE RISC-V SYSTEM ON CHIP ON ARTY100T */
//============================================================================

/*** SPI ***/
object SPIBinderCounter {
  var idx = 0
}

class WithArty100TSPIHarnessBinder extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: SPIPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val idx = SPIBinderCounter.idx
    if(idx == 0) {
      println(f"SPI_0: ${port.io} with rAddress 0x${port.io.c.rAddress}%x")
      ath.io_spi_bb.bundle <> port.io
      SPIBinderCounter.idx += 1
    }else if(idx == 1) {
      println(f"SPI_1: ${port.io} with rAddress 0x${port.io.c.rAddress}%x")
      val harnessIO = IO(new SPIPortIOSingle(port.io.c)).suggestName("spi1")
      // Manual connection since dq width differs
      harnessIO.sck := port.io.sck
      harnessIO.cs  := port.io.cs
      // Connect only dq(0)
      harnessIO.dq(0).o  := port.io.dq(0).o
      port.io.dq(0).i := harnessIO.dq(0).i
      harnessIO.dq(0).ie := port.io.dq(0).ie
      harnessIO.dq(0).oe := port.io.dq(0).oe

      // Tie off unused dq lines on the DUT side
      port.io.dq(1).i := false.B
      port.io.dq(2).i := false.B
      port.io.dq(3).i := false.B

      // Assign pins as needed
      val packagePinsWithPackageIOs = Seq(
        ("U12", IOPin(harnessIO.sck)),
        ("V12", IOPin(harnessIO.cs(0))),
        ("V10", IOPin(harnessIO.dq(0).o)),
        ("V11", IOPin(harnessIO.dq(0).i)),
        ("U14", IOPin(harnessIO.dq(0).ie)),
        ("V14", IOPin(harnessIO.dq(0).oe))
      )
      packagePinsWithPackageIOs.foreach { case (pin, io) =>
        ath.xdc.addPackagePin(io, pin)
        ath.xdc.addIOStandard(io, "LVCMOS33")
        ath.xdc.addIOB(io)
    }
    }
  }
})

class WithArty100TUARTHarnessBinder(uartPins: Seq[(String, String)] = Seq(("A9", "D10"))) extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: UARTPort, chipId: Int) => {
    val ath = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[Arty100THarness]
    val uartId = port.uartNo
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName(s"uart$uartId")
    //print harressIO name
    println(s"UART $uartId: ${harnessIO}")

    harnessIO <> port.io

    // Mapping UART to specified Pins is done here
    val (rxdPin, txdPin) = uartPins.lift(uartId).getOrElse(
      throw new Exception(s"No UART Pin mapping for uartId $uartId")
    )
    println(s"UART $uartId assignment: RXD-$rxdPin   TXD-$txdPin")
    
    // Constraint in XDC
    val packagePinsWithPackageIOs = Seq(
      (rxdPin, IOPin(harnessIO.rxd)),
      (txdPin, IOPin(harnessIO.txd)))
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      ath.xdc.addPackagePin(io, pin)
      ath.xdc.addIOStandard(io, "LVCMOS33")
      ath.xdc.addIOB(io)
    } }
  }
})

// Maps the UART device to PMOD JD pins 3/7
class WithArty100TPMODUARTHarnessBinder extends WithArty100TUARTHarnessBinder(Seq(("G2", "F3")))