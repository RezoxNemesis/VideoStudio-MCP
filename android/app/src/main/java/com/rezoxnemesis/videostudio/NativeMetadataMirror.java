package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;

/** Explicit metadata exchange and native application, with a durable acknowledgement journal. */
public final class NativeMetadataMirror {
    private static final ReentrantLock MUTATIONS = new ReentrantLock(true);
    private static final String PREFS = "videostudio_metadata_mirror_pending_v1";
    private static final long MAX_SAFE_INTEGER = 9_007_199_254_740_991L;
    private static final int MAX_GRAPH_BYTES = 64 * 1024;
    private static final int MAX_RESOLUTION_RECEIPTS = 8;
    private static final int MAX_RECEIPT_BYTES = 4096;
    private static final Pattern PRIVATE_SUFFIX = Pattern.compile("(?:uri|url|path)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PRIVATE_SECRET = Pattern.compile("(?:credential|password|secret|token|api[_-]?key|authorization)", Pattern.CASE_INSENSITIVE);
    private static final Pattern LOCATOR = Pattern.compile("^(?:(?:file|content|https?)://|data:)", Pattern.CASE_INSENSITIVE);
    private static final Set<String> PRIVATE_KEYS = new HashSet<>(java.util.Arrays.asList(
            "uri", "url", "sourceurl", "download_url", "downloadurl", "base64", "ownerkey",
            "authorization", "token", "secret", "localpath", "filepath", "location", "locations", "storagepath"));

    private final ProjectStore store;
    private final AppProtocol protocol;
    private final SharedPreferences prefs;
    private final SharedPreferences ownerPrefs;

    public NativeMetadataMirror(Context context, ProjectStore store, AppProtocol protocol) {
        if (context == null || store == null || protocol == null) throw new IllegalArgumentException("Mirror dependencies are required");
        this.store = store;
        this.protocol = protocol;
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.ownerPrefs = context.getApplicationContext().getSharedPreferences("videostudio_native_v1", Context.MODE_PRIVATE);
    }

    public JSONObject sync(JSONObject parameters) throws Exception {
        MUTATIONS.lockInterruptibly();
        try {
            requireLocalMutation();
            String projectId = projectId(parameters);
            if (!Boolean.TRUE.equals(parameters.opt("enabled"))) throw new IllegalArgumentException("Metadata sync requires enabled:true");
            requireNoPending(projectId);
            JSONObject cloud = cloudGet(projectId);
            requireMutationScope(cloud);
            ProjectStore.Project nativeProject = requireProject(projectId);
            long currentMirrorRevision = cloud.optLong("mirrorRevision", 0L);
            if (currentMirrorRevision > 0L && !parameters.has("expectedMirrorRevision")) {
                throw new IllegalArgumentException("Existing mirrors require expectedMirrorRevision");
            }
            long expectedMirror = parameters.has("expectedMirrorRevision")
                    ? revision(parameters, "expectedMirrorRevision", 0L) : 0L;
            JSONObject request = new JSONObject();
            request.put("projectId", projectId);
            request.put("enabled", true);
            request.put("expectedMirrorRevision", expectedMirror);
            request.put("sourceRevision", safeInteger(nativeProject.revision, "Native revision", 1L));
            request.put("projectGraph", metadataGraph(nativeProject));
            requireLocalMutation();
            return protocol.metadataMirror("sync", request);
        } finally { MUTATIONS.unlock(); }
    }

    public JSONObject get(String projectId) throws Exception {
        String id = stableId(projectId);
        JSONObject response = cloudGet(id);
        response.put("nativeStatus", localStatus(id));
        return response;
    }

    public JSONObject status(String projectId) throws Exception {
        return localStatus(stableId(projectId));
    }

    public JSONObject revoke(JSONObject parameters) throws Exception {
        MUTATIONS.lockInterruptibly();
        try {
            requireLocalMutation();
            String projectId = projectId(parameters);
            requireNoPending(projectId);
            requireMutationScope(cloudGet(projectId));
            JSONObject request = new JSONObject();
            request.put("projectId", projectId);
            request.put("expectedMirrorRevision", revision(parameters, "expectedMirrorRevision", 1L));
            requireLocalMutation();
            return protocol.metadataMirror("revoke", request);
        } finally { MUTATIONS.unlock(); }
    }

    public JSONObject apply(JSONObject parameters) throws Exception {
        MUTATIONS.lockInterruptibly();
        try {
            requireLocalMutation();
            String projectId = projectId(parameters);
            long expectedNative = revision(parameters, "expectedNativeRevision", 1L);
            long expectedMirror = revision(parameters, "expectedMirrorRevision", 1L);
            if (expectedMirror == MAX_SAFE_INTEGER) throw new IllegalStateException("Mirror revision cannot be advanced safely");
            ProjectStore.Project before = requireProject(projectId);
            if (before.revision != expectedNative) throw new ProjectStore.RevisionConflictException(expectedNative, before.revision);
            JSONObject pending = pending(projectId);
            if (pending != null && (!"prepared".equals(pending.optString("phase"))
                    || pending.getLong("expectedNativeRevision") != expectedNative
                    || pending.getLong("expectedMirrorRevision") != expectedMirror
                    || !fingerprint(before).equals(pending.getString("beforeFingerprint")))) {
                return pendingResult(projectId, "A previous apply needs explicit retry or native conflict resolution");
            }

            JSONObject cloud = cloudGet(projectId);
            requireMutationScope(cloud);
            if (!cloud.optBoolean("enabled", false) || !cloud.optBoolean("dirty", false)) {
                throw new IllegalStateException("Apply requires an enabled mirror with pending graph edits");
            }
            if (cloud.getLong("mirrorRevision") != expectedMirror || cloud.getLong("sourceRevision") != expectedNative) {
                return conflict(projectId, "Mirror revision or native source baseline changed", cloud);
            }
            JSONObject graph = cloud.getJSONObject("graph");
            ProjectStore.Project intended = candidate(before, graph);
            String intendedFingerprint = fingerprint(intended);
            String beforeFingerprint = fingerprint(before);
            boolean noOp = intendedFingerprint.equals(beforeFingerprint);
            if (!noOp && expectedNative == MAX_SAFE_INTEGER) throw new IllegalStateException("Native revision cannot be advanced safely");
            if (pending != null && (!intendedFingerprint.equals(pending.getString("fingerprint"))
                    || pending.optBoolean("noOp", false) != noOp)) {
                return pendingResult(projectId, "Prepared graph no longer matches this explicit apply");
            }

            JSONObject prepared = new JSONObject();
            prepared.put("projectId", projectId);
            prepared.put("deviceId", protocol.deviceId());
            prepared.put("pendingId", pending == null ? UUID.randomUUID().toString() : pending.getString("pendingId"));
            prepared.put("phase", "prepared");
            prepared.put("fingerprint", intendedFingerprint);
            prepared.put("beforeFingerprint", beforeFingerprint);
            prepared.put("baseSourceRevision", cloud.getLong("sourceRevision"));
            prepared.put("expectedNativeRevision", expectedNative);
            prepared.put("expectedMirrorRevision", expectedMirror);
            prepared.put("noOp", noOp);
            prepared.put("preparedAt", pending == null ? System.currentTimeMillis() : pending.optLong("preparedAt", 0L));

            ProjectStore.Project applied;
            try {
                applied = store.edit(projectId, expectedNative, "Apply metadata mirror", current -> {
                    requireMutationScope(cloud);
                    if (!fingerprint(current).equals(beforeFingerprint)) throw new IllegalStateException("Native graph changed before apply");
                    ProjectStore.Project next = candidate(current, graph);
                    if (!fingerprint(next).equals(intendedFingerprint)) throw new IllegalStateException("Prepared native graph changed");
                    if (!noOp) {
                        current.tracks.clear(); current.tracks.addAll(next.tracks);
                        current.clips.clear(); current.clips.addAll(next.clips);
                    }
                    // This synchronous write must succeed before ProjectStore commits its SQLite edit.
                    savePending(projectId, prepared);
                });
            } catch (Exception failure) {
                if (pending(projectId) != null) return pendingResult(projectId, failure.getMessage());
                throw failure;
            }

            long appliedRevision = noOp ? expectedNative : expectedNative + 1L;
            if (applied.revision != appliedRevision || !fingerprint(applied).equals(intendedFingerprint)) {
                return pendingResult(projectId, "Native readback differs from the prepared graph");
            }
            prepared.put("phase", "applied");
            prepared.put("appliedRevision", applied.revision);
            try { savePending(projectId, prepared); }
            catch (Exception failure) { return pendingResult(projectId, "Native edit committed; acknowledgement journal update failed"); }
            return acknowledge(projectId, prepared);
        } finally { MUTATIONS.unlock(); }
    }

    public JSONObject retry(JSONObject parameters) throws Exception {
        MUTATIONS.lockInterruptibly();
        try {
            requireLocalMutation();
            String projectId = projectId(parameters);
            JSONObject marker = pending(projectId);
            if (marker == null) throw new IllegalStateException("No pending mirror acknowledgement exists");
            try { requireMutationScope(cloudGet(projectId)); }
            catch (Exception failure) { return pendingResult(projectId, failure.getMessage()); }
            ProjectStore.Project actual = requireProject(projectId);
            if ("prepared".equals(marker.getString("phase"))) {
                long appliedRevision = marker.getLong("expectedNativeRevision") + (marker.optBoolean("noOp", false) ? 0L : 1L);
                if (actual.revision != appliedRevision || !fingerprint(actual).equals(marker.getString("fingerprint"))) {
                    return pendingResult(projectId, "Prepared work is not proven applied; resume it with explicit apply or resolve the stale native graph");
                }
                marker.put("phase", "applied");
                marker.put("appliedRevision", appliedRevision);
                try { savePending(projectId, marker); }
                catch (Exception failure) { return pendingResult(projectId, "Could not persist recovered native acknowledgement"); }
            }
            return acknowledge(projectId, marker);
        } finally { MUTATIONS.unlock(); }
    }

    /** Explicitly preserve current native authoring and retire one identified pending journal. */
    public JSONObject keepNative(JSONObject parameters) throws Exception {
        MUTATIONS.lockInterruptibly();
        try {
            requireLocalMutation();
            String projectId = projectId(parameters);
            long expectedNative = revision(parameters, "expectedNativeRevision", 1L);
            long expectedMirror = revision(parameters, "expectedMirrorRevision", 1L);
            long pendingMirror = revision(parameters, "expectedPendingMirrorRevision", 1L);
            String pendingFingerprint = parameters.getString("expectedPendingFingerprint");
            String pendingId = stableId(parameters.getString("expectedPendingId"));
            JSONObject marker = pending(projectId);
            if (marker == null) throw new IllegalStateException("No pending mirror work exists to resolve");
            if (!pendingId.equals(marker.getString("pendingId")) || !pendingFingerprint.equals(marker.getString("fingerprint"))
                    || pendingMirror != marker.getLong("expectedMirrorRevision")) {
                JSONObject result = pendingResult(projectId, "Pending mirror identity changed; read its status before explicit resolution");
                result.put("conflict", true); return result;
            }
            try {
                ProjectStore.Project actual = requireProject(projectId);
                if (actual.revision != expectedNative) throw new ProjectStore.RevisionConflictException(expectedNative, actual.revision);
                String nativeFingerprint = fingerprint(actual);
                JSONObject graph = metadataGraph(actual);
                JSONObject cloud = cloudGet(projectId);
                requireMutationScope(cloud);
                if (!cloud.optBoolean("enabled", false) || cloud.optLong("mirrorRevision", -1L) != expectedMirror) {
                    return conflict(projectId, "Cloud mirror revision or opt-in changed before native resolution", cloud);
                }

                JSONObject recoveredReceipt = matchingResolutionReceipt(projectId, marker, expectedNative, nativeFingerprint, graph, cloud);
                boolean recovered = recoveredReceipt != null;
                JSONObject receipt;
                if (recovered) {
                    receipt = recoveredReceipt;
                } else {
                    long cloudSource = revision(cloud, "sourceRevision", 1L);
                    if (expectedNative < cloudSource || (expectedNative == cloudSource && !cloud.optBoolean("dirty", false)
                            && !sameGraph(cloud.optJSONObject("graph"), graph))) {
                        return conflict(projectId, "Equal-source clean mirror differs from native authoring, or its source revision is newer", cloud);
                    }
                    if (expectedMirror == MAX_SAFE_INTEGER) throw new IllegalStateException("Mirror revision cannot be advanced safely");
                    receipt = new JSONObject();
                    receipt.put("resolutionId", UUID.randomUUID().toString());
                    receipt.put("projectId", projectId); receipt.put("deviceId", protocol.deviceId());
                    receipt.put("resolution", "keep_native"); receipt.put("phase", "prepared");
                    receipt.put("pendingId", marker.getString("pendingId"));
                    receipt.put("pendingFingerprint", marker.getString("fingerprint"));
                    receipt.put("pendingMirrorRevision", marker.getLong("expectedMirrorRevision"));
                    receipt.put("originalPendingMarker", new JSONObject(marker.toString()));
                    receipt.put("nativeRevision", expectedNative); receipt.put("nativeFingerprint", nativeFingerprint);
                    receipt.put("nativeGraphFingerprint", fingerprintValue(graph));
                    receipt.put("baseSourceRevision", cloudSource); receipt.put("expectedMirrorRevision", expectedMirror);
                    receipt.put("discardedMirrorGraphFingerprint", fingerprintValue(cloud.getJSONObject("graph")));
                    receipt.put("preparedAt", System.currentTimeMillis());
                    final JSONObject preparedReceipt = receipt;
                    final JSONObject preparedCloud = cloud;
                    // Preserve local evidence before cloud rebase, with a transaction across DB instances.
                    store.edit(projectId, expectedNative, "Prepare explicit native mirror resolution", current -> {
                        requireMutationScope(preparedCloud);
                        requireExactMarker(projectId, marker);
                        if (!fingerprint(current).equals(nativeFingerprint)) throw new IllegalStateException("Native graph changed before resolution evidence was saved");
                        saveResolutionReceipt(projectId, preparedReceipt);
                    });
                    JSONObject request = new JSONObject().put("projectId", projectId)
                            .put("expectedMirrorRevision", expectedMirror).put("baseSourceRevision", cloudSource)
                            .put("sourceRevision", expectedNative).put("projectGraph", graph).put("resolution", "keep_native");
                    requireLocalMutation();
                    cloud = protocol.metadataMirror("reconcile", request);
                    if (!acknowledgementMatches(cloud, graph, expectedNative, expectedMirror + 1L)) {
                        return pendingResult(projectId, cloud == null ? "Native resolution was not acknowledged" : cloud.optString("reason", "Native resolution was not acknowledged"));
                    }
                }

                receipt.put("phase", "completed"); receipt.put("resolvedMirrorRevision", cloud.getLong("mirrorRevision"));
                receipt.put("completedAt", System.currentTimeMillis());
                final JSONObject completedReceipt = receipt;
                store.edit(projectId, expectedNative, "Confirm explicit native mirror resolution", current -> {
                    requireLocalMutation();
                    requireExactMarker(projectId, marker);
                    if (!fingerprint(current).equals(nativeFingerprint)) throw new IllegalStateException("Native graph changed while resolution was in flight");
                    // This evidence commit must precede removing the original pending marker.
                    saveResolutionReceipt(projectId, completedReceipt);
                    clearPending(projectId);
                });
                JSONObject result = localStatus(projectId);
                result.put("ok", true); result.put("nativeKept", true); result.put("nativeApplied", false);
                result.put("nativeUnchanged", true); result.put("pendingResolved", true);
                result.put("resolution", "keep_native"); result.put("reconciled", true);
                result.put("acknowledgementRecovered", recovered); result.put("mirror", cloud);
                return result;
            } catch (Exception failure) {
                JSONObject result = pendingResult(projectId, failure.getMessage());
                result.put("resolution", "keep_native"); return result;
            }
        } finally { MUTATIONS.unlock(); }
    }

    private JSONObject acknowledge(String projectId, JSONObject marker) throws Exception {
        try {
            ProjectStore.Project actual = requireProject(projectId);
            long appliedRevision = marker.getLong("appliedRevision");
            if (actual.revision != appliedRevision || !fingerprint(actual).equals(marker.getString("fingerprint"))) {
                return pendingResult(projectId, "Native graph changed after apply; acknowledgement refused");
            }
            JSONObject readback = metadataGraph(actual);
            JSONObject cloud = cloudGet(projectId);
            requireMutationScope(cloud);
            long expectedMirror = marker.getLong("expectedMirrorRevision");
            boolean alreadyAcknowledged = acknowledgementMatches(cloud, readback, appliedRevision, expectedMirror + 1L);
            if (!alreadyAcknowledged) {
                if (!cloud.optBoolean("enabled", false) || !cloud.optBoolean("dirty", false)
                        || cloud.optLong("mirrorRevision", -1L) != expectedMirror
                        || cloud.optLong("sourceRevision", -1L) != marker.getLong("baseSourceRevision")
                        || !sameGraph(cloud.optJSONObject("graph"), readback)) {
                    return pendingResult(projectId, "Cloud mirror changed before acknowledgement");
                }
                JSONObject request = new JSONObject();
                request.put("projectId", projectId);
                request.put("expectedMirrorRevision", expectedMirror);
                request.put("baseSourceRevision", marker.getLong("baseSourceRevision"));
                request.put("sourceRevision", appliedRevision);
                request.put("projectGraph", readback);
                request.put("resolution", "acknowledge_mirror");
                requireLocalMutation();
                cloud = protocol.metadataMirror("reconcile", request);
                if (!acknowledgementMatches(cloud, readback, appliedRevision, expectedMirror + 1L)) {
                    return pendingResult(projectId, cloud.optString("reason", "Cloud acknowledgement was not confirmed"));
                }
            }
            // A SQLite transaction also excludes edits through another ProjectStore instance.
            store.edit(projectId, appliedRevision, "Confirm metadata acknowledgement", finalReadback -> {
                if (!fingerprint(finalReadback).equals(marker.getString("fingerprint"))) {
                    throw new IllegalStateException("Native graph changed while acknowledgement was in flight");
                }
                requireLocalMutation();
                clearPending(projectId);
            });
            JSONObject result = new JSONObject();
            result.put("ok", true); result.put("projectId", projectId);
            result.put("nativeApplied", !marker.optBoolean("noOp", false));
            result.put("nativeUnchanged", marker.optBoolean("noOp", false));
            result.put("appliedRevision", appliedRevision);
            result.put("pendingAcknowledgement", false); result.put("reconciled", true);
            result.put("acknowledgementRecovered", alreadyAcknowledged);
            result.put("mirror", cloud);
            return result;
        } catch (Exception failure) {
            return pendingResult(projectId, failure.getMessage() == null ? "Cloud acknowledgement failed" : failure.getMessage());
        }
    }

    private static boolean acknowledgementMatches(JSONObject cloud, JSONObject readback, long sourceRevision, long mirrorRevision) throws Exception {
        return cloud != null && cloud.optBoolean("ok", false) && cloud.optBoolean("enabled", false)
                && !cloud.optBoolean("dirty", true) && cloud.optLong("sourceRevision", -1L) == sourceRevision
                && cloud.optLong("mirrorRevision", -1L) == mirrorRevision && sameGraph(cloud.optJSONObject("graph"), readback);
    }

    private JSONObject cloudGet(String projectId) throws Exception {
        JSONObject request = new JSONObject(); request.put("projectId", projectId);
        JSONObject result = protocol.metadataMirror("get", request);
        if (result == null || !result.optBoolean("ok", false)) throw new IOException(result == null ? "Mirror read returned no response" : result.optString("reason", "Mirror read failed"));
        if (result.has("projectId") && !projectId.equals(result.getString("projectId"))) throw new IOException("Mirror response belongs to another project");
        return result;
    }

    private JSONObject localStatus(String projectId) throws Exception {
        JSONObject result = new JSONObject(); result.put("ok", true); result.put("projectId", projectId);
        ProjectStore.Project actual = store.get(projectId);
        result.put("nativeProjectExists", actual != null);
        if (actual != null) result.put("nativeRevision", actual.revision);
        JSONArray evidence = resolutionEvidence(projectId);
        if (evidence.length() > 0) {
            result.put("lastResolutionEvidence", evidence.getJSONObject(evidence.length() - 1));
            result.put("resolutionEvidence", evidence);
        }
        JSONObject marker = pending(projectId);
        result.put("pending", marker != null);
        result.put("pendingAcknowledgement", false); result.put("nativeApplied", false);
        if (marker != null) {
            result.put("pendingMarker", marker);
            long baseline = marker.getLong("expectedNativeRevision");
            boolean noOp = marker.optBoolean("noOp", false);
            long committedRevision = "applied".equals(marker.optString("phase"))
                    ? marker.getLong("appliedRevision") : baseline + (noOp ? 0L : 1L);
            String actualFingerprint = actual == null ? "" : fingerprint(actual);
            boolean matches = actual != null && actual.revision == committedRevision && actualFingerprint.equals(marker.getString("fingerprint"));
            boolean committed = "applied".equals(marker.optString("phase")) || matches;
            boolean canResumeApply = !committed && actual != null && actual.revision == baseline
                    && actualFingerprint.equals(marker.getString("beforeFingerprint"));
            result.put("nativeApplied", committed && !noOp);
            result.put("nativeUnchanged", noOp);
            result.put("nativeMatchesPending", matches);
            result.put("pendingAcknowledgement", committed);
            result.put("preparedApplyPending", canResumeApply);
            result.put("requiresExplicitApply", canResumeApply);
            result.put("requiresExplicitRetry", committed && matches);
            result.put("stale", !matches && !canResumeApply);
        }
        return result;
    }

    private JSONObject pendingResult(String projectId, String reason) throws Exception {
        JSONObject result = localStatus(projectId);
        result.put("ok", false); result.put("reason", reason == null ? "Mirror operation remains pending" : reason);
        return result;
    }

    private JSONObject conflict(String projectId, String reason, JSONObject cloud) throws Exception {
        JSONObject result = pendingResult(projectId, reason); result.put("conflict", true); result.put("mirror", cloud); return result;
    }

    private void requireNoPending(String projectId) throws Exception {
        if (pending(projectId) != null) throw new IllegalStateException("Project has pending mirror work; use explicit apply or retry before sync/revoke");
    }

    private void requireLocalMutation() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Metadata operation interrupted");
        if (protocol.isControlPaused() || "one_file".equals(ownerPrefs.getString("permission_mode", "everything"))) {
            throw new IllegalStateException("Owner scope does not permit metadata mirror mutations");
        }
    }

