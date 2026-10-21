import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch
from prepare_render_signed_build import SIGNER_SHA256, extract_keytool, extract_apk_signer, inspect_keystore, configure, verify_signed_apk

class SigningTests(unittest.TestCase):
    def test_identifies_video_studio_certificate(self):
        spaced=":".join(SIGNER_SHA256[i:i+2] for i in range(0,64,2))
        self.assertEqual(extract_keytool("SHA256: "+spaced),SIGNER_SHA256)
        self.assertEqual(extract_apk_signer("Signer #1 certificate SHA-256 digest: "+SIGNER_SHA256.lower()),SIGNER_SHA256)
    def test_missing_keystore_rejected(self):
        with TemporaryDirectory() as d:
            with self.assertRaisesRegex(ValueError,"NOT_RESTORED"):
                inspect_keystore(Path(d)/"missing.keystore")
    def test_multiple_signing_certificates_rejected(self):
        with self.assertRaisesRegex(ValueError,"EXPECTED_ONE_APK_CERTIFICATE"):
            extract_apk_signer("Signer #1 certificate SHA-256 digest: "+SIGNER_SHA256+"\nSigner #2 certificate SHA-256 digest: "+SIGNER_SHA256)
    def test_patches_only_render_gradle(self):
        with TemporaryDirectory() as d:
            p=Path(d)/"build.gradle"
            p.write_text("android {\n    namespace 'org.blender.blender'\n    defaultConfig { applicationId 'com.rezox.render' }\n    signingConfigs {\n        stableRelease {}\n    }\n    buildTypes { debug { debuggable true } }\n}\n")
            with patch("prepare_render_signed_build.inspect_keystore",return_value=SIGNER_SHA256):
                configure(p,Path(d)/"key")
            self.assertIn("signingConfig signingConfigs.videoStudio",p.read_text())
            self.assertIn("System.getenv('VIDEOSTUDIO_SIGNING_KEYSTORE')",p.read_text())
    def test_mismatched_apk_cert_fails(self):
        with patch("prepare_render_signed_build.subprocess.check_output",return_value="Verified using v2 scheme (APK Signature Scheme v2): true\nSigner #1 certificate SHA-256 digest: "+("0"*64)):
            with self.assertRaisesRegex(ValueError,"NOT_SIGNED_WITH_VIDEOSTUDIO_KEY"):
                verify_signed_apk(Path("result.apk"),Path("apksigner"))

if __name__=="__main__": unittest.main()
