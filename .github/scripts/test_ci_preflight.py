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

    def test_detects_missing_gitattributes_and_release_strictmode_leak(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            hyg_errors = ci_preflight.check_git_hygiene_and_scripts(root)
            self.assertTrue(any("Missing .gitattributes" in e for e in hyg_errors), hyg_errors)

            xml_dir = root / "app" / "src" / "main" / "res" / "xml"
            xml_dir.mkdir(parents=True)
            (root / "app" / "src" / "main" / "AndroidManifest.xml").write_text(
                '<manifest xmlns:android="http://schemas.android.com/apk/res/android">'
                '<application android:networkSecurityConfig="@xml/network_security_config"/></manifest>',
                encoding="utf-8",
            )
            (xml_dir / "network_security_config.xml").write_text(
                '<network-security-config><base-config cleartextTrafficPermitted="false"/></network-security-config>',
                encoding="utf-8",
            )
            excl = (
                '<exclude domain="file" path="nnue"/>'
                '<exclude domain="database" path="."/>'
                '<exclude domain="sharedpref" path="user_session.xml"/>'
            )
            (xml_dir / "backup_rules.xml").write_text(f"<full-backup-content>{excl}</full-backup-content>", encoding="utf-8")
            (xml_dir / "data_extraction_rules.xml").write_text(
                f"<data-extraction-rules><cloud-backup>{excl}</cloud-backup><device-transfer>{excl}</device-transfer></data-extraction-rules>",
                encoding="utf-8",
            )
            for p in (
                root / "app" / "src" / "debug" / "java" / "com" / "example" / "chessmentor" / "data" / "local" / "TesterProfiles.kt",
                root / "app" / "src" / "release" / "java" / "com" / "example" / "chessmentor" / "data" / "local" / "TesterProfiles.kt",
                root / "app" / "src" / "debug" / "java" / "com" / "example" / "chessmentor" / "di" / "DebugEnvironment.kt",
            ):
                p.parent.mkdir(parents=True, exist_ok=True)
                p.write_text("// stub", encoding="utf-8")
            rel_dbg = root / "app" / "src" / "release" / "java" / "com" / "example" / "chessmentor" / "di" / "DebugEnvironment.kt"
            rel_dbg.parent.mkdir(parents=True, exist_ok=True)
            rel_dbg.write_text("import android.os.StrictMode", encoding="utf-8")

            appsec_errors = ci_preflight.check_android_xml_and_appsec(root)
            self.assertTrue(any("StrictMode" in e for e in appsec_errors), appsec_errors)


if __name__ == "__main__":
    unittest.main()
