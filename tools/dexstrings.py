import argparse
import struct
import os
from pathlib import Path

def uleb128(b, o):
    r = 0; s = 0
    while True:
        x = b[o]; o += 1
        r |= (x & 0x7f) << s
        if not (x & 0x80): break
        s += 7
    return r, o

def mutf8(b, o):
    # decode modified utf-8 up to NUL
    out = []
    n = len(b)
    while o < n:
        c = b[o]
        if c == 0:
            break
        if c < 0x80:
            out.append(chr(c)); o += 1
        elif (c & 0xe0) == 0xc0:
            out.append(chr(((c & 0x1f) << 6) | (b[o+1] & 0x3f))); o += 2
        elif (c & 0xf0) == 0xe0:
            out.append(chr(((c & 0x0f) << 12) | ((b[o+1] & 0x3f) << 6) | (b[o+2] & 0x3f))); o += 3
        else:
            o += 1
    return "".join(out).encode("utf-8", "surrogatepass").decode("utf-8", "replace")

def parse_dex(path):
    b = open(path, "rb").read()
    if b[:4] != b"dex\n":
        raise SystemExit("not dex: " + path)
    (string_ids_size, string_ids_off, type_ids_size, type_ids_off,
     proto_ids_size, proto_ids_off, field_ids_size, field_ids_off,
     method_ids_size, method_ids_off, class_defs_size, class_defs_off) = struct.unpack_from("<12I", b, 56)
    strings = []
    for i in range(string_ids_size):
        off, = struct.unpack_from("<I", b, string_ids_off + 4*i)
        _, p = uleb128(b, off)
        strings.append(mutf8(b, p))
    types = []
    for i in range(type_ids_size):
        idx, = struct.unpack_from("<I", b, type_ids_off + 4*i)
        types.append(strings[idx] if idx < len(strings) else "?")
    methods = []
    for i in range(method_ids_size):
        cidx, pidx, nidx = struct.unpack_from("<HHI", b, method_ids_off + 8*i)
        cls = types[cidx] if cidx < len(types) else "?"
        name = strings[nidx] if nidx < len(strings) else "?"
        methods.append((cls, name))
    classes = []
    for i in range(class_defs_size):
        cidx, = struct.unpack_from("<I", b, class_defs_off + 32*i)
        classes.append(types[cidx] if cidx < len(types) else "?")
    return strings, types, methods, classes

if __name__ == "__main__":
    root = Path(__file__).resolve().parent.parent
    parser = argparse.ArgumentParser(description="Dump strings, classes and methods from extracted DEX files")
    parser.add_argument("--input", type=Path, default=root / "_extract")
    parser.add_argument("--output", type=Path, default=root / "dexmeta")
    args = parser.parse_args()
    paths = sorted(args.input.glob("classes*.dex"))
    if not paths:
        parser.error("no classes*.dex found; run tools/extract.py first")
    outdir = args.output
    os.makedirs(outdir, exist_ok=True)
    allstr = set(); allcls = set(); allmeth = set()
    for p in paths:
        s, t, m, c = parse_dex(p)
        print(p, "strings", len(s), "types", len(t), "methods", len(m), "classes", len(c))
        allstr.update(s); allcls.update(c)
        allmeth.update("%s->%s" % (a, b) for a, b in m)
    with open(os.path.join(outdir, "strings.txt"), "w", encoding="utf-8") as f:
        for x in sorted(allstr):
            f.write(x.replace("\n", "\\n") + "\n")
    with open(os.path.join(outdir, "classes.txt"), "w", encoding="utf-8") as f:
        for x in sorted(allcls):
            f.write(x + "\n")
    with open(os.path.join(outdir, "methods.txt"), "w", encoding="utf-8") as f:
        for x in sorted(allmeth):
            f.write(x + "\n")
    print("unique strings", len(allstr), "classes", len(allcls), "methods", len(allmeth))
