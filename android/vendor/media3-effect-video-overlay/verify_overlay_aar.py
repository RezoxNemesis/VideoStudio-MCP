#!/usr/bin/env python3
"""Verify actual resolved original/patched EFFECT artifacts; never executes Java."""
import argparse
import json
from pathlib import Path
import sys
import zipfile
# The verifier runs from a transform input directory. Never create bytecode there.
sys.dont_write_bytecode = True
from build_overlay_aar import (
    COMPOSITOR_PREFIX, POLICY_CLASS, REQUIRED_OUTPUTS, OPTIONAL_OUTPUTS,
    PROVENANCE_ENTRY, LICENSE_ENTRY, NOTICE_ENTRY, sha, zip_entries, checked_bytes, fail,
)


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--original-aar",required=True,type=Path)
    parser.add_argument("--patched-aar",required=True,type=Path)
    parser.add_argument("--vendor-dir",required=True,type=Path)
    args=parser.parse_args()
    pins_data=(args.vendor_dir/"build-pins.json").read_bytes()
    pins=json.loads(pins_data)
    original_bytes=checked_bytes(args.original_aar,pins["aarSha256"],"Resolved upstream AAR")
    original=zip_entries(original_bytes,"Resolved upstream AAR")
    patched_bytes=args.patched_aar.read_bytes()
    patched=zip_entries(patched_bytes,"Resolved patched AAR")
    expected_extra={PROVENANCE_ENTRY,LICENSE_ENTRY,NOTICE_ENTRY}
    if set(patched)!=set(original)|expected_extra:
        fail("Resolved EFFECT artifact lacks exact source-overlay provenance entries")
    if sha(original["classes.jar"])!=pins["classesJarSha256"]:
        fail("Original resolved classes.jar differs from maintained pin")
    for name,payload in original.items():
        if name!="classes.jar" and patched[name]!=payload:
            fail("Unrelated resolved AAR payload changed: "+name)
    old_classes=zip_entries(original["classes.jar"],"Original resolved classes.jar")
    new_classes=zip_entries(patched["classes.jar"],"Patched resolved classes.jar")
    family={n for n in new_classes if n.startswith(COMPOSITOR_PREFIX) or n==POLICY_CLASS}
    if not REQUIRED_OUTPUTS<=family or family-REQUIRED_OUTPUTS-OPTIONAL_OUTPUTS:
        fail("Resolved patched class inventory differs from maintained source family")
    old_family={n for n in old_classes if n.startswith(COMPOSITOR_PREFIX)}
    if set(new_classes)-family!=set(old_classes)-old_family:
        fail("Resolved classes.jar has unrelated added/removed classes or resources")
    for name,payload in old_classes.items():
        if name not in old_family and new_classes[name]!=payload:
            fail("Unrelated resolved JAR payload changed: "+name)
    if new_classes[COMPOSITOR_PREFIX+".class"]==old_classes[COMPOSITOR_PREFIX+".class"]:
        fail("Original unpatched compositor was selected")
    provenance=json.loads(patched[PROVENANCE_ENTRY])
    if provenance.get("format")!="videostudio-media3-effect-source-overlay-v1":
        fail("Resolved EFFECT artifact has unknown provenance format")
    for key,value in {
        "coordinate":pins["coordinate"], "upstreamAarSha256":pins["aarSha256"],
        "upstreamClassesJarSha256":pins["classesJarSha256"],
        "maintainedPatchSha256":pins["patchSha256"], "buildPinsSha256":sha(pins_data),
        "builderScriptSha256":sha((args.vendor_dir/"build_overlay_aar.py").read_bytes()),
        "rebuiltClassesJarSha256":sha(patched["classes.jar"]), "compilerRelease":8,
    }.items():
        if provenance.get(key)!=value:fail("Resolved provenance mismatch: "+key)
    if provenance.get("maintainedSourceSha256")!=pins["maintainedSourceSha256"]:
        fail("Resolved source identities differ from maintained pins")
    hashes={name:sha(new_classes[name]) for name in sorted(family)}
    if provenance.get("compiledClassSha256")!=hashes:
        fail("Resolved compiled class payloads differ from provenance")
    for name in family:
        payload=new_classes[name]
        if len(payload)<8 or payload[:4]!=b"\xca\xfe\xba\xbe" or int.from_bytes(payload[6:8],"big")!=52:
            fail("Resolved compositor must retain classfile52: "+name)
    for entry,file_name,pin_name in [(LICENSE_ENTRY,"LICENSE","licenseSha256"),(NOTICE_ENTRY,"NOTICE","noticeSha256")]:
        expected=checked_bytes(args.vendor_dir/file_name,pins[pin_name],file_name)
        if patched[entry]!=expected:fail("Resolved source-overlay license/notice changed")
    print(json.dumps({"status":"RESOLVED_PINNED_SOURCE_OVERLAY_VERIFIED", "javaExecuted":False,
        "originalAarSha256":sha(original_bytes),"patchedAarSha256":sha(patched_bytes),
        "compiledClasses":sorted(family),"unrelatedPayloads":"UNCHANGED"},sort_keys=True))


if __name__=="__main__":
    try:main()
    except (ValueError,OSError,KeyError,zipfile.BadZipFile) as error:
        print("VideoStudio resolved EFFECT verification failed: "+str(error),file=sys.stderr)
        sys.exit(1)
