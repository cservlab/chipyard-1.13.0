// See LICENSE for license details.
package chipyard.base.kr260

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
/* CUSTOM IMPORT FOR BASE RISC-V SYSTEM ON CHIP ON KR260 */
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
import scala.sys.process._
import chipyard.config._


// don't use FPGAShell's DesignKey
class WithNoDesignKey extends Config((site, here, up) => {
  case DesignKey => (p: Parameters) => new SimpleLazyRawModule()(p)
})

//============================================================================
/* CUSTOM CONFIGURATIONS FOR BASE RISC-V SYSTEM ON CHIP ON KR260 */
//============================================================================

class WithKR260BootROM(isAsicCompatible: Boolean=false) extends Config((site, here, up) => {
  case BootROMLocated(x) => up(BootROMLocated(x)).map { p =>
    println("Using Bootrom on KR260 located at " + x.location)
    val freqMHz = (site(SystemBusKey).dtsFrequency.get / (1000 * 1000)).toLong

    var bootRomDir = "base/src/main/resources/kr260/sdboot"
    if(isAsicCompatible) {
      bootRomDir = "base/src/main/resources/kr260/sdboot-scratchpad"
    }
    // Make sure that the bootrom is always rebuilt
    val clean = s"make -C $bootRomDir clean"
    require (clean.! == 0, "Failed to clean")
    val make = s"make -C $bootRomDir PBUS_CLK=${freqMHz} bin"
    require (make.! == 0, "Failed to build bootrom")

    // Set the bootrom parameters
    p.copy(address=0x10000 /*default*/, size = 0x2000 /*8KB*/ , hang = 0x10000, contentFileName = s"$bootRomDir/build/sdboot.bin")
  }
})

class WithSystemModifications extends Config((site, here, up) => {
  case DTSTimebase => BigInt{(1e6).toLong}
  case DesignKey => (p: Parameters) => new SimpleLazyModule()(p) // don't use FPGAShell's DesignKey
  case SerialTLKey => Nil // remove serialized tl port
})

class WithScratchpadAsRAM(sizeKB: Int) extends Config(
  new WithNoMemPort ++
  new testchipip.soc.WithMbusScratchpad(base=0x80000000L, size=(sizeKB<<10))
)

class WithDefaultPeripherals extends Config((site, here, up) => {
  case PeripheryUARTKey => List(
    UARTParams(address = BigInt(0x64000000L)), // default UART0
  )
  case PeripherySPIKey => List(
    SPIParams(rAddress = BigInt(0x64001000L)), // default SPI0
  )
})

// KR260 Clock Configuration
// Input clock: 25MHz on pin C3
// Clocking wizard multiplies to desired frequency (default 50MHz)
case object KR260PLLFreqKey extends Field[Double](50.0) // Default 50MHz output from clocking wizard

class WithBaseKR260Tweaks(freqMHz: Double = 50, sizeKB: Int = 64) extends Config(
  // Set the PLL output frequency for the clocking wizard
  new Config((site, here, up) => {
    case KR260PLLFreqKey => freqMHz // Set system clock frequency output from clocking wizard
  }) ++
  
  // Clock config - KR260 has 25MHz input, clocking wizard generates system clock
  new chipyard.harness.WithHarnessBinderClockFreqMHz(freqMHz) ++
  new chipyard.config.WithUniformBusFrequencies(freqMHz) ++
  new chipyard.harness.WithAllClocksFromHarnessClockInstantiator ++
  new chipyard.clocking.WithPassthroughClockGenerator ++

  // Peripherals
  new WithDefaultPeripherals ++
  new WithKR260UARTHarnessBinder ++ // UART ports
  new WithKR260SPIHarnessBinder ++ // SPI ports
  new WithKR260JTAG ++ // JTAG port

  // Custom MMIO configurations
  // new custom_mmio.crypto.upt.WithUPT(BigInt(0x70001000L), platform="kr260") ++

  // Memory Configuration - KR260 uses on-chip scratchpad memory
  // Note: KR260's 4GB DDR4 is connected to PS (Processing System), not PL (Programmable Logic)
  // To access the DDR, you would need to integrate the Zynq UltraScale+ MPSoC IP
  new WithScratchpadAsRAM(sizeKB) ++

  new WithKR260BootROM(isAsicCompatible=true) ++

  // System modifications
  new WithSystemModifications ++ // Check whether we need to modify the system
  new freechips.rocketchip.subsystem.WithoutTLMonitors ++ // no monitors
  new chipyard.config.WithBroadcastManager // no L2
)

// Base configuration for RV32I on KR260 with 32KB scratchpad at 50MHz (default)
// L1 caches reduced to 2KB each to save BRAM resources
class BaseRocketKR260Config extends Config(
  new freechips.rocketchip.rocket.WithL1ICacheSets(8) ++  // 2KB I-Cache (8 sets × 4 ways × 64B = 2KB)
  new freechips.rocketchip.rocket.WithL1DCacheSets(8) ++  // 2KB D-Cache (8 sets × 4 ways × 64B = 2KB)
  new WithBaseKR260Tweaks(freqMHz=50, sizeKB=16) ++
  new freechips.rocketchip.rocket.WithNRV32ICores(1) ++
  new chipyard.config.AbstractConfig
)

// RV32I configuration with larger 128KB scratchpad at 75MHz
class RocketKR260Config extends Config(
  new WithBaseKR260Tweaks(freqMHz=75, sizeKB=128) ++
  new freechips.rocketchip.rocket.WithNRV32ICores(1) ++
  new chipyard.config.AbstractConfig
)

// RV64GC configuration on KR260 with higher performance and larger memory
class RocketKR260HighPerfConfig extends Config(
  new WithBaseKR260Tweaks(freqMHz=125, sizeKB=256) ++
  new chipyard.config.WithBroadcastManager ++ // no L2
  new chipyard.RocketConfig
)

// Dual-core RV32I configuration on KR260
class DualCoreRocketKR260Config extends Config(
  new WithBaseKR260Tweaks(freqMHz=100, sizeKB=128) ++
  new freechips.rocketchip.rocket.WithNRV32ICores(2) ++
  new chipyard.config.AbstractConfig
)

