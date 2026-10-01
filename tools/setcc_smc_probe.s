.intel_syntax noprefix
.code16
.global _start
_start:
mov dx,offset filename
xor cx,cx
mov ah,0x3c
int 0x21
mov word ptr [handle],ax
round:
mov bx,word ptr [idx]
mov ax,word ptr [flagtable+bx]
mov word ptr [flagword],ax
call external_0
call inline_0
call external_1
call inline_1
call external_2
call inline_2
call external_3
call inline_3
call external_4
call inline_4
call external_5
call inline_5
call external_6
call inline_6
call external_7
call inline_7
call external_8
call inline_8
call external_9
call inline_9
call external_10
call inline_10
call external_11
call inline_11
call external_12
call inline_12
call external_13
call inline_13
call external_14
call inline_14
call external_15
call inline_15
add word ptr [idx],2
cmp word ptr [idx],128
jne round
mov bx,word ptr [handle]
mov ah,0x3e
int 0x21
mov ax,0x4c00
int 0x21
external_0:
call target_0
push word ptr [flagword]
popf
.byte 0x0f,144,0x06
.word target_0+1
call target_0
jmp write
target_0:
mov al,0x7f
ret
inline_0:
push word ptr [flagword]
popf
.byte 0x0f,144,0x06
.word next_0+1
next_0:
mov al,0x7f
jmp write
external_1:
call target_1
push word ptr [flagword]
popf
.byte 0x0f,145,0x06
.word target_1+1
call target_1
jmp write
target_1:
mov al,0x7f
ret
inline_1:
push word ptr [flagword]
popf
.byte 0x0f,145,0x06
.word next_1+1
next_1:
mov al,0x7f
jmp write
external_2:
call target_2
push word ptr [flagword]
popf
.byte 0x0f,146,0x06
.word target_2+1
call target_2
jmp write
target_2:
mov al,0x7f
ret
inline_2:
push word ptr [flagword]
popf
.byte 0x0f,146,0x06
.word next_2+1
next_2:
mov al,0x7f
jmp write
external_3:
call target_3
push word ptr [flagword]
popf
.byte 0x0f,147,0x06
.word target_3+1
call target_3
jmp write
target_3:
mov al,0x7f
ret
inline_3:
push word ptr [flagword]
popf
.byte 0x0f,147,0x06
.word next_3+1
next_3:
mov al,0x7f
jmp write
external_4:
call target_4
push word ptr [flagword]
popf
.byte 0x0f,148,0x06
.word target_4+1
call target_4
jmp write
target_4:
mov al,0x7f
ret
inline_4:
push word ptr [flagword]
popf
.byte 0x0f,148,0x06
.word next_4+1
next_4:
mov al,0x7f
jmp write
external_5:
call target_5
push word ptr [flagword]
popf
.byte 0x0f,149,0x06
.word target_5+1
call target_5
jmp write
target_5:
mov al,0x7f
ret
inline_5:
push word ptr [flagword]
popf
.byte 0x0f,149,0x06
.word next_5+1
next_5:
mov al,0x7f
jmp write
external_6:
call target_6
push word ptr [flagword]
popf
.byte 0x0f,150,0x06
.word target_6+1
call target_6
jmp write
target_6:
mov al,0x7f
ret
inline_6:
push word ptr [flagword]
popf
.byte 0x0f,150,0x06
.word next_6+1
next_6:
mov al,0x7f
jmp write
external_7:
call target_7
push word ptr [flagword]
popf
.byte 0x0f,151,0x06
.word target_7+1
call target_7
jmp write
target_7:
mov al,0x7f
ret
inline_7:
push word ptr [flagword]
popf
.byte 0x0f,151,0x06
.word next_7+1
next_7:
mov al,0x7f
jmp write
external_8:
call target_8
push word ptr [flagword]
popf
.byte 0x0f,152,0x06
.word target_8+1
call target_8
jmp write
target_8:
mov al,0x7f
ret
inline_8:
push word ptr [flagword]
popf
.byte 0x0f,152,0x06
.word next_8+1
next_8:
mov al,0x7f
jmp write
external_9:
call target_9
push word ptr [flagword]
popf
.byte 0x0f,153,0x06
.word target_9+1
call target_9
jmp write
target_9:
mov al,0x7f
ret
inline_9:
push word ptr [flagword]
popf
.byte 0x0f,153,0x06
.word next_9+1
next_9:
mov al,0x7f
jmp write
external_10:
call target_10
push word ptr [flagword]
popf
.byte 0x0f,154,0x06
.word target_10+1
call target_10
jmp write
target_10:
mov al,0x7f
ret
inline_10:
push word ptr [flagword]
popf
.byte 0x0f,154,0x06
.word next_10+1
next_10:
mov al,0x7f
jmp write
external_11:
call target_11
push word ptr [flagword]
popf
.byte 0x0f,155,0x06
.word target_11+1
call target_11
jmp write
target_11:
mov al,0x7f
ret
inline_11:
push word ptr [flagword]
popf
.byte 0x0f,155,0x06
.word next_11+1
next_11:
mov al,0x7f
jmp write
external_12:
call target_12
push word ptr [flagword]
popf
.byte 0x0f,156,0x06
.word target_12+1
call target_12
jmp write
target_12:
mov al,0x7f
ret
inline_12:
push word ptr [flagword]
popf
.byte 0x0f,156,0x06
.word next_12+1
next_12:
mov al,0x7f
jmp write
external_13:
call target_13
push word ptr [flagword]
popf
.byte 0x0f,157,0x06
.word target_13+1
call target_13
jmp write
target_13:
mov al,0x7f
ret
inline_13:
push word ptr [flagword]
popf
.byte 0x0f,157,0x06
.word next_13+1
next_13:
mov al,0x7f
jmp write
external_14:
call target_14
push word ptr [flagword]
popf
.byte 0x0f,158,0x06
.word target_14+1
call target_14
jmp write
target_14:
mov al,0x7f
ret
inline_14:
push word ptr [flagword]
popf
.byte 0x0f,158,0x06
.word next_14+1
next_14:
mov al,0x7f
jmp write
external_15:
call target_15
push word ptr [flagword]
popf
.byte 0x0f,159,0x06
.word target_15+1
call target_15
jmp write
target_15:
mov al,0x7f
ret
inline_15:
push word ptr [flagword]
popf
.byte 0x0f,159,0x06
.word next_15+1
next_15:
mov al,0x7f
jmp write
write:
mov byte ptr [result],al
mov dx,offset result
mov cx,1
mov bx,word ptr [handle]
mov ah,0x40
int 0x21
ret
handle: .word 0
idx: .word 0
flagword: .word 0
result: .byte 0
filename: .asciz "RESULT.BIN"
flagtable:
.word 514
.word 515
.word 518
.word 519
.word 530
.word 531
.word 534
.word 535
.word 578
.word 579
.word 582
.word 583
.word 594
.word 595
.word 598
.word 599
.word 642
.word 643
.word 646
.word 647
.word 658
.word 659
.word 662
.word 663
.word 706
.word 707
.word 710
.word 711
.word 722
.word 723
.word 726
.word 727
.word 2562
.word 2563
.word 2566
.word 2567
.word 2578
.word 2579
.word 2582
.word 2583
.word 2626
.word 2627
.word 2630
.word 2631
.word 2642
.word 2643
.word 2646
.word 2647
.word 2690
.word 2691
.word 2694
.word 2695
.word 2706
.word 2707
.word 2710
.word 2711
.word 2754
.word 2755
.word 2758
.word 2759
.word 2770
.word 2771
.word 2774
.word 2775