    private void requireMutationScope(JSONObject cloud) throws Exception {
        requireLocalMutation();
        JSONObject scope = cloud.optJSONObject("ownerScope");
        if (scope == null || !Boolean.TRUE.equals(scope.opt("mutationAllowed"))) {
            throw new IllegalStateException("Cloud owner scope does not permit metadata mirror mutations");
        }
    }

    private JSONObject pending(String projectId) throws Exception {
        String value = prefs.getString("pending:" + projectId, "");
        if (value == null || value.isEmpty()) return null;
        JSONObject marker = new JSONObject(value);
        if (!projectId.equals(marker.getString("projectId")) || !protocol.deviceId().equals(marker.getString("deviceId"))) {
            throw new IllegalStateException("Pending mirror journal belongs to another native identity");
        }
        long baseline = revision(marker, "expectedNativeRevision", 1L);
        long expectedMirror = revision(marker, "expectedMirrorRevision", 1L);
        if (revision(marker, "baseSourceRevision", 1L) != baseline
                || expectedMirror == MAX_SAFE_INTEGER || (!marker.optBoolean("noOp", false) && baseline == MAX_SAFE_INTEGER)
                || (!"prepared".equals(marker.optString("phase")) && !"applied".equals(marker.optString("phase")))
                || !marker.getString("fingerprint").matches("[0-9a-f]{64}")
                || !marker.getString("beforeFingerprint").matches("[0-9a-f]{64}")) {
            throw new IllegalStateException("Pending mirror journal is invalid; preserve it for conflict resolution");
        }
        if ("applied".equals(marker.getString("phase")) && revision(marker, "appliedRevision", 1L)
                != baseline + (marker.optBoolean("noOp", false) ? 0L : 1L)) throw new IllegalStateException("Pending applied revision is invalid");
        if (!marker.has("pendingId")) {
            // Early journals had no nonce. Bind a stable identity to immutable saved fields.
            JSONObject identity = new JSONObject();
            for (String key : new String[]{"projectId", "deviceId", "fingerprint", "beforeFingerprint", "baseSourceRevision", "expectedNativeRevision", "expectedMirrorRevision", "noOp", "preparedAt"}) {
                identity.put(key, marker.has(key) ? marker.get(key) : JSONObject.NULL);
            }
            marker.put("pendingId", "legacy-" + fingerprintValue(identity));
        }
        stableId(marker.getString("pendingId"));
        return marker;
    }

