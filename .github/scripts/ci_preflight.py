"""Repository DevOps preflight checks (stdlib-only, fast static gate before CI)."""
from __future__ import annotations

import json
import os
import re
import subprocess
import sys
import tomllib
import xml.etree.ElementTree as ET
from pathlib import Path

ANDROID_NS = "{http://schemas.android.com/apk/res/android}"


def repo_root(start: Path | None = None) -> Path:
    if start is not None:
        return start.resolve()
    return Path(__file__).resolve().parents[2]


def check_workflows(root: Path) -> list[str]:
    errors: list[str] = []
    wf_dir = root / ".github" / "workflows"
    if not wf_dir.is_dir():
        return [f"Missing workflows directory: {wf_dir}"]

    workflows = sorted(wf_dir.glob("*.yml")) + sorted(wf_dir.glob("*.yaml"))
    if not workflows:
        return [f"No workflow files found in {wf_dir}"]

    sha_pin_re = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_./-]+@[0-9a-f]{40}$")
    for wf in workflows:
        rel = wf.relative_to(root)
        text = wf.read_text(encoding="utf-8")
        lines = text.splitlines()

        if not re.search(r"^permissions:\s*$", text, re.M):
            errors.append(f"{rel}: missing top-level 'permissions:' block")

        if "chmod +x ./gradlew" in text:
            errors.append(f"{rel}: redundant 'chmod +x ./gradlew' step (gradlew should be 100755 in Git)")

        for lineno, line in enumerate(lines, start=1):
            m = re.match(r"^\s*(?:-\s*)?uses:\s*([^\s#]+)", line)
            if not m:
                continue
            ref = m.group(1).strip("'\"")
            if ref.startswith("./"):
                continue
            if not sha_pin_re.match(ref):
                errors.append(f"{rel}:{lineno}: action '{ref}' is not pinned to a 40-char commit SHA")

        # Every job with `runs-on:` under `jobs:` must specify timeout-minutes (caller jobs with `uses:` cannot).
        in_jobs = False
        current_job: str | None = None
        job_has_runs_on = False
        job_has_timeout = False
        for line in lines:
            if re.match(r"^jobs:\s*$", line):
                in_jobs = True
                continue
            if in_jobs and re.match(r"^[A-Za-z0-9_-]+:", line):
                break
            if not in_jobs:
                continue
            jm = re.match(r"^  ([A-Za-z0-9_-]+):\s*$", line)
            if jm:
                if current_job is not None and job_has_runs_on and not job_has_timeout:
                    errors.append(f"{rel}: job '{current_job}' is missing 'timeout-minutes'")
                current_job = jm.group(1)
                job_has_runs_on = False
                job_has_timeout = False
            elif current_job is not None:
                if re.match(r"^    runs-on:", line):
                    job_has_runs_on = True
                elif re.match(r"^    timeout-minutes:\s*\d+", line):
                    job_has_timeout = True
        if current_job is not None and job_has_runs_on and not job_has_timeout:
            errors.append(f"{rel}: job '{current_job}' is missing 'timeout-minutes'")

    return errors


def check_gradlew_permissions(root: Path) -> list[str]:
    errors: list[str] = []
    gradlew = root / "gradlew"
    if not gradlew.is_file():
        return ["Missing gradlew at repository root"]
    if not os.access(gradlew, os.X_OK):
        errors.append("gradlew is not executable on disk (expected +x)")

    if (root / ".git").exists():
        res = subprocess.run(
            ["git", "ls-files", "-s", "gradlew"],
            cwd=root,
            capture_output=True,
            text=True,
            check=False,
        )
        if res.returncode == 0 and res.stdout.strip():
            mode = res.stdout.strip().split()[0]
            if mode != "100755":
                errors.append(f"Git index mode for gradlew is {mode}, expected 100755")
    return errors


