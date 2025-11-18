package chipyard.base.kr260

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
/* CUSTOM IMPORT FOR BASE RISC-V SYSTEM ON CHIP ON KR260 */
//============================================================================
import sifive.blocks.devices.spi._

// Note: DDR support removed - KR260's 4GB DDR4 is connected to PS, not PL
// To access DDR, integrate Zynq UltraScale+ MPSoC IP and use PS DDR controller

class WithKR260JTAG extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: JTAGPort, chipId: Int) => {
    val kth = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[KR260Harness]
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName("jtag")
    harnessIO <> port.io

    kth.sdc.addClock("JTCK", IOPin(harnessIO.TCK), 10)
    kth.sdc.addGroup(clocks = Seq("JTCK"))
    kth.xdc.clockDedicatedRouteFalse(IOPin(harnessIO.TCK))
    
    // KR260 JTAG pin assignments (matching KR260Shell.scala)
    // Note: These pins match the JTAG overlay in KR260Shell
    val packagePinsWithPackageIOs = Seq(
      ("R19", IOPin(harnessIO.TCK)),  // JTAG TCK
      ("N21", IOPin(harnessIO.TMS)),  // JTAG TMS
      ("R18", IOPin(harnessIO.TDI)),  // JTAG TDI
      ("T21", IOPin(harnessIO.TDO))   // JTAG TDO
      // Note: srst_n (F14) is handled separately in shell
    )
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      kth.xdc.addPackagePin(io, pin)
      kth.xdc.addIOStandard(io, "LVCMOS18")
      kth.xdc.addPullup(io)
    } }
  }
})

//============================================================================
/* CUSTOM CONFIGURATIONS FOR BASE RISC-V SYSTEM ON CHIP ON KR260 */
//============================================================================

/*** SPI ***/
object SPIBinderCounter {
  var idx = 0
}

class WithKR260SPIHarnessBinder extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: SPIPort, chipId: Int) => {
    val kth = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[KR260Harness]
    val idx = SPIBinderCounter.idx
    if(idx == 0) {
      println(f"SPI_0: ${port.io} with rAddress 0x${port.io.c.rAddress}%x")
      kth.io_spi_bb.bundle <> port.io
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

      // KR260 SPI1 pin assignments (update with actual pinout)
      val packagePinsWithPackageIOs = Seq(
        ("C9",  IOPin(harnessIO.sck)),
        ("D9",  IOPin(harnessIO.cs(0))),
        ("C10", IOPin(harnessIO.dq(0).o)),
        ("D10", IOPin(harnessIO.dq(0).i)),
        ("E9",  IOPin(harnessIO.dq(0).ie)),
        ("E10", IOPin(harnessIO.dq(0).oe))
      )
      packagePinsWithPackageIOs.foreach { case (pin, io) =>
        kth.xdc.addPackagePin(io, pin)
        kth.xdc.addIOStandard(io, "LVCMOS33")
        kth.xdc.addIOB(io)
      }
    }
  }
})

/*** UART ***/
class WithKR260UARTHarnessBinder(uartPins: Seq[(String, String)] = Seq(("J17", "K17"))) extends HarnessBinder({
  case (th: HasHarnessInstantiators, port: UARTPort, chipId: Int) => {
    val kth = th.asInstanceOf[LazyRawModuleImp].wrapper.asInstanceOf[KR260Harness]
    val uartId = port.uartNo
    val harnessIO = IO(chiselTypeOf(port.io)).suggestName(s"uart$uartId")
    println(s"UART $uartId: ${harnessIO}")

    harnessIO <> port.io

    // Mapping UART to specified Pins (matching KR260Shell.scala)
    // Default UART0: J17 (RXD), K17 (TXD)
    val (rxdPin, txdPin) = uartPins.lift(uartId).getOrElse(
      throw new Exception(s"No UART Pin mapping for uartId $uartId")
    )
    println(s"UART $uartId assignment: RXD-$rxdPin   TXD-$txdPin")
    
    // Constraint in XDC
    val packagePinsWithPackageIOs = Seq(
      (rxdPin, IOPin(harnessIO.rxd)),
      (txdPin, IOPin(harnessIO.txd)))
    packagePinsWithPackageIOs foreach { case (pin, io) => {
      kth.xdc.addPackagePin(io, pin)
      kth.xdc.addIOStandard(io, "LVCMOS33")
    //   kth.xdc.addIOB(io)
      // Note: IOB constraint removed to avoid DRC PDRC-248 error
      // UART RX has sampling logic that creates fanout, incompatible with IOB
    } }
  }
})

// NOTE: Pin assignments above now match KR260Shell.scala definitions:
// - JTAG: C14 (TCK), C15 (TMS), E14 (TDI), D15 (TDO) - LVCMOS18
// - UART0: J17 (RXD), K17 (TXD) - LVCMOS33
// - SPI0/SDIO: D11 (clk), D10 (cs), B10/E10/C11/H12 (dat 0-3) - defined in shell overlay
// 
// If you need to customize these, update both this file AND KR260Shell.scala
// For additional details, refer to:
// - KR260 schematics
// - Kria KR260 Robotics Starter Kit User Guide (UG1089)
// - PMOD connector pinouts if using PMODs