    private void requireExactMarker(String projectId, JSONObject expected) throws Exception {
        JSONObject current = pending(projectId);
        if (current == null || !sameGraph(current, expected)) throw new IllegalStateException("Pending mirror journal changed during explicit resolution");
    }

    private JSONArray resolutionEvidence(String projectId) throws Exception {
        String value = prefs.getString("resolution:" + projectId, "");
        if (value == null || value.isEmpty()) return new JSONArray();
        if (value.getBytes(StandardCharsets.UTF_8).length > MAX_RESOLUTION_RECEIPTS * (MAX_RECEIPT_BYTES + 1) + 2) {
            throw new IllegalStateException("Resolution evidence exceeds its durable bound");
        }
        JSONArray evidence = new JSONArray(value);
        if (evidence.length() > MAX_RESOLUTION_RECEIPTS) throw new IllegalStateException("Resolution evidence exceeds its retained receipt count");
        for (int i = 0; i < evidence.length(); i++) {
            JSONObject receipt = evidence.getJSONObject(i);
            if (!projectId.equals(receipt.getString("projectId")) || !protocol.deviceId().equals(receipt.getString("deviceId"))) {
                throw new IllegalStateException("Resolution evidence belongs to another native identity");
            }
        }
        return evidence;
    }

    private void saveResolutionReceipt(String projectId, JSONObject receipt) throws Exception {
        if (receipt.toString().getBytes(StandardCharsets.UTF_8).length > MAX_RECEIPT_BYTES) throw new IOException("Resolution receipt exceeds its bounded metadata size");
        JSONArray prior = resolutionEvidence(projectId), retained = new JSONArray();
        ArrayList<JSONObject> entries = new ArrayList<>();
        for (int i = 0; i < prior.length(); i++) {
            JSONObject previous = prior.getJSONObject(i);
            if (!receipt.getString("resolutionId").equals(previous.optString("resolutionId", ""))) entries.add(previous);
        }
        for (int i = Math.max(0, entries.size() - MAX_RESOLUTION_RECEIPTS + 1); i < entries.size(); i++) retained.put(entries.get(i));
        retained.put(new JSONObject(receipt.toString()));
        if (!prefs.edit().putString("resolution:" + projectId, retained.toString()).commit()) throw new IOException("Could not durably preserve native resolution evidence");
    }

