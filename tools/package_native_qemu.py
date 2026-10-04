#!/usr/bin/env python3
"""Package an Android/Termux QEMU prefix into Vectras.

QEMU PIE executables are renamed to libqemu-system-*.so so Android installs them
in the executable nativeLibraryDir. Their non-system DT_NEEDED dependencies and
QEMU firmware data are stored as assets and extracted into app-private storage
at first launch.
"""

from __future__ import annotations

import argparse
import os
import re
import shutil
import subprocess
from pathlib import Path

TARGETS = ("qemu-system-x86_64", "qemu-system-aarch64", "qemu-system-i386", "qemu-system-ppc")
NEEDED_RE = re.compile(r"Shared library: \[(.+?)\]")
SYSTEM_LIBS = {
    "libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so",
    "libz.so", "libEGL.so", "libGLESv1_CM.so", "libGLESv2.so",
    "libOpenSLES.so", "libaaudio.so", "libmediandk.so", "libnativewindow.so",
}


def needed(path: Path, readelf: str) -> list[str]:
    proc = subprocess.run([readelf, "-d", str(path)], text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.PIPE)
    if proc.returncode != 0:
        raise RuntimeError(f"readelf failed for {path}: {proc.stderr.strip()}")
    return NEEDED_RE.findall(proc.stdout)


def build_library_index(prefix: Path) -> dict[str, Path]:
    index: dict[str, Path] = {}
    libroot = prefix / "lib"
    if not libroot.is_dir():
        return index
    for path in libroot.rglob("*"):
        if not path.is_file() and not path.is_symlink():
            continue
        index.setdefault(path.name, path)
    return index


def copy_resolved(source: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    real = source.resolve()
    shutil.copy2(real, destination)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--prefix", required=True, type=Path)
    parser.add_argument("--jni-dir", required=True, type=Path)
    parser.add_argument("--assets-dir", required=True, type=Path)
    parser.add_argument("--readelf", default=os.environ.get("READELF", "readelf"))
    args = parser.parse_args()

    prefix = args.prefix.resolve()
    jni_dir = args.jni_dir.resolve()
    assets_dir = args.assets_dir.resolve()
    asset_lib = assets_dir / "lib"

    shutil.rmtree(jni_dir, ignore_errors=True)
    shutil.rmtree(assets_dir, ignore_errors=True)
    jni_dir.mkdir(parents=True, exist_ok=True)
    asset_lib.mkdir(parents=True, exist_ok=True)

    index = build_library_index(prefix)
    queue: list[Path] = []
    packaged_targets = 0

    for target in TARGETS:
        source = prefix / "bin" / target
        if not source.is_file():
            print(f"warning: target missing: {source}")
            continue
        destination = jni_dir / f"lib{target}.so"
        shutil.copy2(source, destination)
        destination.chmod(0o755)
        queue.append(source)
        packaged_targets += 1
        print(f"packaged {target} -> {destination}")

    if packaged_targets == 0:
        raise SystemExit("No QEMU system executables were found in the supplied prefix")

    copied: set[str] = set()
    examined: set[Path] = set()
    unresolved: set[str] = set()

    while queue:
        current = queue.pop()
        real_current = current.resolve()
        if real_current in examined:
            continue
        examined.add(real_current)

        for libname in needed(real_current, args.readelf):
            if libname in SYSTEM_LIBS or libname in copied:
                continue
            source = index.get(libname)
            if source is None:
                unresolved.add(libname)
                continue
            destination = asset_lib / libname
            copy_resolved(source, destination)
            copied.add(libname)
            queue.append(source)
            print(f"dependency {libname}")

    share = prefix / "share" / "qemu"
    if share.is_dir():
        shutil.copytree(share, assets_dir / "share" / "qemu", dirs_exist_ok=True)
    else:
        print(f"warning: QEMU firmware directory missing: {share}")

    # Keep diagnostics in the APK assets. This makes device linker failures much
    # easier to compare with what CI actually bundled.
    manifest = assets_dir / "runtime-manifest.txt"
    manifest.write_text(
        "QEMU native runtime\n"
        + "targets=" + ",".join(t for t in TARGETS if (prefix / "bin" / t).is_file()) + "\n"
        + "libraries=" + ",".join(sorted(copied)) + "\n"
        + "unresolved=" + ",".join(sorted(unresolved)) + "\n",
        encoding="utf-8",
    )

    print(f"Bundled {packaged_targets} QEMU targets and {len(copied)} runtime libraries")
    if unresolved:
        print("Unresolved DT_NEEDED entries (normally Android system libraries):")
        for name in sorted(unresolved):
            print(f"  {name}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
