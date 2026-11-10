#!/usr/bin/env python3
# Spektrafilm for Android — inventory every app-owned class/member that native code
# resolves BY NAME, generated from the JNI sources so the list cannot drift (#463).
# Port of spektrafilm (GPLv3) by Andrea Volpato — film modeling powered by spektrafilm.
#
# WHY: R8 cannot see a JNI call edge. A member reached only from C++ by literal string
# survives today because proguard-rules.pro keeps com.spectrafilm.** wholesale; narrow
# that rule and the member disappears with every gate still green (the kotlin.Triple
# defect fixed before 1.0; see docs/MAINTAINERS.md). check_release_dex.sh asserts every line printed here exists in
# the minified dex, so a removed member fails the release instead of rendering 0.0.
#
# OUTPUT: one tab-separated line per requirement, sorted and de-duplicated:
#   <descriptor> \t class
#   <descriptor> \t method|field \t <name> \t <signature or empty>
# Platform types (java/*, android/*) are not printed: R8 never removes framework members.
#
# FAIL-CLOSED: a JNI literal this script cannot attribute to a class exits 1 with the
# literal and its line. Extend the rules below; never let an unknown literal pass.
#
# USAGE: tools/r8_check/jni_inventory.py [repo-root]   (default: two levels up)
import re
import sys
from pathlib import Path

ROOT = Path(sys.argv[1]) if len(sys.argv) > 1 else Path(__file__).resolve().parents[2]

JNI_SOURCES = [
    "engine/spektra-core/src/main/cpp/spektra_jni.cpp",
    "lib/libraw/src/main/cpp/raw_decoder_jni.cpp",
    "lib/pngwriter/src/main/cpp/png_writer_jni.cpp",
    "lib/tiffwriter/src/main/cpp/tiff_writer_jni.cpp",
]
# Kotlin whose properties the engine's getter-by-name helpers read.
ENGINE_KOTLIN = ["engine/spektra-core/src/main/kotlin/com/spectrafilm/engine"]
ENGINE_PACKAGE = "com/spectrafilm/engine"

# Helpers in spektra_jni.cpp that call a no-arg getter named by their 3rd argument on
# an object of dynamic class. Value = JNI return descriptor (None: the 4th arg is it).
GETTER_HELPERS = {
    "read_float": "F",
    "read_bool_i32": "Z",
    "read_int": "I",
    "read_triple_f": "Lkotlin/Triple;",
    "read_pair_f": "Lkotlin/Pair;",
    "call_obj": None,
}

# GetMethodID/GetFieldID literals on a receiver from GetObjectClass (dynamic class),
# outside the helpers above. Each maps to the app class it targets, or None when the
# receiver is a platform type (ByteBuffer, Float, Enum, AtomicBoolean) R8 never touches.
# kotlin.Pair/Triple getters reached through tuple_component are listed explicitly.
DYNAMIC_RECEIVER_LITERALS = {
    ("position", "()I"): None,                      # java.nio.Buffer
    ("limit", "()I"): None,                         # java.nio.Buffer
    ("capacity", "()I"): None,                      # java.nio.Buffer
    ("floatValue", "()F"): None,                    # java.lang.Float
    ("ordinal", "()I"): None,                       # java.lang.Enum
    ("get", "()Z"): None,                           # AtomicBoolean cancellation signal
    ("isCancellationRequested", "()Z"): "Lcom/spectrafilm/engine/RenderCancellation;",
}
# The tuple reader: getter first, backing field as the fallback (spektra_jni.cpp).
TUPLE_MEMBERS = [
    ("Lkotlin/Triple;", "method", "getFirst", "()Ljava/lang/Object;"),
    ("Lkotlin/Triple;", "method", "getSecond", "()Ljava/lang/Object;"),
    ("Lkotlin/Triple;", "method", "getThird", "()Ljava/lang/Object;"),
    ("Lkotlin/Pair;", "method", "getFirst", "()Ljava/lang/Object;"),
    ("Lkotlin/Pair;", "method", "getSecond", "()Ljava/lang/Object;"),
]

KOTLIN_TYPE_TO_JNI = {
    "Float": "F", "Boolean": "Z", "Int": "I", "Long": "J", "Double": "D",
    "String": "Ljava/lang/String;", "FloatArray": "[F", "IntArray": "[I", "LongArray": "[J",
    "Triple": "Lkotlin/Triple;", "Pair": "Lkotlin/Pair;", "List": "Ljava/util/List;",
}

errors: list[str] = []
out: set[tuple[str, ...]] = set()


def is_app(desc: str) -> bool:
    return desc.startswith("Lcom/spectrafilm/") or desc.startswith("Lkotlin/")


