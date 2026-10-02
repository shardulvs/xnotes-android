#!/usr/bin/env python3
"""Helpers for tools/pdfium-sync.sh; see that script for the overall flow.

deps          prints the pinned git deps of a DEPS file
var           prints one DEPS variable
probe         turns GN's project.json per ABI into graph.json plus a probe CMake project
vendor        reads the probe builds, copies the files they used, writes pdfium.cmake
same-symbols  checks that two links define the same symbols
"""

import json
import os
import re
import shutil
import subprocess
import sys

ROOT_TARGET = "//:pdfium"
# Never compiled: libc++ comes from the NDK, and PDFium needs no ICU data file.
SKIP_TARGETS = ("//buildtools/third_party/libc++", "//third_party/icu:icudata",
                "//third_party/icu:copy_icudata")
# The NDK has no NASM, and NASM output differs across versions (reproducible
# builds), so x86_64 takes libjpeg-turbo's plain C path.
ABI_DROP_TARGETS = {"x86_64": {"//third_party/libjpeg_turbo:simd"}}
ABI_DROP_DEFINES = {"x86_64": {"WITH_SIMD"}}
# Flags for Chromium's toolchain and diagnostics, flags clang 18 (NDK r27) lacks,
# and -fstack-protector, which would weaken the NDK's -fstack-protector-strong.
DROP_FLAGS = {"-fcolor-diagnostics", "-fdiagnostics-show-inlining-chain",
              "-no-canonical-prefixes", "-fno-lifetime-dse",
              "-gsimple-template-names", "-fstack-protector"}
DROP_PREFIXES = ("--target=", "--sysroot=", "-fcrash-diagnostics-dir=",
                 "-fsanitize-ignore-for-ubsan-feature=", "-ffile-compilation-dir=",
                 "-Wa,-fdebug-compilation-dir")
DROP_PAIRS = {"-mllvm", "-Xclang"}
DROP_DEFINE_PREFIXES = ("ANDROID_NDK_VERSION_ROLL=",)
COMPILED = (".c", ".cc", ".cpp", ".S")
# Public API left out of the probe's roots: xnotes writes PDFs with PdfBox, and
# PDFium's writer alone pulls in HarfBuzz (font subsetting).
EXCLUDED_API = {"fpdf_save.h"}
LICENSE_RE = re.compile(r"^(LICEN[CS]E|COPYING|COPYRIGHT|NOTICE|README\.(chromium|pdfium))"
                        r"([.\-_].*)?$", re.I)
# License texts that the per-directory LICENSE files only point to.
EXTRA_LICENSES = ["third_party/freetype/src/docs/FTL.TXT"]


def load_deps(path):
    """Evaluates a gclient DEPS file the way gclient does for plain git deps."""
    ns = {}
    ns["Var"] = lambda name: ns["vars"][name]
    ns["Str"] = lambda s: s
    with open(path) as f:
        exec(f.read(), ns)
    return ns


def cmd_deps(deps_file, *paths):
    deps = load_deps(deps_file)["deps"]
    for p in paths:
        entry = deps.get(p)
        if entry is None:
            sys.exit(f"DEPS has no entry {p}")
        url = entry if isinstance(entry, str) else entry.get("url")
        if not url or "@" not in url:
            sys.exit(f"DEPS entry {p} is not a pinned git dep")
        repo, rev = url.rsplit("@", 1)
        print(p, repo, rev)


def cmd_var(deps_file, name):
    print(load_deps(deps_file)["vars"][name])


def filter_flags(flags):
    out, skip = [], False
    for f in flags:
        if skip:
            skip = False
        elif f in DROP_PAIRS:
            skip = True
        elif f in DROP_FLAGS or f.startswith(DROP_PREFIXES):
            pass
        elif f.startswith("-W") and not f.startswith(("-Wa,", "-Wl,", "-Wp,")):
            pass
        else:
            out.append(f)
    return out


def rel(label_path):
    """GN source-absolute path to a path relative to the PDFium root."""
    assert label_path.startswith("//"), label_path
    return label_path[2:].rstrip("/") or "."