    private JSONObject matchingResolutionReceipt(String projectId, JSONObject marker, long nativeRevision,
                                                String nativeFingerprint, JSONObject graph, JSONObject cloud) throws Exception {
        JSONArray evidence = resolutionEvidence(projectId);
        String graphFingerprint = fingerprintValue(graph);
        for (int i = evidence.length() - 1; i >= 0; i--) {
            JSONObject receipt = evidence.getJSONObject(i);
            if (!"keep_native".equals(receipt.optString("resolution"))
                    || (!"prepared".equals(receipt.optString("phase")) && !"completed".equals(receipt.optString("phase")))
                    || !marker.getString("pendingId").equals(receipt.optString("pendingId"))
                    || !marker.getString("fingerprint").equals(receipt.optString("pendingFingerprint"))
                    || marker.getLong("expectedMirrorRevision") != receipt.optLong("pendingMirrorRevision", -1L)
                    || !sameGraph(marker, receipt.optJSONObject("originalPendingMarker"))
                    || nativeRevision != receipt.optLong("nativeRevision", -1L)
                    || !nativeFingerprint.equals(receipt.optString("nativeFingerprint"))
                    || !graphFingerprint.equals(receipt.optString("nativeGraphFingerprint"))) continue;
            long expectedMirror = revision(receipt, "expectedMirrorRevision", 1L);
            revision(receipt, "baseSourceRevision", 1L);
            if (expectedMirror < MAX_SAFE_INTEGER && acknowledgementMatches(cloud, graph, nativeRevision, expectedMirror + 1L)) {
                return new JSONObject(receipt.toString());
            }
        }
        return null;
    }

