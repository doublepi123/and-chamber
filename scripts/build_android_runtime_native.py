#!/usr/bin/env python3
"""Offline native build of the Android runtime PRoot libraries from prepared sources.

STRICTLY OFFLINE. Consumes the tree produced by prepare_android_runtime_sources.py and
never downloads anything. Every component is copied into --work-dir before it is built,
so the prepared sources are never modified.

Outputs use the jniLibs names the app loads (NATIVE_LIBRARY_NAMES) under
--output-dir/<abi>/. Provenance is written to --output-dir/PROVENANCE_NAME, outside the
per-ABI jniLibs directories.
"""

from __future__ import annotations

import argparse
import json
import shutil
import subprocess
import sys
from pathlib import Path


REPO_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_LOCK = REPO_ROOT / "runtime_tools" / "runtime_sources.lock.json"
SUPPORTED_ABIS = ("arm64-v8a", "x86_64")
MIN_API = 26
NDK_VERSION = "29.0.14206865"
TERMUX_PREFIX = "/data/data/com.termux/files/usr"
# Compiled-in loader fallback. The app always sets PROOT_LOADER / PROOT_LOADER_32.
PROOT_UNBUNDLE_LOADER = f"{TERMUX_PREFIX}/libexec/proot"
SHMEM_PATCH = REPO_ROOT / "runtime_tools" / "patches" / "libandroid-shmem-0.7-andchamber-tmpdir.patch"
PROOT_STRING_PATCH = REPO_ROOT / "runtime_tools" / "patches" / "proot-5.1.107.97-andchamber-string-include.patch"
LOADER_INFO_AWK = REPO_ROOT / "runtime_tools" / "patches" / "loader-info-posix-awk.awk"
NDK_TRIPLE = {"arm64-v8a": "aarch64-linux-android", "x86_64": "x86_64-linux-android"}
# 32-bit companion loader per ABI: (ABI name, clang triple, LOADER_ADDRESS from upstream arch.h).
LOADER32 = {
    "arm64-v8a": ("armeabi-v7a", "armv7a-linux-androideabi", 0x20000000),
    "x86_64": ("x86", "i686-linux-android", 0xA0000000),
}
NATIVE_LIBRARY_NAMES = {
    "proot": "libandchamber_android_proot.so",
    "loader": "libandchamber_android_proot_loader.so",
    "loader32": "libandchamber_android_proot_loader32.so",
    "talloc": "libtalloc.so",
    "shmem": "libandroid-shmem.so",
}
PROVENANCE_NAME = "runtime-native-provenance.json"
CROSS_ANSWERS = """Checking uname sysname type: "Linux"
Checking uname machine type: "dontcare"
Checking uname release type: "dontcare"
Checking uname version type: "dontcare"
Checking simple C program: OK
building library support: OK
Checking for large file support: OK
Checking for -D_FILE_OFFSET_BITS=64: OK
Checking for WORDS_BIGENDIAN: OK
Checking for C99 vsnprintf: OK
Checking for HAVE_SECURE_MKSTEMP: OK
rpath library support: OK
-Wl,--version-script support: FAIL
Checking correct behavior of strtoll: OK
Checking correct behavior of strptime: OK
Checking for HAVE_IFACE_GETIFADDRS: OK
Checking for HAVE_IFACE_IFCONF: OK
Checking for HAVE_IFACE_IFREQ: OK
Checking getconf LFS_CFLAGS: OK
Checking for large file support without additional flags: OK
Checking for working strptime: OK
Checking for HAVE_SHARED_MMAP: OK
Checking for HAVE_MREMAP: OK
Checking for HAVE_INCOHERENT_MMAP: OK
Checking getconf large file support flags work: OK
"""


class BuildInputError(RuntimeError):
    """Missing or invalid prepared input. The message names the command that fixes it."""


def prep_hint(sources_dir: Path) -> str:
    return ("run `python3 scripts/prepare_android_runtime_sources.py --output-dir "
            f"{sources_dir}` (the only network step), then retry")


