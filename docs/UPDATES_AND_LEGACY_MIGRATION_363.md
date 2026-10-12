# VideoStudio Next 3.6.3 — Safe Updates and One-Time Project Migration

## What went wrong

The old 3.6.0 debug artifact has certificate SHA-256:
`92021f4fca3c69902f51d10db933c6e14247ebbfe5bb53193ed9111b36ca9b7c`.

The stabilized 3.6.2 debug APK is signed by:
`7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd`.

Although both APKs used the package ID `com.rezoxnemesis.videostudio`,
Android refuses to replace an installed APK if its certificate differs.
The existing phone installation's actual fingerprint must be independently
checked; the 3.6.0 artifact fingerprint alone cannot prove what is installed.
Neither a higher versionCode, ADB `-r`, nor a new installer UI can bypass
the matching-signature rule. Signature lineage rotation requires proof from
the old signing key, which is not known to be available.

## No-uninstall recovery approach

**VideoStudio Next** is deliberately a different Android app ID:
`com.rezoxnemesis.videostudio.next`. It installs beside the old
`com.rezoxnemesis.videostudio`, leaving its APK, data, owner pairing,
and projects untouched. It uses the pinned, stable signing key in the
GitHub Actions pipeline. Subsequent Next updates must use the same
package and signing cert and strictly increase versionCode.

The old app may be kept installed permanently. Do not uninstall it just to
try a signing-incompatible APK. Future patch versions of Next use native
in-place Android updates via a user-confirmed system installer.

## Step-by-step mobile migration

1. In old VideoStudio, use **Control → Cloud Workspace → Archive Active
   Project** for each project, selecting your existing
   `VideoStudio-MCP-Temp-Frames` folder if needed. The older 3.6.0 archive
   often stored project.json and generated files but **not original imports**.
2. Confirm that original import images/videos are separately saved. For the
   current *Revenge of Ashes* project, the 17 approved source frames have
   already been staged in
   `VideoStudio-MCP-Temp-Frames/Revenge_of_Ashes_3_6_0_SourceFrames`.
   Two old project assets share the stem `ash_frame_03`; the migrator
   reports missing files rather than manufacturing images.
3. Install VideoStudio Next **without uninstalling the old app**. It will
   have a separate icon labelled "VideoStudio Next".
4. In Next → **Control**, link the SAME selected Drive workspace folder
   using Android's system folder picker. The new app must receive its own
   persisted read permission; the old app's grant cannot be reused.
5. Tap **Recover projects from legacy cloud archive**. The app reads only
   the selected folder's Projects/<id>/project.json and existing media.
   It maps saved generated frames to the Next app's private workspace,
   maps the approved original source-frame staging folder by filename stem,
   and restores only projects with all referenced assets available.
6. Check the imported projects and play their images/MP4s before relying
   on them. **Old projects and old VideoStudio stay unchanged**, even if
   the migration is incomplete.

## Future update workflow (no uninstall)

1. Download a newer, stable-signed **VideoStudio Next** APK.
2. Open Next → Control → **Check and install newer Next APK**.
3. Pick the file using Android Files. Next stages the APK privately,
   asks Android's PackageManager for its real certificate, verifies
   same application ID and increasing versionCode.
4. Only if compatible, the app opens Android's standard package installer
   and the user approves installation. The installed SQLite DB, original
   imports, native connection and project workspace remain intact across
   a normal in-place update.
5. If signing differs, it stops **before installation** with the actual
   certificate mismatch and specifically warns not to uninstall.

No silent privileged installation or certificate bypass is attempted.

## Cloud archive completeness for all NEW versions

The cloud archive copies **explicitly imported media assets** from the
project's authenticated imported assets, besides its creative workspace,
project.json and known generated final MP4. Sources are saved under
Projects/<id>/Originals/<asset-id>_<filename>, with an explicit
`allOriginalsArchived` status and list of missing media. On recovery,
these source files are preferred over older one-off staging folders.

Only the user-selected scoped document-tree is enumerated. Gallery and
unrelated device files are never queried.

## Verification needed before relying on the migration

- Source smoke checks and Android Gradle/JUnit compilation must be green.
- The Next APK must have the new `.next` application ID, versionCode 363,
  and pinned certificate.
- Side-by-side installation must show **both** old VideoStudio and Next
  without changing or deleting the old one.
- Link the exact old cloud folder and confirm original source images,
  18-clip storyboard, 156-frame generated project and rendered MP4 can
  be recovered. Any unreadable or missing assets must be reported as
  incomplete, not counted as successful.
- Build a Next v364 APK signed by the same pinned key and verify it upgrades
  the installed Next v363 while retaining its project store. This is the
  actual acceptance test for future seamless updates.
- Verify the package-install permission UX on Android 16. Keep user
  confirmation in the system installer.

**This is a safe one-time parallel migration, not an in-place replacement of
an existing APK whose private signing key is unavailable.** Claiming
otherwise would be misleading and could cause loss of the current projects.

## Signing-key durability

CI pins the original cached development certificate and fails if absent or
changed; it cannot silently generate a new key again. GitHub Actions cache
is not a permanent signing-key vault and can expire. Before permanent
release distribution, back up the existing keystore securely to an
owner-controlled secret manager and configure CI to restore it from there,
without ever publishing the private key. Do not rely solely on cache for
years of app updates.