    private void savePending(String projectId, JSONObject marker) throws IOException {
        if (!prefs.edit().putString("pending:" + projectId, marker.toString()).commit()) throw new IOException("Could not durably save mirror journal");
    }

    private void clearPending(String projectId) throws Exception {
        String key = "pending:" + projectId;
        String previous = prefs.getString(key, "");
        if (!prefs.edit().remove(key).commit()) {
            // commit(false) can change the in-memory preference map; restore the recovery evidence.
            prefs.edit().putString(key, previous).commit();
            throw new IOException("Cloud accepted the graph but the local journal could not be cleared durably");
        }
    }

    private ProjectStore.Project requireProject(String projectId) {
        ProjectStore.Project project = store.get(projectId);
        if (project == null) throw new IllegalArgumentException("Native project does not exist");
        return project;
    }

    static JSONObject metadataGraph(ProjectStore.Project project) throws Exception {
        if (project.assets.size() > 200 || project.tracks.isEmpty() || project.tracks.size() > 32 || project.clips.size() > 120) {
            throw new IllegalArgumentException("Mirror supports 200 assets, 32 nonempty tracks and 120 clips");
        }
        JSONObject graph = new JSONObject();
        graph.put("id", stableId(project.id)); graph.put("timelineSchema", 2);
        graph.put("name", authoringText(project.name, 180)); graph.put("sourcePrompt", authoringText(project.sourcePrompt, 2048));
        JSONArray assets = new JSONArray(), tracks = new JSONArray(), clips = new JSONArray();
        for (ProjectStore.Asset asset : project.assets) {
            JSONObject item = new JSONObject(); item.put("id", stableId(asset.id));
            item.put("name", authoringText(asset.name, 180)); item.put("mime", authoringText(asset.mime, 120));
            item.put("durationMs", safeInteger(asset.durationMs, "Asset duration", 0L));
            item.put("sizeBytes", safeInteger(Math.max(0L, asset.sizeBytes), "Asset size", 0L));
            item.put("width", safeInteger(asset.width, "Asset width", 0L)); item.put("height", safeInteger(asset.height, "Asset height", 0L));
            if (asset.rotation < -360 || asset.rotation > 360) throw new IllegalArgumentException("Asset rotation is out of bounds");
            item.put("rotation", asset.rotation); item.put("hasAudio", asset.hasAudio);
            item.put("role", authoringText(asset.role == null || asset.role.isEmpty() ? "source" : asset.role, 80));
            item.put("mediaAvailability", "metadata_only"); item.put("explicitlySynced", true); assets.put(item);
        }
        for (ProjectStore.Track track : project.tracks) {
            stableId(track.id); authoringText(track.name, 120); authoringText(track.type, 40);
            safeInteger(track.order, "Track order", 0L); tracks.put(track.toJson());
        }
        for (ProjectStore.Clip clip : project.clips) {
            stableId(clip.id); stableId(clip.assetId); stableId(clip.trackId);
            String linkGroup = clip.linkGroupId == null ? "" : clip.linkGroupId;
            if (linkGroup.length() > 128 || !linkGroup.equals(linkGroup.trim())) throw new IllegalArgumentException("Link group ID exceeds metadata bounds");
            safeInteger(clip.startMs, "Clip start", 0L); safeInteger(clip.inMs, "Clip source in", 0L);
            safeInteger(clip.outMs, "Clip source out", 0L); safeInteger(clip.programDurationMs, "Program duration", -1L);
            authoringText(clip.title, 1000); authoringText(clip.transition, 80);
            if (!Float.isFinite(clip.speed) || clip.speed < 0.1f || clip.speed > 16f
                    || !Float.isFinite(clip.volume) || clip.volume < 0f || clip.volume > 2f) {
                throw new IllegalArgumentException("Clip speed or volume is out of mirror bounds");
            }
            JSONObject item = clip.toJson();
            item.put("linkGroupId", linkGroup);
            validateNativeAudioEvidence(clip.effects);
            // Match the Worker's Float32-to-JSON-double normalization exactly.
            item.put("speed", (double) clip.speed); item.put("volume", (double) clip.volume);
            item.put("effects", publicMetadata(clip.effects == null ? new JSONObject() : clip.effects, 0)); clips.put(item);
        }
        graph.put("assets", assets); graph.put("tracks", tracks); graph.put("clips", clips);
        if (graph.toString().getBytes(StandardCharsets.UTF_8).length > MAX_GRAPH_BYTES) throw new IllegalArgumentException("Project metadata exceeds the 64 KiB mirror limit");
        return graph;
    }