def check_gradle_and_dependencies(root: Path) -> list[str]:
    errors: list[str] = []
    toml_path = root / "gradle" / "libs.versions.toml"
    if not toml_path.is_file():
        return ["Missing gradle/libs.versions.toml"]

    try:
        catalog = tomllib.loads(toml_path.read_text(encoding="utf-8"))
    except tomllib.TOMLDecodeError as exc:
        return [f"Invalid TOML in gradle/libs.versions.toml: {exc}"]

    for key, val in catalog.get("versions", {}).items():
        if isinstance(val, str) and (
            val.endswith("+") or "SNAPSHOT" in val.upper() or "latest." in val.lower()
        ):
            errors.append(f"gradle/libs.versions.toml: dynamic version not allowed for {key}='{val}'")

    build_gradle = root / "app" / "build.gradle.kts"
    build_text = build_gradle.read_text(encoding="utf-8")
    if 'pickFirsts.add("**/*.so")' in build_text:
        errors.append("app/build.gradle.kts: overly broad jniLibs pickFirsts('**/*.so') masks conflicts")

    in_deps = False
    hardcoded_coord = re.compile(r'"([A-Za-z0-9_.-]+:[A-Za-z0-9_.-]+:[^"$]+)"')
    for lineno, line in enumerate(build_text.splitlines(), start=1):
        stripped = line.strip()
        if stripped.startswith("dependencies {"):
            in_deps = True
            continue
        if in_deps and stripped == "}":
            in_deps = False
            continue
        if in_deps and not stripped.startswith("//"):
            m = hardcoded_coord.search(line)
            if m:
                errors.append(
                    f"app/build.gradle.kts:{lineno}: hardcoded dependency '{m.group(1)}' should be in gradle/libs.versions.toml"
                )

    settings_text = (root / "settings.gradle.kts").read_text(encoding="utf-8")
    if "jitpack.io" in settings_text and "includeGroup" not in settings_text:
        errors.append("settings.gradle.kts: jitpack.io repository must be scoped with content { includeGroup(...) }")

    wrapper_props = (root / "gradle" / "wrapper" / "gradle-wrapper.properties").read_text(encoding="utf-8")
    if "validateDistributionUrl=true" not in wrapper_props:
        errors.append("gradle/wrapper/gradle-wrapper.properties: missing validateDistributionUrl=true")

    gradle_props = (root / "gradle.properties").read_text(encoding="utf-8")
    if not re.search(r"^org\.gradle\.caching=true\s*$", gradle_props, re.M):
        errors.append("gradle.properties: org.gradle.caching=true must be enabled")

    return errors


def check_cpp_and_16kb(root: Path) -> list[str]:
    errors: list[str] = []
    cpp_dir = root / "app" / "src" / "main" / "cpp"
    cmake_path = cpp_dir / "CMakeLists.txt"
    if not cmake_path.is_file():
        return ["Missing app/src/main/cpp/CMakeLists.txt"]

    cmake_text = cmake_path.read_text(encoding="utf-8")
    if "NNUE_EMBEDDING_OFF" not in cmake_text:
        errors.append("CMakeLists.txt: NNUE_EMBEDDING_OFF must be defined")
    if "-Wl,-z,max-page-size=16384" not in cmake_text:
        errors.append("CMakeLists.txt: 16 KB ELF page alignment linker flag (-Wl,-z,max-page-size=16384) missing")

    for raw in re.findall(r"\$\{SF_SRC_DIR\}/([A-Za-z0-9_./-]+\.cpp)", cmake_text):
        p = cpp_dir / "stockfish" / "src" / raw
        if not p.is_file():
            errors.append(f"CMakeLists.txt: missing Stockfish source file {p.relative_to(root)}")

    for raw in re.findall(r"\b(stockfish_[A-Za-z0-9_]+\.cpp)\b", cmake_text):
        p = cpp_dir / raw
        if not p.is_file():
            errors.append(f"CMakeLists.txt: missing JNI/bridge source file {p.relative_to(root)}")

    return errors


def _excluded_pairs(parent: ET.Element | None) -> set[tuple[str, str]]:
    if parent is None:
        return set()
    return {
        (child.attrib.get("domain", ""), child.attrib.get("path", ""))
        for child in parent.findall("exclude")
    }


