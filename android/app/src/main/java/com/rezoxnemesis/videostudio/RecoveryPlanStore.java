package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.UUID;

/**
 * Durable high-level work specifications for restart-safe VideoStudio jobs.
 *
 * JobManager persists runtime checkpoints. This store persists the action and
 * parameters required to reconstruct selected long-running jobs after Android
 * kills or restarts the process.
 */
public final class RecoveryPlanStore {
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY = "durable_recovery_plans_v1";
    private static final int MAX = 24;
    private static final long MAX_AGE_MS = 48L * 60L * 60L * 1000L;
    private static final int MAX_AUTO_ATTEMPTS = 4;

    private final SharedPreferences prefs;

    public RecoveryPlanStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized String begin(String action, JSONObject parameters, String projectId) {
        int active = 0;
        JSONArray existing = read();
        for (int i = 0; i < existing.length(); i++) {
            JSONObject item = existing.optJSONObject(i);
            if (protectedPlan(item)) active++;
        }
        if (active >= MAX) throw new IllegalStateException("Durable work queue is full; finish existing work or resume retained source transfers first");
        String id = UUID.randomUUID().toString();
        JSONObject plan = new JSONObject();
        try {
            plan.put("id", id);
            plan.put("action", clean(action, "unknown"));
            plan.put("projectId", clean(projectId, ""));
            plan.put("parameters", parameters == null ? new JSONObject() : scrubInternal(parameters));
            plan.put("state", "queued");
            plan.put("stage", "queued");
            plan.put("progress", 0);
            plan.put("detail", "Durable work plan created");
            plan.put("attempts", 0);
            plan.put("jobId", "");
            plan.put("outputUri", "");
            plan.put("outputName", "");
            plan.put("createdAt", System.currentTimeMillis());
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
        return id;
    }

    public synchronized JSONObject get(String id) {
        JSONArray arr = read();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject plan = arr.optJSONObject(i);
            if (plan != null && id != null && id.equals(plan.optString("id"))) {
                try { return new JSONObject(plan.toString()); }
                catch (Exception ignored) { return plan; }
            }
        }
        return null;
    }

    /** Exact trusted request lookup, including terminal receipts for replay. */
    public synchronized JSONObject findByRequest(String action, String key, String value) {
        if (!"_mcpCommandId".equals(key) && !"_manualExportSession".equals(key) && !"_manualSourceSession".equals(key))
            throw new IllegalArgumentException("Unsupported recovery request identity");
        if (action == null || action.isEmpty() || value == null || value.isEmpty()) return null;
        JSONArray plans = read();
        for (int index = 0; index < plans.length(); index++) {
            JSONObject plan = plans.optJSONObject(index);
            JSONObject parameters = plan == null ? null : plan.optJSONObject("parameters");
            if (plan != null && action.equals(plan.optString("action")) && parameters != null
                    && value.equals(parameters.optString(key))) return copy(plan);
        }
        return null;
    }

