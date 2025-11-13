// See LICENSE for license details.
package chipyard.base.arty100t

import org.chipsalliance.cde.config._
import freechips.rocketchip.subsystem._
import freechips.rocketchip.devices.debug._
import freechips.rocketchip.devices.tilelink._
import org.chipsalliance.diplomacy._
import org.chipsalliance.diplomacy.lazymodule._
import freechips.rocketchip.system._
import freechips.rocketchip.tile._

import sifive.blocks.devices.uart._
import sifive.fpgashells.shell.{DesignKey}

import testchipip.serdes.{SerialTLKey}

import chipyard.{BuildSystem}

//============================================================================
/* CUSTOM IMPORT FOR BASE RISC-V SYSTEM ON CHIP ON ARTY100T */
//============================================================================
import freechips.rocketchip.devices.tilelink.BootROMLocated
import sifive.blocks.devices.spi.{PeripherySPIKey, SPIParams}
import sifive.blocks.devices.uart.{PeripheryUARTKey, UARTParams}
import sifive.blocks.devices.gpio.{PeripheryGPIOKey, GPIOParams}
import testchipip.boot.{BootAddrRegKey, BootAddrRegParams}
import testchipip.boot.{CustomBootPinKey, CustomBootPinParams}
import chipyard.{ExtTLMem}
import sifive.fpgashells.shell.DesignKey
import testchipip.serdes.{SerialTLKey}
import freechips.rocketchip.resources.{DTSTimebase}
import sifive.fpgashells.shell.xilinx.{ArtyDDRSize}
import scala.sys.process._
import chipyard.config._


// don't use FPGAShell's DesignKey
class WithNoDesignKey extends Config((site, here, up) => {
  case DesignKey => (p: Parameters) => new SimpleLazyRawModule()(p)
})

// By default, this uses the on-board USB-UART for the TSI-over-UART link
// The PMODUART HarnessBinder maps the actual UART device to JD pin
class WithArty100TTweaks(freqMHz: Double = 50) extends Config(
  new WithArty100TPMODUART ++
  new WithArty100TUARTTSI ++
  new WithArty100TDDRTL ++
  new WithArty100TJTAG ++
  new WithNoDesignKey ++
  new testchipip.tsi.WithUARTTSIClient ++
  new chipyard.harness.WithSerialTLTiedOff ++
  new chipyard.harness.WithHarnessBinderClockFreqMHz(freqMHz) ++
  new chipyard.config.WithUniformBusFrequencies(freqMHz) ++
  new chipyard.harness.WithAllClocksFromHarnessClockInstantiator ++
  new chipyard.clocking.WithPassthroughClockGenerator ++
  new chipyard.config.WithTLBackingMemory ++ // FPGA-shells converts the AXI to TL for us
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt(256) << 20) ++ // 256mb on ARTY
  new freechips.rocketchip.subsystem.WithoutTLMonitors)

class RocketArty100TConfig extends Config(
  new WithArty100TTweaks ++
  new chipyard.config.WithBroadcastManager ++ // no l2
  new chipyard.RocketConfig)

class NoCoresArty100TConfig extends Config(
  new WithArty100TTweaks ++
  new chipyard.config.WithBroadcastManager ++ // no l2
  new chipyard.NoCoresConfig)

// This will fail to close timing above 50 MHz
class BringupArty100TConfig extends Config(
  new WithArty100TSerialTLToGPIO ++
  new WithArty100TTweaks(freqMHz = 50) ++
  new testchipip.serdes.WithSerialTLPHYParams(testchipip.serdes.InternalSyncSerialPhyParams(freqMHz=50)) ++
  new chipyard.ChipBringupHostConfig)

//============================================================================
/* CUSTOM CONFIGURATIONS FOR BASE RISC-V SYSTEM ON CHIP ON ARTY100T */
//============================================================================

class WithArty100TBootROM(isAsicCompatible: Boolean=false) extends Config((site, here, up) => {
  case BootROMLocated(x) => up(BootROMLocated(x)).map { p =>
    println("Using Bootrom on Arty100T located at " + x.location)
    val freqMHz = (site(SystemBusKey).dtsFrequency.get / (1000 * 1000)).toLong

    var bootRomDir = "base/src/main/resources/arty100t/sdboot"
    if(isAsicCompatible) {
      bootRomDir = "base/src/main/resources/arty100t/sdboot-scratchpad"
    }
    // Make sure that the bootrom is always rebuilt
    val clean = s"make -C $bootRomDir clean"
    require (clean.! == 0, "Failed to clean")
    val make = s"make -C $bootRomDir PBUS_CLK=${freqMHz} bin"
    require (make.! == 0, "Failed to build bootrom")

    // Set the bootrom parameters
    p.copy(address=0x10000 /*default*/, size = 0x2000 /*4KB*/ , hang = 0x10000, contentFileName = s"$bootRomDir/build/sdboot.bin")
  }
})

class WithSystemModifications extends Config((site, here, up) => {
  case DTSTimebase => BigInt{(1e6).toLong}
  // case ExtMem => up(ExtMem, site).map(x => x.copy(master = x.master.copy(size = site(ArtyDDRSize)))) // set extmem to DDR size (note the size)
  case DesignKey => (p: Parameters) => new SimpleLazyModule()(p) // don't use FPGAShell's DesignKey
  case SerialTLKey => Nil // remove serialized tl port
})

class WithScratchpadAsRAM(sizeKB: Int) extends Config(
  new WithNoMemPort ++
  new testchipip.soc.WithMbusScratchpad(base=0x80000000L, size=(sizeKB<<10)) // 16KB
)
class WithDefaultDDRAsRAM() extends Config(
  new WithArty100TDDRTL ++
  new freechips.rocketchip.subsystem.WithExtMemSize(BigInt(256) << 20) ++ // 256mb on ARTY
  new chipyard.config.WithTLBackingMemory() // FPGA-shells converts the AXI to TL
)

class WithDefaultPeripherals extends Config((site, here, up) => {
  case PeripheryUARTKey => List(
    UARTParams(address = BigInt(0x64000000L)), // default UART0
  )
  case PeripherySPIKey => List(
    SPIParams(rAddress = BigInt(0x64001000L)), // default SPI0
  )
})

class WithBaseArty100TTweaks(freqMHz: Double = 50, isAsicCompatible: Boolean = false) extends Config(
  // Clock config
  new chipyard.harness.WithHarnessBinderClockFreqMHz(freqMHz) ++
  new chipyard.config.WithUniformBusFrequencies(freqMHz) ++
  new chipyard.harness.WithAllClocksFromHarnessClockInstantiator ++
  new chipyard.clocking.WithPassthroughClockGenerator ++

  // Peripherals
  new WithDefaultPeripherals ++
  new WithArty100TUARTHarnessBinder ++ // UART ports
  new WithArty100TSPIHarnessBinder ++ // SPI ports
  new WithArty100TJTAG ++ // JTAG port

  // Custom MMIO configurations
  
  // Memory Configurations
  (if (isAsicCompatible) {
    new WithScratchpadAsRAM(sizeKB=16)
  }else{
    new WithDefaultDDRAsRAM
  }) ++

  new WithArty100TBootROM(isAsicCompatible) ++

  // System modifications
  new WithSystemModifications ++ // Check whether we need to modify the system
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++ // no monitors
  new chipyard.config.WithBroadcastManager // no L2
)

class BaseRocketArty100TConfig extends Config(
  new WithBaseArty100TTweaks(isAsicCompatible=true) ++
  new freechips.rocketchip.rocket.WithNRV32ICores(1) ++
  new chipyard.config.AbstractConfig
)