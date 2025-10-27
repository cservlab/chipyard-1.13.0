# file: step_mult.gdb

#target remote :3333

#set  disassemble-next-line on
#show disassemble-next-line
#set pagination off

#load

#set $pc=0x80000000

define step_mult
    set $step_mult_max = 1000
    if $argc >= 1
        set $step_mult_max = $arg0
    end

    set $step_mult_count = 0
    while ($step_mult_count < $step_mult_max)
        set $step_mult_count = $step_mult_count + 1
        printf "step #%d\n", $step_mult_count
        si
    end
end
