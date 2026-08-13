import sys

def check(path):
    src = open(path, encoding="utf-8").read()
    brace = paren = brack = 0
    i, n = 0, len(src)
    in_line = in_block = in_str = in_char = in_tstr = False
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ""
        if in_line:
            if c == "\n":
                in_line = False
        elif in_block:
            if c == "*" and nxt == "/":
                in_block = False
                i += 1
        elif in_tstr:
            if c == '"' and src[i:i + 3] == '"""':
                in_tstr = False
                i += 2
        elif in_str:
            if c == "\\":
                i += 1
            elif c == "$" and nxt == "{":
                depth = 1
                i += 2
                while i < n and depth:
                    if src[i] == "{":
                        depth += 1
                    elif src[i] == "}":
                        depth -= 1
                    elif src[i] == '"':
                        while i + 1 < n and src[i + 1] != '"':
                            if src[i + 1] == "\\":
                                i += 1
                            i += 1
                        i += 1
                    i += 1
                i -= 1
            elif c == '"':
                in_str = False
        elif in_char:
            if c == "\\":
                i += 1
            elif c == "'":
                in_char = False
        else:
            if c == "/" and nxt == "/":
                in_line = True
                i += 1
            elif c == "/" and nxt == "*":
                in_block = True
                i += 1
            elif c == '"' and src[i:i + 3] == '"""':
                in_tstr = True
                i += 2
            elif c == '"':
                in_str = True
            elif c == "'":
                in_char = True
            elif c == "{":
                brace += 1
            elif c == "}":
                brace -= 1
            elif c == "(":
                paren += 1
            elif c == ")":
                paren -= 1
            elif c == "[":
                brack += 1
            elif c == "]":
                brack -= 1
        i += 1
    status = "OK" if brace == paren == brack == 0 else "FAIL"
    print(f"{path}: brace={brace} paren={paren} brack={brack} {status}")
    return brace == paren == brack == 0

ok = all(check(p) for p in sys.argv[1:])
sys.exit(0 if ok else 1)
