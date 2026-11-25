#!/usr/bin/env python3
"""Fetch the exact Frida Core 17.27.3 Android ARM64 devkit from upstream."""
from __future__ import annotations
import hashlib
import io
import os
import tarfile
import urllib.request
from pathlib import Path

VERSION = "17.27.3"
URL = os.environ.get(
    "PVZH_DEVKIT_URL",
    f"https://github.com/frida/frida/releases/download/{VERSION}/frida-core-devkit-{VERSION}-android-arm64.tar.xz",
)
DEST = Path(__file__).resolve().parents[1] / "app" / "src" / "main" / "jni" / "frida-devkit"
REQUIRED = {"frida-core.h", "libfrida-core.a"}


def main() -> None:
    if all((DEST / name).is_file() for name in REQUIRED) and (DEST / "includes.mk").is_file():
        print(f"Frida Core {VERSION} devkit already present: {DEST}")
        return
    print(f"Downloading official Frida Core {VERSION} Android ARM64 devkit (~60 MB)...")
    print(f"  {URL}")
    request = urllib.request.Request(URL, headers={"User-Agent": "PVZH-Online-Builder/1.0"})
    try:
        with urllib.request.urlopen(request, timeout=120) as response:
            archive = response.read()
    except Exception as exc:  # noqa: BLE001
        raise SystemExit(
            f"下载失败：{type(exc).__name__}: {exc}\n"
            "可以设置 PVZH_DEVKIT_URL 指向镜像/代理地址后重跑，或手动下载该 tar.xz 解压到\n"
            f"  {DEST}\n"
            "（需要包含 frida-core.h 与 libfrida-core.a）"
        )
    digest = hashlib.sha256(archive).hexdigest()
    print(f"Downloaded {len(archive):,} bytes; SHA-256: {digest}")
    DEST.mkdir(parents=True, exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(archive), mode="r:xz") as tar:
        root = DEST.resolve()
        for member in tar.getmembers():
            target = (DEST / member.name).resolve()
            if root not in target.parents and target != root:
                raise RuntimeError(f"Unsafe path in devkit archive: {member.name}")
        tar.extractall(DEST, filter="data")
    for name in REQUIRED:
        target = DEST / name
        if target.is_file():
            continue
        matches = list(DEST.rglob(name))
        if not matches:
            raise RuntimeError(f"Devkit archive missing required file: {name}")
        target.write_bytes(matches[0].read_bytes())

    # Include every header-bearing directory: the public Frida header also
    # imports GLib/GIO/JSON-GLib headers shipped in the devkit bundle.
    include_dirs = sorted({p.parent for p in DEST.rglob("*.h")})
    rel_dirs = ["$(LOCAL_PATH)/frida-devkit/" + p.relative_to(DEST).as_posix() for p in include_dirs]
    (DEST / "includes.mk").write_text(
        "FRIDA_DEVKIT_INCLUDE_DIRS := " + " \
  ".join(rel_dirs) + "\n",
        encoding="utf-8",
    )
    print(f"Frida Core {VERSION} devkit installed: {DEST}")


if __name__ == "__main__":
    main()