def add(*row: str) -> None:
    if is_app(row[0]):
        out.add(row)


def strip_comments(src: str) -> str:
    src = re.sub(r"/\*.*?\*/", lambda m: "\n" * m.group(0).count("\n"), src, flags=re.S)
    return re.sub(r"//[^\n]*", "", src)


def line_of(src: str, pos: int) -> int:
    return src.count("\n", 0, pos) + 1


def unmangle(s: str) -> str:
    # JNI short-name mangling: _1 = '_', _00024 = '$' (other escapes are not used here).
    return s.replace("_00024", "$").replace("_1", "\x00").replace("_", "/").replace("\x00", "_")


# ---- Kotlin engine model: class descriptor -> {getter name: JNI return type} ---------
def kotlin_getters() -> dict[str, dict[str, str]]:
    classes: dict[str, dict[str, str]] = {}
    decl = re.compile(r"^(?:(?:data|enum|internal|private|sealed|open|abstract)\s+)*class\s+(\w+)", re.M)
    prop = re.compile(r"\b(val|var)\s+(\w+)\s*:\s*([\w.]+)(\??)")
    fun = re.compile(r"\bfun\s+(\w+)\s*\(\s*\)\s*:\s*([\w.]+)")
    for d in ENGINE_KOTLIN:
        for f in sorted((ROOT / d).glob("*.kt")):
            src = strip_comments(f.read_text(encoding="utf-8"))
            # Top-level classes only (header at column 0): the engine's params are flat
            # data classes. Members between two headers belong to the first.
            heads = [(m.start(), m.group(1)) for m in decl.finditer(src)]
            for i, (start, name) in enumerate(heads):
                end = heads[i + 1][0] if i + 1 < len(heads) else len(src)
                body = src[start:end]
                getters = classes.setdefault(f"L{ENGINE_PACKAGE}/{name};", {})
                for m in prop.finditer(body):
                    pname, ktype, nullable = m.group(2), m.group(3).split(".")[-1], m.group(4)
                    jni = KOTLIN_TYPE_TO_JNI.get(ktype, f"L{ENGINE_PACKAGE}/{ktype};")
                    if nullable and jni in ("F", "Z", "I", "J", "D"):
                        jni = {"F": "Ljava/lang/Float;", "Z": "Ljava/lang/Boolean;", "I": "Ljava/lang/Integer;",
                               "J": "Ljava/lang/Long;", "D": "Ljava/lang/Double;"}[jni]
                    getter = pname if re.match(r"is[A-Z]", pname) else "get" + pname[0].upper() + pname[1:]
                    getters[getter] = jni
                for m in fun.finditer(body):
                    ktype = m.group(2).split(".")[-1]
                    getters[m.group(1)] = KOTLIN_TYPE_TO_JNI.get(ktype, f"L{ENGINE_PACKAGE}/{ktype};")
    return classes


KOTLIN = kotlin_getters()


def resolve_getter(name: str, ret: str, where: str) -> None:
    owners = [c for c, g in KOTLIN.items() if g.get(name) == ret]
    if not owners:
        # A declared type the JNI widens (e.g. call_obj(..., "()Ljava/lang/String;") on a
        # String? property) still matches by name; a name nobody declares is drift.
        owners = [c for c, g in KOTLIN.items() if name in g and ret.startswith("L") and g[name].startswith("L")]
        if owners:
            ret = ""  # name-only: the Kotlin and JNI spellings of the type differ
    if not owners:
        errors.append(f"{where}: getter {name} {ret} matches no property in {ENGINE_PACKAGE}")
        return
    # A name shared by several param classes (getStrength) is required on each of them:
    # fail-closed is the point, and they are all kept today.
    for c in owners:
        add(c, "method", name, f"(){ret}" if ret else "")


# ---- scan the JNI sources --------------------------------------------------------------
lookup = re.compile(r"\b(GetMethodID|GetStaticMethodID|GetFieldID|GetStaticFieldID)\s*\(\s*([^,]+?)\s*,\s*\"([^\"]+)\"\s*,\s*\"([^\"]+)\"\s*\)", re.S)
findclass = re.compile(r"(\w+)\s*(?:=|\()\s*(?:\w+(?:->|\.))?\s*(?:env\s*(?:->|\.)\s*)?FindClass\s*\(\s*\"([^\"]+)\"", re.S)
getobjectclass = re.compile(r"(\w+)\s*(?:=|\()\s*(?:\w+(?:->|\.))?\s*GetObjectClass\s*\(", re.S)
findclass_any = re.compile(r"FindClass\s*\(\s*\"([^\"]+)\"")
getter_call = re.compile(r"\b(" + "|".join(GETTER_HELPERS) + r")\s*\(\s*env\s*,\s*[^,()]+,\s*\"(\w+)\"(?:\s*,\s*\"([^\"]+)\")?")
macro_def = re.compile(r"#define\s+(\w+)\(([^)]*)\)[^\n]*(?:\\\n[^\n]*)*?Java_(\w+?)_##(\w+)")
explicit_native = re.compile(r"\bJava_(com_spectrafilm_\w+)\s*\(")

