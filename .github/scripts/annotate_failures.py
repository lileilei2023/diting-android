#!/usr/bin/env python3
"""Turn a Gradle failure into GitHub check annotations.

Why this exists: a run's log archive is served from a storage host, and an
environment that can reach api.github.com but not that host can see *that* a
build failed and nothing about *why*. Annotations travel with the check run on
the API itself, so emitting them here makes a CI failure diagnosable from
anywhere the API is reachable.

Two sources, in order of usefulness:

1. JUnit XML — the exact assertion, per test. Precise, so it comes first.
2. The Gradle console log — Kotlin `e:` lines and the `FAILURE:` block, for
   everything that fails before a test ever runs (a compile error, an
   unresolved dependency, KSP).
"""

from __future__ import annotations

import glob
import os
import re
import sys
import xml.etree.ElementTree as ET

# GitHub keeps a bounded number of annotations per check run and truncates long
# ones. Emitting fewer, more complete ones beats emitting many stubs.
MAX_ANNOTATIONS = 20
MAX_MESSAGE = 900


def clean(text: str) -> str:
    """One line, bounded: annotations render newlines inconsistently."""
    collapsed = " ".join((text or "").split())
    return collapsed[:MAX_MESSAGE]


def emit(title: str, message: str) -> None:
    # `::` and newlines would end the workflow command early.
    safe_title = clean(title).replace("::", ":")
    print(f"::error title={safe_title}::{clean(message)}", flush=True)


def test_failures() -> int:
    found = 0
    for path in glob.glob("**/build/test-results/**/*.xml", recursive=True):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        for case in root.iter("testcase"):
            for bad in list(case.findall("failure")) + list(case.findall("error")):
                found += 1
                if found > MAX_ANNOTATIONS:
                    continue
                detail = bad.get("message") or bad.text or "(no message)"
                emit(f"{case.get('classname')}.{case.get('name')}", detail)
    return found


# `e: file:///path/Foo.kt:12:9 Unresolved reference: bar`
KOTLIN_ERROR = re.compile(r"^e: (?:file://)?(\S+?):(\d+):(\d+)\s+(.*)$")


def log_failures(paths: list[str]) -> int:
    found = 0
    for path in paths:
        if not os.path.exists(path):
            continue
        with open(path, encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()

        for line in lines:
            match = KOTLIN_ERROR.match(line.strip())
            if not match:
                continue
            found += 1
            if found > MAX_ANNOTATIONS:
                continue
            file_path, line_no, col, message = match.groups()
            rel = file_path.split(os.getcwd() + "/")[-1]
            print(
                f"::error file={rel},line={line_no},col={col},"
                f"title=Kotlin::{clean(message)}",
                flush=True,
            )

        if found:
            continue

        # No Kotlin error: fall back to Gradle's own summary, which is where an
        # unresolved dependency or a plugin problem is explained.
        for index, line in enumerate(lines):
            if line.startswith("FAILURE:") or line.startswith("* What went wrong:"):
                emit("Gradle", " ".join(lines[index : index + 12]))
                found += 1
                break

    return found


def main() -> int:
    total = test_failures()
    if total:
        emit("Summary", f"{total} failing test(s); see the annotations above.")
        return 0

    logs = sys.argv[1:] or ["/tmp/gradle.log"]
    if not log_failures(logs):
        emit(
            "Summary",
            "The build failed but produced no test failures and no recognised "
            "Kotlin or Gradle error. Open the run log for the raw output.",
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
