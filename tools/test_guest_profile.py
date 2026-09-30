# SPDX-License-Identifier: GPL-2.0-or-later
import unittest
from analyze_guest_profile import Timeline, summarize


def block(time, identity, host, guest):
    return dict(event='block', time=time, serial=time, block=identity, host=host,
                size=16, guest=guest, csBase=0, code32=True,
                instructions=[[host, guest, 0x75]])


class TimelineTest(unittest.TestCase):
    def test_clear_and_address_reuse(self):
        events = [dict(event='begin',time=0,version=1), block(1,1,4096,100),
                  dict(event='clear',time=3,block=1),block(5,2,4096,200)]
        samples = [dict(time=t,ip=4100,period=10**9) for t in (2,4,6)]
        result = summarize(events,samples)
        self.assertEqual(result['directJitSeconds'],2)
        self.assertEqual(result['unattributedSeconds'],1)
        self.assertEqual({x['guestLinear'] for x in result['hotBlocks']},{'0x64','0xc8'})

    def test_linked_block_and_nearest_helper_caller(self):
        events=[block(1,1,4096,100),block(2,2,8192,200)]
        samples=[dict(time=3,ip=8196,period=10**9),
                 dict(time=4,ip=1,period=10**9,callchain=[8196,4100])]
        result=summarize(events,samples)
        self.assertEqual(len(result['hotBlocks']),1)
        self.assertEqual(result['hotBlocks'][0]['guestLinear'],'0xc8')
        self.assertEqual(result['directJitSeconds'],1)
        self.assertEqual(result['jitCallerSeconds'],1)

    def test_ambiguous_mapping_is_rejected(self):
        timeline=Timeline()
        timeline.apply(block(1,1,4096,100))
        timeline.apply(block(2,2,4100,200))
        with self.assertRaises(ValueError): timeline.lookup(4101)

    def test_half_open_boundaries_and_clock_order(self):
        timeline=Timeline();timeline.apply(block(2,1,4096,100))
        self.assertIsNotNone(timeline.lookup(4111))
        self.assertIsNone(timeline.lookup(4112))
        with self.assertRaises(ValueError):timeline.apply(dict(event='clear',time=1,block=1))


if __name__=='__main__': unittest.main()
