#!/usr/bin/env python3
"""Hash the file contents of a Gitiles subtree archive, not its variable tar headers."""

import hashlib
import pathlib
import sys
import tarfile


def tree_sha256(filename: str) -> str:
    digest = hashlib.sha256()
    with tarfile.open(filename, "r:gz") as archive:
        files = {}
        for member in archive:
            path = pathlib.PurePosixPath(member.name)
            if path.is_absolute() or ".." in path.parts or member.name in files:
                raise ValueError(f"Unsafe or duplicate archive entry: {member.name}")
            if member.isfile():
                files[member.name] = archive.extractfile(member).read()
            elif member.issym():
                files[member.name] = member.linkname.encode()
            elif not member.isdir():
                raise ValueError(f"Unsupported archive entry: {member.name}")
        for name, data in sorted(files.items()):
            digest.update(name.encode() + b"\0" + data + b"\0")
    return digest.hexdigest()


if __name__ == "__main__":
    print(tree_sha256(sys.argv[1]))
