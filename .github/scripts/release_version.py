"""Translate a release tag into Android versionName/versionCode (Actions outputs)."""
import re
import sys


def version(tag):
    number = r"(0|[1-9][0-9]?)"
    match = re.fullmatch(rf"v{number}\.{number}\.{number}(?:-rc\.([1-9][0-9]?))?", tag)
    if not match:
        raise ValueError("Expected vMAJOR.MINOR.PATCH or vMAJOR.MINOR.PATCH-rc.N")
    major, minor, patch = map(int, match.groups()[:3])
    rc = match[4]
    if (major, minor, patch) == (0, 0, 0) or rc == "99":
        raise ValueError("Version must be > 0.0.0; rc must be 1..98")
    code = ((major * 100 + minor) * 100 + patch) * 100 + (int(rc) if rc else 99)
    return tag[1:], code


if __name__ == "__main__":
    name, code = version(sys.argv[1])
    print(f"version_name={name}\nversion_code={code}")
