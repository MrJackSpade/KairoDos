# SPDX-License-Identifier: GPL-2.0-or-later
import unittest
import struct
from analyze_host_instructions import summarize
from analyze_host_instructions import call_sequence
from analyze_host_instructions import block_link_sequence


def block(time, code, size=4):
    return dict(event='block', time=time, block=1, host=4096,
                size=size, hostCodeHex=code)


class AttributionTest(unittest.TestCase):
    def test_block_link_pointer_chase(self):
        code=struct.pack('<7I',0xd280000a,0xf2a0000a,0xf2c0000a,0xf2e0000a,
                         0xf940014c,0xf940098a,0xd61f0140)
        self.assertEqual([block_link_sequence(code,i*4) for i in range(7)],
                         ['link address setup']*4+['load target block','load target code','branch'])
        self.assertIsNone(block_link_sequence(code[:-4],0))
        # Different base register cannot be classified as the link pattern.
        altered=code[:20]+struct.pack('<I',0xf94009aa)+code[24:]
        self.assertIsNone(block_link_sequence(altered,20))

    def test_exact_call_sequence_and_target(self):
        target = 0x123456789abcdef0
        words = [op | (((target >> (16*i)) & 65535) << 5)
                 for i,op in enumerate((0xd280000a,0xf2a0000a,0xf2c0000a,0xf2e0000a))]
        code = struct.pack('<5I', *words, 0xd63f0140)
        for slot in range(5):
            self.assertEqual(call_sequence(code,slot*4), (slot,target))
        self.assertIsNone(call_sequence(code[:-4],0))
        self.assertIsNone(call_sequence(struct.pack('<I',0xd503201f)+code[4:],0))

    def test_same_address_different_translation_and_invalidated_gap(self):
        events = [block(1, '1f2003d5'), dict(event='clear',time=3,block=1),
                  block(5, '200040b9')]
        result = summarize(events, [dict(time=t,ip=4096,period=10**9) for t in (2,4,6)])
        self.assertEqual(result['directJitSeconds'], 2)
        self.assertEqual(result['familiesSeconds'], {'nop':1, 'load':1})

    def test_truncated_code_rejected(self):
        with self.assertRaises(ValueError):
            summarize([block(1,'1f20')], [dict(time=2,ip=4096,period=1)])

    def test_call_proximity_does_not_cross_block_boundary(self):
        samples = [dict(time=2,ip=4100,period=10**9)]
        result = summarize([block(1,'40013fd6200040b9',8)],samples)
        self.assertEqual(result['withinEightInstructionsAfterCall'],
                         [dict(distance=1,mnemonic='ldr',seconds=1)])
        result = summarize([block(1,'200040b9')], [dict(time=2,ip=4096,period=10**9)])
        self.assertEqual(result['withinEightInstructionsAfterCall'], [])


if __name__ == '__main__':
    unittest.main()
