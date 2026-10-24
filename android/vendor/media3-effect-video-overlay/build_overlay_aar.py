#!/usr/bin/env python3
"""Build a verified copy of one pinned AAR; never edit Gradle caches or bytecode.

Gradle/root invokes this script. Preparing this source does not execute javac.
Only DefaultVideoCompositor's source-compiled class family and the new policy
interface may change. All other original entry payloads must remain identical.
"""

import argparse
import difflib
import hashlib
import io
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile


PROVENANCE_ENTRY = "META-INF/videostudio/Media3-Video-Overlay-Provenance.json"
LICENSE_ENTRY = "META-INF/videostudio/Media3-Video-Overlay-LICENSE.txt"
NOTICE_ENTRY = "META-INF/videostudio/Media3-Video-Overlay-NOTICE.txt"
COMPOSITOR_PREFIX = "androidx/media3/effect/DefaultVideoCompositor"
POLICY_CLASS = "androidx/media3/effect/VideoCompositorFramePolicy.class"
REQUIRED_OUTPUTS = {
    COMPOSITOR_PREFIX + ".class",
    COMPOSITOR_PREFIX + "$FrameInfo.class",
    COMPOSITOR_PREFIX + "$InputSource.class",
    COMPOSITOR_PREFIX + "$PolicyOverlaySettings.class",
    POLICY_CLASS,
}
OPTIONAL_OUTPUTS = {COMPOSITOR_PREFIX + "$1.class"}


def sha(data):
    return hashlib.sha256(data).hexdigest()


def fail(message):
    raise ValueError(message)


def checked_bytes(path, expected, label):
    data = path.read_bytes()
    if sha(data) != expected:
        fail(label + " SHA256 differs from the maintained pin: " + str(path))
    return data


def zip_entries(data, label):
    with zipfile.ZipFile(io.BytesIO(data)) as archive:
        names = archive.namelist()
        if len(set(names)) != len(names):
            fail(label + " contains duplicate ZIP entries")
        if any(name.startswith("/") or ".." in Path(name).parts for name in names):
            fail(label + " contains an unsafe ZIP path")
        return {name: archive.read(name) for name in names}


def deterministic_zip(entries):
    # STORED avoids different zlib releases producing different archives. AGP
    # later packages the AAR normally; this is a small build input, not the APK.
    result = io.BytesIO()
    with zipfile.ZipFile(result, "w", compression=zipfile.ZIP_STORED) as archive:
        for name in sorted(entries):
            info = zipfile.ZipInfo(name, date_time=(1980, 1, 1, 0, 0, 0))
            info.create_system = 3
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = ((0o40755 if name.endswith("/") else 0o100644) << 16)
            if name.endswith("/"):
                info.external_attr |= 0x10
            archive.writestr(info, entries[name])
    return result.getvalue()


