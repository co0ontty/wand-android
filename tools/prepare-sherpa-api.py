#!/usr/bin/env python3
"""Reproduce the checked-in JVM API from a locally downloaded, pinned official AAR."""

import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile


def sha256(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("aar", type=Path)
    parser.add_argument("--output", type=Path)
    args = parser.parse_args()
    libs = Path(__file__).resolve().parents[1] / "app/libs"
    spec = json.loads((libs / "sherpa-onnx-api-1.13.2.json").read_text())
    source = args.aar.read_bytes()
    if len(source) != spec["sourceSize"] or sha256(source) != spec["sourceSha256"]:
        parser.error("AAR size or SHA-256 differs from the pinned official release")
    with zipfile.ZipFile(io.BytesIO(source)) as archive:
        classes = archive.read("classes.jar")
    if sha256(classes) != spec["classesSha256"]:
        parser.error("classes.jar SHA-256 differs from the pinned release")
    with zipfile.ZipFile(io.BytesIO(classes)) as jar:
        names = set(jar.namelist())
        if not set(spec["removedClasses"]) <= names:
            parser.error("The pinned JNI wrappers are missing")
        entries = {
            name: jar.read(name) for name in names
            if (name.endswith(".class") or name.endswith(".kotlin_module"))
            and name not in spec["removedClasses"]
        }
    entries["META-INF/LICENSE"] = (libs / "sherpa-onnx-LICENSE.txt").read_bytes()
    entries["META-INF/NOTICE"] = (libs / "sherpa-onnx-NOTICE.txt").read_bytes()
    output = io.BytesIO()
    # Stored entries avoid differences between zlib versions; order, date and mode are fixed.
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as jar:
        for name, data in sorted(entries.items()):
            entry = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            entry.create_system = 3
            entry.external_attr = 0o100644 << 16
            jar.writestr(entry, data)
    result = output.getvalue()
    if sha256(result) != spec["apiSha256"]:
        parser.error("API SHA-256 differs from manifest: " + sha256(result))
    target = args.output or libs / "sherpa-onnx-api-1.13.2.jar"
    target.write_bytes(result)
    print(json.dumps({"size": len(result), "sha256": sha256(result)}))


if __name__ == "__main__":
    main()
