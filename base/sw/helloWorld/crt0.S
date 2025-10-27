// See LICENSE.Sifive for license details.
#include <platform.h>
#include <smp.h>
#include "common.h"

  .section .text.init
  .option norvc
  .globl _prog_start
_prog_start:
  beq a0, zero, _main
  j _loopback
_main:
  csrw mie, s2
  li sp, (PAYLOAD_DEST + 0x1ff0)
  call main
  wfi
_loopback:
  j _loopback
