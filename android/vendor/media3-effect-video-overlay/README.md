# Pinned native compositor extension

This directory maintains a small opt-in source extension to **androidx.media3:media3-effect:1.11.1**. `common` and every other Media3 artifact remain unchanged. The original AndroidX Maven component and dependency metadata are retained; this is neither an app shadow class nor a second effect library.

`src/` contains the maintained compositor and policy. `upstream/DefaultVideoCompositor.java` is the byte-exact pinned original. `DefaultVideoCompositor.patch` is the canonical pristine-to-maintained diff. `build-pins.json` binds original AAR/classes/source identities, maintained source/patch and license/notice hashes. The Apache2 license and original copyright remain available here and in the generated AAR.

## Build route

The app applies `transform.gradle.kts`. The AAR **artifact type** defaults to the unpatched marker; no marker is placed on component variants, which would block variant selection before transforms. App configurations request the patched artifact marker. The transform passes unrelated AARs through as their original files. Only the exact `media3-effect-1.11.1.aar` proceeds to `build_overlay_aar.py` with the original resolved artifact, Android SDK JAR, JDK17 compiler and resolved compiler dependencies. Other EFFECT versions fail closed. The isolated annotation/checker compiler support configuration is never added to app dependencies. AGP's parent plugin script exports a lazy Android platform provider; the applied vendor script uses only Gradle-core types.

Compiler support resolves its own mixed AAR/JAR variants (common, annotations, checker and Guava plus their transitives). The transform does not request `InputArtifactDependencies`: that would inherit the AAR artifact request and incorrectly ask transitive JVM JAR modules for nonexistent AAR variants. The builder combines only this isolated support classpath with the verified original EFFECT classes and Android platform JAR.

Before `preBuild`, `verifyVideoStudioEffectOverlay` resolves actual original and patched AAR views filtered by the selected Maven component identity. It invokes the data-only `verify_overlay_aar.py` to require the new policy, changed compositor, exact source/class provenance and unchanged unrelated payloads. It rejects an original unpatched AAR even if variant selection accidentally admitted it. Task views are lazy, after Android configurations exist. This audit does not add original AAR classes to the application or alter the transitive dependency graph.

The Python builder verifies every pin before compilation, including exact canonical patch equivalence. It invokes JDK17 with `--release 8`, disables annotation processors and implicit source compilation, and compiles only the maintained compositor/policy. It accepts exactly the expected compositor/nested/policy class inventory (including Java8's optional synthetic private-access marker). It replaces that family in a **copy** of the original classes.jar; all other original JAR/AAR entry payloads must remain identical. It does not edit cached archives, binary bytecode, package resources or source metadata.

Both ZIP layers use sorted names, fixed1980 timestamps, fixed permissions and STORED entries. Every original payload outside the replacement family remains identical. The outer AAR additionally contains license, notice and deterministic provenance under `META-INF/videostudio/`. Provenance records original/source/patch/classpath/SDK/compiler hashes and exact compiled class inventory; it contains no wall clock, temporary path or credentials. Publication of the generated AAR is atomic after archive readback verification.

## Validation status and next proof

**Source preparation only: the Gradle/Kotlin/API integration has not been executed by this agent.** Root owns every JVM/Gradle/native/ADB operation. The source/archive-only verification mode can verify hashes without executing Java or producing an AAR; this does not establish build or rendering success.

Root must confirm actual Gradle component variant selection and transform execution for compile, unit-test runtime and APK runtime, then inspect the final APK/classpath to prove exactly one patched compositor definition and no unpatched shadow. Compare standalone and Gradle compiled replacement class hashes. Rebuild the same inputs twice and compare output AAR hashes; deliberately mismatching each critical pin must fail closed before compilation/publication.

Actual codec/GPU tests must separately establish primary-clock fidelity, overlay draw order, gap/source phase selection, output-time motion/half-open alpha, cancellation/resource release and preview/export parity. Unit composition/settings checks do not prove those pixels. Legacy no-policy normal and two/four-plate paths must retain their existing behavior. No finished animation, artistic acceptance, voice model or AI generation capability is asserted by this build route.

## Deliberate source updates

Keep the frozen RED tests and earlier failed source generations. Review a source change, regenerate the canonical patch, then deliberately update maintained source/patch hashes in `build-pins.json`. Upstream artifact changes require a separate source/API/license review and new pinned original inventories. Never automatically accept a new upstream hash or generate a replacement signer.
