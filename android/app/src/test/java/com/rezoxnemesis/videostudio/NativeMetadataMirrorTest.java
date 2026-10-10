package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;

import java.io.IOException;

import static org.junit.Assert.*;

/** Source regressions; the transport is a shadow and never opens a connection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE, shadows = NativeMetadataMirrorTest.ShadowProtocol.class)
public class NativeMetadataMirrorTest {
    private Context context;
    private ProjectStore store;
    private NativeMetadataMirror mirror;
    private ProjectStore.Project baseline;
    private JSONObject cloud;
    private SharedPreferences pendingPrefs;
    private int gets;
    private int reconciles;
    private boolean failBeforeAck;
    private boolean loseAckReply;
    private Action afterCloudAck;

    @Before public void prepare() throws Exception {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("videostudio_v3.db");
        context.getSharedPreferences("videostudio_native_v1", Context.MODE_PRIVATE).edit().clear().commit();
        pendingPrefs = context.getSharedPreferences("videostudio_metadata_mirror_pending_v1", Context.MODE_PRIVATE);
        pendingPrefs.edit().clear().commit();
        ShadowProtocol.paused = false;
        ShadowProtocol.gates = 0;
        ShadowProtocol.pauseOnGate = 0;
        store = new ProjectStore(context);
        ProjectStore.Project empty = store.create("Native project");
        baseline = store.edit(empty.id, empty.revision, "Import fixture", project -> {
            ProjectStore.Asset asset = new ProjectStore.Asset();
            asset.id = "asset-a"; asset.name = "Actual video"; asset.uri = "content://private/video-a";
            asset.mime = "video/mp4"; asset.durationMs = 4000; asset.sizeBytes = -1;
            asset.width = 640; asset.height = 360; asset.hasAudio = true;
            asset.generationMetadata.put("sourceUrl", "https://private.invalid/source");
            project.assets.add(asset);
            ProjectStore.Clip clip = new ProjectStore.Clip();
            clip.id = "clip-a"; clip.assetId = asset.id; clip.trackId = project.tracks.get(0).id;
            clip.startMs = 0; clip.inMs = 0; clip.outMs = 4000;
            project.clips.add(clip);
        });
        cloud = new JSONObject().put("ok", true).put("projectId", baseline.id).put("enabled", true)
                .put("dirty", true).put("sourceRevision", baseline.revision).put("mirrorRevision", 7)
                .put("ownerScope", new JSONObject().put("mutationAllowed", true))
                .put("graph", NativeMetadataMirror.metadataGraph(baseline));
        ShadowProtocol.exchange = (operation, parameters) -> {
            if ("get".equals(operation)) { gets++; return copy(cloud); }
            if ("reconcile".equals(operation)) {
                reconciles++;
                if (failBeforeAck) throw new IOException("Offline before acknowledgement");
                assertEquals(cloud.getLong("mirrorRevision"), parameters.getLong("expectedMirrorRevision"));
                assertEquals(cloud.getLong("sourceRevision"), parameters.getLong("baseSourceRevision"));
                String resolution = parameters.getString("resolution");
                assertTrue("acknowledge_mirror".equals(resolution) || "keep_native".equals(resolution));
                if ("keep_native".equals(resolution)) {
                    JSONObject evidence = mirror.status(baseline.id).getJSONObject("lastResolutionEvidence");
                    assertEquals("prepared", evidence.getString("phase"));
                    assertTrue(pendingPrefs.contains("pending:" + baseline.id));
                    cloud.put("discardedGraph", copy(cloud.getJSONObject("graph")));
                }
                cloud.put("sourceRevision", parameters.getLong("sourceRevision"));
                cloud.put("mirrorRevision", cloud.getLong("mirrorRevision") + 1);
                cloud.put("dirty", false); cloud.put("graph", copy(parameters.getJSONObject("projectGraph")));
                if (afterCloudAck != null) afterCloudAck.run();
                if (loseAckReply) throw new IOException("Acknowledgement reply lost");
                return copy(cloud);
            }
            throw new AssertionError("Unexpected transport operation " + operation);
        };
        mirror = new NativeMetadataMirror(context, store, new AppProtocol(context, null));
    }

    @Test public void lostAcknowledgementReplyRecoversWithExactCloudReadbackWithoutAnotherEdit() throws Exception {
        changeCloudVolume(.5f);
        loseAckReply = true;
        JSONObject first = mirror.apply(applyParameters());
        assertFalse(first.getBoolean("ok"));
        assertTrue(first.getBoolean("nativeApplied"));
        assertTrue(first.getBoolean("pendingAcknowledgement"));
        assertEquals(baseline.revision + 1, store.get(baseline.id).revision);
        assertEquals(.5f, store.get(baseline.id).clip("clip-a").volume, 0f);
        assertEquals("content://private/video-a", store.get(baseline.id).asset("asset-a").uri);
        assertTrue(marker().getString("fingerprint").matches("[a-f0-9]{64}"));
        loseAckReply = false;
        JSONObject recovered = mirror.retry(projectParameters());
        assertTrue(recovered.getBoolean("ok"));
        assertTrue(recovered.getBoolean("acknowledgementRecovered"));
        assertFalse(recovered.getBoolean("pendingAcknowledgement"));
        assertEquals(1, reconciles);
        assertEquals(baseline.revision + 1, store.get(baseline.id).revision);
        assertFalse(pendingPrefs.contains("pending:" + baseline.id));
    }

    @Test public void preparedCrashAfterNativeCommitRecoversExactRevisionAndFingerprint() throws Exception {
        changeCloudVolume(.5f);
        ProjectStore.Project intended = NativeMetadataMirror.candidate(baseline, cloud.getJSONObject("graph"));
        persistPrepared(intended, false);
        store.edit(baseline.id, baseline.revision, "Committed before journal phase update", project -> project.clip("clip-a").volume = .5f);
        JSONObject result = mirror.retry(projectParameters());
        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("nativeApplied"));
        assertEquals(baseline.revision + 1, store.get(baseline.id).revision);
        assertEquals(1, reconciles);
        assertFalse(pendingPrefs.contains("pending:" + baseline.id));
    }

    @Test public void preparedCrashBeforeNativeCommitRequiresExplicitApply() throws Exception {
        changeCloudVolume(.5f);
        persistPrepared(NativeMetadataMirror.candidate(baseline, cloud.getJSONObject("graph")), false);
        JSONObject retry = mirror.retry(projectParameters());
        assertFalse(retry.getBoolean("ok"));
        assertFalse(retry.getBoolean("nativeApplied"));
        assertTrue(retry.getBoolean("requiresExplicitApply"));
        assertFalse(retry.getBoolean("pendingAcknowledgement"));
        assertEquals(0, reconciles);
        assertEquals(baseline.revision, store.get(baseline.id).revision);
        assertEquals("prepared", marker().getString("phase"));
        assertTrue(mirror.apply(applyParameters()).getBoolean("ok"));
        assertEquals(baseline.revision + 1, store.get(baseline.id).revision);
    }

    @Test public void laterNativeEditPreventsAcknowledgementAndPreservesJournal() throws Exception {
        changeCloudVolume(.5f);
        failBeforeAck = true;
        mirror.apply(applyParameters());
        String evidence = pendingPrefs.getString("pending:" + baseline.id, "");
        store.edit(baseline.id, baseline.revision + 1, "Owner edited later", project -> project.clip("clip-a").volume = .25f);
        failBeforeAck = false;
        JSONObject result = mirror.retry(projectParameters());
        assertFalse(result.getBoolean("ok"));
        assertTrue(result.getBoolean("stale"));
        assertTrue(result.getBoolean("nativeApplied"));
        assertEquals(1, reconciles);
        assertEquals(evidence, pendingPrefs.getString("pending:" + baseline.id, ""));
        assertTrue(cloud.getBoolean("dirty"));
    }

    @Test public void dirtyEditRevertAcknowledgesWithoutNativeRevisionAdvance() throws Exception {
        JSONObject result = mirror.apply(applyParameters());
        assertTrue(result.getBoolean("ok"));
        assertFalse(result.getBoolean("nativeApplied"));
        assertTrue(result.getBoolean("nativeUnchanged"));
        assertEquals(baseline.revision, result.getLong("appliedRevision"));
        assertEquals(baseline.revision, store.get(baseline.id).revision);
        assertEquals(baseline.revision, cloud.getLong("sourceRevision"));
        assertEquals(8, cloud.getLong("mirrorRevision"));
        assertFalse(cloud.getBoolean("dirty"));
    }

    @Test public void preparedNoOpRecoveryCanAcknowledgeTheBaselineRevision() throws Exception {
        persistPrepared(baseline, true);
        JSONObject result = mirror.retry(projectParameters());
        assertTrue(result.getBoolean("ok"));
        assertFalse(result.getBoolean("nativeApplied"));
        assertTrue(result.getBoolean("nativeUnchanged"));
        assertEquals(baseline.revision, store.get(baseline.id).revision);
        assertEquals(1, reconciles);
    }

    @Test public void cloudScopeDenialPreventsNativeEditAndJournalPreparation() throws Exception {
        changeCloudVolume(.5f);
        cloud.getJSONObject("ownerScope").put("mutationAllowed", false);
        expectRejected(() -> mirror.apply(applyParameters()));
        assertEquals(baseline.revision, store.get(baseline.id).revision);
        assertFalse(pendingPrefs.contains("pending:" + baseline.id));
        assertEquals(0, reconciles);
    }

    @Test public void pauseInsideStoreMutationPreventsPreparedMarkerAndCommit() throws Exception {
        changeCloudVolume(.5f);
        // Entry, checked cloud scope, then the gate inside the ProjectStore edit callback.
        ShadowProtocol.pauseOnGate = 3;
        expectRejected(() -> mirror.apply(applyParameters()));
        assertEquals(baseline.revision, store.get(baseline.id).revision);
        assertFalse(pendingPrefs.contains("pending:" + baseline.id));
        assertEquals(0, reconciles);
    }

    @Test public void localStatusRemainsAvailableOfflineAndPausedWithoutRest() throws Exception {
        persistPrepared(baseline, true);
        ShadowProtocol.paused = true;
        ShadowProtocol.exchange = (operation, parameters) -> { throw new AssertionError("Status must not call REST"); };
        JSONObject status = mirror.status(baseline.id);
        assertEquals(baseline.revision, status.getLong("nativeRevision"));
        assertTrue(status.getBoolean("pending"));
        assertTrue(status.getBoolean("requiresExplicitRetry"));
        assertEquals(0, gets);
    }

    @Test public void oneFilePermissionModeRejectsMutationBeforeCloudRead() throws Exception {
        context.getSharedPreferences("videostudio_native_v1", Context.MODE_PRIVATE).edit()
                .putString("permission_mode", "one_file").commit();
        expectRejected(() -> mirror.apply(applyParameters()));
        assertEquals(0, gets);
        assertEquals(baseline.revision, store.get(baseline.id).revision);
        assertFalse(pendingPrefs.contains("pending:" + baseline.id));
    }

    @Test public void laterCloudRevisionCannotMasqueradeAsTheLostAcknowledgement() throws Exception {
        changeCloudVolume(.5f);
        loseAckReply = true;
        mirror.apply(applyParameters());
        cloud.put("mirrorRevision", 9);
        loseAckReply = false;
        JSONObject result = mirror.retry(projectParameters());
        assertFalse(result.getBoolean("ok"));
        assertTrue(result.getBoolean("pendingAcknowledgement"));
        assertEquals(1, reconciles);
        assertTrue(pendingPrefs.contains("pending:" + baseline.id));
    }

    @Test public void syncPublishesActualNativeMetadataAndRequiresExplicitOptIn() throws Exception {
        cloud.put("mirrorRevision", 0); cloud.put("enabled", false);
        final JSONObject[] captured = new JSONObject[1];
        ShadowProtocol.exchange = (operation, parameters) -> {
            if ("get".equals(operation)) return copy(cloud);
            if ("sync".equals(operation)) { captured[0] = copy(parameters); return new JSONObject().put("ok", true); }
            throw new AssertionError(operation);
        };
        expectRejected(() -> mirror.sync(projectParameters()));
        JSONObject supplied = projectParameters().put("enabled", true).put("sourceRevision", 999)
                .put("projectGraph", new JSONObject().put("name", "Fake caller graph"));
        assertTrue(mirror.sync(supplied).getBoolean("ok"));
        assertEquals(baseline.revision, captured[0].getLong("sourceRevision"));
        assertEquals(0, captured[0].getLong("expectedMirrorRevision"));
        assertEquals("Native project", captured[0].getJSONObject("projectGraph").getString("name"));
        assertFalse(captured[0].toString().contains("content://"));
        assertFalse(captured[0].toString().contains("private.invalid"));
        cloud.put("mirrorRevision", 7);
        expectRejected(() -> mirror.sync(projectParameters().put("enabled", true)));
    }

    @Test public void syncAndRevokeRefusePendingWorkWithoutReadingCloud() throws Exception {
        persistPrepared(baseline, true);
        expectRejected(() -> mirror.sync(projectParameters().put("enabled", true).put("expectedMirrorRevision", 7)));
        expectRejected(() -> mirror.revoke(projectParameters().put("expectedMirrorRevision", 7)));
        assertEquals(0, gets);
        assertNotNull(marker());
    }

    @Test public void recursivePrivateFieldsSurvivePublicEffectEditsAndUnknownSizeMatches() throws Exception {
        baseline.clip("clip-a").effects = new JSONObject().put("image", new JSONObject()
                .put("layerForegroundUri", "file:///private/foreground.png").put("opacity", .75))
                .put("metadata", new JSONArray().put(new JSONArray().put(new JSONObject().put("id", "layer-a").put("sourceUri", "content://private/layer"))));
        JSONObject graph = NativeMetadataMirror.metadataGraph(baseline);
        assertFalse(graph.toString().contains("file:///"));
        assertFalse(graph.toString().contains("content://"));
        assertFalse(graph.toString().contains("generationMetadata"));
        assertEquals(0, graph.getJSONArray("assets").getJSONObject(0).getLong("sizeBytes"));
        graph.getJSONArray("clips").getJSONObject(0).getJSONObject("effects").getJSONObject("image").put("opacity", .5);
        ProjectStore.Project next = NativeMetadataMirror.candidate(baseline, graph);
        assertEquals("file:///private/foreground.png", next.clip("clip-a").effects.getJSONObject("image").getString("layerForegroundUri"));
        assertEquals(.5, next.clip("clip-a").effects.getJSONObject("image").getDouble("opacity"), 0d);
        assertEquals("content://private/layer", next.clip("clip-a").effects.getJSONArray("metadata").getJSONArray(0).getJSONObject(0).getString("sourceUri"));
        assertEquals(-1, next.asset("asset-a").sizeBytes);
        assertEquals("https://private.invalid/source", next.asset("asset-a").generationMetadata.getString("sourceUrl"));
    }

    @Test public void originalLockedTrackCannotBeTrimmedBySimultaneousUnlock() throws Exception {
        baseline.tracks.get(0).locked = true;
        JSONObject graph = NativeMetadataMirror.metadataGraph(baseline);
        graph.getJSONArray("tracks").getJSONObject(0).put("locked", false);
        graph.getJSONArray("clips").getJSONObject(0).put("outMs", 3000);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, graph));
        graph.getJSONArray("clips").getJSONObject(0).put("outMs", 4000);
        assertFalse(NativeMetadataMirror.candidate(baseline, graph).tracks.get(0).locked);
    }

    @Test public void metadataMismatchOrUnsupportedEffectCannotEnterNativeGraph() throws Exception {
        JSONObject wrongAsset = NativeMetadataMirror.metadataGraph(baseline);
        wrongAsset.getJSONArray("assets").getJSONObject(0).put("durationMs", 9000);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, wrongAsset));
        JSONObject unsupported = NativeMetadataMirror.metadataGraph(baseline);
        unsupported.getJSONArray("clips").getJSONObject(0).getJSONObject("effects").put("reverse", true);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, unsupported));
    }

    @Test public void layeredEffectsRequireBothNativeForegroundAndBackground() throws Exception {
        baseline.clip("clip-a").effects.put("headUri", "file:///private/head.png");
        JSONObject graph = NativeMetadataMirror.metadataGraph(baseline);
        graph.getJSONArray("clips").getJSONObject(0).getJSONObject("effects").put("animatedScene", true);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, graph));
        baseline.clip("clip-a").effects.put("foregroundUri", "file:///private/foreground.png")
                .put("backgroundUri", "file:///private/background.png");
        assertTrue(NativeMetadataMirror.candidate(baseline, graph).clip("clip-a").effects.getBoolean("animatedScene"));
    }

    @Test public void keepNativeResolvesUncommittedPreparedWorkAtEqualSourceWithoutNativeEdit() throws Exception {
        changeCloudVolume(.5f);
        persistPrepared(NativeMetadataMirror.candidate(baseline, cloud.getJSONObject("graph")), false);
        JSONObject result = mirror.keepNative(keepNativeParameters());
        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("nativeKept"));
        assertTrue(result.getBoolean("pendingResolved"));
        assertFalse(result.getBoolean("pending"));
        assertEquals(baseline.revision, store.get(baseline.id).revision);
        assertEquals(1f, store.get(baseline.id).clip("clip-a").volume, 0f);
        assertEquals(.5, cloud.getJSONObject("discardedGraph").getJSONArray("clips").getJSONObject(0).getDouble("volume"), 0d);
        JSONObject receipt = result.getJSONObject("lastResolutionEvidence");
        assertEquals("completed", receipt.getString("phase"));
        assertEquals("prepared", receipt.getJSONObject("originalPendingMarker").getString("phase"));
        assertEquals(8, receipt.getLong("resolvedMirrorRevision"));
        assertFalse(receipt.has("graph"));
    }

    @Test public void keepNativeResolvesStaleAppliedMarkerAndPreservesItsOriginalEvidence() throws Exception {
        changeCloudVolume(.5f);
        failBeforeAck = true;
        assertTrue(mirror.apply(applyParameters()).getBoolean("nativeApplied"));
        store.edit(baseline.id, baseline.revision + 1, "Owner chose later native authoring", project -> project.clip("clip-a").volume = .25f);
        failBeforeAck = false;
        JSONObject result = mirror.keepNative(keepNativeParameters());
        assertTrue(result.getBoolean("ok"));
        assertFalse(result.getBoolean("pending"));
        assertTrue(result.getBoolean("nativeUnchanged"));
        assertEquals(baseline.revision + 2, store.get(baseline.id).revision);
        assertEquals(.25f, store.get(baseline.id).clip("clip-a").volume, 0f);
        JSONObject original = result.getJSONObject("lastResolutionEvidence").getJSONObject("originalPendingMarker");
        assertEquals("applied", original.getString("phase"));
        assertEquals(baseline.revision + 1, original.getLong("appliedRevision"));
        assertEquals(.5, cloud.getJSONObject("discardedGraph").getJSONArray("clips").getJSONObject(0).getDouble("volume"), 0d);
    }

    @Test public void keepNativeFailureRetainsOriginalMarkerAndPreparedEvidence() throws Exception {
        changeCloudVolume(.5f);
        persistPrepared(NativeMetadataMirror.candidate(baseline, cloud.getJSONObject("graph")), false);
        String original = pendingPrefs.getString("pending:" + baseline.id, "");
        failBeforeAck = true;
        JSONObject result = mirror.keepNative(keepNativeParameters());
        assertFalse(result.getBoolean("ok"));
        assertTrue(result.getBoolean("pending"));
        assertEquals(original, pendingPrefs.getString("pending:" + baseline.id, ""));
        assertEquals("prepared", result.getJSONObject("lastResolutionEvidence").getString("phase"));
        assertEquals(baseline.revision, store.get(baseline.id).revision);
        assertTrue(cloud.getBoolean("dirty"));
    }

    @Test public void keepNativeReceiptsRemainBoundedAcrossRepeatedFailedExplicitAttempts() throws Exception {
        persistPrepared(baseline, true);
        failBeforeAck = true;
        for (int i = 0; i < 12; i++) assertFalse(mirror.keepNative(keepNativeParameters()).getBoolean("ok"));
        JSONObject status = mirror.status(baseline.id);
        assertEquals(8, status.getJSONArray("resolutionEvidence").length());
        assertTrue(status.getBoolean("pending"));
        assertEquals(baseline.revision, status.getLong("nativeRevision"));
        assertTrue(status.getJSONArray("resolutionEvidence").toString().getBytes("UTF-8").length <= 8 * 4097 + 2);
    }

    @Test public void keepNativeCloudConflictPreservesPreparedReceiptAndOriginalJournal() throws Exception {
        persistPrepared(baseline, true);
        String original = pendingPrefs.getString("pending:" + baseline.id, "");
        ShadowProtocol.exchange = (operation, parameters) -> {
            if ("get".equals(operation)) return copy(cloud);
            if ("reconcile".equals(operation)) return new JSONObject().put("ok", false).put("conflict", true).put("reason", "Mirror revision changed");
            throw new AssertionError(operation);
        };
        JSONObject result = mirror.keepNative(keepNativeParameters());
        assertFalse(result.getBoolean("ok"));
        assertTrue(result.getBoolean("pending"));
        assertEquals("prepared", result.getJSONObject("lastResolutionEvidence").getString("phase"));
        assertEquals(original, pendingPrefs.getString("pending:" + baseline.id, ""));
        assertEquals(baseline.revision, store.get(baseline.id).revision);
    }

    @Test public void keepNativeRefusesCleanEqualDifferentGraphOrNewerCloudSourceWithoutDiscardingEvidence() throws Exception {
        persistPrepared(baseline, true);
        changeCloudVolume(.5f);
        cloud.put("dirty", false);
        JSONObject clean = mirror.keepNative(keepNativeParameters());
        assertFalse(clean.getBoolean("ok"));
        assertTrue(clean.getBoolean("pending"));
        cloud.put("dirty", true).put("sourceRevision", baseline.revision + 1);
        JSONObject newer = mirror.keepNative(keepNativeParameters());
        assertFalse(newer.getBoolean("ok"));
        assertTrue(newer.getBoolean("pending"));
        assertEquals(0, reconciles);
        assertFalse(newer.has("lastResolutionEvidence"));
    }

    @Test public void keepNativeCanExplicitlySettleCleanEqualIdenticalGraphWithNewEvidence() throws Exception {
        persistPrepared(baseline, true);
        cloud.put("dirty", false);
        JSONObject result = mirror.keepNative(keepNativeParameters());
        assertTrue(result.getBoolean("ok"));
        assertTrue(result.getBoolean("nativeKept"));
        assertFalse(result.getBoolean("acknowledgementRecovered"));
        assertEquals(1, reconciles);
        assertEquals(8, cloud.getLong("mirrorRevision"));
        assertEquals(baseline.revision, store.get(baseline.id).revision);
        assertEquals("completed", result.getJSONObject("lastResolutionEvidence").getString("phase"));
    }

    @Test public void keepNativeLostReplyNeedsCurrentMirrorRevisionAndExactReceiptProof() throws Exception {
        changeCloudVolume(.5f);
        persistPrepared(NativeMetadataMirror.candidate(baseline, cloud.getJSONObject("graph")), false);
        JSONObject originalParameters = keepNativeParameters();
        loseAckReply = true;
        JSONObject first = mirror.keepNative(originalParameters);
        assertFalse(first.getBoolean("ok"));
        assertTrue(first.getBoolean("pending"));
        assertEquals("prepared", first.getJSONObject("lastResolutionEvidence").getString("phase"));
        loseAckReply = false;
        assertFalse(mirror.keepNative(originalParameters).getBoolean("ok"));
        JSONObject recovered = mirror.keepNative(keepNativeParameters());
        assertTrue(recovered.getBoolean("ok"));
        assertTrue(recovered.getBoolean("acknowledgementRecovered"));
        assertEquals(1, reconciles);
        assertEquals(baseline.revision, store.get(baseline.id).revision);
        assertEquals("completed", recovered.getJSONObject("lastResolutionEvidence").getString("phase"));
    }

    @Test public void keepNativeRefusesChangedPendingIdentityBeforeCloudRead() throws Exception {
        persistPrepared(baseline, true);
        JSONObject parameters = keepNativeParameters().put("expectedPendingId", "replacement-pending-id");
        JSONObject result = mirror.keepNative(parameters);
        assertFalse(result.getBoolean("ok"));
        assertTrue(result.getBoolean("conflict"));
        assertEquals(0, gets);
        assertEquals(0, reconciles);
        assertTrue(pendingPrefs.contains("pending:" + baseline.id));
    }

    @Test public void keepNativeFinalMarkerCheckPreventsClearingReplacementJournal() throws Exception {
        changeCloudVolume(.5f);
        persistPrepared(NativeMetadataMirror.candidate(baseline, cloud.getJSONObject("graph")), false);
        afterCloudAck = () -> {
            JSONObject replaced = marker().put("pendingId", "replacement-pending-id");
            pendingPrefs.edit().putString("pending:" + baseline.id, replaced.toString()).commit();
        };
        JSONObject result = mirror.keepNative(keepNativeParameters());
        assertFalse(result.getBoolean("ok"));
        assertEquals("replacement-pending-id", result.getJSONObject("pendingMarker").getString("pendingId"));
        assertEquals("prepared", result.getJSONObject("lastResolutionEvidence").getString("phase"));
        assertEquals(baseline.revision, store.get(baseline.id).revision);
    }

    @Test public void renewedKeepNativeCanResolveOwnerEditDuringEarlierCloudRoundTrip() throws Exception {
        changeCloudVolume(.5f);
        persistPrepared(NativeMetadataMirror.candidate(baseline, cloud.getJSONObject("graph")), false);
        ProjectStore editorStore = new ProjectStore(context);
        afterCloudAck = () -> editorStore.edit(baseline.id, baseline.revision, "Owner edited during resolution", project -> project.clip("clip-a").volume = .25f);
        JSONObject first = mirror.keepNative(keepNativeParameters());
        assertFalse(first.getBoolean("ok"));
        assertTrue(first.getBoolean("pending"));
        assertEquals("prepared", first.getJSONObject("lastResolutionEvidence").getString("phase"));
        afterCloudAck = null;
        JSONObject renewed = mirror.keepNative(keepNativeParameters());
        assertTrue(renewed.getBoolean("ok"));
        assertFalse(renewed.getBoolean("acknowledgementRecovered"));
        assertEquals(2, reconciles);
        assertEquals(2, renewed.getJSONArray("resolutionEvidence").length());
        assertEquals(.25f, store.get(baseline.id).clip("clip-a").volume, 0f);
        assertEquals(baseline.revision + 1, store.get(baseline.id).revision);
    }

    @Test public void legacyPendingIdentityIsStableAcrossPhaseRecovery() throws Exception {
        changeCloudVolume(.5f);
        persistPrepared(NativeMetadataMirror.candidate(baseline, cloud.getJSONObject("graph")), false);
        String preparedId = mirror.status(baseline.id).getJSONObject("pendingMarker").getString("pendingId");
        assertTrue(preparedId.startsWith("legacy-"));
        store.edit(baseline.id, baseline.revision, "Prepared edit actually committed", project -> project.clip("clip-a").volume = .5f);
        failBeforeAck = true;
        mirror.retry(projectParameters());
        assertEquals(preparedId, mirror.status(baseline.id).getJSONObject("pendingMarker").getString("pendingId"));
    }

    @Test public void mirrorCannotManufactureNativeLinkOrDetachedAudioEvidence() throws Exception {
        JSONObject linked = NativeMetadataMirror.metadataGraph(baseline);
        linked.getJSONArray("clips").getJSONObject(0).put("linkGroupId", "invented-group");
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, linked));
        JSONObject detached = NativeMetadataMirror.metadataGraph(baseline);
        detached.getJSONArray("clips").getJSONObject(0).getJSONObject("effects").put("audioDetached", true);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, detached));
        detached.getJSONArray("clips").getJSONObject(0).getJSONObject("effects").put("audioDetached", false);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, detached));
        JSONObject extraction = NativeMetadataMirror.metadataGraph(baseline);
        extraction.getJSONArray("clips").getJSONObject(0).getJSONObject("effects").put("audioExtractionDetached", true);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, extraction));
        extraction.getJSONArray("clips").getJSONObject(0).getJSONObject("effects").put("audioExtractionDetached", false);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, extraction));
    }

    @Test public void nativeExtractedLinkMembershipAndAudioDetachedSurviveMirrorEdits() throws Exception {
        ProjectTimeline.apply(baseline, "extract_audio", new JSONObject().put("clipId", "clip-a"));
        ProjectTimeline.validate(baseline);
        assertEquals(2, baseline.clips.size());
        assertFalse(baseline.clip("clip-a").linkGroupId.isEmpty());
        assertTrue(baseline.clip("clip-a").effects.getBoolean("audioDetached"));
        assertTrue(baseline.clip("clip-a").effects.getBoolean("audioExtractionDetached"));
        JSONObject graph = NativeMetadataMirror.metadataGraph(baseline);
        graph.getJSONArray("clips").getJSONObject(0).put("volume", .5d);
        ProjectStore.Project next = NativeMetadataMirror.candidate(baseline, graph);
        assertEquals(baseline.clip("clip-a").linkGroupId, next.clip("clip-a").linkGroupId);
        assertTrue(next.clip("clip-a").effects.getBoolean("audioDetached"));
        assertTrue(next.clip("clip-a").effects.getBoolean("audioExtractionDetached"));
        JSONObject unlinked = copy(graph);
        for (int i = 0; i < unlinked.getJSONArray("clips").length(); i++) unlinked.getJSONArray("clips").getJSONObject(i).put("linkGroupId", "");
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, unlinked));
        JSONObject toggled = copy(graph);
        toggled.getJSONArray("clips").getJSONObject(0).getJSONObject("effects").put("audioDetached", false);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, toggled));
        JSONObject removed = copy(graph);
        removed.getJSONArray("clips").getJSONObject(0).getJSONObject("effects").remove("audioDetached");
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, removed));
        JSONObject extractionRemoved = copy(graph);
        extractionRemoved.getJSONArray("clips").getJSONObject(0).getJSONObject("effects").remove("audioExtractionDetached");
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, extractionRemoved));
        JSONObject extractionChanged = copy(graph);
        extractionChanged.getJSONArray("clips").getJSONObject(0).getJSONObject("effects").put("audioExtractionDetached", false);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, extractionChanged));
        JSONObject deleted = copy(graph);
        deleted.getJSONArray("clips").remove(1);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, deleted));
    }

    @Test public void mirrorPreservesUnlinkedExtractionOwnershipAndExactBooleans() throws Exception {
        ProjectTimeline.apply(baseline, "extract_audio", new JSONObject().put("clipId", "clip-a"));
        ProjectTimeline.apply(baseline, "unlink", new JSONObject().put("clipId", "clip-a"));
        assertTrue(baseline.clip("clip-a").linkGroupId.isEmpty());
        JSONObject deleted = NativeMetadataMirror.metadataGraph(baseline);
        deleted.getJSONArray("clips").remove(0);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, deleted));
        ProjectStore.Asset replacement = new ProjectStore.Asset();
        replacement.id = "asset-b"; replacement.name = "Replacement video"; replacement.uri = "content://private/video-b";
        replacement.mime = "video/mp4"; replacement.durationMs = 4000; replacement.hasAudio = true;
        baseline.assets.add(replacement);
        JSONObject rebound = NativeMetadataMirror.metadataGraph(baseline);
        rebound.getJSONArray("clips").getJSONObject(0).put("assetId", replacement.id);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, rebound));
        for (String key : new String[]{"audioDetached", "audioExtractionDetached"}) {
            baseline.clip("clip-a").effects.put(key, "true");
            expectRejected(() -> NativeMetadataMirror.metadataGraph(baseline));
            baseline.clip("clip-a").effects.put(key, true);
        }
    }

    @Test public void float32RatesProjectAsExpandedDoublesAndApplyWithoutDrift() throws Exception {
        baseline.clip("clip-a").speed = .1f;
        baseline.clip("clip-a").volume = .3f;
        JSONObject graph = NativeMetadataMirror.metadataGraph(baseline);
        JSONObject clip = graph.getJSONArray("clips").getJSONObject(0);
        assertEquals(0.10000000149011612d, clip.getDouble("speed"), 0d);
        assertEquals(0.30000001192092896d, clip.getDouble("volume"), 0d);
        ProjectStore.Project roundTrip = NativeMetadataMirror.candidate(baseline, copy(graph));
        assertEquals(.1f, roundTrip.clip("clip-a").speed, 0f);
        assertEquals(.3f, roundTrip.clip("clip-a").volume, 0f);
        assertEquals(NativeMetadataMirror.fingerprint(baseline), NativeMetadataMirror.fingerprint(roundTrip));
    }

    @Test public void mirroredTimelinePreservesNativeMarkersAndReconcilesPrivateRehearsalRange() throws Exception {
        ProjectMarkers.add(baseline, new JSONObject().put("markerId", "owner-marker").put("atMs", 3000L).put("name", "Owner cue"));
        ProjectMarkers.setRange(baseline, new JSONObject().put("inMs", 1000L).put("outMs", 3500L).put("enabled", true));
        JSONObject graph = NativeMetadataMirror.metadataGraph(baseline);
        assertFalse(graph.has("markers")); assertFalse(graph.has("editorRange"));
        graph.getJSONArray("clips").getJSONObject(0).put("outMs", 2000L);
        ProjectStore.Project next = NativeMetadataMirror.candidate(baseline, graph);
        assertEquals(baseline.markers.toString(), next.markers.toString());
        assertFalse(ProjectMarkers.list(next).getJSONObject(0).getBoolean("inProgram"));
        assertFalse(next.editorRange.getBoolean("enabled"));
        assertTrue(baseline.editorRange.getBoolean("enabled"));
    }

    @Test public void fingerprintIgnoresObjectOrderAndRevisionButBindsPrivateAssetIdentity() throws Exception {
        baseline.clip("clip-a").effects = new JSONObject().put("brightness", .1).put("image", new JSONObject().put("layerUri", "file:///private/a.png"));
        String fingerprint = NativeMetadataMirror.fingerprint(baseline);
        ProjectStore.Project reordered = ProjectStore.Project.fromJson(baseline.toJson());
        reordered.revision += 20; reordered.updatedAt += 100;
        reordered.clip("clip-a").effects = new JSONObject().put("image", new JSONObject().put("layerUri", "file:///private/a.png")).put("brightness", .1);
        assertEquals(fingerprint, NativeMetadataMirror.fingerprint(reordered));
        reordered.asset("asset-a").uri = "content://private/replaced";
        assertNotEquals(fingerprint, NativeMetadataMirror.fingerprint(reordered));
    }

    @Test public void metadataApplyPreservesOwnerChosenNativeCadence() throws Exception {
        baseline.animationFrameRate = 24;
        JSONObject graph = NativeMetadataMirror.metadataGraph(baseline);
        graph.getJSONArray("clips").getJSONObject(0).put("title", "Metadata title edit");
        ProjectStore.Project next = NativeMetadataMirror.candidate(baseline, graph);
        assertEquals(24, next.animationFrameRate);
        assertEquals(24, baseline.animationFrameRate);
    }

    @Test public void mirrorCannotManufactureOrDiscardNativeCelExposureProvenance() throws Exception {
        JSONObject manufactured = NativeMetadataMirror.metadataGraph(baseline);
        manufactured.getJSONArray("clips").getJSONObject(0).getJSONObject("effects")
                .put("celExposure", new JSONObject().put("version", 1).put("frameCount", 1));
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, manufactured));
        baseline.clip("clip-a").effects.put("celExposure", new JSONObject().put("version", 1).put("frameCount", 1));
        JSONObject projected = NativeMetadataMirror.metadataGraph(baseline);
        assertFalse(projected.getJSONArray("clips").getJSONObject(0).getJSONObject("effects").has("celExposure"));
        projected.getJSONArray("clips").remove(0);
        expectRejected(() -> NativeMetadataMirror.candidate(baseline, projected));
    }

    private void changeCloudVolume(float volume) throws Exception {
        cloud.getJSONObject("graph").getJSONArray("clips").getJSONObject(0).put("volume", (double) volume);
    }

    private JSONObject projectParameters() throws Exception { return new JSONObject().put("projectId", baseline.id); }
    private JSONObject applyParameters() throws Exception {
        return projectParameters().put("expectedNativeRevision", baseline.revision).put("expectedMirrorRevision", 7);
    }

    private JSONObject keepNativeParameters() throws Exception {
        JSONObject marker = mirror.status(baseline.id).getJSONObject("pendingMarker");
        return projectParameters().put("expectedNativeRevision", store.get(baseline.id).revision)
                .put("expectedMirrorRevision", cloud.getLong("mirrorRevision"))
                .put("expectedPendingFingerprint", marker.getString("fingerprint"))
                .put("expectedPendingMirrorRevision", marker.getLong("expectedMirrorRevision"))
                .put("expectedPendingId", marker.getString("pendingId"));
    }

    private void persistPrepared(ProjectStore.Project intended, boolean noOp) throws Exception {
        JSONObject marker = new JSONObject().put("projectId", baseline.id).put("deviceId", "test-device")
                .put("phase", "prepared").put("fingerprint", NativeMetadataMirror.fingerprint(intended))
                .put("beforeFingerprint", NativeMetadataMirror.fingerprint(baseline)).put("noOp", noOp)
                .put("baseSourceRevision", baseline.revision).put("expectedNativeRevision", baseline.revision)
                .put("expectedMirrorRevision", 7);
        assertTrue(pendingPrefs.edit().putString("pending:" + baseline.id, marker.toString()).commit());
    }

    private JSONObject marker() throws Exception { return new JSONObject(pendingPrefs.getString("pending:" + baseline.id, "")); }
    private static JSONObject copy(JSONObject value) throws Exception { return new JSONObject(value.toString()); }
    private interface Action { void run() throws Exception; }
    private static void expectRejected(Action action) throws Exception {
        try { action.run(); fail("Mutation should be rejected"); }
        catch (IllegalArgumentException | IllegalStateException expected) { assertNotNull(expected.getMessage()); }
    }

    private interface Exchange { JSONObject invoke(String operation, JSONObject parameters) throws Exception; }

    @Implements(AppProtocol.class)
    public static class ShadowProtocol {
        private static Exchange exchange;
        private static boolean paused;
        private static int gates;
        private static int pauseOnGate;

        @Implementation public void __constructor__(Context context, AppProtocol.Callback callback) {}
        @Implementation public String deviceId() { return "test-device"; }
        @Implementation public boolean isControlPaused() {
            gates++;
            if (pauseOnGate > 0 && gates >= pauseOnGate) paused = true;
            return paused;
        }
        @Implementation public JSONObject metadataMirror(String operation, JSONObject parameters) throws Exception {
            if (exchange == null) throw new AssertionError("No fake metadata transport configured");
            return exchange.invoke(operation, parameters);
        }
    }
}
