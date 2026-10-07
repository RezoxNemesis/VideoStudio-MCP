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

    public CreativeNodeStore(CreativeWorkspace workspace) {
        this.workspace = workspace;
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
                state.put("providerResolved", planned.optBoolean("providerResolved", false));
                if (planned.optJSONObject("provider") != null) {
                    state.put("provider", planned.optJSONObject("provider"));
                }
                if (planned.optJSONObject("resourcePlan") != null) {
                    state.put("resourcePlan", planned.optJSONObject("resourcePlan"));
                }

                boolean sameCache = old != null && cacheKey.equals(old.optString("cacheKey", ""));
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
        JSONObject root = read(projectId);
        JSONObject node = requireNode(root, nodeId);
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
        JSONObject root = read(projectId);
        JSONObject node = requireNode(root, nodeId);
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
        return new JSONObject(new String(data, StandardCharsets.UTF_8));
    }

    private void write(String projectId, JSONObject value) throws Exception {
        File file = stateFile(projectId);
        File parent = file.getParentFile();
        if (!parent.exists() && !parent.mkdirs() && !parent.exists()) {
            throw new IllegalStateException("Could not create creative checkpoint directory");
        }
        File temp = new File(parent, file.getName() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(value.toString(2).getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
        if (file.exists() && !file.delete()) {
            throw new IllegalStateException("Could not replace creative node state");
        }
        if (!temp.renameTo(file)) {
            throw new IllegalStateException("Could not commit creative node state");
        }
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
