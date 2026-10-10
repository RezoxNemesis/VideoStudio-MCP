package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Durable per-project execution state for CreativeIR DAG nodes.
 *
 * A node is reusable only when its cacheKey still matches. Recompiling a scene
 * therefore keeps valid completed work while invalidating only changed nodes.
 * This is the foundation for targeted regeneration instead of throwing away an
 * entire long render after one local change.
 */
public final class CreativeNodeStore {
    private static final int STORE_VERSION = 1;
    private final CreativeWorkspace workspace;
    private final ProjectStore projects;

    public CreativeNodeStore(CreativeWorkspace workspace) {
        this(workspace, null);
    }
    public CreativeNodeStore(CreativeWorkspace workspace, ProjectStore projects) {
        this.workspace = workspace;
        this.projects = projects;
    }

    public synchronized JSONObject prepare(String projectId, JSONObject graph) throws Exception {
        if (projectId == null || projectId.trim().isEmpty()) {
            throw new IllegalArgumentException("projectId is required");
        }
        if (graph == null) throw new IllegalArgumentException("execution graph is required");

        JSONObject prior = read(projectId);
        Map<String, JSONObject> oldById = index(prior.optJSONArray("nodes"));
        JSONArray graphNodes = graph.optJSONArray("nodes");
        JSONArray next = new JSONArray();

        int reused = 0;
        int invalidated = 0;
        if (graphNodes != null) {
            for (int i = 0; i < graphNodes.length(); i++) {
                JSONObject planned = graphNodes.optJSONObject(i);
                if (planned == null) continue;
                String id = planned.optString("id", "");
                String cacheKey = planned.optString("cacheKey", "");
                JSONObject old = oldById.get(id);
                JSONObject state = new JSONObject();

                state.put("id", id);
                state.put("kind", planned.optString("kind", ""));
                state.put("capability", planned.optString("capability", ""));
                state.put("cacheKey", cacheKey);
                state.put("checkpointKey", planned.optString("checkpointKey", ""));
                state.put("dependencies", planned.optJSONArray("dependencies") == null
                        ? new JSONArray() : planned.optJSONArray("dependencies"));
                state.put("input", planned.optJSONObject("input") == null
                        ? new JSONObject() : new JSONObject(planned.optJSONObject("input").toString()));
                String sourceIdentity = sourceIdentity(projectId, state);
                state.put("sourceIdentity", sourceIdentity);
                state.put("quality", planned.optString("quality", "balanced"));
                state.put("providerResolved", planned.optBoolean("providerResolved", false));
                if (planned.optJSONObject("provider") != null) {
                    state.put("provider", planned.optJSONObject("provider"));
                }
                if (planned.optJSONObject("resourcePlan") != null) {
                    state.put("resourcePlan", planned.optJSONObject("resourcePlan"));
                }

                boolean sameCache = old != null && cacheKey.equals(old.optString("cacheKey", ""))
                        && (projects == null || old.has("sourceIdentity"))
                        && sourceIdentity.equals(old.optString("sourceIdentity", ""));
                boolean completed = sameCache && "completed".equals(old.optString("state", ""));
                if (completed) {
                    copyRuntime(old, state);
                    reused++;
                } else {
                    if (old != null && !sameCache) invalidated++;
                    state.put("state", "planned");
                    state.put("attempts", sameCache ? old.optInt("attempts", 0) : 0);
                    state.put("progress", 0);
                    state.put("detail", sameCache ? old.optString("detail", "Ready") : "Ready");
                    state.put("recoverable", true);
                    state.put("updatedAt", System.currentTimeMillis());
                    state.put("createdAt", old == null
                            ? System.currentTimeMillis()
                            : old.optLong("createdAt", System.currentTimeMillis()));
                }
                next.put(state);
            }
        }

        JSONObject out = new JSONObject();
        out.put("storeVersion", STORE_VERSION);
        out.put("graphVersion", graph.optInt("graphVersion", 1));
        out.put("projectId", projectId);
        out.put("graphReady", graph.optBoolean("ready", false));
        out.put("unresolvedCapabilities", graph.optJSONArray("unresolvedCapabilities") == null
                ? new JSONArray() : graph.optJSONArray("unresolvedCapabilities"));
        out.put("nodes", next);
        out.put("reusedCompletedNodes", reused);
        out.put("invalidatedNodes", invalidated);
        out.put("updatedAt", System.currentTimeMillis());
        write(projectId, out);
        return out;
    }

