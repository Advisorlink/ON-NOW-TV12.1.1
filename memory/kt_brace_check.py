import re, sys

def check(path):
    src = open(path, encoding="utf-8").read()
    src = re.sub(r'"""[\s\S]*?"""', '""', src)
    src = re.sub(r'"(?:\\.|[^"\\\n])*"', '""', src)
    src = re.sub(r"'(?:\\.|[^'\\\n])'", "''", src)
    src = re.sub(r"//[^\n]*", "", src)
    src = re.sub(r"/\*[\s\S]*?\*/", "", src)
    b = src.count("{") - src.count("}")
    p = src.count("(") - src.count(")")
    k = src.count("[") - src.count("]")
    print(f"{path}: brace={b} paren={p} brack={k} {'OK' if b == p == k == 0 else 'CHECK'}")

for f in sys.argv[1:]:
    check(f)