def target_name(label):
    path, name = label[2:].split(":")
    base = path if path.split("/")[-1] == name else f"{path}_{name}"
    return re.sub(r"[^A-Za-z0-9]+", "_", base).strip("_") or name


def load_abi(abi, project_json):
    """The compiled targets reachable from ROOT_TARGET, normalized."""
    p = json.load(open(project_json))
    targets = p["targets"]
    build_dir = p["build_settings"]["build_dir"]
    drop = ABI_DROP_TARGETS.get(abi, set())
    seen, stack = set(), [ROOT_TARGET]
    while stack:
        label = stack.pop()
        if label in seen:
            continue
        seen.add(label)
        for d in targets[label].get("deps", []):
            # Other toolchains only build host tools (NASM).
            if "(" in d or d.startswith(SKIP_TARGETS) or d in drop:
                continue
            stack.append(d)
    out = {}
    for label in sorted(seen):
        t = targets[label]
        sources = sorted(rel(s) for s in t.get("sources", []) if s.endswith(COMPILED))
        if not sources:
            continue
        kind = {"source_set": "OBJECT", "static_library": "STATIC"}.get(t["type"])
        if kind is None:
            sys.exit(f"{abi}: {label} is a {t['type']} with sources")
        includes = []
        for d in t.get("include_dirs", []):
            if d.startswith(build_dir):
                continue  # nothing reachable is generated
            includes.append(rel(d))
        defines = [d for d in t.get("defines", [])
                   if not d.startswith(DROP_DEFINE_PREFIXES)
                   and d not in ABI_DROP_DEFINES.get(abi, set())]
        out[label] = {
            "name": target_name(label),
            "kind": kind,
            "sources": sources,
            "defines": defines,
            "includes": includes,
            "flags": filter_flags(t.get("cflags", [])),
            "cflags": filter_flags(t.get("cflags_c", [])),
            "cxxflags": filter_flags(t.get("cflags_cc", [])),
        }
    names = [r["name"] for r in out.values()]
    dups = {n for n in names if names.count(n) > 1}
    if dups:
        sys.exit(f"{abi}: target names collide: {sorted(dups)}")
    return out


def cmake_quote(s):
    if ";" in s:
        sys.exit(f"cannot express {s!r} in a CMake list")
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"').replace("$", "\\$") + '"'


def cmake_target(rec, indent):
    pad = " " * indent
    lines = [f"{pad}pdfium_library({rec['name']} {rec['kind']}"]
    for key, field in (("SOURCES", "sources"), ("DEFINES", "defines"),
                       ("INCLUDES", "includes"), ("FLAGS", "flags"),
                       ("CFLAGS", "cflags"), ("CXXFLAGS", "cxxflags")):
        if rec[field]:
            lines.append(f"{pad}  {key}")
            lines += [f"{pad}    {cmake_quote(v)}" for v in rec[field]]
    lines.append(f"{pad})")
    return "\n".join(lines)


CMAKE_HEADER = """\
# Generated by tools/pdfium-sync.sh from PDFium {rev}. Do not edit.
#
# One CMake library per GN target that the pdfium target reaches: source_set
# becomes OBJECT and static_library becomes STATIC, with GN's resolved defines,
# include dirs and flags minus Chromium's toolchain-only ones. What every target
# of an ABI shares sits in the PDFIUM_BASE_* lists of that ABI. The includer sets
# PDFIUM_DIR and links ${{PDFIUM_LIBRARIES}}.

set(PDFIUM_REVISION {rev})

function(pdfium_library name kind)
  cmake_parse_arguments(PARSE_ARGV 2 A "" ""
    "SOURCES;DEFINES;INCLUDES;FLAGS;CFLAGS;CXXFLAGS")
  list(TRANSFORM A_SOURCES PREPEND "${{PDFIUM_DIR}}/")
  list(TRANSFORM A_INCLUDES PREPEND "${{PDFIUM_DIR}}/")
  add_library(pdfium_${{name}} ${{kind}} ${{A_SOURCES}})
  target_compile_definitions(pdfium_${{name}} PRIVATE
    ${{PDFIUM_BASE_DEFINES}} ${{A_DEFINES}})
  target_include_directories(pdfium_${{name}} PRIVATE ${{A_INCLUDES}})
  # -w: vendored code; the prefix maps keep build paths out of the binary.
  target_compile_options(pdfium_${{name}} PRIVATE ${{PDFIUM_BASE_FLAGS}} ${{A_FLAGS}}
    "$<$<COMPILE_LANGUAGE:C>:${{PDFIUM_BASE_CFLAGS}};${{A_CFLAGS}}>"
    "$<$<COMPILE_LANGUAGE:CXX>:${{PDFIUM_BASE_CXXFLAGS}};${{A_CXXFLAGS}}>"
    -w "-ffile-prefix-map=${{PDFIUM_DIR}}/=" "-ffile-prefix-map=${{ANDROID_NDK}}/=ndk/")
endfunction()
"""
BASE_FIELDS = (("PDFIUM_BASE_DEFINES", "defines"), ("PDFIUM_BASE_FLAGS", "flags"),
               ("PDFIUM_BASE_CFLAGS", "cflags"), ("PDFIUM_BASE_CXXFLAGS", "cxxflags"))