    public synchronized JSONObject status(String projectId) {
        try { return read(projectId); }
        catch (Exception error) {
            JSONObject out = new JSONObject();
            try {
                out.put("storeVersion", STORE_VERSION);
                out.put("projectId", projectId == null ? "" : projectId);
                out.put("nodes", new JSONArray());
                out.put("error", error.getMessage() == null ? "Could not read node state" : error.getMessage());
            } catch (Exception ignored) {}
            return out;
        }
    }

    public synchronized JSONObject node(String projectId, String nodeId) throws Exception {
        JSONObject root = read(projectId);
        return new JSONObject(requireNode(root, nodeId).toString());
    }

    public synchronized JSONObject startNode(String projectId, String nodeId) throws Exception {
        JSONObject root = read(projectId);
        JSONObject node = requireNode(root, nodeId);
        node.put("sourceIdentity", sourceIdentity(projectId, node));
        node.put("evaluationId", java.util.UUID.randomUUID().toString());
        node.remove("sourceInvalidated");
        node.put("state", "running");
        node.put("progress", Math.max(0, node.optInt("progress", 0)));
        node.put("attempts", node.optInt("attempts", 0) + 1);
        node.put("startedAt", System.currentTimeMillis());
        node.put("updatedAt", System.currentTimeMillis());
        node.put("detail", "Running");
        write(projectId, root);
        return new JSONObject(node.toString());
    }

    public synchronized void progress(String projectId,
                                      String nodeId,
                                      int progress,
                                      String detail) throws Exception {
        progress(projectId, nodeId, progress, detail, null);
    }
    public synchronized void progress(String projectId, String nodeId, int progress, String detail, String evaluationId) throws Exception {
        JSONObject root = read(projectId);
        JSONObject node = requireNode(root, nodeId);
        if (projects != null && (evaluationId == null || !evaluationId.equals(node.optString("evaluationId", ""))))
            throw new IllegalStateException("Progress belongs to an older creative evaluation");
        node.put("state", "running");
        node.put("progress", Math.max(0, Math.min(99, progress)));
        node.put("detail", detail == null ? "" : detail);
        node.put("updatedAt", System.currentTimeMillis());
        write(projectId, root);
    }

    public synchronized JSONObject complete(String projectId,
                                            String nodeId,
                                            JSONObject result) throws Exception {
        JSONObject root = read(projectId);
        JSONObject node = requireNode(root, nodeId);
        String expectedSource = node.optString("sourceIdentity", "");
        if (!expectedSource.isEmpty() && !expectedSource.equals(sourceIdentity(projectId, node)))
            throw new IllegalStateException("Source media changed while this creative node was executing; regenerate the node from the current asset");
        if (projects != null && (node.optBoolean("sourceInvalidated", false) || result == null
                || !expectedSource.equals(result.optString("evaluatedSourceIdentity", ""))
                || node.optString("evaluationId", "").isEmpty()
                || !node.getString("evaluationId").equals(result.optString("evaluationId", ""))))
            throw new IllegalStateException("Creative completion does not match the current source evaluation; regenerate the node instead of reusing stale work");
        node.put("state", "completed");
        node.put("progress", 100);
        node.put("detail", "Completed");
        node.put("recoverable", true);
        node.put("completedAt", System.currentTimeMillis());
        node.put("updatedAt", System.currentTimeMillis());
        if (result != null) node.put("result", new JSONObject(result.toString()));
        write(projectId, root);
        return new JSONObject(node.toString());
    }