def check_android_xml_and_appsec(root: Path) -> list[str]:
    errors: list[str] = []
    manifest_path = root / "app" / "src" / "main" / "AndroidManifest.xml"
    backup_path = root / "app" / "src" / "main" / "res" / "xml" / "backup_rules.xml"
    extract_path = root / "app" / "src" / "main" / "res" / "xml" / "data_extraction_rules.xml"
    netsec_path = root / "app" / "src" / "main" / "res" / "xml" / "network_security_config.xml"

    for path in (manifest_path, backup_path, extract_path, netsec_path):
        if not path.is_file():
            errors.append(f"Missing required Android XML config: {path.relative_to(root)}")
    if errors:
        return errors

    try:
        manifest = ET.parse(manifest_path).getroot()
        app_el = manifest.find("application")
        if app_el is None or app_el.attrib.get(f"{ANDROID_NS}networkSecurityConfig") != "@xml/network_security_config":
            errors.append("AndroidManifest.xml: <application> must specify android:networkSecurityConfig=\"@xml/network_security_config\"")
    except ET.ParseError as exc:
        errors.append(f"AndroidManifest.xml parse error: {exc}")

    try:
        netsec = ET.parse(netsec_path).getroot()
        base = netsec.find("base-config")
        if base is None or base.attrib.get("cleartextTrafficPermitted") != "false":
            errors.append("network_security_config.xml: <base-config> must enforce cleartextTrafficPermitted=\"false\"")
    except ET.ParseError as exc:
        errors.append(f"network_security_config.xml parse error: {exc}")

    required_excludes = {("file", "nnue"), ("database", "."), ("sharedpref", "user_session.xml")}
    try:
        backup = ET.parse(backup_path).getroot()
        missing = required_excludes - _excluded_pairs(backup)
        if missing:
            errors.append(f"backup_rules.xml: missing required exclusions {sorted(missing)}")
    except ET.ParseError as exc:
        errors.append(f"backup_rules.xml parse error: {exc}")

    try:
        extract = ET.parse(extract_path).getroot()
        for section in ("cloud-backup", "device-transfer"):
            missing = required_excludes - _excluded_pairs(extract.find(section))
            if missing:
                errors.append(f"data_extraction_rules.xml <{section}>: missing exclusions {sorted(missing)}")
    except ET.ParseError as exc:
        errors.append(f"data_extraction_rules.xml parse error: {exc}")

    # TesterProfiles must live in debug/release source sets, never in main.
    main_tester = root / "app" / "src" / "main" / "java" / "com" / "example" / "chessmentor" / "data" / "local" / "TesterProfiles.kt"
    debug_tester = root / "app" / "src" / "debug" / "java" / "com" / "example" / "chessmentor" / "data" / "local" / "TesterProfiles.kt"
    release_tester = root / "app" / "src" / "release" / "java" / "com" / "example" / "chessmentor" / "data" / "local" / "TesterProfiles.kt"
    if main_tester.exists():
        errors.append("TesterProfiles.kt must not reside in app/src/main (move to debug/release source sets)")
    if not debug_tester.is_file():
        errors.append("Missing app/src/debug/.../TesterProfiles.kt")
    if not release_tester.is_file():
        errors.append("Missing app/src/release/.../TesterProfiles.kt")
    elif "local-debug-persona" in release_tester.read_text(encoding="utf-8"):
        errors.append("Release TesterProfiles.kt stub must not contain local-debug-persona marker")

    return errors