def check_ndk(ndk_dir: Path) -> Path:
    """Return the NDK llvm bin directory after checking the pinned revision."""
    props = ndk_dir / "source.properties"
    if not props.is_file():
        raise BuildInputError(f"not an NDK directory (no source.properties): {ndk_dir}")
    revision = ""
    for line in props.read_text(encoding="utf-8").splitlines():
        if line.startswith("Pkg.Revision"):
            revision = line.split("=", 1)[1].strip()
    if revision != NDK_VERSION:
        raise BuildInputError(f"NDK revision {revision!r} found, expected {NDK_VERSION}")
    prebuilt = ndk_dir / "toolchains" / "llvm" / "prebuilt"
    hosts = sorted(p for p in prebuilt.glob("*") if (p / "bin").is_dir()) if prebuilt.is_dir() else []
    if not hosts:
        raise BuildInputError(f"NDK LLVM toolchain not found under {prebuilt}")
    return hosts[0] / "bin"


def load_lock(lock_file: Path = DEFAULT_LOCK) -> dict:
    """Read the current pinned source lock. Its version, API and components are the contract."""
    try:
        lock = json.loads(lock_file.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BuildInputError(f"cannot read source lock {lock_file}: {error}") from error
    if lock.get("version") != 1:
        raise BuildInputError(f"unsupported source lock version in {lock_file}: {lock.get('version')!r}")
    return lock


def _is_relative_inside(child: Path, parent: Path) -> bool:
    try:
        child.relative_to(parent)
        return True
    except ValueError:
        return False


def load_provenance(sources_dir: Path, lock_file: Path = DEFAULT_LOCK) -> dict:
    """Load sources.json and verify it against the CURRENT lock. Stale or hand-edited manifests fail.

    Checks: lock version, API/NDK, the exact component set (no missing, extra or duplicate entries),
    and per component version, archive filename/URL/SHA-256, commit, license SPDX/file/SHA-256.
    source_root must be the stable path <component>/<archive root_dir> and must resolve inside
    sources_dir. Errors name the preparation command to rerun.
    """
    manifest = sources_dir / "sources.json"
    if not manifest.is_file():
        raise BuildInputError(f"prepared sources missing: {manifest}. {prep_hint(sources_dir)}")
    try:
        provenance = json.loads(manifest.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise BuildInputError(f"prepared sources manifest unreadable: {manifest}: {error}. "
                              f"{prep_hint(sources_dir)}") from error
    lock = load_lock(lock_file)
    stale = f"{manifest} does not match the current lock ({lock_file.name}). {prep_hint(sources_dir)}"

    if provenance.get("lock_version") != lock["version"]:
        raise BuildInputError(f"lock_version mismatch in manifest. {stale}")
    if provenance.get("ndk_version") != lock["android"]["ndk_version"] or provenance.get("ndk_version") != NDK_VERSION:
        raise BuildInputError(f"ndk_version mismatch in manifest. {stale}")
    if provenance.get("min_api") != lock["android"]["min_api"] or provenance.get("min_api") != MIN_API:
        raise BuildInputError(f"min_api mismatch in manifest. {stale}")

    records = provenance.get("components")
    if not isinstance(records, list):
        raise BuildInputError(f"manifest components is not a list. {stale}")
    names = [record.get("component") for record in records]
    duplicates = sorted({name for name in names if names.count(name) > 1})
    if duplicates:
        raise BuildInputError(f"duplicate component entries in manifest: {duplicates}. {stale}")
    expected = lock["components"]
    if set(names) != set(expected):
        raise BuildInputError(f"component set mismatch: manifest {sorted(names)} vs lock {sorted(expected)}. {stale}")

    resolved_sources = sources_dir.resolve()
    for record in records:
        name = record["component"]
        spec = expected[name]
        archive = spec["archive"]
        license_spec = spec["license"]
        expected_fields = {
            "version": spec["version"],
            "archive": archive["filename"],
            "archive_url": archive["url"],
            "archive_sha256": archive["sha256"],
            "commit": spec.get("commit"),
            "license_spdx": license_spec["spdx"],
            "license_file": license_spec["file"],
            "license_sha256": license_spec["sha256"],
            "termux_recipe_commit": spec.get("termux_recipe_commit"),
            "termux_patches": spec.get("termux_patches", []),
        }
        for field, want in expected_fields.items():
            if record.get(field) != want:
                raise BuildInputError(f"{name}: field {field!r} is stale (manifest {record.get(field)!r}, "
                                      f"lock {want!r}). {stale}")
        expected_root = f"{name}/{archive['root_dir']}"
        source_root = record.get("source_root")
        if not isinstance(source_root, str) or source_root != expected_root:
            raise BuildInputError(f"{name}: source_root {source_root!r} must be {expected_root!r}. {stale}")
        if Path(source_root).is_absolute() or ".." in Path(source_root).parts:
            raise BuildInputError(f"{name}: source_root escapes the sources directory. {stale}")
        if not _is_relative_inside((resolved_sources / source_root).resolve(), resolved_sources):
            raise BuildInputError(f"{name}: source_root resolves outside the sources directory. {stale}")
    return provenance


def component_source(sources_dir: Path, provenance: dict, component: str) -> Path:
    for record in provenance["components"]:
        if record["component"] == component:
            path = sources_dir / record["source_root"]
            if not path.is_dir():
                raise BuildInputError(f"prepared {component} tree missing: {path}. {prep_hint(sources_dir)}")
            return path
    raise BuildInputError(f"{component} is not in {sources_dir / 'sources.json'}. {prep_hint(sources_dir)}")


def assert_disjoint(**paths: Path) -> None:
    """Refuse any pair of directories that are equal or nested (either direction), after resolving
    symlinks. Runs before any write or rmtree, so a bad layout deletes nothing."""
    resolved = {name: path.resolve() for name, path in paths.items()}
    names = sorted(resolved)
    for i, left in enumerate(names):
        for right in names[i + 1:]:
            a, b = resolved[left], resolved[right]
            if a == b or _is_relative_inside(a, b) or _is_relative_inside(b, a):
                raise BuildInputError(
                    f"--{left.replace('_', '-')} and --{right.replace('_', '-')} overlap "
                    f"({a} vs {b}); choose disjoint directories")


def fresh_copy(source: Path, work: Path) -> Path:
    if work.exists():
        shutil.rmtree(work)
    shutil.copytree(source, work, symlinks=True)
    return work


def run(cmd: list[str], cwd: Path, env: dict[str, str]) -> None:
    result = subprocess.run(cmd, cwd=cwd, env=env, capture_output=True, text=True, check=False)
    if result.returncode != 0:
        tail = "\n".join((result.stdout + result.stderr).splitlines()[-40:])
        raise RuntimeError(f"command failed ({result.returncode}) in {cwd}: {' '.join(cmd)}\n{tail}")


def base_env(tmp_dir: Path) -> dict[str, str]:
    """Deterministic, host-independent environment for every compiler invocation."""
    tmp_dir.mkdir(parents=True, exist_ok=True)
    return {
        "PATH": "/usr/bin:/bin",
        "LC_ALL": "C",
        "TZ": "UTC",
        "SOURCE_DATE_EPOCH": "1700000000",
        "TMPDIR": str(tmp_dir),
    }


def clang(bin_dir: Path, triple: str) -> str:
    return str(bin_dir / f"{triple}{MIN_API}-clang")


def build_talloc(bin_dir: Path, triple: str, source: Path, work: Path, prefix: Path, env: dict[str, str]) -> None:
    tree = fresh_copy(source, work / "talloc")
    (tree / "cross-answers.txt").write_text(CROSS_ANSWERS, encoding="utf-8")
    cc = clang(bin_dir, triple)
    tool_env = dict(env, CC=cc, AR=str(bin_dir / "llvm-ar"), RANLIB=str(bin_dir / "llvm-ranlib"),
                    STRIP=str(bin_dir / "llvm-strip"),
                    CFLAGS=f"-ffile-prefix-map={work}=. -O2")
    run(["./configure", f"--prefix={prefix}", "--disable-rpath", "--disable-python",
         "--cross-compile", "--cross-answers=cross-answers.txt"], tree, tool_env)
    run(["make", "-j4"], tree, tool_env)
    run(["make", "install"], tree, tool_env)
    install_talloc_as_app_name(prefix)


def install_talloc_as_app_name(prefix: Path) -> None:
    """Rewrite the installed talloc SONAME through its libtalloc.so symlink.

    Upstream installs libtalloc.so.2.5.0 with SONAME libtalloc.so.2. The app ships one file named
    libtalloc.so, and PRoot's NEEDED entry names that file. The SONAME string is replaced in place
    (equal length, NUL padded). Writing the installed libtalloc.so symlink updates its
    libtalloc.so.2.5.0 target, which is the file packaged below; other ELF bytes are unchanged.
    """
    upstream = prefix / "lib" / "libtalloc.so.2.5.0"
    payload = upstream.read_bytes()
    old = b"libtalloc.so.2\0"
    if payload.count(old) != 1:
        raise RuntimeError(f"expected one libtalloc.so.2 SONAME in {upstream}")
    rewritten = payload.replace(old, b"libtalloc.so\0\0\0")
    output = prefix / "lib" / "libtalloc.so"
    output.write_bytes(rewritten)
    output.chmod(0o755)


def build_shmem(bin_dir: Path, triple: str, source: Path, work: Path, prefix: Path, env: dict[str, str]) -> None:
    tree = fresh_copy(source, work / "shmem")
    # AndChamber adaptation: key symlinks go to PROOT_TMP_DIR at runtime (see the patch header).
    run(["patch", "-p1", "--quiet", "-i", str(SHMEM_PATCH)], tree, env)
    cc = clang(bin_dir, triple)
    run([cc, "-fpic", "-std=c11", "-Wall", "-Wextra", "-Werror",
         f"-ffile-prefix-map={work}=.", "-c", "shmem.c", "-o", "shmem.o"], tree, env)
    run([cc, "-shared", "-Wl,--version-script=exports.txt", "-Wl,--build-id=none",
         "-Wl,-soname,libandroid-shmem.so", "shmem.o", "-o", "libandroid-shmem.so", "-llog", "-landroid"],
        tree, env)
    (prefix / "lib").mkdir(parents=True, exist_ok=True)
    (prefix / "include" / "sys").mkdir(parents=True, exist_ok=True)
    shutil.copyfile(tree / "libandroid-shmem.so", prefix / "lib" / "libandroid-shmem.so")
    shutil.copyfile(tree / "shm.h", prefix / "include" / "sys" / "shm.h")


def build_loader(bin_dir: Path, triple: str, proot_src: Path, env: dict[str, str]) -> Path:
    """Primary loader: built by the upstream makefile so its own flags stay in effect.

    Also regenerates loader/loader-info.c with the POSIX-awk tool (mawk lacks strtonum); the
    file is only consumed by the HAS_POKEDATA_WORKAROUND path on AArch64.
    """
    cc = clang(bin_dir, triple)
    run(["make", "loader/loader", f"PROOT_UNBUNDLE_LOADER={PROOT_UNBUNDLE_LOADER}",
         f"CC={cc}", f"LD={cc}", f"STRIP={bin_dir / 'llvm-strip'}",
         f"OBJCOPY={bin_dir / 'llvm-objcopy'}", f"OBJDUMP={bin_dir / 'llvm-objdump'}"], proot_src, env)
    info = proot_src / "loader" / "loader-info.c"
    info.unlink(missing_ok=True)
    readelf = subprocess.run([str(bin_dir / "llvm-readelf"), "-s", str(proot_src / "loader" / "loader")],
                             capture_output=True, text=True, check=True, env=env)
    awk = subprocess.run(["awk", "-f", str(LOADER_INFO_AWK)], input=readelf.stdout, capture_output=True,
                         text=True, check=True, env=env)
    info.write_text(awk.stdout, encoding="utf-8")
    return proot_src / "loader" / "loader"


def build_loader32(bin_dir: Path, abi: str, proot_src: Path, env: dict[str, str]) -> Path:
    """32-bit companion loader. NDK clang has no -m32, so it uses the armv7/i686 target instead."""
    _, triple32, address = LOADER32[abi]
    cc = clang(bin_dir, triple32)
    loader_dir = proot_src / "loader"
    obj_c = loader_dir / "loader-m32.o"
    obj_s = loader_dir / "assembly-m32.o"
    out = loader_dir / "loader-m32"
    flags = dict(env, CFLAGS=f"-ffile-prefix-map={proot_src.parent}=.")
    # arch.h sets LOADER_ARCH_CFLAGS=-mregparm=3 for the i686 loader; arm needs no extra flag.
    arch_flags = ["-mregparm=3"] if abi == "x86_64" else []
    run([cc, "-D_FILE_OFFSET_BITS=64", "-D_GNU_SOURCE", "-I.", "-I./", "-fPIC", "-ffreestanding",
         *arch_flags, "-O2", "-Wall", "-Wextra", "-c", "loader/loader.c", "-o", str(obj_c)], proot_src, flags)
    run([cc, "-I.", "-I./", "-fPIC", "-ffreestanding", *arch_flags, "-c", "loader/assembly.S",
         "-o", str(obj_s)], proot_src, flags)
    run([cc, "-static", "-nostdlib", "-o", str(out), str(obj_c), str(obj_s), "-Wl,--build-id=none",
         f"-Wl,-Ttext={address:#x}", "-Wl,--rosegment", "-Wl,-z,noexecstack"], proot_src, flags)
    return out


def build_abi(abi: str, bin_dir: Path, sources_dir: Path, provenance: dict, work_root: Path,
              output_dir: Path) -> dict[str, str]:
    triple = NDK_TRIPLE[abi]
    work = work_root / abi
    if work.exists():
        shutil.rmtree(work)
    work.mkdir(parents=True)
    env = base_env(work / "tmp")
    prefix = work / "sysroot"

    build_talloc(bin_dir, triple, component_source(sources_dir, provenance, "talloc"), work, prefix, env)
    shmem_env = dict(env, CFLAGS=f"-I{prefix / 'include'}", LDFLAGS=f"-L{prefix / 'lib'}")
    build_shmem(bin_dir, triple, component_source(sources_dir, provenance, "libandroid-shmem"), work, prefix,
                shmem_env)

    proot_root = fresh_copy(component_source(sources_dir, provenance, "proot"), work / "proot")
    proot_src = proot_root / "src"
    # Compatibility fix (oracle-approved, component-scoped): ashmem_memfd.c uses strcmp/memset
    # without <string.h>. Applied to the build copy only, never to the prepared tree.
    apply_patch(PROOT_STRING_PATCH, proot_root)
    proot_env = dict(env, CPPFLAGS=f"-I{prefix / 'include'} -DARG_MAX=131072",
                     CFLAGS=f"-ffile-prefix-map={work}=.", LDFLAGS=f"-L{prefix / 'lib'}")
    loader = build_loader(bin_dir, triple, proot_src, proot_env)
    loader32 = build_loader32(bin_dir, abi, proot_src, proot_env)
    proot = build_proot(bin_dir, triple, proot_src, proot_env,
                        proot_version(provenance))
    talloc_so = prefix / "lib" / "libtalloc.so.2.5.0"
    patch_needed_talloc(proot)
    outputs = {"proot": proot, "loader": loader, "loader32": loader32, "talloc": talloc_so,
               "shmem": prefix / "lib" / "libandroid-shmem.so"}
    for path in outputs.values():
        strip_comment(bin_dir, path)
    return copy_outputs(outputs, output_dir / abi)


def copy_outputs(outputs: dict[str, Path], abi_output: Path) -> dict[str, str]:
    """Copy each built library to its jniLibs name. Returns {jniLibs name: source key}."""
    if abi_output.exists():
        shutil.rmtree(abi_output)
    abi_output.mkdir(parents=True)
    written: dict[str, str] = {}
    for key, source in sorted(outputs.items()):
        destination = abi_output / NATIVE_LIBRARY_NAMES[key]
        shutil.copyfile(source, destination)
        destination.chmod(0o755)
        written[destination.name] = key
    return written


def apply_patch(patch: Path, tree: Path) -> None:
    run(["patch", "-p1", "--quiet", "-i", str(patch)], tree, base_env(tree / ".tmp"))


def proot_version(provenance: dict) -> str:
    """PRoot's own version string, from the verified lock (never from the app's git describe)."""
    for record in provenance["components"]:
        if record["component"] == "proot":
            return record["version"]
    raise BuildInputError("proot component missing from sources manifest")


def build_proot(bin_dir: Path, triple: str, proot_src: Path, env: dict[str, str], version: str) -> Path:
    """Main PRoot binary, linked against the talloc and shmem built above. Loaders are built first.

    Upstream writes build.h's VERSION from `$(GIT) describe`. GIT=true (a make command-line
    argument, which beats the makefile's own assignment) makes that empty, so the define is
    skipped and the -D below is the only VERSION. Upstream macros in CPPFLAGS are preserved.
    """
    cc = clang(bin_dir, triple)
    # Environment CPPFLAGS (sysroot include, ARG_MAX) is extended by the makefile's own `+=`
    # entries, so upstream -I. / -I$(VPATH) survive. The VERSION define is quoted for make.
    version_define = f'-DVERSION=\\"{version}\\"'
    build_env = dict(env, CPPFLAGS=f"{env['CPPFLAGS']} {version_define}")
    run(["make", "proot", "GIT=true", f"CC={cc}", f"LD={cc}", f"STRIP={bin_dir / 'llvm-strip'}",
         f"OBJCOPY={bin_dir / 'llvm-objcopy'}", f"OBJDUMP={bin_dir / 'llvm-objdump'}",
         f"PROOT_UNBUNDLE_LOADER={PROOT_UNBUNDLE_LOADER}", "PROOT_WITH_LIBANDROID_SHMEM=true"],
        proot_src, build_env)
    # build.h must not carry an app git describe value; VERSION comes only from the -D above.
    build_h = proot_src / "build.h"
    if build_h.is_file() and "VERSION \"v" in build_h.read_text(encoding="utf-8"):
        raise RuntimeError("build.h carries an application git version; GIT=true was not honored")
    return proot_src / "proot"


def patch_needed_talloc(proot: Path) -> None:
    """Ensure PRoot's NEEDED entry names libtalloc.so, the jniLibs name of the talloc library.

    The compile links against the talloc SONAME provided by the built library, so a versioned
    NEEDED (libtalloc.so.2) is rewritten in place (equal length, NUL padded). Anything else
    fails closed.
    """
    payload = proot.read_bytes()
    old = b"libtalloc.so.2\0"
    if payload.count(old) == 1:
        proot.write_bytes(payload.replace(old, b"libtalloc.so\0\0\0"))
        payload = proot.read_bytes()
    if payload.count(b"libtalloc.so\0") != 1:
        raise RuntimeError(f"PRoot NEEDED libtalloc entry not in the expected form: {proot}")


def strip_comment(bin_dir: Path, path: Path) -> None:
    run([str(bin_dir / "llvm-objcopy"), "--remove-section", ".comment", str(path)], path.parent,
        base_env(path.parent))


def parse_args(argv: list[str] | None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Offline native build of the runtime PRoot libraries")
    parser.add_argument("--sources-dir", required=True, help="Output of prepare_android_runtime_sources.py")
    parser.add_argument("--ndk-dir", required=True, help="Android NDK root (revision %s)" % NDK_VERSION)
    parser.add_argument("--output-dir", required=True, help="jniLibs output directory")
    parser.add_argument("--work-dir", required=True, help="Scratch build directory (not the prepared sources)")
    parser.add_argument("--abis", nargs="+", default=list(SUPPORTED_ABIS), choices=SUPPORTED_ABIS)
    return parser.parse_args(argv)


def main(argv: list[str] | None = None) -> int:
    args = parse_args(argv)
    sources_dir = Path(args.sources_dir).expanduser().resolve()
    output_dir = Path(args.output_dir).expanduser().resolve()
    work_dir = Path(args.work_dir).expanduser().resolve()
    try:
        # Refuse overlapping layouts before anything is written or removed.
        assert_disjoint(sources_dir=sources_dir, output_dir=output_dir, work_dir=work_dir)
        bin_dir = check_ndk(Path(args.ndk_dir).expanduser().resolve())
        provenance = load_provenance(sources_dir)
        results: dict[str, dict[str, str]] = {}
        for abi in args.abis:
            results[abi] = build_abi(abi, bin_dir, sources_dir, provenance, work_dir, output_dir)
    except (BuildInputError, RuntimeError, OSError, json.JSONDecodeError) as error:
        print(f"build_android_runtime_native: {error}", file=sys.stderr)
        return 1
    output_dir.mkdir(parents=True, exist_ok=True)
    record = {
        "ndk_version": NDK_VERSION,
        "min_api": MIN_API,
        "proot_version": proot_version(provenance),
        "sources": {c["component"]: c["archive_sha256"] for c in provenance["components"]},
        "abis": {abi: dict(sorted(files.items())) for abi, files in sorted(results.items())},
    }
    (output_dir / PROVENANCE_NAME).write_text(json.dumps(record, indent=2, sort_keys=True) + "\n",
                                              encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
