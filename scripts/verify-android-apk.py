#!/usr/bin/env python3
"""Record public APK identity/integrity only; never inspect private key material."""
import argparse, hashlib, json, os, pathlib, re, subprocess

parser = argparse.ArgumentParser()
parser.add_argument('--apk', required=True, type=pathlib.Path)
parser.add_argument('--aapt', required=True, type=pathlib.Path)
parser.add_argument('--apksigner', required=True, type=pathlib.Path)
args = parser.parse_args()
repo = pathlib.Path(__file__).resolve().parents[1]
build = (repo / 'android/app/build.gradle.kts').read_text()
protocol = (repo / 'android/app/src/main/java/com/rezoxnemesis/videostudio/AppProtocol.java').read_text()
expected_code = int(re.search(r'versionCode\s*=\s*(\d+)', build).group(1))
expected_name = re.search(r'versionName\s*=\s*"([^"]+)"', build).group(1)
native_name = re.search(r'APP_VERSION\s*=\s*"([^"]+)"', protocol).group(1)
badging = subprocess.check_output([str(args.aapt), 'dump', 'badging', str(args.apk)], text=True)
manifest = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
if manifest is None:
    raise SystemExit('APK package metadata unavailable')
package, actual_code, actual_name = manifest.groups()
if package != 'com.rezoxnemesis.videostudio.next' or int(actual_code) != expected_code or actual_name != expected_name or actual_name != native_name or expected_code <= 371:
    raise SystemExit('APK package/version continuity check failed')
signature = subprocess.check_output([str(args.apksigner), 'verify', '--print-certs', str(args.apk)], text=True)
certificate = re.search(r'^Signer #1 certificate SHA-256 digest: ([a-fA-F0-9]+)$', signature, re.M)
pinned = '7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd'
if certificate is None or certificate.group(1).lower() != pinned:
    raise SystemExit('APK public certificate does not match original VideoStudio signer')
digest = hashlib.sha256()
with args.apk.open('rb') as stream:
    for chunk in iter(lambda: stream.read(1024 * 1024), b''):
        digest.update(chunk)
proof = {
    'format': 'videostudio.apk-build-proof.v1', 'commit': os.environ.get('GITHUB_SHA', ''),
    'sourceHeadCommit': os.environ.get('VIDEOSTUDIO_SOURCE_HEAD_SHA', os.environ.get('GITHUB_SHA', '')),
    'package': package, 'versionCode': int(actual_code), 'versionName': actual_name,
    'apkSha256': digest.hexdigest(), 'certificateSha256': pinned,
    'apkBytes': args.apk.stat().st_size, 'packageVersionAndSignerVerified': True,
    'installedDeviceComparison': 'UNAVAILABLE', 'nativeCinematicAcceptance': False,
}
args.apk.with_name('VideoStudio-build-proof.json').write_text(json.dumps(proof, indent=2) + '\n')
args.apk.with_suffix('.apk.sha256').write_text(digest.hexdigest() + '  ' + args.apk.name + '\n')
print('APK package/version, original signer and SHA-256 integrity verified; native acceptance remains separate.')