    public synchronized JSONObject fail(String projectId,
                                        String nodeId,
                                        String error,
                                        boolean recoverable) throws Exception {
        return fail(projectId, nodeId, error, recoverable, null);
    }
    public synchronized JSONObject fail(String projectId, String nodeId, String error, boolean recoverable, String evaluationId) throws Exception {
        JSONObject root = read(projectId);
        JSONObject node = requireNode(root, nodeId);
        if (projects != null && (evaluationId == null || !evaluationId.equals(node.optString("evaluationId", "")))) {
            JSONObject current = new JSONObject(node.toString());
            current.put("staleFailureIgnored", true); return current;
        }
        node.put("state", recoverable ? "waiting_retry" : "failed");
        node.put("detail", error == null ? "Execution failed" : error);
        node.put("recoverable", recoverable);
        node.put("updatedAt", System.currentTimeMillis());
        write(projectId, root);
        return new JSONObject(node.toString());
    }

    public synchronized JSONObject invalidate(String projectId,
                                              String nodeId,
                                              boolean downstream) throws Exception {
        JSONObject root = read(projectId);
        JSONArray nodes = root.optJSONArray("nodes");
        if (nodes == null) nodes = new JSONArray();

        Map<String, JSONObject> map = index(nodes);
        if (!map.containsKey(nodeId)) throw new IllegalArgumentException("Creative node not found: " + nodeId);

        java.util.LinkedHashSet<String> targets = new java.util.LinkedHashSet<>();
        targets.add(nodeId);
        if (downstream) {
            boolean changed;
            do {
                changed = false;
                for (int i = 0; i < nodes.length(); i++) {
                    JSONObject node = nodes.optJSONObject(i);
                    if (node == null || targets.contains(node.optString("id"))) continue;
                    JSONArray deps = node.optJSONArray("dependencies");
                    if (deps == null) continue;
                    for (int d = 0; d < deps.length(); d++) {
                        if (targets.contains(deps.optString(d))) {
                            targets.add(node.optString("id"));
                            changed = true;
                            break;
                        }
                    }
                }
            } while (changed);
        }

        int count = 0;
        for (String id : targets) {
            JSONObject node = map.get(id);
            if (node == null) continue;
            node.remove("result");
            node.remove("completedAt");
            node.remove("startedAt");
            node.remove("evaluationId");
            node.put("state", "planned");
            node.put("progress", 0);
            node.put("detail", "Invalidated for targeted re-execution");
            node.put("recoverable", true);
            node.put("updatedAt", System.currentTimeMillis());
            count++;
        }
        root.put("updatedAt", System.currentTimeMillis());
        write(projectId, root);

        JSONObject result = new JSONObject();
        result.put("projectId", projectId);
        result.put("invalidated", count);
        result.put("downstream", downstream);
        result.put("nodeIds", new JSONArray(targets));
        return result;
    }

