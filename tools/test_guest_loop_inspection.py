# SPDX-License-Identifier: GPL-2.0-or-later
import unittest
from inspect_guest_loops import decode_blocks, inspect


class LoopInspectionTest(unittest.TestCase):
    def block(self):
        return dict(guestLinear='0x0', code32=True, directSeconds=1, callerSeconds=2,
                    instructions=[dict(guestLinear='0x0', opcodeByte='0x49'),
                                  dict(guestLinear='0x1', opcodeByte='0x75')])

    def test_counter_loop_is_only_a_candidate(self):
        result = inspect([self.block()], bytes.fromhex('49 75 fd'))
        self.assertEqual(result['validatedBlockForms'], 1)
        self.assertEqual(len(result['candidates']), 1)
        self.assertEqual(result['candidates'][0]['start'], '0x0')
        self.assertNotIn('isPolling', result['candidates'][0])

    def test_changed_opcode_is_rejected(self):
        valid, rejected = decode_blocks([self.block()], bytes.fromhex('90 75 fd'))
        self.assertFalse(valid)
        self.assertEqual(len(rejected), 1)

    def test_changed_instruction_boundaries_are_rejected(self):
        block = self.block()
        block['instructions'][0]['opcodeByte'] = '0x66'
        valid, rejected = decode_blocks([block], bytes.fromhex('66 75 fd'))
        self.assertFalse(valid)
        self.assertIn('boundaries', rejected[0]['reason'])


if __name__ == '__main__':
    unittest.main()