def last_opt(flags):
    return next((f for f in reversed(flags) if f.startswith("-O")), None)


def split_base(recs):
    """Moves what every target shares into a base; the targets keep the rest.
    The ABI's default -O joins the base too: the base comes first on the command
    line, so a target's own -O3 still wins."""
    base = {}
    for _, field in BASE_FIELDS:
        lists = [r[field] for r in recs.values()]
        shared = set(lists[0]).intersection(*map(set, lists[1:])) if lists else set()
        base[field] = [v for v in lists[0] if v in shared] if lists else []
    opts = [f for r in recs.values() for f in r["flags"] if f.startswith("-O")]
    default_opt = max(sorted(set(opts)), key=opts.count) if opts else None
    if default_opt:
        base["flags"].append(default_opt)
    out = {}
    for label, rec in recs.items():
        rest = {f: [v for v in rec[f] if v not in base[f]] for _, f in BASE_FIELDS}
        if last_opt(base["flags"] + rest["flags"]) != last_opt(rec["flags"]):
            sys.exit(f"{label}: the base would change its optimization level")
        out[label] = dict(rec, **rest)
    return base, out


def write_cmake(path, rev, graph, sources=None):
    """Writes pdfium.cmake; `sources` (abi -> set) keeps only those files."""
    abis = sorted(graph)
    bases, per_abi = {}, {}
    for abi in abis:
        recs = {}
        for label, rec in graph[abi].items():
            if sources is not None:
                rec = dict(rec, sources=[s for s in rec["sources"] if s in sources[abi]])
                if not rec["sources"]:
                    continue
            recs[label] = rec
        bases[abi], per_abi[abi] = split_base(recs)
    labels = sorted(set().union(*(r.keys() for r in per_abi.values())))
    common, specific = [], {abi: [] for abi in abis}
    for label in labels:
        recs = [per_abi[abi].get(label) for abi in abis]
        if all(r is not None for r in recs) and all(r == recs[0] for r in recs):
            common.append(recs[0])
        else:
            for abi, r in zip(abis, recs):
                if r is not None:
                    specific[abi].append(r)
    branches = []
    for i, abi in enumerate(abis):
        body = []
        for var, field in BASE_FIELDS:
            body.append(f"  set({var}")
            body += [f"    {cmake_quote(v)}" for v in bases[abi][field]]
            body.append("  )")
        body += [cmake_target(r, 2) for r in specific[abi]]
        branches.append(f'{"if" if i == 0 else "elseif"}(ANDROID_ABI STREQUAL "{abi}")\n'
                        + "\n".join(body))
    branches.append('else()\n  message(FATAL_ERROR "pdfium.cmake has no lists for ${ANDROID_ABI}")\nendif()')
    parts = [CMAKE_HEADER.format(rev=rev), "\n".join(branches)]
    parts += [cmake_target(r, 0) for r in common]
    # One link order for every ABI and every pruning, whatever block defines a target.
    names = []
    for label in labels:
        name = next(per_abi[abi][label]["name"] for abi in abis if label in per_abi[abi])
        names.append(f"    {name}")
    parts.append("set(PDFIUM_LIBRARIES)\nforeach(name\n" + "\n".join(names) + "\n  )\n"
                 "  if(TARGET pdfium_${name})\n    list(APPEND PDFIUM_LIBRARIES pdfium_${name})\n"
                 "  endif()\nendforeach()")
    with open(path, "w") as f:
        f.write("\n\n".join(parts) + "\n")


