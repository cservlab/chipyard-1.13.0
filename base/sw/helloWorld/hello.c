// #include <stdio.h>
#include <stdint.h>
#include <riscv-pk/encoding.h>
#include "include/platform.h"
#include "kprintf.h"

#define REG32(p, i)	((p)[(i) >> 2])

int main(int argc, char **arv) {
  
  REG32(uart, UART_REG_TXCTRL) = UART_TXEN;

  uint32_t mhartid = read_csr(mhartid);

  kprintf("Hello world from core %c!!!\r\n", mhartid + 48);

  return 0;
}
