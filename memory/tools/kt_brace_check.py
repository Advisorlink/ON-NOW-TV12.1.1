import sys

def check(path):
    src = open(path, encoding='utf-8').read()
    stack = []
    line = 1
    i = 0
    n = len(src)
    in_str = in_chr = in_lc = in_bc = False
    while i < n:
        c = src[i]
        nxt = src[i + 1] if i + 1 < n else ''
        if c == '\n':
            line += 1
            in_lc = False
            i += 1
            continue
        if in_lc:
            i += 1
            continue
        if in_bc:
            if c == '*' and nxt == '/':
                in_bc = False
                i += 2
                continue
            i += 1
            continue
        if in_str:
            if c == '\\':
                i += 2
                continue
            if c == '"':
                if src[i:i + 3] == '"""':
                    i += 3
                else:
                    i += 1
                in_str = False
                continue
            i += 1
            continue
        if in_chr:
            if c == '\\':
                i += 2
                continue
            if c == "'":
                in_chr = False
            i += 1
            continue
        if c == '/' and nxt == '/':
            in_lc = True
            i += 2
            continue
        if c == '/' and nxt == '*':
            in_bc = True
            i += 2
            continue
        if c == '"':
            if src[i:i + 3] == '"""':
                end = src.find('"""', i + 3)
                if end == -1:
                    print(f"FAIL {path}: unterminated raw string at line {line}")
                    return False
                line += src[i:end].count('\n')
                i = end + 3
                continue
            in_str = True
            i += 1
            continue
        if c == "'":
            in_chr = True
            i += 1
            continue
        if c in '({[':
            stack.append((c, line))
            i += 1
            continue
        if c in ')}]':
            pair = {')': '(', '}': '{', ']': '['}[c]
            if not stack or stack[-1][0] != pair:
                print(f"FAIL {path}: unmatched '{c}' at line {line}")
                return False
            stack.pop()
            i += 1
            continue
        i += 1
    if stack:
        c, l = stack[-1]
        print(f"FAIL {path}: unclosed '{c}' opened at line {l}")
        return False
    print(f"OK   {path}")
    return True

ok = all(check(p) for p in sys.argv[1:])
sys.exit(0 if ok else 1)
