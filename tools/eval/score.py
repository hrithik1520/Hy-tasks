#!/usr/bin/env python3
"""Scores host-test eval results (one JSON per line): action accuracy, reply quality, speed.

Usage: score.py results.jsonl [more.jsonl ...]
"""
import json, re, sys

TELLS = [r"\bcertainly\b", r"\bi hope this helps\b", r"\blet me know if\b", r"\bfeel free\b", r"—",
         r"<think>", r"</think>", r"\bas an ai\b", r"\bdelve\b", r"\bgreat question\b"]


def flat(out):
    try:
        return json.loads(out)
    except Exception:
        return None


def check(r):
    k, out, exp = r["kind"], r["out"], r["expect"]
    if k in ("router", "orchestrator"):
        d = flat(out)
        if not isinstance(d, dict):
            return False, "invalid JSON"
        for key, want in exp.items():
            if key.endswith("~"):
                if want.lower() not in str(d.get(key[:-1], "")).lower():
                    return False, f"{key[:-1]}={d.get(key[:-1])!r}"
            elif str(d.get(key, "")).lower() != want.lower():
                return False, f"{key}={d.get(key)!r}"
        return True, ""
    tells = [t for t in TELLS if re.search(t, out, re.I)]
    if k == "reply":
        sentences = len(re.findall(r"[.!?](\s|$)", out.strip())) or 1
        if tells:
            return False, "tells: " + ", ".join(tells)
        if sentences > 2 or len(out) > 220:
            return False, f"too long ({len(out)} chars)"
        return bool(out.strip()), ""
    return (not tells and bool(out.strip())), ("tells: " + ", ".join(tells)) if tells else ""


for path in sys.argv[1:]:
    rows = [json.loads(l) for l in open(path) if l.startswith("{")]
    by = {}
    print(f"\n=== {path}")
    for r in rows:
        ok, why = check(r)
        by.setdefault(r["kind"], []).append(ok)
        mark = "PASS" if ok else "FAIL"
        print(f"{mark} {r['id']:<14} {r['ms']:>6} ms  {r['out'][:90]!r} {why}")
    total = sum(sum(v) for v in by.values())
    print("--- " + "  ".join(f"{k}: {sum(v)}/{len(v)}" for k, v in by.items()) +
          f"  | total {total}/{len(rows)}  | avg {sum(r['ms'] for r in rows) // len(rows)} ms/case")
