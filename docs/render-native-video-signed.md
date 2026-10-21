# Render cloned into VideoStudio — same signing certificate

Render is pinned as a real Git submodule at render-native/ within the
VideoStudio-MCP repository, tracking public source commit
82ba789d9ba067a5b524055f30a379cc50277428 of RezoxNemesis/Render.
Git checkout with --recurse-submodules brings over the full source:
Android Blender UI/NDK C++, embedded bpy operations, the native agent and
VideoStudio MCP/Binder connector. Original Blender engine binaries are
SHA-256-verified and bundled during GitHub CI, not run on a server.

## Existing VideoStudio signer only

The workflow restores the same cached signing keystore used by VideoStudio's
original Android workflow: videostudio-android-debug-signing-v1.
Expected certificate SHA-256:
7E:01:87:47:03:56:61:6B:E2:A4:5B:C7:F4:14:45:89:40:85:18:5D:B1:10:ED:FD:C0:CD:FD:23:29:42:A6:DD

The pipeline never generates a replacement keystore and never accepts
another certificate. It configures the Render Gradle build to use only
VideoStudio's original signer and verifies the resulting APK using
Android build-tools apksigner. Any mismatch fails before artifact upload.

## App identity and installation safety

The resulting APK remains com.rezox.render. VideoStudio is
com.rezoxnemesis.videostudio. They are two separate installed apps with
the same requested signing certificate, not one merged package.
This can support signature-based Android Binder permissions but does
not itself establish that the Binder connection is live on the phone.

The new certificate will not match older Render debug or the dedicated
Render stable-release certificate. Back up your saved Blender projects
and linked assets before uninstalling earlier Render versions.
Uninstalling can delete app-specific files and credentials.

## CI evidence and outputs

Workflow: .github/workflows/render-native-signed.yml
APK artifact: Render-Full-Blender-VideoStudio-Signing-Certificate-ONLY
Build proof: Render-VideoStudio-Signing-Proof

See the GitHub Actions run for native Blender library inclusion, full runtime
assets, Render Android build/tests, package name, signer fingerprint
and APK checksum. Physical Android Vulkan/rendering and end-to-end
VideoStudio MCP Binder control still require on-device validation.
An APK build does not prove the user has installed or opened it.

The original VideoStudio Android module, main branch, existing installed
VideoStudio signing process, and Cloudflare Worker are not changed by this
build task. Render's independent project data remains isolated.