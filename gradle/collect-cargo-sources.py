#!/usr/bin/env python3
"""Archive every locked Kable FFI crate and its distinct publisher notices."""

import hashlib
import io
import json
import pathlib
import re
import sys
import tarfile
import tomllib
import urllib.parse
import urllib.request

REGISTRY = "registry+https://github.com/rust-lang/crates.io-index"
MPL_URL = "https://www.mozilla.org/media/MPL/2.0/index.txt"
MPL_SHA256 = "3f3d9e0024b1921b067d6f7f88deb4a60cbe7a78e76c64e3f1d7fc3b779b9d04"
NOTICE_NAME = re.compile(r"(?i)^(LICENSE|LICENCE|NOTICE|COPYING)([._-].*)?$")


def download(url: str) -> bytes:
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read()


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main(upstream: pathlib.Path, output: pathlib.Path) -> None:
    with tarfile.open(upstream, "r:gz") as archive:
        locks = [m for m in archive if m.name.endswith("/kable-btleplug-ffi/Cargo.lock")]
        if len(locks) != 1:
            raise ValueError("Expected one Kable FFI Cargo.lock")
        packages = tomllib.loads(archive.extractfile(locks[0]).read().decode())["package"]

    notices_dir = output / "cargo-notices"
    notices_dir.mkdir()
    source_records = []
    notice_records = []
    for package in sorted(packages, key=lambda p: (p["name"], p["version"])):
        if "source" not in package:
            continue  # The local btleplug-ffi crate is in the pinned Kable source archive.
        if package["source"] != REGISTRY:
            raise ValueError(f"Unpinned Cargo source: {package['name']}")
        name, version, checksum = package["name"], package["version"], package["checksum"]
        if not re.fullmatch(r"[0-9a-f]{64}", checksum):
            raise ValueError(f"Missing crate checksum: {name} {version}")
        filename = f"cargo-{name}-{version}.crate"
        url = f"https://static.crates.io/crates/{urllib.parse.quote(name)}/{urllib.parse.quote(name)}-{urllib.parse.quote(version)}.crate"
        data = download(url)
        if digest(data) != checksum:
            raise ValueError(f"Cargo.lock checksum mismatch: {name} {version}")
        (output / filename).write_bytes(data)
        coordinate = f"cargo:{name}:{version}"
        source_records.append({"artifact": coordinate, "sources": [{"file": filename, "sha256": checksum, "url": url}]})

        prefix = f"{name}-{version}/"
        with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
            manifest = archive.extractfile(prefix + "Cargo.toml")
            if manifest is None:
                raise ValueError(f"Missing crate Cargo.toml: {coordinate}")
            metadata = tomllib.loads(manifest.read().decode())["package"]
            if (metadata["name"], metadata["version"]) != (name, version):
                raise ValueError(f"Crate metadata differs from Cargo.lock: {coordinate}")
            license_claim = metadata.get("license") or metadata.get("license-file")
            if not license_claim:
                raise ValueError(f"Missing crate license claim: {coordinate}")
            entries = sorted(
                (m for m in archive.getmembers() if m.isfile() and m.name.startswith(prefix)
                 and NOTICE_NAME.fullmatch(m.name[len(prefix):])),
                key=lambda m: m.name,
            )
            notices = []
            for entry in entries:
                contents = archive.extractfile(entry).read()
                notice_name = f"cargo-{name}-{version}-{entry.name[len(prefix):].replace('/', '_')}.txt"
                if (notices_dir / notice_name).exists():
                    raise ValueError(f"Duplicate Cargo notice: {notice_name}")
                (notices_dir / notice_name).write_bytes(contents)
                notices.append({"file": notice_name, "entry": entry.name, "sha256": digest(contents)})
            if not notices:
                notice_name = f"cargo-{name}-{version}-PUBLISHER-LICENSE.txt"
                contents = (f"Artifact: {coordinate}\nPublisher Cargo.toml license: {license_claim}\n"
                            f"Corresponding source: {filename} (SHA-256: {checksum})\n"
                            "The publisher did not embed a separate license text in this crate archive.\n").encode()
                (notices_dir / notice_name).write_bytes(contents)
                notices.append({"file": notice_name, "entry": prefix + "Cargo.toml/license", "sha256": digest(contents)})
            notice_records.append({"artifact": coordinate, "license": license_claim, "notices": notices})

    mpl = download(MPL_URL)
    if digest(mpl) != MPL_SHA256:
        raise ValueError("Mozilla MPL-2.0 license hash mismatch")
    (notices_dir / "MPL-2.0.txt").write_bytes(mpl)
    (output / "cargo-source-records.json").write_text(json.dumps(source_records, sort_keys=True) + "\n")
    (output / "cargo-notice-records.json").write_text(json.dumps(notice_records, sort_keys=True) + "\n")


if __name__ == "__main__":
    main(pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2]))
