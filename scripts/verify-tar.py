#!/usr/bin/env python3
"""Verify the real app fixture archive using only the Python standard library."""
import io
import json
from pathlib import Path
import tarfile
import zipfile

root = Path(__file__).resolve().parents[1]
archives = list((root / "build/tasks").rglob("image.tar"))
assert len(archives) == 1, f"Expected one fixture image tar; found {archives}"
archive = archives[0]
with tarfile.open(archive) as image:
    manifest = json.load(image.extractfile("manifest.json"))[0]
    assert manifest["RepoTags"] == ["example/ktc-jib:local"]
    config_bytes = image.extractfile(manifest["Config"]).read()
    config = json.loads(config_bytes)
    assert config["os"] == "linux"
    assert config["architecture"] == "amd64"
    entrypoint = config["config"]["Entrypoint"]
    assert entrypoint[0] == "java"
    assert entrypoint[-1] == "example.MainKt"
    assert entrypoint[1] == "-cp"
    paths = entrypoint[2].split(":")
    assert paths[0] == "/app/application.jar"
    content = {}
    for layer in manifest["Layers"]:
        with tarfile.open(fileobj=image.extractfile(layer), mode="r|*") as files:
            for member in files:
                if member.isfile():
                    content["/" + member.name.lstrip("./")] = files.extractfile(member).read()
    assert all(path in content for path in paths), paths
    with zipfile.ZipFile(io.BytesIO(content["/app/application.jar"])) as app:
        assert "example/MainKt.class" in app.namelist()
    kotlin_jars = [path for path in paths if "kotlin-stdlib" in path]
    assert kotlin_jars, "The app's Kotlin runtime dependency was not packaged"
    with zipfile.ZipFile(io.BytesIO(content[kotlin_jars[0]])) as kotlin:
        assert "kotlin/Unit.class" in kotlin.namelist()
digest = archive.with_name("image.digest").read_text().strip()
assert digest.startswith("sha256:") and len(digest) == 71, digest
print(f"Verified {archive}: application, runtime classpath, image metadata, digest {digest}")