    public synchronized int recoverRetryable(String projectId, boolean includeRunning) throws Exception {
        JSONObject root = read(projectId);
        JSONArray nodes = root.optJSONArray("nodes");
        if (nodes == null) return 0;
        int recovered = 0;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            String state = node.optString("state", "");
            boolean retryable = "waiting_retry".equals(state)
                    || (includeRunning && ("running".equals(state)
                    || "waiting_thermal".equals(state)
                    || "waiting_memory".equals(state)));
            if (!retryable || !node.optBoolean("recoverable", true)) continue;
            node.remove("evaluationId");
            node.put("state", "planned");
            node.put("progress", 0);
            node.put("detail", includeRunning
                    ? "Recovered after process interruption"
                    : "Retry requested");
            node.put("updatedAt", System.currentTimeMillis());
            recovered++;
        }
        if (recovered > 0) {
            root.put("updatedAt", System.currentTimeMillis());
            write(projectId, root);
        }
        return recovered;
    }

    public synchronized JSONObject readyNodes(String projectId) throws Exception {
        JSONObject root = read(projectId);
        JSONArray nodes = root.optJSONArray("nodes");
        if (nodes == null) nodes = new JSONArray();
        Map<String, JSONObject> map = index(nodes);
        JSONArray ready = new JSONArray();

        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null || !"planned".equals(node.optString("state"))) continue;
            if (!node.optBoolean("providerResolved", false)) continue;

            JSONArray deps = node.optJSONArray("dependencies");
            boolean satisfied = true;
            if (deps != null) {
                for (int d = 0; d < deps.length(); d++) {
                    JSONObject dependency = map.get(deps.optString(d));
                    if (dependency == null || !"completed".equals(dependency.optString("state"))) {
                        satisfied = false;
                        break;
                    }
                }
            }
            if (satisfied) ready.put(new JSONObject(node.toString()));
        }

        JSONObject out = new JSONObject();
        out.put("projectId", projectId);
        out.put("readyCount", ready.length());
        out.put("nodes", ready);
        return out;
    }

    private JSONObject read(String projectId) throws Exception {
        File file = stateFile(projectId);
        if (!file.isFile() || file.length() == 0) {
            JSONObject empty = new JSONObject();
            empty.put("storeVersion", STORE_VERSION);
            empty.put("projectId", projectId == null ? "" : projectId);
            empty.put("nodes", new JSONArray());
            return empty;
        }
        if (file.length() > 8L * 1024L * 1024L) {
            throw new IllegalStateException("Creative node state exceeded safe metadata size");
        }
        byte[] data = new byte[(int) file.length()];
        try (FileInputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int n = in.read(data, offset, data.length - offset);
                if (n < 0) break;
                offset += n;
            }
            if (offset != data.length) throw new IllegalStateException("Creative node state read was incomplete");
        }
        JSONObject result = new JSONObject(new String(data, StandardCharsets.UTF_8));
        if (refreshSourceState(projectId, result)) write(projectId, result);
        return result;
    }

    private void write(String projectId, JSONObject value) throws Exception {
        File file = stateFile(projectId);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IllegalStateException("Could not create creative checkpoint directory");
        }
        File temp = new File(parent, file.getName() + "." + java.util.UUID.randomUUID() + ".tmp");
        try {
            byte[] bytes = value.toString(2).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > 8L * 1024L * 1024L) throw new IllegalStateException("Creative node state exceeded safe metadata size");
            try (FileOutputStream out = new FileOutputStream(temp)) {
                out.write(bytes); out.flush(); out.getFD().sync();
            }
            StorageVault.commit(temp, file);
        } finally { if (temp.exists()) temp.delete(); }
    }
    private String sourceIdentity(String projectId, JSONObject node) throws Exception {
        if (projects == null) return "";
        ProjectStore.Project project = projects.get(projectId);
        if (project == null) throw new IllegalStateException("Creative source project no longer exists");
        JSONObject input = node.optJSONObject("input");
        String assetId = input == null ? "" : input.optString("assetId", "");
        if (assetId.isEmpty() && input != null && input.optJSONObject("subject") != null)
            assetId = input.getJSONObject("subject").optString("assetId", "");
        java.util.ArrayList<String> sources = new java.util.ArrayList<>();
        for (ProjectStore.Asset asset : project.assets) {
            if (!assetId.isEmpty() ? !assetId.equals(asset.id) : asset.generated || !"source".equals(asset.role)) continue;
            long epoch = asset.importMetadata == null ? 0 : asset.importMetadata.optLong("relinkedAt", 0);
            String fileIdentity = "";
            android.net.Uri uri = android.net.Uri.parse(asset.uri == null ? "" : asset.uri);
            if ("file".equals(uri.getScheme()) && uri.getPath() != null) {
                File file = new File(uri.getPath()); fileIdentity = file.length() + ":" + file.lastModified();
            }
            sources.add(asset.id + "\n" + asset.uri + "\n" + asset.sizeBytes + "\n" + epoch
                    + "\n" + asset.mime + ":" + asset.durationMs + ":" + asset.width + ":" + asset.height
                    + ":" + asset.rotation + ":" + asset.hasAudio + "\n" + fileIdentity
                    + "\n" + (asset.importMetadata == null ? "" : asset.importMetadata.toString()));
        }
        if (!assetId.isEmpty() && sources.isEmpty()) throw new IllegalStateException("Creative source asset is no longer registered");
        java.util.Collections.sort(sources);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(String.join("\n", sources).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for (byte value : digest) hex.append(String.format(java.util.Locale.US, "%02x", value & 255));
        return hex.toString();
    }
    private boolean refreshSourceState(String projectId, JSONObject root) throws Exception {
        if (projects == null) return false;
        ProjectStore.Project project = projects.get(projectId);
        if (project == null) throw new IllegalStateException("Creative source project no longer exists");
        JSONArray nodes = root.optJSONArray("nodes"); if (nodes == null) return false;
        java.util.LinkedHashSet<String> stale = new java.util.LinkedHashSet<>();
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.optJSONObject(index); if (node == null) continue;
            String expected = node.optString("sourceIdentity", "");
            boolean changed;
            try { changed = !expected.isEmpty() && !expected.equals(sourceIdentity(projectId, node)); }
            catch (IllegalStateException unavailable) { changed = !"source-unavailable".equals(expected); }
            // A legacy result without provenance cannot establish which source
            // produced it. Never stamp today's identity onto completed output.
            if (expected.isEmpty()) changed = true;
            if (changed) stale.add(node.optString("id", ""));
        }
        boolean expanded;
        do {
            expanded = false;
            for (int index = 0; index < nodes.length(); index++) {
                JSONObject node = nodes.optJSONObject(index); if (node == null || stale.contains(node.optString("id"))) continue;
                JSONArray dependencies = node.optJSONArray("dependencies"); if (dependencies == null) continue;
                for (int d = 0; d < dependencies.length(); d++) if (stale.contains(dependencies.optString(d))) {
                    stale.add(node.optString("id")); expanded = true; break;
                }
            }
        } while (expanded);
        if (stale.isEmpty()) return false;
        for (int index = 0; index < nodes.length(); index++) {
            JSONObject node = nodes.optJSONObject(index); if (node == null || !stale.contains(node.optString("id"))) continue;
            node.remove("result"); node.remove("completedAt"); node.remove("startedAt");
            node.remove("evaluationId"); node.put("sourceInvalidated", true);
            try { node.put("sourceIdentity", sourceIdentity(projectId, node)); }
            catch (IllegalStateException unavailable) { node.put("sourceIdentity", "source-unavailable"); node.put("providerResolved", false); }
            node.put("state", "planned"); node.put("progress", 0);
            node.put("detail", "Source media changed; cached work invalidated");
            node.put("updatedAt", System.currentTimeMillis());
        }
        root.put("updatedAt", System.currentTimeMillis());
        return true;
    }

    private File stateFile(String projectId) {
        File project = workspace.projectRoot(projectId);
        return new File(new File(project, "checkpoints"), "creative_nodes_v1.json");
    }

    private static Map<String, JSONObject> index(JSONArray nodes) {
        LinkedHashMap<String, JSONObject> out = new LinkedHashMap<>();
        if (nodes == null) return out;
        for (int i = 0; i < nodes.length(); i++) {
            JSONObject node = nodes.optJSONObject(i);
            if (node == null) continue;
            String id = node.optString("id", "");
            if (!id.isEmpty()) out.put(id, node);
        }
        return out;
    }

    private static JSONObject requireNode(JSONObject root, String nodeId) {
        if (nodeId == null || nodeId.trim().isEmpty()) {
            throw new IllegalArgumentException("nodeId is required");
        }
        JSONArray nodes = root.optJSONArray("nodes");
        if (nodes != null) {
            for (int i = 0; i < nodes.length(); i++) {
                JSONObject node = nodes.optJSONObject(i);
                if (node != null && nodeId.equals(node.optString("id"))) return node;
            }
        }
        throw new IllegalArgumentException("Creative node not found: " + nodeId);
    }

    private static void copyRuntime(JSONObject from, JSONObject to) throws Exception {
        to.put("state", from.optString("state", "completed"));
        to.put("attempts", from.optInt("attempts", 0));
        to.put("progress", from.optInt("progress", 100));
        to.put("detail", from.optString("detail", "Completed from cache"));
        to.put("recoverable", from.optBoolean("recoverable", true));
        to.put("createdAt", from.optLong("createdAt", System.currentTimeMillis()));
        to.put("startedAt", from.optLong("startedAt", 0));
        to.put("completedAt", from.optLong("completedAt", 0));
        to.put("updatedAt", System.currentTimeMillis());
        if (from.optJSONObject("result") != null) {
            to.put("result", new JSONObject(from.optJSONObject("result").toString()));
        }
    }
}