def api_functions(src, clang, defines):
    """The public API as our configuration declares it (exports switched on),
    minus the headers in EXCLUDED_API."""
    headers = sorted(h for h in os.listdir(f"{src}/public") if h.endswith(".h"))
    tu = "".join(f'#include "public/{h}"\n' for h in headers)
    cmd = [clang, "--target=aarch64-linux-android26", "-x", "c++", "-std=c++20", "-E",
           "-DCOMPONENT_BUILD", "-DFPDF_IMPLEMENTATION", f"-I{src}", "-"]
    cmd += [f"-D{d}" for d in defines]
    out = subprocess.run(cmd, input=tu, capture_output=True, text=True, check=True).stdout
    # Line markers say which header each declaration came from.
    chunks = re.split(r'^# \d+ "([^"]*)".*$', out, flags=re.M)
    names = set()
    for path, text in zip(chunks[1::2], chunks[2::2]):
        if os.path.basename(path) in EXCLUDED_API:
            continue
        names.update(re.findall(
            r'__attribute__\(\(visibility\("default"\)\)\)[^;(]*?(\w+)\s*\(', text, re.S))
    return headers, sorted(names)


# Configured with -DPDFIUM_DIR and -DPDFIUM_CMAKE: the checkout with the full
# lists, or the vendored tree with the pruned ones.
PROBE_CMAKE = """\
cmake_minimum_required(VERSION 3.22.1)
project(pdfium_probe C CXX)
include("${PDFIUM_CMAKE}")
add_library(pdfium_probe SHARED api.cpp)
target_include_directories(pdfium_probe PRIVATE "${PDFIUM_DIR}")
target_compile_options(pdfium_probe PRIVATE -std=c++20 -g0)
target_link_libraries(pdfium_probe PRIVATE ${PDFIUM_LIBRARIES})
# Linked like the app (app/src/main/cpp/CMakeLists.txt), which exports only its
# JNI entry points: here only the API table. Plus the link map.
file(WRITE ${CMAKE_BINARY_DIR}/exports.ver "{ global: xnotes_pdfium_api; local: *; };")
target_link_options(pdfium_probe PRIVATE -Wl,--gc-sections -Wl,--build-id=none
  -Wl,-z,max-page-size=16384 "-Wl,--version-script=${CMAKE_BINARY_DIR}/exports.ver"
  "-Wl,-Map=${CMAKE_BINARY_DIR}/probe.map")
"""


def cmd_probe(src, work, clang, rev, *abi_jsons):
    graph = {}
    for arg in abi_jsons:
        abi, path = arg.split("=", 1)
        graph[abi] = load_abi(abi, path)
    probe = f"{work}/probe"
    os.makedirs(probe, exist_ok=True)
    with open(f"{work}/graph.json", "w") as f:
        json.dump(graph, f, indent=1, sort_keys=True)
    write_cmake(f"{probe}/pdfium.cmake", rev, graph)
    sdk = graph["arm64-v8a"]["//fpdfsdk:fpdfsdk"]["defines"]
    headers, names = api_functions(src, clang, sdk)
    body = "".join(f'#include "public/{h}"\n' for h in headers)
    body += '\n// Every public function, so the probe link keeps all that they reach.\n'
    body += 'extern "C" __attribute__((visibility("default"), used))\n'
    body += "const void* const xnotes_pdfium_api[] = {\n"
    body += "".join(f"    reinterpret_cast<const void*>(&{n}),\n" for n in names)
    body += "};\n"
    with open(f"{probe}/api.cpp", "w") as f:
        f.write(body)
    with open(f"{probe}/CMakeLists.txt", "w") as f:
        f.write(PROBE_CMAKE)
    for abi, targets in sorted(graph.items()):
        n = sum(len(t["sources"]) for t in targets.values())
        print(f"{abi}: {len(targets)} targets, {n} sources")
    print(f"api: {len(names)} functions")