    static ProjectStore.Project candidate(ProjectStore.Project nativeProject, JSONObject graph) throws Exception {
        if (!nativeProject.id.equals(graph.getString("id"))) throw new IllegalArgumentException("Mirrored graph belongs to another project");
        JSONObject baseline = metadataGraph(nativeProject);
        if (!sameGraph(baseline.getJSONArray("assets"), graph.getJSONArray("assets"))
                || !baseline.getString("name").equals(graph.getString("name"))
                || !baseline.getString("sourcePrompt").equals(graph.getString("sourcePrompt"))) {
            throw new IllegalArgumentException("Mirrored asset metadata or project identity differs from native metadata");
        }
        ProjectStore.Project next = new ProjectStore.Project();
        next.id = nativeProject.id; next.name = nativeProject.name; next.sourcePrompt = nativeProject.sourcePrompt;
        next.revision = nativeProject.revision; next.updatedAt = nativeProject.updatedAt;
        next.latestExportUri = nativeProject.latestExportUri; next.latestExportName = nativeProject.latestExportName; next.latestExportAt = nativeProject.latestExportAt;
        // Markers and rehearsal range are native authoring state. The metadata
        // mirror has no operations for them and must preserve them on apply.
        next.markers = new JSONArray(nativeProject.markers == null ? "[]" : nativeProject.markers.toString());
        next.editorRange = new JSONObject(nativeProject.editorRange == null ? "{}" : nativeProject.editorRange.toString());
        next.animationFrameRate = nativeProject.animationFrameRate;
        next.assets.addAll(nativeProject.assets);
        JSONArray tracks = graph.getJSONArray("tracks"), clips = graph.getJSONArray("clips");
        if (tracks.length() == 0 || tracks.length() > 32 || clips.length() > 120) throw new IllegalArgumentException("Mirrored timeline exceeds supported limits");
        for (int i = 0; i < tracks.length(); i++) next.tracks.add(ProjectStore.Track.fromJson(tracks.getJSONObject(i)));
        for (int i = 0; i < clips.length(); i++) {
            JSONObject raw = clips.getJSONObject(i);
            JSONObject publicEffects = raw.getJSONObject("effects");
            if (!sameGraph(publicEffects, publicMetadata(publicEffects, 0))) throw new IllegalArgumentException("Cloud effects contain private fields or locators");
            ProjectStore.Clip clip = ProjectStore.Clip.fromJson(raw);
            clip.effects = new JSONObject(publicEffects.toString());
            ProjectStore.Clip old = nativeProject.clip(clip.id);
            if (old != null && old.assetId.equals(clip.assetId)) restorePrivate(old.effects, clip.effects);
            if (clip.effects.optBoolean("animatedScene", false) && (old == null || !old.assetId.equals(clip.assetId) || !hasLayerLocator(old.effects))) {
                throw new IllegalArgumentException("Mirrored animated layers require the same native clip and local layer locators");
            }
            List<String> unsupported = new ArrayList<>(NativeVideoEffects.unsupported(clip));
            ProjectStore.Track track = next.track(clip.trackId);
            if (track != null && track.isAudio()) unsupported.addAll(NativeVideoEffects.unsupportedAudio(clip));
            if (!unsupported.isEmpty()) throw new IllegalArgumentException("Unsupported mirrored effects: " + String.join(", ", unsupported));
            JSONObject scene = clip.effects.optJSONObject("proceduralScene");
            if (scene != null) ProceduralScene.validate(scene);
            next.clips.add(clip);
        }
        protectNativeLinks(nativeProject, next);
        protectNativeAnimation(nativeProject, next);
        ProjectTimeline.normalize(next); ProjectMarkers.reconcile(next); ProjectTimeline.validate(next); protectOriginalLocks(nativeProject, next);
        if (!sameGraph(metadataGraph(next), graph)) throw new IllegalArgumentException("Mirrored graph cannot be applied without changing its public metadata");
        return next;
    }