for rel in JNI_SOURCES:
    path = ROOT / rel
    if not path.is_file():
        errors.append(f"missing JNI source {rel}")
        continue
    src = strip_comments(path.read_text(encoding="utf-8"))

    for m in findclass_any.finditer(src):
        add(f"L{m.group(1)};", "class")

    # Variable -> class for FindClass results, in source order (the nearest preceding
    # assignment wins, so reused names like `cls` resolve per site).
    assigns = [(m.start(), m.group(1), f"L{m.group(2)};") for m in findclass.finditer(src)]
    # GetObjectClass results are dynamic (None): a later lookup on that variable must not
    # inherit an earlier FindClass of the same name (`cls` is reused all over).
    assigns += [(m.start(), m.group(1), None) for m in getobjectclass.finditer(src)]
    assigns.sort()

    for m in lookup.finditer(src):
        fn, recv, name, sig = m.group(1), m.group(2).strip(), m.group(3), m.group(4)
        kind = "field" if "Field" in fn else "method"
        where = f"{rel}:{line_of(src, m.start())}"
        recv_var = re.sub(r"\.get\(\)$", "", recv)
        owner = None
        for pos, var, desc in assigns:
            if pos < m.start() and var == recv_var:
                owner = desc
        if owner is not None and not is_app(owner):
            continue  # FindClass of a platform type: nothing R8 can remove
        if owner is not None:
            add(owner, kind, name, sig)
            continue
        key = (name, sig)
        if key in DYNAMIC_RECEIVER_LITERALS:
            target = DYNAMIC_RECEIVER_LITERALS[key]
            if target:
                add(target, kind, name, sig)
            continue
        errors.append(f"{where}: {fn}({recv}, \"{name}\", \"{sig}\") has no attributable class; "
                      "add it to DYNAMIC_RECEIVER_LITERALS in tools/r8_check/jni_inventory.py")

    for m in getter_call.finditer(src):
        helper, name, sig = m.group(1), m.group(2), m.group(3)
        where = f"{rel}:{line_of(src, m.start())}"
        if helper == "call_obj" and sig is None:
            continue  # call_obj(env, t, getter, ...) inside a helper: argument, not literal
        ret = GETTER_HELPERS[helper] or re.sub(r"^\(\)", "", sig)
        resolve_getter(name, ret, where)

    if "tuple_component(" in src:
        for row in TUPLE_MEMBERS:
            add(*row)
        for owner in ("Lkotlin/Triple;", "Lkotlin/Pair;"):
            for fld in (["first", "second", "third"] if "Triple" in owner else ["first", "second"]):
                add(owner, "field", fld, "Ljava/lang/Object;")

    # Native methods: `#define MACRO(..., name) ... Java_<pkg>_<Class>_##name`, then each
    # MACRO(...) invocation names one external fun; plus explicit Java_... definitions.
    for d in macro_def.finditer(src):
        macro, params, prefix, param = d.group(1), [p.strip() for p in d.group(2).split(",")], d.group(3), d.group(4)
        if param not in params:
            continue
        idx = params.index(param)
        owner = "L" + unmangle(prefix) + ";"
        for inv in re.finditer(r"(?<![#\w])" + macro + r"\s*\(([^()]*)\)", src[d.end():]):
            args = [a.strip() for a in inv.group(1).split(",")]
            if len(args) == len(params) and re.fullmatch(r"\w+", args[idx]):
                add(owner, "method", args[idx].replace("_1", "_"), "")
    for m in explicit_native.finditer(src):
        parts = m.group(1).split("_")
        # com_spectrafilm_<pkg>_<Class>_<method...>: class segments start upper-case.
        cls_end = next(i for i, p in enumerate(parts) if p[:1].isupper())
        owner = "L" + "/".join(parts[:cls_end + 1]) + ";"
        add(owner, "method", "_".join(parts[cls_end + 1:]), "")

if errors:
    for e in errors:
        print(f"FAIL: {e}", file=sys.stderr)
    sys.exit(1)

for row in sorted(out):
    print("\t".join(row))