def check_room_schema_sync(root: Path) -> list[str]:
    errors: list[str] = []
    schema_path = root / "app" / "schemas" / "com.example.chessmentor.data.local.AppDatabase" / "5.json"
    app_db_path = root / "app" / "src" / "main" / "java" / "com" / "example" / "chessmentor" / "data" / "local" / "AppDatabase.kt"
    migrations_path = root / "app" / "src" / "main" / "java" / "com" / "example" / "chessmentor" / "data" / "local" / "DatabaseMigrations.kt"

    if not schema_path.is_file():
        return [f"Missing Room schema file: {schema_path.relative_to(root)}"]

    mig_text = migrations_path.read_text(encoding="utf-8")
    m_ver = re.search(r"const\s+val\s+CURRENT_VERSION\s*=\s*(\d+)", mig_text)
    if not m_ver or int(m_ver.group(1)) != 5:
        errors.append("DatabaseMigrations.CURRENT_VERSION does not equal 5")

    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    db_obj = schema.get("database", {})
    if db_obj.get("version") != 5:
        errors.append(f"Room schema version is {db_obj.get('version')}, expected 5")

    app_db_text = app_db_path.read_text(encoding="utf-8")
    m_entities = re.search(r"entities\s*=\s*\[(.*?)\]", app_db_text, re.S)
    if not m_entities:
        errors.append("AppDatabase.kt: could not locate @Database(entities = [...])")
    else:
        declared = [item.strip() for item in m_entities.group(1).split(",") if item.strip()]
        schema_entities = db_obj.get("entities", [])
        if len(declared) != len(schema_entities):
            errors.append(
                f"Room entity count mismatch: AppDatabase.kt has {len(declared)}, 5.json has {len(schema_entities)}"
            )

    return errors


def check_git_hygiene_and_scripts(root: Path) -> list[str]:
    errors: list[str] = []
    if (root / ".git").exists():
        res = subprocess.run(
            ["git", "ls-files"],
            cwd=root,
            capture_output=True,
            text=True,
            check=False,
        )
        if res.returncode == 0:
            forbidden_suffixes = (".jks", ".keystore", ".p12", ".pfx", ".pem", ".key")
            forbidden_exact = {
                "local.properties",
                ".idea/assetWizardSettings.xml",
                ".idea/deploymentTargetSelector.xml",
            }
            for path in res.stdout.splitlines():
                low = path.lower()
                if low.endswith(forbidden_suffixes) or path in forbidden_exact:
                    errors.append(f"Forbidden file tracked in Git: {path}")

            for rel in res.stdout.splitlines():
                if not rel.startswith((".idea/", ".github/", "gradle/", "app/build.gradle.kts")):
                    continue
                if rel == ".github/scripts/ci_preflight.py" or rel == ".github/scripts/test_ci_preflight.py":
                    continue
                full = root / rel
                if not full.is_file():
                    continue
                content = full.read_text(encoding="utf-8", errors="ignore")
                if re.search(r"[A-Za-z]:\\Users\\", content):
                    errors.append(f"Local Windows user path leaked in tracked file: {rel}")
                if re.search(r'identifier="serial=[^"]+"', content):
                    errors.append(f"Device serial identifier leaked in tracked file: {rel}")

    for sh_file in sorted((root / ".github" / "scripts").glob("*.sh")):
        proc = subprocess.run(["bash", "-n", str(sh_file)], capture_output=True, text=True, check=False)
        if proc.returncode != 0:
            errors.append(f"{sh_file.relative_to(root)}: bash -n failed: {proc.stderr.strip()}")

    return errors


def run_all_checks(root: Path | None = None) -> list[str]:
    base = repo_root(root)
    errors: list[str] = []
    errors.extend(check_workflows(base))
    errors.extend(check_gradlew_permissions(base))
    errors.extend(check_gradle_and_dependencies(base))
    errors.extend(check_cpp_and_16kb(base))
    errors.extend(check_android_xml_and_appsec(base))
    errors.extend(check_room_schema_sync(base))
    errors.extend(check_git_hygiene_and_scripts(base))
    return errors


def main() -> int:
    errors = run_all_checks()
    if errors:
        for err in errors:
            print(f"PREFLIGHT ERROR: {err}", file=sys.stderr)
        print(f"Preflight failed with {len(errors)} error(s).", file=sys.stderr)
        return 1
    print("Preflight OK: workflows, gradlew, dependencies, CMake/16KB, AppSec XML, Room schema & hygiene verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