    static void protectNativeLinks(ProjectStore.Project before, ProjectStore.Project next) throws Exception {
        for (ProjectStore.Clip old : before.clips) {
            String group = old.linkGroupId == null ? "" : old.linkGroupId;
            ProjectStore.Clip current = next.clip(old.id);
            boolean audioEvidence = old.effects != null && (old.effects.has("audioDetached") || old.effects.has("audioExtractionDetached"));
            if ((!group.isEmpty() || audioEvidence) && (current == null || !old.assetId.equals(current.assetId))) {
                throw new IllegalArgumentException("Mirror cannot remove or replace native linked or detached-audio provenance; use the native timeline operation");
            }
        }
        for (ProjectStore.Clip current : next.clips) {
            ProjectStore.Clip old = before.clip(current.id);
            String currentGroup = current.linkGroupId == null ? "" : current.linkGroupId;
            String oldGroup = old == null || old.linkGroupId == null ? "" : old.linkGroupId;
            if (!oldGroup.equals(currentGroup)) throw new IllegalArgumentException("Mirror must preserve native link membership; use native link or unlink");
            validateNativeAudioEvidence(current.effects);
            if (old != null) validateNativeAudioEvidence(old.effects);
            for (String key : new String[]{"audioDetached", "audioExtractionDetached"}) {
                boolean oldEvidence = old != null && old.effects != null && old.effects.has(key);
                boolean currentEvidence = current.effects != null && current.effects.has(key);
                if (oldEvidence != currentEvidence || (oldEvidence
                        && (!old.assetId.equals(current.assetId)
                        || !sameGraph(old.effects.get(key), current.effects.get(key))))) {
                    throw new IllegalArgumentException("Mirror must preserve native " + key + " evidence; use native extract audio or restore audio");
                }
            }
        }
    }

    private static void protectNativeAnimation(ProjectStore.Project before, ProjectStore.Project next) throws Exception {
        for (ProjectStore.Clip old : before.clips) for (String key : new String[]{"rig2d", "motionPath", "celExposure"}) {
            if (old.effects == null || !old.effects.has(key)) continue;
            ProjectStore.Clip current = next.clip(old.id);
            if (current == null || !old.assetId.equals(current.assetId) || !current.effects.has(key)
                    || !sameGraph(old.effects.get(key), current.effects.get(key)))
                throw new IllegalArgumentException("Mirror must preserve native " + key + " authoring; use the shared native animation operation");
        }
    }

    private static void validateNativeAudioEvidence(JSONObject effects) throws Exception {
        if (effects == null) return;
        for (String key : new String[]{"audioDetached", "audioExtractionDetached"}) {
            if (effects.has(key) && !(effects.get(key) instanceof Boolean)) {
                throw new IllegalArgumentException("Native detached-audio state must be boolean metadata: " + key);
            }
        }
        if (Boolean.TRUE.equals(effects.opt("audioExtractionDetached")) && !Boolean.TRUE.equals(effects.opt("audioDetached"))) {
            throw new IllegalArgumentException("Native audio extraction provenance requires detached audio");
        }
    }

    static void protectOriginalLocks(ProjectStore.Project before, ProjectStore.Project next) throws Exception {
        for (ProjectStore.Track track : before.tracks) {
            if (!track.locked) continue;
            ProjectStore.Track replacement = next.track(track.id);
            if (replacement == null || !track.name.equals(replacement.name) || !track.type.equals(replacement.type) || track.order != replacement.order) {
                throw new IllegalStateException("Originally locked track cannot be removed or restructured: " + track.id);
            }
            for (ProjectStore.Clip clip : before.clipsOnTrack(track.id)) {
                ProjectStore.Clip replacementClip = next.clip(clip.id);
                if (replacementClip == null || !sameGraph(clip.toJson(), replacementClip.toJson())) throw new IllegalStateException("Originally locked clips cannot be changed: " + clip.id);
            }
            for (ProjectStore.Clip clip : next.clipsOnTrack(track.id)) {
                ProjectStore.Clip previous = before.clip(clip.id);
                if (previous == null || !track.id.equals(previous.trackId)) throw new IllegalStateException("Cannot insert into an originally locked track: " + track.id);
            }
        }
    }

    private static boolean hasLayerLocator(JSONObject effects) {
        return effects != null && !effects.optString("foregroundUri", "").isEmpty()
                && !effects.optString("backgroundUri", "").isEmpty();
    }

