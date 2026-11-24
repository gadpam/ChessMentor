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


if __name__ == "__main__":
    unittest.main()
