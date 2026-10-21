#!/usr/bin/env python3
"""Fail-closed Render signing using VideoStudio's ORIGINAL development keystore."""
import argparse
from pathlib import Path
import re
import subprocess

SIGNER_SHA256 = "7E0187470356616BE2A45BC7F41445894085185DB110EDFDC0CDFD232942A6DD"

def normalized(value):
    return re.sub("[^A-Fa-f0-9]", "", value).upper()

def extract_keytool(text):
    found = re.findall(r"SHA256:\s*([0-9a-fA-F:]{64,95})", text)
    if len(found) != 1:
        raise ValueError("EXPECTED_SINGLE_VIDEOSTUDIO_CERTIFICATE")
    return normalized(found[0])

def inspect_keystore(path):
    path = Path(path)
    if not path.is_file() or path.stat().st_size < 1000:
        raise ValueError("VIDEOSTUDIO_SIGNING_KEY_NOT_RESTORED")
    info = subprocess.check_output(
        ["keytool", "-list", "-v", "-keystore", str(path),
         "-storepass", "android", "-alias", "androiddebugkey"],
        text=True, stderr=subprocess.DEVNULL,
    )
    digest = extract_keytool(info)
    if digest != SIGNER_SHA256:
        raise ValueError("NOT_THE_VIDEOSTUDIO_CERTIFICATE")
    return digest

def configure(path,keystore):
    inspect_keystore(keystore)
    source=Path(path).read_text(encoding="utf8")
    signing="    signingConfigs {\n"
    debug="debug { debuggable true }"
    if source.count(signing)!=1 or source.count(debug)!=1:
        raise ValueError("PINNED_RENDER_GRADLE_FORMAT_CHANGED")
    if "applicationId 'com.rezox.render'" not in source:
        raise ValueError("RENDER_PACKAGE_ID_CHANGED")
    if "namespace 'org.blender.blender'" not in source:
        raise ValueError("BLENDER_NAMESPACE_CHANGED")
    source=source.replace(signing,signing + """        videoStudio {
            storeFile file(System.getenv('VIDEOSTUDIO_SIGNING_KEYSTORE'))
            storePassword 'android'
            keyAlias 'androiddebugkey'
            keyPassword 'android'
        }
""",1)
    source=source.replace(debug,"debug { debuggable true; signingConfig signingConfigs.videoStudio }",1)
    Path(path).write_text(source,encoding="utf8")

def extract_apk_signer(text):
    matches=re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([a-f0-9]{64})",text,re.I)
    if len(matches)!=1: raise ValueError("EXPECTED_ONE_APK_CERTIFICATE")
    return normalized(matches[0])

def verify_signed_apk(apk,apksigner):
    info=subprocess.check_output([str(apksigner),"verify","--verbose","--print-certs",str(apk)],text=True)
    if extract_apk_signer(info)!=SIGNER_SHA256:
        raise ValueError("RENDER_APK_NOT_SIGNED_WITH_VIDEOSTUDIO_KEY")
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in info:
        raise ValueError("APK_SIGNATURE_SCHEME_V2_NOT_PRESENT")
    return SIGNER_SHA256

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--keystore",required=True,type=Path)
    p.add_argument("--gradle",type=Path)
    p.add_argument("--apk",type=Path)
    p.add_argument("--apksigner",type=Path)
    a=p.parse_args()
    inspect_keystore(a.keystore)
    if a.gradle:
        configure(a.gradle,a.keystore)
        print("VideoStudio signer verified, configured Render debug build; no fallback")
    elif a.apk and a.apksigner:
        print("Render APK signed with original VideoStudio certificate:",
              verify_signed_apk(a.apk,a.apksigner))
    else:
        p.error("Specify --gradle, or --apk and --apksigner")

if __name__=="__main__": main()
