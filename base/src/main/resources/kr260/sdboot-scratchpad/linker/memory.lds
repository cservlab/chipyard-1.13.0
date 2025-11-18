MEMORY
{
  bootrom_mem (rx) : ORIGIN = 0x10000, LENGTH = 0x2000
  /* memory_mem (rwx) : ORIGIN = 0x80000000, LENGTH = 0x40000000 */ /* Original: 1GB for DDR */
  memory_mem (rwx) : ORIGIN = 0x80000000, LENGTH = 0x4000 /* Modified: 16KB for on-chip scratchpad */
}