CONTENT_SECTIONS = (".text", ".rodata", ".data", ".bss", ".tdata", ".tbss",
                    ".init_array", ".fini_array", ".preinit_array")


def object_source(obj, src):
    """CMake object path (CMakeFiles/<t>.dir/<abs source>.o) to a source path."""
    path = "/" + obj.split(".dir/", 1)[1][:-2]
    return path[len(src) + 1:] if path.startswith(src + "/") else None


def linked_sources(map_path, targets, src):
    """Sources with at least one section in the final link, from lld's map."""
    members = {}
    for rec in targets.values():
        if rec["kind"] == "STATIC":
            for s in rec["sources"]:
                key = (f"libpdfium_{rec['name']}.a", os.path.basename(s) + ".o")
                if key in members:
                    sys.exit(f"archive member {key} is ambiguous")
                members[key] = s
    used = set()
    for line in open(map_path):
        cols = line.split(None, 4)
        if len(cols) < 5 or ":(" not in cols[4]:
            continue
        obj, sect = cols[4].rstrip().rsplit(":(", 1)
        if int(cols[2], 16) == 0 or not sect.startswith(CONTENT_SECTIONS):
            continue
        m = re.match(r"(libpdfium_\w+\.a)\((.+)\)$", obj)
        if m:
            used.add(members[(m.group(1), m.group(2))])
        elif obj.startswith("CMakeFiles/pdfium_"):
            s = object_source(obj, src)
            if s:
                used.add(s)
    return used


def included_files(build_dir, ninja, sources, src):
    """Files under src that the given sources' compiles read, from ninja's log."""
    out = subprocess.run([ninja, "-C", build_dir, "-t", "deps"], capture_output=True,
                         text=True, check=True).stdout
    files, keep = set(), False
    for line in out.splitlines():
        if line and not line[0].isspace():
            obj = line.split(": #deps", 1)[0]
            keep = obj.startswith("CMakeFiles/pdfium_") and object_source(obj, src) in sources
        elif keep and line.strip():
            path = os.path.normpath(line.strip())
            if path.startswith(src + "/"):
                files.add(path[len(src) + 1:])
    return files


def license_files(src, files):
    """License and provenance files of every directory the files sit under."""
    dirs = {os.path.dirname(f) for f in files}
    ancestors = set()
    for d in dirs:
        while d not in ancestors:
            ancestors.add(d)
            if not d:
                break
            d = os.path.dirname(d)
    out = set()
    for d in ancestors:
        full = os.path.join(src, d)
        for name in os.listdir(full):
            if LICENSE_RE.match(name) and os.path.isfile(os.path.join(full, name)):
                out.add(os.path.join(d, name))
    return out | {f for f in EXTRA_LICENSES if os.path.exists(os.path.join(src, f))}


BLOCK_BEGIN = "--- pdfium: written by tools/pdfium-sync.sh, do not edit ---"
BLOCK_END = "--- end pdfium ---"


def readme_field(src, d, field):
    for name in ("README.pdfium", "README.chromium"):
        path = f"{src}/{d}/{name}"
        if os.path.exists(path):
            for line in open(path, errors="replace"):
                if line.lower().startswith(field.lower() + ":"):
                    return line.split(":", 1)[1].strip()
    return None


