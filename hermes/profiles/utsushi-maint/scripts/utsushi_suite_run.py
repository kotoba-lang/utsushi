#!/usr/bin/env python3
"""utsushi kbb suite run — appends one measured entry to suite-ledger.jsonl.

Runs target/run-suite.cljk directly under kbb (no -M:test temp script,
measured 2026-09-16) with NBB_CLJK_ROOTS covering utsushi + the fs closure
+ org-iso-* (WITHOUT it kotoba.lang.fs dies "Could not find namespace").
Prints MEASURE lines; append-only ledger entry even when red.

Exit 0 = measured (green or red). Exit 2 = could not measure.
"""
import json
import os
import re
import subprocess
import sys
import time

UTSUSHI = os.path.expanduser(
    "~/github/com-junkawasaki/orgs/kotoba-lang/utsushi")
KLB = os.path.expanduser("~/github/com-junkawasaki/orgs/kotoba-lang")
LEDGER = os.path.expanduser(
    "~/.hermes/profiles/utsushi-maint/workspace/suite-ledger.jsonl")

CP = ":".join([
    "src", "test", "bench/ffmpeg-comparison/src",
    KLB + "/org-iso-h264/src", KLB + "/org-iso-isobmff/src",
    KLB + "/fs/src", KLB + "/text/src",
    KLB + "/fs-filesystem/src", KLB + "/fs-async-filesystem/src",
])
# JSON array form is REQUIRED: python str([...]) emits single quotes,
# which the engine rejects ("... is not valid JSON", measured 2026-09-16).
ROOTS = json.dumps([UTSUSHI, KLB + "/fs", KLB + "/text",
                    KLB + "/fs-filesystem", KLB + "/fs-async-filesystem",
                    KLB + "/org-iso-h264", KLB + "/org-iso-isobmff"])

LAUNCHER_FP = ("Could not find namespace:", "target-incompatible",
               "invalid-origin", "ambiguous-source")


def main():
    if not os.path.isdir(UTSUSHI):
        print("MEASURE\tcheckout\tMISSING")
        sys.exit(2)

    env = dict(os.environ)
    env["NBB_CLJK_ROOTS"] = ROOTS
    r = subprocess.run(
        "kbb --backend sci --classpath '%s' target/run-suite.cljk" % CP,
        shell=True, cwd=UTSUSHI, timeout=900, capture_output=True, text=True,
        env=env)
    rc = r.returncode
    text = r.stdout + r.stderr

    died_at = None
    for fp in LAUNCHER_FP:
        idx = text.find(fp)
        if idx != -1:
            line_end = text.find("\n", idx)
            died_at = text[idx:min(line_end, idx + 120)] if line_end != -1 \
                else text[idx:idx + 120]
            break

    m = re.search(r"(\d+) tests?\s+(?:containing\s+)?(\d+) assertions", text)
    f = re.search(r"(\d+) failures?", text)
    e = re.search(r"(\d+) errors?", text)
    totals = {"tests": int(m.group(1)) if m else None,
              "assertions": int(m.group(2)) if m else None,
              "failures": int(f.group(1)) if f else None,
              "errors": int(e.group(1)) if e else None}

    r2 = subprocess.run("git rev-parse --short HEAD", shell=True,
                        cwd=UTSUSHI, capture_output=True, text=True)
    head = r2.stdout.strip() if r2.returncode == 0 else "UNKNOWN"

    ts = time.strftime("%Y-%m-%dT%H:%M:%S%z")
    print("MEASURE\tas_of\t%s" % ts)
    print("MEASURE\tgit_head\t%s" % head)
    if died_at:
        print("MEASURE\tsuite\tUNMEASURED")
        print("MEASURE\tdied_at\t%s" % died_at)
        sys.exit(2)

    print("MEASURE\trc\t%s" % rc)
    for k, v in totals.items():
        print("MEASURE\t%s\t%s" % (k, v))
    entry = {"as_of": ts, "git_head": head, "rc": rc}
    entry.update(totals)
    os.makedirs(os.path.dirname(LEDGER), exist_ok=True)
    with open(LEDGER, "a") as fh:
        fh.write(json.dumps(entry) + "\n")
    seq = sum(1 for _ in open(LEDGER)) if os.path.exists(LEDGER) else 0
    print("MEASURE\tledger_seq\t%s" % seq)
    sys.exit(0)


if __name__ == "__main__":
    main()
