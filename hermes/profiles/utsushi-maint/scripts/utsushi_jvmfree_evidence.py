#!/usr/bin/env python3
"""utsushi JVM-free evidence — no-user measure job.

Prints MEASURE<TAB>key<TAB>value lines. Read-only.

Exit codes: 0 = measured, 2 = could not measure.
"""
import json
import os
import re
import subprocess
import sys
import time

UTSUSHI = os.path.expanduser(
    "~/github/com-junkawasaki/orgs/kotoba-lang/utsushi")
SUPER = os.path.expanduser("~/github/com-junkawasaki")


def sh(cmd, cwd, timeout=240):
    return subprocess.run(cmd, shell=True, cwd=cwd, timeout=timeout,
                          capture_output=True, text=True)


def strip_prose(src_text):
    """Drop ;;-comment lines and string-literal CONTENTS so docstring
    prose mentioning old JVM names is not counted as a dep hit
    (measured 2026-09-16: all 4 raw grep hits were prose; the underlying
    code had already been ported). Line-oriented approximation, escapes
    honored, closing quote ends the string."""
    out = []
    in_str = False
    for line in src_text.splitlines():
        res = []
        i = 0
        n = len(line)
        while i < n:
            c = line[i]
            if c == "\\" and i + 1 < n:
                i += 2
                continue
            if in_str:
                if c == '"':
                    in_str = False
                i += 1
                continue
            if c == '"':
                in_str = True
                res.append(" ")
                i += 1
                continue
            if c == ";" and line[i:i + 2] == ";;":
                break
            res.append(c)
            i += 1
        out.append("".join(res))
    return "\n".join(out)


def cljk_files(scope):
    r = sh(
        f"find {scope} -name '*.cljk' -not -path '*/target/*' 2>/dev/null",
        UTSUSHI)
    return [ln for ln in r.stdout.splitlines() if ln.strip()]


def main():
    out = []
    if not os.path.isdir(UTSUSHI):
        print("MEASURE\tcheckout\tMISSING")
        sys.exit(2)

    r = sh("git rev-parse --short HEAD", UTSUSHI)
    head = r.stdout.strip() if r.returncode == 0 else "UNKNOWN"
    out.append(("git_head", head))

    pats = ["clojure.java.io", "clojure.java.shell",
            "clojure.lang.ExceptionInfo", "java.util", "java.nio",
            "java.net", "java.security", "java.time"]
    per_pat = {}
    # raw grep counts kept as grep.* keys (over-count on prose)
    for scope in ("src", "test", "bench"):
        for p in pats:
            r = sh(
                f"grep -rl '{p}' {scope} --include='*.cljk' 2>/dev/null | wc -l",
                UTSUSHI)
            per_pat[f"grep.{scope}.{p}"] = int(r.stdout.strip() or 0)
    # code-level counts: comments + string contents stripped
    for scope in ("src", "test", "bench"):
        files = cljk_files(scope)
        for p in pats:
            n = 0
            for fp in files:
                try:
                    with open(os.path.join(UTSUSHI, fp),
                              encoding="utf-8") as f:
                        if p in strip_prose(f.read()):
                            n += 1
                except OSError:
                    pass
            per_pat[f"{scope}.{p}"] = n
    total = sum(v for k, v in per_pat.items() if not k.startswith("grep."))
    out.append(("jvm_dep_file_hits", total))
    for k, v in sorted(per_pat.items()):
        out.append((f"jvm_dep.{k}", v))

    # --- files still carrying clojure.java.io in CODE (the frontier) ---
    files = []
    for scope in ("test", "bench", "src"):
        for fp in cljk_files(scope):
            try:
                with open(os.path.join(UTSUSHI, fp), encoding="utf-8") as f:
                    if "clojure.java.io" in strip_prose(f.read()):
                        files.append(fp)
            except OSError:
                pass
    files.sort()
    out.append(("io_files_remaining", len(files)))

    # --- read the last suite result the land job recorded (if any) ---
    led = os.path.expanduser(
        "~/.hermes/profiles/utsushi-maint/workspace/suite-ledger.jsonl")
    last_suite = None
    if os.path.exists(led):
        with open(led) as f:
            for ln in f:
                ln = ln.strip()
                if ln:
                    try:
                        last_suite = json.loads(ln)
                    except Exception:
                        pass
    if last_suite:
        out.append(("last_suite_rc", last_suite.get("rc", "NA")))
        totals = last_suite.get("totals")
        if totals is None and "tests" in last_suite:
            # 2026-09-22: the runner records tests/assertions/failures/errors
            # as separate keys, not a 'totals' string — compose the line.
            totals = ("{tests}t/{a}a/{f}f/{e}e".format(
                tests=last_suite.get("tests"),
                a=last_suite.get("assertions"),
                f=last_suite.get("failures"),
                e=last_suite.get("errors")))
        out.append(("last_suite_totals",
                    totals if totals is not None else "NA"))

    ts = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    print(f"MEASURE\tas_of\t{ts}")
    for k, v in out:
        print(f"MEASURE\t{k}\t{v}")
    if not files:
        print("STATUS\tALL-JVM-FREE")
    else:
        print(f"STATUS\tFRONTIER\t{files[0]}")
    print(f"STATUS\tgit_head\t{head}")
    sys.exit(0)


if __name__ == "__main__":
    main()
