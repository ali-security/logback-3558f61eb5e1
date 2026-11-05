#!/usr/bin/env python
# Print per-module surefire totals and every skipped test with its reason.
# Surefire's console output only prints "Skipped: N"; this lists each skip.
# Works on Python 2.7 (Travis trusty) and Python 3 (GitHub runners).
from __future__ import print_function

import glob
import io
import os
import re
import xml.etree.ElementTree as ET

GENERIC = ("assumption is not true", "")


def source_path(module, fqcn):
    rel = fqcn.split("$")[0].replace(".", "/") + ".java"
    for cand in [os.path.join(module, "src", "test", "java", rel)] + glob.glob(
        os.path.join("*", "src", "test", "java", rel)
    ):
        if os.path.isfile(cand):
            return cand
    return None


def reason_from_source(module, fqcn, method):
    path = source_path(module, fqcn)
    if not path:
        return None
    with io.open(path, encoding="utf-8", errors="replace") as fh:
        lines = fh.read().splitlines()
    hits = []
    idx = None
    if method:
        pat = re.compile(r"\b%s\s*\(" % re.escape(method))
        for i, line in enumerate(lines):
            if pat.search(line) and "void" in line:
                idx = i
                break
    if idx is not None:
        j = idx - 1
        while j >= 0 and (lines[j].strip().startswith("@") or lines[j].strip().startswith("//")):
            if "@Disabled" in lines[j] or "@Ignore" in lines[j]:
                hits.append(lines[j].strip())
            j -= 1
        for line in lines[idx : idx + 40]:
            if "assume" in line.lower():
                hits.append(line.strip())
                break
    if not hits:
        for line in lines:
            if "@Disabled" in line or "@Ignore" in line:
                hits.append(line.strip() + " (class-level)")
                break
    return "; ".join(hits) if hits else None


def main():
    total = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
    print("=" * 72)
    print("SUREFIRE TEST REPORT (per module, from target/surefire-reports)")
    print("=" * 72)
    for module in sorted(d for d in os.listdir(".") if os.path.isdir(os.path.join(d, "target"))):
        rdir = os.path.join(module, "target", "surefire-reports")
        if not os.path.isdir(rdir):
            continue
        mod = {"tests": 0, "failures": 0, "errors": 0, "skipped": 0}
        skips = []
        xmls = sorted(glob.glob(os.path.join(rdir, "TEST-*.xml")))
        if xmls:
            for x in xmls:
                suite = ET.parse(x).getroot()
                for k in mod:
                    mod[k] += int(suite.get(k, "0") or 0)
                for tc in suite.iter("testcase"):
                    sk = tc.find("skipped")
                    if sk is None:
                        continue
                    cls = tc.get("classname") or suite.get("name")
                    name = tc.get("name")
                    msg = (sk.get("message") or "").strip()
                    if msg.split(":")[-1].strip() in GENERIC or "assumption is not true" in msg:
                        src = reason_from_source(module, cls, name)
                        if src:
                            msg = (msg + " -- source: " if msg else "source: ") + src
                    skips.append("%s#%s (report file %s): %s" % (cls, name, os.path.basename(x), msg or "<no message>"))
        else:
            for t in sorted(glob.glob(os.path.join(rdir, "*.txt"))):
                with io.open(t, encoding="utf-8", errors="replace") as fh:
                    lines = fh.read().splitlines()
                for line in lines:
                    m = re.search(r"Tests run: (\d+), Failures: (\d+), Errors: (\d+), Skipped: (\d+)", line)
                    if m:
                        mod["tests"] += int(m.group(1))
                        mod["failures"] += int(m.group(2))
                        mod["errors"] += int(m.group(3))
                        mod["skipped"] += int(m.group(4))
                        break
                for line in lines:
                    if "skipped" in line.lower() and "Tests run:" not in line:
                        fq = line.split()[0] if line.split() else ""
                        src = reason_from_source(module, fq, None) if "." in fq else None
                        skips.append("%s (report file %s)%s" % (line.strip(), os.path.basename(t), (" -- source: " + src) if src else ""))
        for k in total:
            total[k] += mod[k]
        passed = mod["tests"] - mod["failures"] - mod["errors"] - mod["skipped"]
        print(
            "MODULE %-26s tests=%d passed=%d failures=%d errors=%d skipped=%d"
            % (module, mod["tests"], passed, mod["failures"], mod["errors"], mod["skipped"])
        )
        for s in skips:
            print("    SKIPPED %s" % s)
    passed = total["tests"] - total["failures"] - total["errors"] - total["skipped"]
    print("-" * 72)
    print(
        "TOTAL tests=%d passed=%d failures=%d errors=%d skipped=%d"
        % (total["tests"], passed, total["failures"], total["errors"], total["skipped"])
    )
    print("=" * 72)


if __name__ == "__main__":
    main()
