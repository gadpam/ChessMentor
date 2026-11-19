import unittest

from release_version import version


class ReleaseVersionTest(unittest.TestCase):
    def test_codes_increase_from_rc_to_stable_and_across_versions(self):
        tags = ["v0.0.1-rc.1", "v1.1.0-rc.1", "v1.1.0-rc.98", "v1.1.0",
                "v1.1.1-rc.1", "v1.2.0", "v2.0.0", "v99.99.99"]
        codes = [version(tag)[1] for tag in tags]
        self.assertEqual(codes, sorted(set(codes)))
        self.assertGreater(codes[0], 1)  # existing local builds use versionCode=1
        self.assertLessEqual(codes[-1], 2_100_000_000)
        self.assertEqual(version("v1.1.0-rc.1"), ("1.1.0-rc.1", 1010001))

    def test_ambiguous_or_out_of_range_tags_fail(self):
        for tag in ["1.1.0", "v1.1", "v01.1.0", "v1.0.100", "v1.2.3-rc.0",
                    "v1.2.3-rc.99", "v1.2.3-rc.01", "v1.0.0-beta", "v0.0.0"]:
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                version(tag)