    /** Complete retained ledger, without auto-resume age/attempt filtering. */
    public synchronized JSONArray allPlans() {
        JSONArray source = read(), out = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject plan = source.optJSONObject(index);
            if (plan != null) out.put(copy(plan));
        }
        return out;
    }

    private static JSONObject copy(JSONObject object) {
        try { return new JSONObject(object.toString()); }
        catch (Exception error) { throw new IllegalStateException("Could not copy durable work plan", error); }
    }

    public synchronized void attachJob(String planId, String jobId) {
        JSONObject plan = get(planId);
        if (plan == null || terminal(plan)) return;
        try {
            plan.put("jobId", clean(jobId, ""));
            plan.put("state", "running");
            plan.put("attempts", plan.optInt("attempts", 0) + 1);
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void attachQueuedJob(String planId, String jobId) {
        JSONObject plan = get(planId);
        if (plan == null || terminal(plan)) return;
        String linked = plan.optString("jobId", "");
        // Work may have started between submit() and this admission checkpoint.
        if (!linked.isEmpty()) {
            if (!linked.equals(jobId)) throw new IllegalStateException("Recovery plan is already attached to another job");
            return;
        }
        try {
            plan.put("jobId", clean(jobId, ""));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception error) { throw new IllegalStateException("Could not attach queued recovery job", error); }
        upsert(plan);
    }

    public synchronized void checkpointForJob(String jobId, String stage, int progress, String detail) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null || terminal(plan)) return;
        try {
            // Progress is not output verification. Completion has its own path.
            plan.put("state", "running");
            plan.put("stage", clean(stage, "working"));
            plan.put("progress", Math.max(0, Math.min(100, progress)));
            plan.put("detail", clean(detail, ""));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized JSONObject outputForJob(String jobId) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null) return null;
        String uri = plan.optString("outputUri", "");
        if (uri.isEmpty()) return null;
        JSONObject out = new JSONObject();
        try {
            out.put("uri", uri);
            out.put("name", plan.optString("outputName", ""));
            out.put("stage", plan.optString("stage", ""));
            out.put("state", plan.optString("state", ""));
            JSONObject evidence = plan.optJSONObject("outputEvidence");
            if (evidence != null) out.put("evidence", new JSONObject(evidence.toString()));
        } catch (Exception ignored) {}
        return out;
    }

    public synchronized void markOutputForJob(String jobId, String uri, String name) {
        markOutputForJob(jobId, uri, name, null);
    }

    public synchronized void markOutputForJob(String jobId, String uri, String name, JSONObject evidence) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null || terminal(plan)) return;
        try {
            plan.put("outputUri", clean(uri, ""));
            plan.put("outputName", clean(name, ""));
            JSONObject pending = plan.optJSONObject("pendingPublication");
            if (pending != null && uri != null && uri.equals(pending.optString("uri")))
                plan.remove("pendingPublication");
            if (evidence != null) {
                String encoded = evidence.toString();
                if (encoded.length() > 65536) throw new IllegalArgumentException("Output evidence exceeds the durable receipt limit");
                plan.put("outputEvidence", new JSONObject(encoded));
            }
            plan.put("stage", "output_published");
            plan.put("progress", Math.max(98, plan.optInt("progress", 0)));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception error) { throw new IllegalStateException("Could not persist verified output receipt", error); }
        upsert(plan);
    }

    /** Fully copied pending MediaStore row, durable before public visibility. */
    public synchronized void markPendingPublicationForJob(String jobId, JSONObject receipt) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null || terminal(plan))
            throw new IllegalStateException("This export no longer owns an active durable publication plan");
        if (receipt == null || receipt.optString("uri", "").isEmpty())
            throw new IllegalArgumentException("A pending output URI receipt is required");
        try {
            String encoded = receipt.toString();
            if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
                throw new IllegalArgumentException("Pending publication receipt exceeds its 64 KiB budget");
            JSONObject existing = plan.optJSONObject("pendingPublication");
            if (existing != null && !existing.optString("uri").equals(receipt.optString("uri")))
                throw new IllegalStateException("A different pending publication already belongs to this export");
            plan.put("pendingPublication", new JSONObject(encoded));
            plan.put("stage", "publication_pending");
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception error) { throw new IllegalStateException("Could not persist pending export publication", error); }
        upsert(plan);
    }

    /** Allocation is distinct from a fully copied/verified pending output. */
    public synchronized void markAllocatedPublicationForJob(String jobId, JSONObject receipt) {
        if (receipt == null || !"allocated".equals(receipt.optString("phase"))
                || receipt.optBoolean("published", false))
            throw new IllegalArgumentException("A hidden allocated publication receipt is required");
        JSONObject plan = findByJobId(jobId);
        if (plan == null || terminal(plan))
            throw new IllegalStateException("This export no longer owns an active durable publication plan");
        JSONObject retained = plan.optJSONObject("pendingPublication");
        if (retained != null && !"allocated".equals(retained.optString("phase")))
            throw new IllegalStateException("A verified pending publication must be recovered rather than overwritten");
        markPendingPublicationForJob(jobId, receipt);
    }

    public synchronized JSONObject pendingPublicationForJob(String jobId) {
        JSONObject plan = findByJobId(jobId);
        JSONObject receipt = plan == null ? null : plan.optJSONObject("pendingPublication");
        return receipt == null ? null : copy(receipt);
    }

    /** Publication recorder: success means the exact visible output is durable. */
    public synchronized void recordPublishedForJob(String jobId, String uri, String name,
                                                  JSONObject evidence, JSONObject publication) {
        JSONObject plan = findByJobId(jobId);
        JSONObject pending = plan == null ? null : plan.optJSONObject("pendingPublication");
        JSONObject retainedPublication = plan == null ? null : plan.optJSONObject("publication");
        // Cancellation can race with MediaStore's irreversible visibility
        // update. Retain that exact committed output fact without restarting
        // cancelled work or changing the cancellation state.
        boolean exactRecordedPublication = plan != null && uri != null && publication != null
                && uri.equals(plan.optString("outputUri", "")) && retainedPublication != null
                && uri.equals(retainedPublication.optString("uri", ""))
                && retainedPublication.optBoolean("published", false)
                && jsonEquivalent(retainedPublication.opt("requestProof"), publication.opt("requestProof"), 0)
                && jsonEquivalent(retainedPublication.opt("bytes"), publication.opt("bytes"), 0)
                && jsonEquivalent(retainedPublication.opt("sha256"), publication.opt("sha256"), 0);
        boolean publishedAfterCancel = plan != null && "cancelled".equals(plan.optString("state"))
                && ((pending != null && uri != null && uri.equals(pending.optString("uri")))
                || exactRecordedPublication);
        if (plan == null || (terminal(plan) && !publishedAfterCancel))
            throw new IllegalStateException("This export no longer owns an active durable publication plan");
        if (uri == null || uri.isEmpty() || publication == null
                || !uri.equals(publication.optString("uri")) || !publication.optBoolean("published", false))
            throw new IllegalArgumentException("An exact published output receipt is required");
        String existingUri = plan.optString("outputUri", "");
        if (!existingUri.isEmpty() && !existingUri.equals(uri))
            throw new IllegalStateException("Another published output already belongs to this export");
        if (pending != null && !uri.equals(pending.optString("uri")))
            throw new IllegalStateException("Published output does not match the retained pending URI");
        try {
            JSONObject bounded = new JSONObject();
            bounded.put("publication", publication);
            if (evidence != null) bounded.put("evidence", evidence);
            if (bounded.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
                throw new IllegalArgumentException("Published output evidence exceeds its 64 KiB budget");
            plan.put("outputUri", uri); plan.put("outputName", clean(name, ""));
            plan.put("publication", copy(publication));
            if (evidence != null) plan.put("outputEvidence", copy(evidence));
            plan.remove("pendingPublication");
            plan.put("stage", publishedAfterCancel ? "output_published_after_cancel" : "output_published");
            if (publishedAfterCancel)
                plan.put("detail", "Cancellation arrived after this exact export became visible; the published output was retained");
            else plan.put("progress", Math.max(98, plan.optInt("progress", 0)));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception error) { throw new IllegalStateException("Could not record committed export publication", error); }
        upsert(plan);
    }

    /** Small committed-operation receipt, separate from transient job history. */
    public synchronized void markResultForJob(String jobId, JSONObject receipt) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null || terminal(plan)) return;
        if (receipt == null) throw new IllegalArgumentException("A committed operation receipt is required");
        try {
            String encoded = receipt.toString();
            if (encoded.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 65536)
                throw new IllegalArgumentException("Operation receipt exceeds the 64 KiB durable limit");
            plan.put("result", new JSONObject(encoded));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception error) { throw new IllegalStateException("Could not persist committed operation receipt", error); }
        upsert(plan);
    }

    public synchronized JSONObject resultForJob(String jobId) {
        JSONObject plan = findByJobId(jobId);
        JSONObject result = plan == null ? null : plan.optJSONObject("result");
        return result == null ? null : copy(result);
    }

    public synchronized void completeByJob(String jobId) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null || terminal(plan)) return;
        try {
            plan.put("state", "completed");
            plan.put("stage", "completed");
            plan.put("progress", 100);
            plan.put("detail", "Completed");
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void completePlan(String planId, String detail) {
        JSONObject plan = get(planId);
        if (plan == null || terminal(plan)) return;
        try {
            plan.put("state", "completed");
            plan.put("stage", "completed");
            plan.put("progress", 100);
            plan.put("detail", clean(detail, "Completed"));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void markResuming(String planId) {
        JSONObject plan = get(planId);
        if (plan == null || terminal(plan)) return;
        try {
            String oldJobId = plan.optString("jobId", "");
            if (!oldJobId.isEmpty()) plan.put("previousJobId", oldJobId);
            plan.put("jobId", "");
            plan.put("state", "retrying");
            plan.put("stage", "restart_recovery");
            plan.put("detail", "Resuming durable work after process restart");
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    /**
     * Explicit native-owner resumption only. A cancelled original-media transfer
     * keeps its immutable request, source pin and provider generation, but must
     * never become automatic work until the same owner request resumes it.
     * The caller must first establish that the previous worker has stopped.
     */
    public synchronized JSONObject reviveCancelledSourcePlan(String planId, String manualSourceSession) {
        JSONObject plan = get(planId);
        JSONObject parameters = plan == null ? null : plan.optJSONObject("parameters");
        if (manualSourceSession == null || manualSourceSession.isEmpty()
                || parameters == null || !ownerSourcePlan(plan)
                || !manualSourceSession.equals(parameters.optString("_manualSourceSession", "")))
            throw new IllegalArgumentException("Only the same native owner source request can resume this transfer");
        String state = plan.optString("state", "");
        if (!"cancelled".equals(state)) {
            if (resumable(state)) return copy(plan);
            throw new IllegalStateException("Only a cancelled source transfer can be explicitly resumed");
        }
        int otherProtected = 0;
        JSONArray plans = read();
        for (int index = 0; index < plans.length(); index++) {
            JSONObject candidate = plans.optJSONObject(index);
            if (candidate != null && !planId.equals(candidate.optString("id")) && protectedPlan(candidate))
                otherProtected++;
        }
        if (otherProtected >= MAX)
            throw new IllegalStateException("Durable work queue is full; finish an existing transfer first");
        try {
            String oldJobId = plan.optString("jobId", "");
            if (!oldJobId.isEmpty()) plan.put("previousJobId", oldJobId);
            plan.put("jobId", "");
            plan.put("state", "retrying");
            plan.put("stage", "owner_source_resume");
            plan.put("detail", "Owner explicitly resumed the retained original-media transfer");
            plan.put("attempts", 0);
            plan.put("ownerResumeCount", Math.min(Integer.MAX_VALUE,
                    Math.max(0L, plan.optLong("ownerResumeCount", 0)) + 1));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception error) {
            throw new IllegalStateException("Could not prepare explicit owner source resumption", error);
        }
        upsert(plan);
        return copy(plan);
    }

    /**
     * Drop only an explicitly abandoned native-owner source request. The caller
     * must have stopped its workers and successfully released the exact local
     * Vault intent/reference/pin first. Original media and committed archives
     * are outside this operation's capabilities.
     */
    public synchronized boolean forgetCancelledSourcePlan(String planId, String manualSourceSession) {
        JSONObject plan = get(planId);
        if (plan == null) return false;
        JSONObject parameters = plan.optJSONObject("parameters");
        if (!ownerSourcePlan(plan) || manualSourceSession == null || manualSourceSession.isEmpty()
                || !manualSourceSession.equals(parameters.optString("_manualSourceSession", "")))
            throw new IllegalArgumentException("Only the same native owner source request can forget this transfer");
        if (!"cancelled".equals(plan.optString("state")))
            throw new IllegalStateException("Cancel and stop the retained source transfer before forgetting it");
        JSONArray source = read(), retained = new JSONArray();
        for (int index = 0; index < source.length(); index++) {
            JSONObject item = source.optJSONObject(index);
            if (item != null && planId.equals(item.optString("id"))) continue;
            retained.put(source.opt(index));
        }
        write(retained);
        return true;
    }

    public synchronized void failByJob(String jobId, String error, boolean recoverable) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null || terminal(plan)) return;
        try {
            plan.put("state", recoverable ? "waiting_retry" : "failed");
            plan.put("detail", clean(error, recoverable ? "Recoverable failure" : "Failed"));
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized void cancelByJob(String jobId) {
        JSONObject plan = findByJobId(jobId);
        if (plan == null || terminal(plan)) return;
        try {
            plan.put("state", "cancelled");
            plan.put("detail", "Cancelled");
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    public synchronized int cancelActive() {
        return cancelActive(false);
    }

    public synchronized int cancelAutonomous() {
        return cancelActive(true);
    }

    public synchronized void cancelPlan(String planId) {
        JSONObject plan = get(planId);
        if (plan == null || terminal(plan)) return;
        try {
            plan.put("state", "cancelled");
            plan.put("detail", "Cancelled");
            plan.put("updatedAt", System.currentTimeMillis());
        } catch (Exception ignored) {}
        upsert(plan);
    }

    private int cancelActive(boolean autonomousOnly) {
        JSONArray arr = read();
        int count = 0;
        for (int i = 0; i < arr.length(); i++) {
            JSONObject plan = arr.optJSONObject(i);
            if (plan == null) continue;
            JSONObject parameters = plan.optJSONObject("parameters");
            if (autonomousOnly && parameters != null && parameters.optBoolean("_ownerInitiated", false)) continue;
            if (resumable(plan.optString("state"))) {
                try {
                    plan.put("state", "cancelled");
                    plan.put("detail", "Cancelled");
                    plan.put("updatedAt", System.currentTimeMillis());
                    count++;
                } catch (Exception ignored) {}
            }
        }
        write(arr);
        return count;
    }

    public synchronized JSONArray pendingForAutoResume() {
        JSONArray source = read();
        JSONArray out = new JSONArray();
        java.util.ArrayList<JSONObject> candidates = new java.util.ArrayList<>();
        long cutoff = System.currentTimeMillis() - MAX_AGE_MS;
        for (int i = 0; i < source.length(); i++) {
            JSONObject plan = source.optJSONObject(i);
            if (plan == null) continue;
            if (plan.optLong("updatedAt", 0) < cutoff) continue;
            if (plan.optInt("attempts", 0) >= MAX_AUTO_ATTEMPTS) continue;
            if (resumable(plan.optString("state"))) {
                try { candidates.add(new JSONObject(plan.toString())); }
                catch (Exception ignored) {}
            }
        }
        candidates.sort(java.util.Comparator.comparingInt((JSONObject plan) -> ownerPlan(plan) ? 0 : 1)
                .thenComparingLong(plan -> plan.optLong("createdAt", 0)));
        for (JSONObject plan : candidates) { if (out.length() >= 8) break; out.put(plan); }
        return out;
    }
    public synchronized boolean hasPendingOwnerWork() {
        JSONArray plans = pendingForAutoResume();
        for (int index = 0; index < plans.length(); index++) if (ownerPlan(plans.optJSONObject(index))) return true;
        return false;
    }
    private static boolean ownerPlan(JSONObject plan) {
        JSONObject parameters=plan==null?null:plan.optJSONObject("parameters");
        return parameters!=null && parameters.optBoolean("_ownerInitiated",false);
    }

    public synchronized JSONArray recent(int limit) {
        JSONArray source = read();
        JSONArray out = new JSONArray();
        int count = Math.max(1, Math.min(MAX, limit));
        for (int i = 0; i < source.length() && out.length() < count; i++) {
            JSONObject plan = source.optJSONObject(i);
            if (plan != null) out.put(plan);
        }
        return out;
    }

    private JSONObject findByJobId(String jobId) {
        if (jobId == null || jobId.isEmpty()) return null;
        JSONArray arr = read();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject plan = arr.optJSONObject(i);
            if (plan != null && jobId.equals(plan.optString("jobId"))) {
                try { return new JSONObject(plan.toString()); }
                catch (Exception ignored) { return plan; }
            }
        }
        return null;
    }

    private void upsert(JSONObject plan) {
        if (plan == null) return;
        JSONArray old = read();
        JSONArray next = new JSONArray();
        next.put(plan);
        String id = plan.optString("id");
        // Active recovery specifications and explicitly resumable cancelled
        // owner source transfers take precedence over terminal history. Dropping
        // a cancelled request would strand its source pin and upload journal.
        for (int pass = 0; pass < 2; pass++) {
            for (int i = 0; i < old.length() && next.length() < MAX; i++) {
                JSONObject item = old.optJSONObject(i);
                if (item == null || id.equals(item.optString("id"))) continue;
                if (protectedPlan(item) != (pass == 0)) continue;
                next.put(item);
            }
        }
        write(next);
    }

    private JSONArray read() {
        try { return new JSONArray(prefs.getString(KEY, "[]")); }
        catch (Exception ignored) { return new JSONArray(); }
    }

    private void write(JSONArray array) {
        if (!prefs.edit().putString(KEY, array == null ? "[]" : array.toString()).commit()) {
            throw new IllegalStateException("Could not persist durable work plan");
        }
    }

    private static JSONObject scrubInternal(JSONObject input) {
        JSONObject out = new JSONObject();
        if (input == null) return out;
        JSONArray names = input.names();
        if (names == null) return out;
        for (int i = 0; i < names.length(); i++) {
            String key = names.optString(i);
            if (key.startsWith("_") && !"_mcpCommandId".equals(key)
                    && !"_ownerInitiated".equals(key) && !"_manualExportSession".equals(key)
                    && !"_exportPinId".equals(key) && !"_exportPinnedRevision".equals(key)
                    && !"_exportPinOwner".equals(key) && !"_manualSourceSession".equals(key)
                    && !"_sourcePinId".equals(key) && !"_sourcePinOwner".equals(key)) continue;
            try { out.put(key, input.opt(key)); }
            catch (Exception ignored) {}
        }
        return out;
    }

    private static boolean terminal(JSONObject plan) {
        return plan != null && JobManager.isTerminal(plan.optString("state"));
    }

    private static boolean resumable(String state) {
        return state != null && !JobManager.isTerminal(state);
    }

    private static boolean ownerSourcePlan(JSONObject plan) {
        if (plan == null) return false;
        String action = plan.optString("action", "");
        if (!"archive_source_media".equals(action) && !"restore_source_media".equals(action)) return false;
        JSONObject parameters = plan.optJSONObject("parameters");
        return parameters != null && Boolean.TRUE.equals(parameters.opt("_ownerInitiated"))
                && !parameters.optString("_manualSourceSession", "").isEmpty();
    }

    private static boolean protectedPlan(JSONObject plan) {
        return plan != null && (resumable(plan.optString("state"))
                || ("cancelled".equals(plan.optString("state")) && ownerSourcePlan(plan)));
    }

    private static boolean jsonEquivalent(Object first, Object second, int depth) {
        if (depth > 32) return false;
        if (first == null || first == JSONObject.NULL)
            return second == null || second == JSONObject.NULL;
        if (first instanceof JSONObject && second instanceof JSONObject) {
            JSONObject left = (JSONObject) first, right = (JSONObject) second;
            if (left.length() != right.length()) return false;
            java.util.Iterator<String> keys = left.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!right.has(key) || !jsonEquivalent(left.opt(key), right.opt(key), depth + 1)) return false;
            }
            return true;
        }
        if (first instanceof JSONArray && second instanceof JSONArray) {
            JSONArray left = (JSONArray) first, right = (JSONArray) second;
            if (left.length() != right.length()) return false;
            for (int index = 0; index < left.length(); index++)
                if (!jsonEquivalent(left.opt(index), right.opt(index), depth + 1)) return false;
            return true;
        }
        if (first instanceof Number && second instanceof Number)
            return first.toString().equals(second.toString());
        return first.equals(second);
    }

    private static String clean(String value, String fallback) {
        String out = value == null ? "" : value.trim();
        if (out.isEmpty()) out = fallback;
        if (out.length() > 1000) out = out.substring(0, 1000);
        return out;
    }
}
