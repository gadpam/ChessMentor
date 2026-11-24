import tempfile
import unittest
from pathlib import Path

import ci_preflight


class CiPreflightRepoTest(unittest.TestCase):
    def test_current_repository_passes_all_preflight_checks(self):
        errors = ci_preflight.run_all_checks()
        self.assertEqual(errors, [])

    def test_detects_unpinned_workflow_action_and_missing_database_exclude(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            wf = root / ".github" / "workflows" / "test.yml"
            wf.parent.mkdir(parents=True)
            wf.write_text(
                "name: test\n"
                "permissions:\n"
                "  contents: read\n"
                "jobs:\n"
                "  caller:\n"
                "    uses: ./.github/workflows/other.yml\n"
                "  build:\n"
                "    runs-on: ubuntu-latest\n"
                "    steps:\n"
                "      - uses: actions/setup-java@v5\n",
                encoding="utf-8",
            )
            wf_errors = ci_preflight.check_workflows(root)
            self.assertTrue(any("not pinned to a 40-char commit SHA" in e for e in wf_errors), wf_errors)
            self.assertTrue(any("job 'build' is missing 'timeout-minutes'" in e for e in wf_errors), wf_errors)
            self.assertFalse(any("job 'caller'" in e for e in wf_errors), wf_errors)

    def test_detects_missing_wrapper_sha256_and_emulator_sh_pipefail(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "gradle" / "wrapper").mkdir(parents=True)
            (root / "app").mkdir(parents=True)
            (root / "gradle" / "libs.versions.toml").write_text("[versions]\n", encoding="utf-8")
            (root / "app" / "build.gradle.kts").write_text("", encoding="utf-8")
            (root / "settings.gradle.kts").write_text("", encoding="utf-8")
            (root / "gradle.properties").write_text("org.gradle.caching=true\n", encoding="utf-8")
            (root / "gradle" / "wrapper" / "gradle-wrapper.properties").write_text(
                "validateDistributionUrl=true\ndistributionSha256Sum=abc123\n",
                encoding="utf-8",
            )
            gradle_errors = ci_preflight.check_gradle_and_dependencies(root)
            self.assertTrue(any("distributionSha256Sum" in e for e in gradle_errors), gradle_errors)

            wf = root / ".github" / "workflows" / "emu.yml"
            wf.parent.mkdir(parents=True)
            wf.write_text(
                "name: emu\n"
                "permissions:\n"
                "  contents: read\n"
                "jobs:\n"
                "  emu:\n"
                "    runs-on: ubuntu-latest\n"
                "    timeout-minutes: 25\n"
                "    steps:\n"
                "      - uses: reactivecircus/android-emulator-runner@a421e43855164a8197daf9d8d40fe71c6996bb0d\n"
                "        with:\n"
                "          script: |\n"
                "            set -o pipefail\n",
                encoding="utf-8",
            )
            wf_errors = ci_preflight.check_workflows(root)
            self.assertTrue(any("POSIX /bin/sh" in e for e in wf_errors), wf_errors)


if __name__ == "__main__":
    unittest.main()