def classpath_entry(path, work):
    """Return a regular JAR/directory, extracting only classes.jar from an AAR."""
    if path.is_dir():
        return path
    if not path.is_file():
        fail("Compiler dependency does not exist: " + str(path))
    if path.suffix == ".aar":
        content = path.read_bytes()
        entries = zip_entries(content, "Compiler dependency " + path.name)
        if "classes.jar" not in entries:
            fail("Compiler AAR has no classes.jar: " + str(path))
        target = work / (sha(content) + "-classes.jar")
        target.write_bytes(entries["classes.jar"])
        return target
    if path.suffix == ".jar":
        return path
    fail("Only JAR/AAR/directory compiler dependencies are accepted: " + str(path))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--input-aar", required=True, type=Path)
    parser.add_argument("--output-aar", required=True, type=Path)
    parser.add_argument("--vendor-dir", required=True, type=Path)
    parser.add_argument("--android-jar", required=True, type=Path)
    parser.add_argument("--javac", required=True, type=Path)
    parser.add_argument("--compiler-module", type=Path,
                        help="JDK17 jmods/jdk.compiler.jmod; inferred from javac when omitted")
    parser.add_argument("--classpath", action="append", default=[], type=Path)
    parser.add_argument("--report", type=Path)
    parser.add_argument("--verify-inputs-only", action="store_true",
                        help="Verify pinned source/archive data; do not invoke Java or build an AAR")
    args = parser.parse_args()
    vendor = args.vendor_dir.resolve()
    pins_data = (vendor / "build-pins.json").read_bytes()
    pins = json.loads(pins_data)
    if pins.get("coordinate") != "androidx.media3:media3-effect:1.11.1":
        fail("The maintained Media3 coordinate pin is missing")
    if args.input_aar.resolve() == args.output_aar.resolve():
        fail("Refusing to overwrite the original AAR")
    original_bytes = checked_bytes(args.input_aar, pins["aarSha256"], "Original AAR")
    if len(original_bytes) != pins["aarSizeBytes"]:
        fail("Original AAR size differs from the maintained pin")
    original = zip_entries(original_bytes, "Original AAR")
    original_classes = checked_bytes_data(original.get("classes.jar"), pins["classesJarSha256"], "Original classes.jar")
    classes = zip_entries(original_classes, "Original classes.jar")
    existing_family = {name: sha(data) for name, data in classes.items() if name.startswith(COMPOSITOR_PREFIX)}
    if existing_family != pins["originalCompositorClasses"]:
        fail("Original compositor class inventory differs from the maintained pin")
    if POLICY_CLASS in classes:
        fail("Input AAR already contains the app-specific policy class")
    upstream_java = checked_bytes(vendor / "upstream/DefaultVideoCompositor.java", pins["upstreamJavaSha256"], "Pristine upstream Java")
    sources = [vendor / "src/androidx/media3/effect/DefaultVideoCompositor.java",
               vendor / "src/androidx/media3/effect/VideoCompositorFramePolicy.java"]
    source_hashes = {str(path.relative_to(vendor)): sha(path.read_bytes()) for path in sources}
    maintained_sources = pins.get("maintainedSourceSha256")
    if not maintained_sources or maintained_sources != source_hashes:
        fail("Maintained source hashes differ; root must review and update build-pins.json deliberately")
    patch = vendor / "DefaultVideoCompositor.patch"
    checked_bytes(patch, pins["patchSha256"], "Maintained source patch")
    expected_patch = "".join(difflib.unified_diff(
        upstream_java.decode("utf-8").splitlines(keepends=True),
        sources[0].read_text(encoding="utf-8").splitlines(keepends=True),
        fromfile="a/androidx/media3/effect/DefaultVideoCompositor.java",
        tofile="b/androidx/media3/effect/DefaultVideoCompositor.java"))
    if patch.read_text(encoding="utf-8") != expected_patch:
        fail("The maintained patch does not exactly describe pristine-to-modified source")
    license_data = checked_bytes(vendor / "LICENSE", pins["licenseSha256"], "Apache license")
    notice_data = checked_bytes(vendor / "NOTICE", pins["noticeSha256"], "Vendor notice")
    if args.verify_inputs_only:
        print(json.dumps({"status": "SOURCE_AND_ARCHIVE_PINS_MATCH_ONLY",
                          "javaExecuted": False, "outputAarBuilt": False,
                          "upstreamAarSha256": sha(original_bytes),
                          "maintainedSourceSha256": source_hashes}, sort_keys=True))
        return
    if not args.android_jar.is_file() or not args.javac.is_file():
        fail("A real Android SDK platform JAR and JDK17 javac are required")
    compiler_module = args.compiler_module or (args.javac.resolve().parent.parent / "jmods/jdk.compiler.jmod")
    if not compiler_module.is_file():
        fail("A full pinned JDK compiler module is required: " + str(compiler_module))
    compiler_version = subprocess.run([str(args.javac), "-version"], check=True,
                                      text=True, capture_output=True)
    compiler_label = (compiler_version.stdout + compiler_version.stderr).strip()
    if not re.fullmatch(r"javac 17(?:[. +\w-].*)?", compiler_label):
        fail("The maintained compiler is JDK17, received " + compiler_label)
    args.output_aar.parent.mkdir(parents=True, exist_ok=True)
    dependencies = sorted(set(path.resolve() for path in args.classpath), key=lambda path: (path.name, str(path)))
    dependency_hashes = []
    for path in dependencies:
        if path.is_dir():
            fail("Unhashed directory compiler dependencies are forbidden; supply resolved JAR/AAR artifacts")
        dependency_hashes.append({"name": path.name, "sha256": sha(path.read_bytes())})
    dependency_hashes.sort(key=lambda item: (item["name"], item["sha256"]))
    with tempfile.TemporaryDirectory(prefix="videostudio-effect-source-", dir=args.output_aar.parent) as temp:
        work = Path(temp)
        original_jar = work / "pinned-original-effect.jar"
        original_jar.write_bytes(original_classes)
        compile_classpath = [original_jar, args.android_jar.resolve()]
        compile_classpath += [classpath_entry(path, work) for path in dependencies]
        output_classes = work / "compiled"
        output_classes.mkdir()
        command = [str(args.javac), "--release", "8", "-proc:none", "-encoding", "UTF-8",
                   "-g:source,lines", "-implicit:none", "-sourcepath", "", "-classpath",
                   os.pathsep.join(str(path) for path in compile_classpath), "-d", str(output_classes)]
        subprocess.run(command + [str(path) for path in sources], check=True)
        compiled = {str(path.relative_to(output_classes)).replace(os.sep, "/"): path.read_bytes()
                    for path in output_classes.rglob("*.class")}
        actual_names = set(compiled)
        if not REQUIRED_OUTPUTS <= actual_names or actual_names - REQUIRED_OUTPUTS - OPTIONAL_OUTPUTS:
            fail("Unexpected source-compiler class inventory: " + repr(sorted(actual_names)))
        for name, data in compiled.items():
            if len(data) < 8 or data[:4] != b"\xca\xfe\xba\xbe" or int.from_bytes(data[6:8], "big") != 52:
                fail("Source compiler must retain Java8 classfile52: " + name)
        removed = set(existing_family) - actual_names
        unchanged = {name: data for name, data in classes.items() if name not in existing_family}
        for name, data in unchanged.items():
            if any(stale[:-6].encode("ascii") in data for stale in removed):
                fail("An unchanged class refers to a removed compositor nested class: " + name)
        rebuilt_classes = dict(unchanged)
        rebuilt_classes.update(compiled)
        if any(rebuilt_classes[name] != data for name, data in unchanged.items()):
            fail("A non-compositor class payload changed")
        new_classes_bytes = deterministic_zip(rebuilt_classes)
        provenance = {
            "format": "videostudio-media3-effect-source-overlay-v1",
            "coordinate": pins["coordinate"],
            "upstreamAarSha256": sha(original_bytes),
            "upstreamClassesJarSha256": sha(original_classes),
            "upstreamSourcesJarSha256": pins["sourcesJarSha256"],
            "upstreamJavaSha256": pins["upstreamJavaSha256"],
            "maintainedSourceSha256": source_hashes,
            "maintainedPatchSha256": pins["patchSha256"],
            "buildPinsSha256": sha(pins_data),
            "builderScriptSha256": sha(Path(__file__).read_bytes()),
            "compiler": compiler_label,
            "jdkCompilerModuleSha256": sha(compiler_module.read_bytes()),
            "compilerRelease": 8,
            "androidJarSha256": sha(args.android_jar.read_bytes()),
            "compilerDependencyArtifacts": dependency_hashes,
            "compiledClassSha256": {name: sha(data) for name, data in sorted(compiled.items())},
            "removedOriginalClasses": sorted(removed),
            "unchangedOriginalClassEntryCount": len(unchanged),
            "rebuiltClassesJarSha256": sha(new_classes_bytes),
            "policy": "opt-in; common unchanged; original primary clock; normal legacy default",
        }
        result = dict(original)
        result["classes.jar"] = new_classes_bytes
        result[PROVENANCE_ENTRY] = (json.dumps(provenance, sort_keys=True, indent=2) + "\n").encode("utf-8")
        result[LICENSE_ENTRY] = license_data
        result[NOTICE_ENTRY] = notice_data
        for name, data in original.items():
            if name != "classes.jar" and result[name] != data:
                fail("An unrelated original AAR entry changed: " + name)
        final_bytes = deterministic_zip(result)
        staged = work / "verified-output.aar"
        staged.write_bytes(final_bytes)
        # Read the finished archive again before its atomic publication.
        finished = zip_entries(staged.read_bytes(), "Generated AAR")
        if finished != result:
            fail("Generated archive readback differs from expected payloads")
        os.replace(staged, args.output_aar)
        report = {"outputAarSha256": sha(final_bytes), "outputSizeBytes": len(final_bytes), **provenance}
        if args.report:
            args.report.parent.mkdir(parents=True, exist_ok=True)
            args.report.write_text(json.dumps(report, sort_keys=True, indent=2) + "\n", encoding="utf-8")
        print(json.dumps({"outputAarSha256": report["outputAarSha256"],
                          "compiledClasses": sorted(compiled), "unrelatedPayloads": "UNCHANGED"}, sort_keys=True))


def checked_bytes_data(data, expected, label):
    if data is None or sha(data) != expected:
        fail(label + " differs from the maintained pin")
    return data


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, zipfile.BadZipFile) as error:
        print("VideoStudio pinned effect source build failed: " + str(error), file=sys.stderr)
        sys.exit(1)