def write_third_party(path, src, rev, branch, files):
    """Lists PDFium and every third_party component the vendored files use."""
    deps = load_deps(f"{src}/DEPS")["deps"]
    lines = [BLOCK_BEGIN,
             "pdfium/           https://pdfium.googlesource.com/pdfium",
             f"                  {branch + ', ' if branch else ''}commit {rev}",
             "                  (BSD-3-Clause, Apache-2.0)"]
    comps = sorted({"/".join(f.split("/")[:2]) for f in files if f.startswith("third_party/")})
    for c in comps:
        entry = deps.get(f"{c}/src") or deps.get(c)
        url = entry if isinstance(entry, str) else (entry or {}).get("url")
        where = (f"{url.rsplit('@', 1)[0].removesuffix('.git')}\n"
                 f"{'':18}commit {url.rsplit('@', 1)[1]}") if url else "in the PDFium tree"
        version = readme_field(src, c, "Version")
        licence = readme_field(src, c, "License")
        lines.append(f"pdfium/{c + '/':28}{where}")
        lines.append(f"{'':18}version {version} ({licence})")
    lines.append(BLOCK_END)
    text = open(path).read() if os.path.exists(path) else ""
    if BLOCK_BEGIN in text:
        head, rest = text.split(BLOCK_BEGIN, 1)
        text = head + "\n".join(lines) + rest.split(BLOCK_END, 1)[1]
    else:
        text = text.rstrip("\n") + "\n\n" + "\n".join(lines) + "\n"
    with open(path, "w") as f:
        f.write(text)


def cmd_vendor(src, work, dest, ninja, rev, branch=None):
    graph = json.load(open(f"{work}/graph.json"))
    kept, files = {}, set()
    for abi, targets in graph.items():
        build = f"{work}/probe-build/{abi}"
        kept[abi] = linked_sources(f"{build}/probe.map", targets, src)
        files |= kept[abi] | included_files(build, ninja, kept[abi], src)
    files |= {f"public/{h}" for h in os.listdir(f"{src}/public") if h.endswith(".h")}
    files |= {f"public/cpp/{h}" for h in os.listdir(f"{src}/public/cpp")}
    files |= license_files(src, files)
    out = f"{dest}/pdfium"
    shutil.rmtree(out, ignore_errors=True)
    for f in sorted(files):
        os.makedirs(os.path.dirname(f"{out}/{f}"), exist_ok=True)
        shutil.copy2(f"{src}/{f}", f"{out}/{f}")
    write_cmake(f"{dest}/pdfium.cmake", rev, graph, kept)
    write_third_party(f"{dest}/THIRD_PARTY", src, rev, branch, files)
    total = {abi: len({s for t in g.values() for s in t["sources"]}) for abi, g in graph.items()}
    for abi in sorted(graph):
        print(f"{abi}: {len(kept[abi])} of {total[abi]} sources reach the link")
    print(f"vendored {len(files)} files")


# Layout-dependent linker veneers, exception-table labels and mapping symbols.
IGNORED_SYMBOLS = re.compile(r"^(GCC_except_table\d+|__CortexA53843419_\w+|__\w*Thunk_\w*|\$.*)$")


def defined_symbols(nm, so):
    out = subprocess.run([nm, "-S", "--defined-only", so], capture_output=True, text=True,
                         check=True).stdout
    syms = set()
    for line in out.splitlines():
        cols = line.split()
        if len(cols) == 4 and not IGNORED_SYMBOLS.match(cols[3]):
            syms.add((cols[3], cols[1], cols[2]))
    return syms


def cmd_same_symbols(nm, full, pruned):
    """Exits non-zero unless both links define the same symbols with the same sizes.
    Bytes can differ: dead sources still pull runtime archive members into a link,
    and lld keeps those members' exception tables."""
    a, b = defined_symbols(nm, full), defined_symbols(nm, pruned)
    if a != b:
        for name, size, kind in sorted(a ^ b)[:20]:
            print(f"{'-' if (name, size, kind) in a else '+'} {kind} {size} {name}")
        sys.exit(f"{len(a - b)} symbols only in {full}, {len(b - a)} only in {pruned}")
    print(f"{len(a)} symbols match")


if __name__ == "__main__":
    commands = {"deps": cmd_deps, "var": cmd_var, "probe": cmd_probe, "vendor": cmd_vendor,
                "same-symbols": cmd_same_symbols}
    if len(sys.argv) < 2 or sys.argv[1] not in commands:
        sys.exit(f"usage: {sys.argv[0]} {'|'.join(commands)} ...")
    commands[sys.argv[1]](*sys.argv[2:])
