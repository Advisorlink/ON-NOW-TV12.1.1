#!/usr/bin/env python3
"""Kotlin brace/paren/bracket balance checker (comments & strings stripped)."""
import sys
import glob


def strip_code(code: str) -> str:
    out = []
    i, n = 0, len(code)
    line_comment = False
    block_depth = 0
    in_string = in_triple = in_char = False
    while i < n:
        c = code[i]
        nxt = code[i + 1] if i + 1 < n else ""
        if line_comment:
            if c == "\n":
                line_comment = False
                out.append(c)
            i += 1
            continue
        if block_depth:
            if c == "/" and nxt == "*":
                block_depth += 1
                i += 2
                continue
            if c == "*" and nxt == "/":
                block_depth -= 1
                i += 2
                continue
            if c == "\n":
                out.append(c)
            i += 1
            continue
        if in_triple:
            if code.startswith('"""', i):
                in_triple = False
                i += 3
                continue
            if c == "\n":
                out.append(c)
            i += 1
            continue
        if in_string:
            if c == "\\":
                i += 2
                continue
            if c == '"':
                in_string = False
            i += 1
            continue
        if in_char:
            if c == "\\":
                i += 2
                continue
            if c == "'":
                in_char = False
            i += 1
            continue
        if c == "/" and nxt == "/":
            line_comment = True
            i += 2
            continue
        if c == "/" and nxt == "*":
            block_depth = 1
            i += 2
            continue
        if code.startswith('"""', i):
            in_triple = True
            i += 3
            continue
        if c == '"':
            in_string = True
            i += 1
            continue
        if c == "'":
            in_char = True
            i += 1
            continue
        out.append(c)
        i += 1
    return "".join(out)


def check(path: str) -> bool:
    with open(path, encoding="utf-8") as f:
        code = f.read()
    stripped = strip_code(code)
    pairs = {"{": "}", "(": ")", "[": "]"}
    closers = {v: k for k, v in pairs.items()}
    counts = {k: 0 for k in pairs}
    ok = True
    line = 1
    for ch in stripped:
        if ch == "\n":
            line += 1
        elif ch in pairs:
            counts[ch] += 1
        elif ch in closers:
            counts[closers[ch]] -= 1
            if counts[closers[ch]] < 0:
                print(f"FAIL {path}: extra '{ch}' at line {line}")
                return False
    for opener, bal in counts.items():
        if bal != 0:
            print(f"FAIL {path}: '{opener}{pairs[opener]}' imbalance = {bal:+d}")
            ok = False
    if ok:
        print(f"OK   {path}")
    return ok


if __name__ == "__main__":
    files = sys.argv[1:] or glob.glob("/app/android/**/*.kt", recursive=True)
    failed = [f for f in files if not check(f)]
    sys.exit(1 if failed else 0)