    private static Object publicMetadata(Object value, int depth) throws Exception {
        if (depth > 8) throw new IllegalArgumentException("Effect metadata exceeds eight nesting levels");
        if (value == null || value == JSONObject.NULL || value instanceof Boolean) return value == null ? JSONObject.NULL : value;
        if (value instanceof String) return isLocator(value) ? "" : authoringText((String) value, 2048);
        if (value instanceof Number) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number) || (Math.rint(number) == number && Math.abs(number) > MAX_SAFE_INTEGER)) throw new IllegalArgumentException("Effect number exceeds safe precision");
            return value;
        }
        if (value instanceof JSONObject) {
            JSONObject input = (JSONObject) value, output = new JSONObject();
            if (input.length() > 128) throw new IllegalArgumentException("Effect metadata has too many fields");
            Iterator<String> keys = input.keys();
            while (keys.hasNext()) { String key = keys.next(); if (!privateKey(key)) output.put(key, publicMetadata(input.get(key), depth + 1)); }
            return output;
        }
        if (value instanceof JSONArray) {
            JSONArray input = (JSONArray) value, output = new JSONArray();
            if (input.length() > 256) throw new IllegalArgumentException("Effect metadata array exceeds 256 items");
            for (int i = 0; i < input.length(); i++) output.put(publicMetadata(input.get(i), depth + 1));
            return output;
        }
        throw new IllegalArgumentException("Unsupported effect metadata value");
    }

    private static boolean privateKey(String key) {
        return "rig2d".equals(key) || "celExposure".equals(key) || key.startsWith("_") || PRIVATE_KEYS.contains(key.toLowerCase(Locale.US))
                || PRIVATE_SUFFIX.matcher(key).find() || PRIVATE_SECRET.matcher(key).find()
                || "__proto__".equals(key) || "prototype".equals(key) || "constructor".equals(key);
    }

    private static boolean isLocator(Object value) { return value instanceof String && LOCATOR.matcher(((String) value).trim()).find(); }

    private static void restorePrivate(JSONObject original, JSONObject target) throws Exception {
        if (original == null) return;
        Iterator<String> keys = original.keys();
        while (keys.hasNext()) {
            String key = keys.next(); Object old = original.get(key), replacement = target.opt(key);
            if (privateKey(key) || isLocator(old)) { target.put(key, copyValue(old)); continue; }
            if (old instanceof JSONObject && containsPrivate(old)) {
                if (!(replacement instanceof JSONObject)) throw new IllegalArgumentException("Cannot remove an effect container holding private native fields: " + key);
                restorePrivate((JSONObject) old, (JSONObject) replacement);
            } else if (old instanceof JSONArray && containsPrivate(old)) {
                if (!(replacement instanceof JSONArray)) throw new IllegalArgumentException("Cannot remove effect items holding private native fields: " + key);
                restorePrivateArray((JSONArray) old, (JSONArray) replacement);
            }
        }
    }

    private static void restorePrivateArray(JSONArray previous, JSONArray incoming) throws Exception {
        if (previous.length() != incoming.length()) throw new IllegalArgumentException("Cannot structurally change effect items with private native fields");
        for (int i = 0; i < previous.length(); i++) {
            Object original = previous.get(i), replacement = incoming.get(i);
            if (!containsPrivate(original)) continue;
            // A public identity match binds local fields to their original array item.
            if (!sameGraph(publicMetadata(original, 0), replacement)) throw new IllegalArgumentException("Cannot reorder or replace effect items carrying private native fields");
            if (original instanceof JSONObject && replacement instanceof JSONObject) restorePrivate((JSONObject) original, (JSONObject) replacement);
            else if (original instanceof JSONArray && replacement instanceof JSONArray) restorePrivateArray((JSONArray) original, (JSONArray) replacement);
            else if (isLocator(original)) incoming.put(i, copyValue(original));
            else throw new IllegalArgumentException("Unsupported private effect item structure");
        }
    }

    private static boolean containsPrivate(Object value) throws Exception {
        if (isLocator(value)) return true;
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String key = keys.next(); if (privateKey(key) || containsPrivate(object.get(key))) return true; }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; for (int i = 0; i < array.length(); i++) if (containsPrivate(array.get(i))) return true;
        }
        return false;
    }

    private static Object copyValue(Object value) throws Exception {
        if (value instanceof JSONObject) return new JSONObject(value.toString());
        if (value instanceof JSONArray) return new JSONArray(value.toString());
        return value;
    }

    static String fingerprint(ProjectStore.Project project) throws Exception {
        JSONObject graph = project.toJson(); graph.remove("revision"); graph.remove("updatedAt");
        return fingerprintValue(graph);
    }

    private static String fingerprintValue(Object graph) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        canonical(graph, digest, null);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) result.append(String.format(Locale.US, "%02x", value & 0xff));
        return result.toString();
    }

    private static boolean sameGraph(Object a, Object b) throws Exception {
        StringBuilder first = new StringBuilder(), second = new StringBuilder();
        canonical(a, null, first); canonical(b, null, second); return first.toString().equals(second.toString());
    }

    private static void canonical(Object value, MessageDigest digest, StringBuilder output) throws Exception {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; ArrayList<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys(); while (iterator.hasNext()) keys.add(iterator.next()); Collections.sort(keys);
            emit("{", digest, output);
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) emit(",", digest, output); String key = keys.get(i);
                emit(JSONObject.quote(key) + ":", digest, output); canonical(object.get(key), digest, output);
            }
            emit("}", digest, output);
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; emit("[", digest, output);
            for (int i = 0; i < array.length(); i++) { if (i > 0) emit(",", digest, output); canonical(array.get(i), digest, output); }
            emit("]", digest, output);
        } else if (value == null || value == JSONObject.NULL) emit("null", digest, output);
        else if (value instanceof String) emit(JSONObject.quote((String) value), digest, output);
        else if (value instanceof Number) emit(((Number) value).doubleValue() == 0d ? "0" : JSONObject.numberToString((Number) value), digest, output);
        else if (value instanceof Boolean) emit(value.toString(), digest, output);
        else throw new IllegalArgumentException("Unsupported canonical graph value");
    }

    private static void emit(String value, MessageDigest digest, StringBuilder output) {
        if (digest != null) digest.update(value.getBytes(StandardCharsets.UTF_8));
        if (output != null) output.append(value);
    }

    private static String authoringText(String value, int limit) {
        String text = value == null ? "" : value;
        if (text.length() > limit) throw new IllegalArgumentException("Authoring text exceeds metadata mirror limit " + limit);
        return text;
    }

    private static String projectId(JSONObject parameters) throws Exception { return stableId(parameters.getString("projectId")); }

    private static String stableId(String value) {
        if (value == null || value.isEmpty() || value.length() > 180 || !value.equals(value.trim())) throw new IllegalArgumentException("Stable ID must contain 1..180 characters without surrounding whitespace");
        return value;
    }

    private static long revision(JSONObject value, String key, long minimum) throws Exception {
        Object number = value.get(key);
        if (!(number instanceof Number)) throw new IllegalArgumentException(key + " must be an integer");
        double decimal = ((Number) number).doubleValue(); long integer = ((Number) number).longValue();
        if (!Double.isFinite(decimal) || decimal != integer) throw new IllegalArgumentException(key + " must be an integer");
        return safeInteger(integer, key, minimum);
    }

    private static long safeInteger(long value, String name, long minimum) {
        if (value < minimum || value > MAX_SAFE_INTEGER) throw new IllegalArgumentException(name + " exceeds supported integer range");
        return value;
    }
}
