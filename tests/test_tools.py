import json
import os
import sys
import unittest

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
sys.path.insert(0, os.path.join(ROOT, "tools"))

import broadcast_payload as bp  # noqa: E402
import profile_tool as pt  # noqa: E402

TAILS = ["e5157d", "e49c6c", "e7075e", "e68e4f", "e1313b", "e0b82a", "e32318", "e2aa09", "ed5df1", "ecd4e0"]
PREFIX = "6db643ce97fe427c"
PROFILE = os.path.join(ROOT, "android", "assets", "profiles", "zasj047.json")


class PayloadTests(unittest.TestCase):
    def test_zasj047_matches_captures(self):
        prof = bp.load_profile(PROFILE)
        for code, tail in enumerate(TAILS):
            self.assertEqual(bp.packet(prof, code).hex(), PREFIX + tail, code)

    def test_bundled_profile_is_sane(self):
        prof = bp.load_profile(PROFILE)
        codes = bp.button_codes(prof)
        self.assertEqual(len(codes), len(set(codes)))
        self.assertIn(0x18, codes)
        self.assertNotIn(0x0A, codes)
        self.assertNotIn(bp.off_code(prof), codes)


class LearnTests(unittest.TestCase):
    def capture(self):
        return {code: bytes.fromhex(PREFIX + tail) for code, tail in enumerate(TAILS)}

    def test_learn_recovers_formula(self):
        prof, how = pt.learn(self.capture(), "x")
        self.assertIn("CRC", how)
        pl = prof["payload"]
        self.assertEqual(pl["prefix"], PREFIX)
        self.assertEqual(pl["xor"], "e5")
        self.assertEqual(pl["crc"], {"poly": "8408", "init": "0000", "reflected": True, "swap": True, "xorOut": "157d"})
        # the derived profile also computes codes that were not in the capture
        original = bp.load_profile(PROFILE)
        for code in (0x0B, 0x18, 0x38, 0xFF):
            self.assertEqual(bp.packet(prof, code), bp.packet(original, code))

    def test_learn_falls_back_to_table(self):
        weird = {0: bytes.fromhex("aabb01"), 1: bytes.fromhex("11cc77"), 2: bytes.fromhex("5e0042")}
        prof, how = pt.learn(weird, "y")
        self.assertEqual(prof["payload"]["type"], "table")
        for code, pkt in weird.items():
            self.assertEqual(bp.packet(prof, code), pkt)

    def test_table_profile_missing_code(self):
        prof = {"payload": {"type": "table", "packets": {"00": "01"}}}
        with self.assertRaises(KeyError):
            bp.packet(prof, 5)

    def test_non_reflected_crc_is_standard_ccitt(self):
        # CRC-16/XMODEM over the byte 0x31 (check value from the literature: 0x2672 for "1")
        self.assertEqual(bp.crc16(0x31, 0x1021, 0, reflected=False), 0x2672)


if __name__ == "__main__":
    unittest.main()
