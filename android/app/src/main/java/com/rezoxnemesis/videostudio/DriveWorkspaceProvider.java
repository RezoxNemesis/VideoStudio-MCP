package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.TreeMap;
import java.util.Iterator;

/**
 * Folder-scoped cloud/archive storage using Android's Storage Access Framework.
 *
 * This provider intentionally avoids broad Google Drive OAuth scopes. The user
 * selects one writable document-tree folder in Android's system picker. If the
 * Google Drive DocumentsProvider is available, that folder may live in Drive.
 *
 * VideoStudio then receives only a persisted capability URI for that selected
 * tree. It cannot enumerate unrelated Drive content through this class.
 */
public final class DriveWorkspaceProvider {
    public interface Progress {
        void onProgress(int progress, String detail) throws Exception;
    }

    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_TREE = "drive_workspace_tree_uri";
    private static final String KEY_LINKED_AT = "drive_workspace_linked_at";
    private static final int BUFFER = 256 * 1024;
    private static final int MAX_REMOTE_ENTRIES = 20000;
    private static final int MAX_REMOTE_DEPTH = 32;
    private static final int MAX_MARKER_BYTES = 4 * 1024 * 1024;
    private static final int MAX_GRAPH_BYTES = 16 * 1024 * 1024;
    private static final String WORKSPACE_FORMAT = "videostudio-workspace-archive";
    private static final String MODEL_FORMAT = "videostudio-model-pack-archive";
    private static final String EMPTY_HASH = "0000000000000000000000000000000000000000000000000000000000000000";

    private final Context context;
    private final ContentResolver resolver;
    private final SharedPreferences prefs;

    public DriveWorkspaceProvider(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        this.prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public synchronized void link(Uri treeUri) {
        if (treeUri == null) throw new IllegalArgumentException("Workspace folder is required");
        String authority = treeUri.getAuthority();
        if (authority == null || authority.trim().isEmpty()) {
            throw new IllegalArgumentException("Workspace folder provider is invalid");
        }
        StorageProfiles profiles = new StorageProfiles(context);
        int slot = Math.max(0, prefs.getInt("storage_profiles_active_slot", 0));
        profiles.link(slot, treeUri, "Storage " + (slot + 1), "archive");
        profiles.select(slot);
    }

    public synchronized void unlink() {
        int slot = prefs.getInt("storage_profiles_active_slot", -1);
        if (slot >= 0) new StorageProfiles(context).unlink(slot);
        else prefs.edit().remove(KEY_TREE).remove(KEY_LINKED_AT).apply();
    }

    public synchronized boolean isLinked() {
        Uri tree = treeUri();
        return tree != null && canReadRoot(tree);
    }

    public synchronized JSONObject status() { return statusFor(treeUri()); }

    private JSONObject statusFor(Uri tree) {
        JSONObject out = new JSONObject();
        try {
            out.put("linked", tree != null && canReadRoot(tree));
            out.put("scope", "single-user-selected-document-tree");
            out.put("broadDrivePermission", false); out.put("galleryAccess", false);
            out.put("providerAuthority", tree == null ? "" : String.valueOf(tree.getAuthority()));
            out.put("quotaStatus", "unknown"); out.put("availableBytes", JSONObject.NULL);
            out.put("archiveTreeUri", tree == null ? "" : tree.toString());
            if (tree != null) {
                out.put("displayName", displayName(rootDocumentUri(tree)));
                out.put("persistedRead", hasPersistedPermission(tree, false));
                out.put("persistedReadWrite", hasPersistedPermission(tree, true));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public String captureTreeUri() { return requireTree(false).toString(); }
    public void validateArchiveTree(String treeUri) { requireTree(treeUri, false); }
    public void validateArchiveTree(String treeUri, boolean requireWrite) { requireTree(treeUri, requireWrite); }

    public JSONObject syncProject(ProjectStore.Project project, File workspace, Progress progress) throws Exception {
        return syncProject(project, workspace, progress, null);
    }

    public JSONObject syncProject(ProjectStore.Project project, File workspace, Progress progress, String selectedTree) throws Exception {
        if (project == null) throw new IllegalArgumentException("Project is required");
        ProjectStore.Project snapshot = ProjectStore.copy(project);
        Uri tree = requireTree(selectedTree, true);
        Progress report = progress == null ? (p, d) -> {} : progress;
        List<File> files = localFiles(workspace, true);
        byte[] graph = snapshot.toJson().toString(2).getBytes(StandardCharsets.UTF_8);
        if (graph.length > MAX_GRAPH_BYTES) throw new IllegalArgumentException("Project graph exceeds archive metadata limit");
        JSONObject commit = new JSONObject();
        commit.put("format", WORKSPACE_FORMAT); commit.put("version", 1); commit.put("projectId", snapshot.id);
        commit.put("revision", snapshot.revision); commit.put("committedAt", System.currentTimeMillis());
        commit.put("projectSha256", hashBytes(graph));
        preflight(workspace, files, commit, true);
        report.onProgress(2, "Opening folder-scoped cloud workspace");
        Uri projects = ensureDirectory(tree, rootDocumentUri(tree), "Projects");
        Uri projectRoot = ensureDirectory(tree, projects, safeName(snapshot.id));
        Uri revisions = ensureDirectory(tree, projectRoot, "Revisions");
        Uri generation = createGeneration(tree, revisions, "revision-" + snapshot.revision + "-" + java.util.UUID.randomUUID());
        JSONObject uploaded = archiveFiles(tree, generation, workspace, files, report);
        commit.put("files", uploaded);
        writeBytes(tree, generation, "project.json", "application/json", graph);
        Uri graphUri = requireChild(tree, generation, "project.json", false);
        if (!commit.getString("projectSha256").equals(streamHash(requireInput(graphUri))))
            throw new IllegalStateException("Archived project graph readback checksum failed");
        materializedFiles(tree, generation, declaredFiles(commit, true), true);
        publishCommit(tree, generation, commit);
        readCommitted(tree, generation, WORKSPACE_FORMAT, "projectId", snapshot.id, true);
        JSONObject result = archiveResult(tree, generation, files.size() + 1, uploaded, "projectId", snapshot.id);
        result.put("revision", snapshot.revision);
        report.onProgress(100, "Verified project archive committed to selected folder");
        return result;
    }

    public JSONObject inventory() throws Exception {
        Uri tree = requireTree(false);
        Uri root = rootDocumentUri(tree);
        JSONArray areas = new JSONArray();
        areas.put(inventoryArea(tree, root, "Projects")); areas.put(inventoryArea(tree, root, "ModelPacks"));
        long bytes = 0L; int files = 0, directories = 0;
        for (int i = 0; i < areas.length(); i++) {
            JSONObject area = areas.optJSONObject(i);
            if (area == null) continue;
            bytes = StorageBudget.saturatingAdd(bytes, area.optLong("bytes", 0L));
            files += area.optInt("files", 0); directories += area.optInt("directories", 0);
        }
        JSONObject out = statusFor(tree); // Use the capability captured for this scan.
        out.put("areas", areas); out.put("workspaceBytesVisible", bytes);
        out.put("workspaceFilesVisible", files); out.put("workspaceDirectoriesVisible", directories);
        out.put("scanLimit", MAX_REMOTE_ENTRIES); out.put("storageRole", "cold-and-warm-project-model-archive");
        return out;
    }

    public JSONObject restoreProjectWorkspace(String id, File workspace, Progress report) throws Exception {
        return restoreProjectWorkspace(id, workspace, false, report);
    }

    public JSONObject restoreProjectWorkspace(String id, File workspace, boolean preserve, Progress report) throws Exception {
        return restoreProjectWorkspace(id, workspace, preserve, report, null, null);
    }

    public JSONObject restoreProjectWorkspace(String id, File workspace, boolean preserve, Progress report, String selectedTree) throws Exception {
        return restoreProjectWorkspace(id, workspace, preserve, report, selectedTree, null);
    }

    /** Exact archive provenance is used for offloaded-media recovery, independent of active profile. */
    public JSONObject restoreProjectWorkspace(String id, File workspace, boolean preserve, Progress report,
                                               String selectedTree, String archiveDirectory) throws Exception {
        requireId(id, "projectId");
        if (workspace == null) throw new IllegalArgumentException("Project workspace destination is required");
        Uri tree = requireTree(selectedTree, false);
        Uri generation;
        JSONObject commit;
        if (archiveDirectory != null && !archiveDirectory.trim().isEmpty()) {
            if (selectedTree == null || selectedTree.trim().isEmpty()) throw new IllegalArgumentException("Pinned archive requires its owner-selected tree");
            generation = pinnedDirectory(tree, archiveDirectory);
            commit = readCommitted(tree, generation, WORKSPACE_FORMAT, "projectId", id, true);
        } else {
            Uri projects = requireChild(tree, rootDocumentUri(tree), "Projects", true);
            Uri projectRoot = requireChild(tree, projects, safeName(id), true);
            generation = latestCommittedArchive(tree, projectRoot, WORKSPACE_FORMAT, "projectId", id, true);
            commit = findChild(tree, generation, "COMMITTED.json", false) == null ? null
                    : readCommitted(tree, generation, WORKSPACE_FORMAT, "projectId", id, true);
        }
        JSONObject result = restoreFiles(tree, generation, workspace, preserve, report, commit, true);
        result.put("projectId", id);
        return result;
    }

    public JSONObject syncModelPack(String id, File directory, Progress report) throws Exception {
        return syncModelPack(id, directory, report, null);
    }

    public JSONObject syncModelPack(String id, File directory, Progress progress, String selectedTree) throws Exception {
        requireId(id, "Model pack id");
        if (directory == null || !directory.isDirectory()) throw new IllegalArgumentException("Installed model pack directory is missing");
        Uri tree = requireTree(selectedTree, true);
        Progress report = progress == null ? (p, d) -> {} : progress;
        List<File> files = localFiles(directory, false);
        if (files.isEmpty()) throw new IllegalArgumentException("Model pack has no readable files");
        JSONObject commit = new JSONObject();
        commit.put("format", MODEL_FORMAT); commit.put("version", 1); commit.put("packId", id);
        commit.put("committedAt", System.currentTimeMillis());
        preflight(directory, files, commit, false);
        Uri modelPacks = ensureDirectory(tree, rootDocumentUri(tree), "ModelPacks");
        Uri packRoot = ensureDirectory(tree, modelPacks, safeName(id));
        Uri revisions = ensureDirectory(tree, packRoot, "Revisions");
        Uri generation = createGeneration(tree, revisions, "revision-" + java.util.UUID.randomUUID());
        JSONObject uploaded = archiveFiles(tree, generation, directory, files, report);
        commit.put("files", uploaded);
        materializedFiles(tree, generation, declaredFiles(commit, false), false);
        publishCommit(tree, generation, commit);
        readCommitted(tree, generation, MODEL_FORMAT, "packId", id, false);
        JSONObject result = archiveResult(tree, generation, files.size(), uploaded, "id", id);
        report.onProgress(100, "Verified model-pack archive committed to selected folder");
        return result;
    }

    public JSONObject restoreModelPack(String id, File destination, Progress report) throws Exception {
        return restoreModelPack(id, destination, report, null);
    }

    public JSONObject restoreModelPack(String id, File destination, Progress report, String selectedTree) throws Exception {
        requireId(id, "Model pack id");
        if (destination == null) throw new IllegalArgumentException("Model pack restore destination is required");
        Uri tree = requireTree(selectedTree, false);
        Uri modelPacks = requireChild(tree, rootDocumentUri(tree), "ModelPacks", true);
        Uri packRoot = requireChild(tree, modelPacks, safeName(id), true);
        Uri generation = latestCommittedArchive(tree, packRoot, MODEL_FORMAT, "packId", id, false);
        JSONObject commit = findChild(tree, generation, "COMMITTED.json", false) == null ? null
                : readCommitted(tree, generation, MODEL_FORMAT, "packId", id, false);
        JSONObject result = restoreFiles(tree, generation, destination, false, report, commit, false);
        result.put("id", id); result.put("path", destination.getAbsolutePath());
        return result;
    }

    private JSONObject archiveFiles(Uri tree, Uri generation, File root, List<File> files, Progress progress) throws Exception {
        JSONObject records = new JSONObject();
        long total = 0L, copied = 0L;
        for (File file : files) total = StorageBudget.saturatingAdd(total, file.length());
        int count = 0;
        for (File file : files) {
            interrupted();
            String relative = relativePath(root, file);
            Uri parent = ensureRelativeDirectories(tree, generation, parentPath(relative));
            long expected = file.length();
            JSONObject record;
            if (expected >= StorageVault.CHUNK_BYTES) {
                final long before = copied, all = total;
                JSONObject vault = StorageVault.archive(file, vaultStore(tree, generation, true),
                        (done, size) -> progress.onProgress(4 + (int) Math.min(90, 90d * (before + done) / Math.max(1L, all)), "Archiving verified Vault chunks"));
                byte[] bytes = vault.toString().getBytes(StandardCharsets.UTF_8);
                if (bytes.length > MAX_MARKER_BYTES) throw new IllegalArgumentException("Vault manifest exceeds archive metadata limit");
                String name = leafName(relative) + ".vsvault.json";
                writeBytes(tree, parent, name, "application/json", bytes);
                if (!hashBytes(bytes).equals(streamHash(requireInput(requireChild(tree, parent, name, false)))))
                    throw new IllegalStateException("Vault manifest readback checksum failed");
                record = new JSONObject().put("size", vault.getLong("size")).put("sha256", vault.getString("sha256")).put("vault", true);
            } else {
                record = copyVerifiedFile(tree, parent, leafName(relative), mimeFor(relative), file);
                record.put("vault", false);
            }
            records.put(relative, record);
            copied = StorageBudget.saturatingAdd(copied, record.getLong("size")); count++;
            progress.onProgress(4 + (int) Math.min(90, 90d * copied / Math.max(1L, total)), "Archived " + count + " verified file(s)");
        }
        return records;
    }

    private void preflight(File root, List<File> files, JSONObject commit, boolean project) throws Exception {
        JSONObject placeholders = new JSONObject(); Set<String> entries = new HashSet<>(); long chunks = 0L;
        entries.add("COMMITTED.json"); if (project) entries.add("project.json");
        for (File file : files) {
            String relative = relativePath(root, file); validateDataPath(relative, project);
            long size = file.length();
            if (size < 0L || (size > 0L && (size - 1L) / StorageVault.CHUNK_BYTES >= 4096L))
                throw new IllegalArgumentException("File exceeds Vault archive limits");
            if (placeholders.has(relative)) throw new IllegalArgumentException("Duplicate local archive path");
            placeholders.put(relative, new JSONObject().put("size", size).put("sha256", EMPTY_HASH).put("vault", size >= StorageVault.CHUNK_BYTES));
            if (size >= StorageVault.CHUNK_BYTES) chunks += 1L + (size - 1L) / StorageVault.CHUNK_BYTES;
            if (chunks > MAX_REMOTE_ENTRIES) throw new IllegalArgumentException("Archive exceeds total chunk lookup limit");
            if (chunks > 0L) entries.add("VaultChunks");
            addEntryPaths(entries, relative + (size >= StorageVault.CHUNK_BYTES ? ".vsvault.json" : ""));
        }
        commit.put("files", placeholders);
        if (entries.size() > MAX_REMOTE_ENTRIES) throw new IllegalArgumentException("Archive exceeds restore entry limit");
        if (commit.toString().getBytes(StandardCharsets.UTF_8).length > MAX_MARKER_BYTES)
            throw new IllegalArgumentException("Archive manifest exceeds restore metadata limit");
    }

    private void publishCommit(Uri tree, Uri generation, JSONObject commit) throws Exception {
        byte[] bytes = commit.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_MARKER_BYTES) throw new IllegalArgumentException("Archive manifest exceeds restore metadata limit");
        writeBytes(tree, generation, "COMMITTED.json", "application/json", bytes);
        if (!hashBytes(bytes).equals(streamHash(requireInput(requireChild(tree, generation, "COMMITTED.json", false)))))
            throw new IllegalStateException("Archive commit marker readback checksum failed");
    }

    private JSONObject archiveResult(Uri tree, Uri generation, int count, JSONObject records, String idKey, String id) throws Exception {
        long size = 0L; Iterator<String> keys = records.keys();
        while (keys.hasNext()) size = StorageBudget.saturatingAdd(size, records.getJSONObject(keys.next()).getLong("size"));
        return new JSONObject().put("ok", true).put(idKey, id).put("filesWritten", count).put("bytesWritten", size)
                .put("archivedFiles", new JSONObject(records.toString()))
                .put("archiveTreeUri", tree.toString()).put("archiveDirectoryUri", generation.toString())
                .put("scope", "single-user-selected-document-tree").put("providerAuthority", tree.getAuthority())
                .put("broadDrivePermission", false).put("galleryAccess", false);
    }

    private JSONObject restoreFiles(Uri tree, Uri generation, File root, boolean preserve, Progress progress,
                                    JSONObject commit, boolean project) throws Exception {
        Progress report = progress == null ? (p, d) -> {} : progress;
        Map<String, JSONObject> declared = commit == null ? null : declaredFiles(commit, project);
        Map<String, RemoteEntry> materialized = materializedFiles(tree, generation, declared, project);
        if (!project && materialized.isEmpty()) throw new IllegalStateException("Model archive contains no restorable files");
        long copied = 0L, total = 0L; int count = 0;
        for (String path : materialized.keySet()) {
            JSONObject record = declared == null ? null : declared.get(path);
            total = StorageBudget.saturatingAdd(total, Math.max(0L, record == null ? materialized.get(path).size : record.getLong("size")));
        }
        report.onProgress(2, "Restoring complete verified archive into local storage");
        for (Map.Entry<String, RemoteEntry> item : materialized.entrySet()) {
            interrupted(); String relative = item.getKey(); RemoteEntry entry = item.getValue();
            if (preserve && project && (relative.startsWith("checkpoints/") || relative.startsWith("scenes/") || "cloud_offload.json".equals(relative))) continue;
            File target = safeTarget(root, relative); File parent = target.getParentFile();
            if (!parent.exists() && !parent.mkdirs() && !parent.exists()) throw new IllegalStateException("Could not prepare restore directory");
            JSONObject record = declared == null ? null : declared.get(relative);
            long restored;
            if (entry.relative.endsWith(".vsvault.json")) {
                JSONObject vault = readVaultManifest(entry.uri);
                if (record != null && (record.getLong("size") != vault.optLong("size", -1L) || !record.getString("sha256").equals(vault.optString("sha256"))))
                    throw new IllegalStateException("Vault manifest differs from committed archive");
                restored = StorageVault.restore(vault, vaultStore(tree, generation, false), target,
                        (done, expected) -> report.onProgress(4, "Verifying and restoring Vault chunks"));
            } else {
                long expected = record == null ? entry.size : record.getLong("size");
                if (expected >= 0L && !StorageBudget.check(parent.getUsableSpace(), expected, StorageBudget.DEFAULT_TRANSFER_RESERVE_BYTES).allowed)
                    throw new IllegalStateException("Not enough storage to restore archived media safely");
                File temp = new File(parent, target.getName() + "." + java.util.UUID.randomUUID() + ".cloudtmp");
                try {
                    try (InputStream input = new BufferedInputStream(requireInput(entry.uri), BUFFER)) { copyToFile(input, temp, expected); }
                    if (record != null && !record.getString("sha256").equals(fileHash(temp))) throw new IllegalStateException("Archived file checksum mismatch");
                    restored = temp.length(); StorageVault.commit(temp, target);
                } finally { if (temp.exists()) temp.delete(); }
            }
            copied = StorageBudget.saturatingAdd(copied, restored); count++;
            report.onProgress(4 + (int) Math.min(94, 94d * copied / Math.max(1L, total)), "Restored " + count + " verified file(s)");
        }
        JSONObject result = new JSONObject().put("ok", true).put("filesRestored", count).put("bytesRestored", copied)
                .put("archiveTreeUri", tree.toString()).put("archiveDirectoryUri", generation.toString())
                .put("scope", "single-user-selected-document-tree").put("preservedLocalControlMetadata", preserve).put("galleryAccess", false);
        result.put("integrity", commit == null ? "legacy-size-and-vault-checks" : "sha256-committed");
        report.onProgress(100, commit == null ? "Legacy archive restored" : "Verified archive restore completed");
        return result;
    }

    private Map<String, RemoteEntry> materializedFiles(Uri tree, Uri generation, Map<String, JSONObject> declared, boolean project) throws Exception {
        List<RemoteEntry> listing = new ArrayList<>();
        collectRemoteFiles(tree, generation, "", listing, 0);
        Set<String> vaulted = new HashSet<>();
        for (RemoteEntry entry : listing) if (entry.relative.endsWith(".vsvault.json"))
            vaulted.add(entry.relative.substring(0, entry.relative.length() - ".vsvault.json".length()));
        Map<String, RemoteEntry> materialized = new TreeMap<>();
        for (RemoteEntry entry : listing) {
            if ("COMMITTED.json".equals(entry.relative) || (project && "project.json".equals(entry.relative))) continue;
            if (entry.relative.endsWith(".partial") || entry.relative.endsWith(".backup") || entry.relative.endsWith(".cloudtmp")) continue;
            boolean vault = entry.relative.endsWith(".vsvault.json");
            if (declared == null && !vault && vaulted.contains(entry.relative)) continue; // Prior archive layout.
            String relative = vault ? entry.relative.substring(0, entry.relative.length() - ".vsvault.json".length()) : entry.relative;
            validateDataPath(relative, project);
            if (materialized.put(relative, entry) != null) throw new IllegalStateException("Duplicate archive path: " + relative);
            if (declared != null) {
                JSONObject expected = declared.get(relative);
                if (expected == null || expected.getBoolean("vault") != vault) throw new IllegalStateException("Unlisted or mismatched archive file: " + relative);
            }
        }
        if (declared != null && !materialized.keySet().equals(declared.keySet()))
            throw new IllegalStateException("Committed archive is missing declared files; local media was preserved");
        // Plan the complete Vault restore before replacing the first local file.
        // Metadata checks catch missing chunks without downloading all generations.
        if (!vaulted.isEmpty()) validateVaultFiles(tree, generation, materialized, declared);
        if (declared != null) for (Map.Entry<String, RemoteEntry> entry : materialized.entrySet()) {
            if (!entry.getValue().relative.endsWith(".vsvault.json") && entry.getValue().size >= 0L
                    && entry.getValue().size != declared.get(entry.getKey()).getLong("size"))
                throw new IllegalStateException("Committed archive file size differs from provider listing");
        }
        return materialized;
    }

    private void validateVaultFiles(Uri tree, Uri generation, Map<String, RemoteEntry> files,
                                    Map<String, JSONObject> declared) throws Exception {
        Uri directory = requireChild(tree, generation, "VaultChunks", true);
        Map<String, Long> chunks = new TreeMap<>();
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(directory));
        try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Vault chunk index is unavailable");
            int count = 0;
            while (cursor.moveToNext()) {
                interrupted();
                if (++count > MAX_REMOTE_ENTRIES) throw new IllegalStateException("Vault archive exceeds chunk lookup limit");
                String name = cursor.getString(0);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1))
                        || name == null || !name.matches("[a-f0-9-]{36}\\.[0-9]{1,4}\\.chunk"))
                    throw new IllegalArgumentException("Invalid Vault chunk index entry");
                long size = cursor.isNull(2) ? -1L : cursor.getLong(2);
                if (chunks.put(name, size) != null) throw new IllegalStateException("Duplicate provider Vault chunk name");
            }
        }
        Set<String> usedChunks = new HashSet<>();
        for (Map.Entry<String, RemoteEntry> file : files.entrySet()) {
            if (!file.getValue().relative.endsWith(".vsvault.json")) continue;
            JSONObject vault = readVaultManifest(file.getValue().uri);
            JSONArray entries = vault.optJSONArray("chunks");
            long expected = vault.optLong("size", -1L), offset = 0L;
            if (!"videostudio-vault".equals(vault.optString("format")) || vault.optInt("version") != 1
                    || expected <= 0L || entries == null || entries.length() == 0 || entries.length() > 4096)
                throw new IllegalArgumentException("Invalid Vault archive manifest");
            requireHash(vault.getString("sha256"));
            JSONObject record = declared == null ? null : declared.get(file.getKey());
            if (record != null && (expected != record.getLong("size") || !vault.getString("sha256").equals(record.getString("sha256"))))
                throw new IllegalStateException("Vault manifest differs from committed archive");
            for (int index = 0; index < entries.length(); index++) {
                JSONObject entry = entries.getJSONObject(index);
                String name = entry.getString("name"); long size = entry.getLong("size");
                requireHash(entry.getString("sha256"));
                if (!chunks.containsKey(name) || !usedChunks.add(name) || entry.getLong("offset") != offset
                        || size <= 0L || size > StorageVault.CHUNK_BYTES || size > expected - offset
                        || (chunks.get(name) >= 0L && chunks.get(name) != size))
                    throw new IllegalStateException("Vault archive has missing or invalid chunk metadata");
                offset += size;
            }
            if (offset != expected) throw new IllegalStateException("Vault archive is incomplete");
        }
    }

    private JSONObject inventoryArea(Uri tree, Uri root, String name) throws Exception {
        JSONObject out = new JSONObject();
        out.put("name", name);
        Uri area = findChild(tree, root, name, true);
        if (area == null) {
            out.put("present", false);
            out.put("files", 0);
            out.put("directories", 0);
            out.put("bytes", 0);
            return out;
        }
        RemoteStats stats = scanRemoteStats(tree, area, 0, new int[]{0});
        out.put("present", true);
        out.put("files", stats.files);
        out.put("directories", stats.directories);
        out.put("bytes", stats.bytes);
        out.put("truncated", stats.truncated);
        return out;
    }

    private RemoteStats scanRemoteStats(Uri tree, Uri directory, int depth, int[] visited) throws Exception {
        interrupted();
        RemoteStats stats = new RemoteStats();
        if (depth > MAX_REMOTE_DEPTH) {
            stats.truncated = true;
            return stats;
        }
        String parentId = DocumentsContract.getDocumentId(directory);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Archive inventory is unavailable");
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
                interrupted();
                if (++visited[0] > MAX_REMOTE_ENTRIES) {
                    stats.truncated = true;
                    break;
                }
                String id = cursor.getString(idIndex);
                String mime = mimeIndex >= 0 ? cursor.getString(mimeIndex) : "";
                Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    stats.directories++;
                    RemoteStats nested = scanRemoteStats(tree, child, depth + 1, visited);
                    stats.add(nested);
                    if (nested.truncated) stats.truncated = true;
                } else {
                    stats.files++;
                    stats.bytes = StorageBudget.saturatingAdd(stats.bytes, sizeIndex >= 0 && !cursor.isNull(sizeIndex)
                            ? Math.max(0, cursor.getLong(sizeIndex)) : 0);
                }
            }
        }
        return stats;
    }

    private void collectRemoteFiles(Uri tree, Uri directory, String prefix, List<RemoteEntry> out, int depth) throws Exception {
        collectRemoteFiles(tree, directory, prefix, out, depth, new int[]{0}, new HashSet<>(), new HashSet<>());
    }

    private void collectRemoteFiles(Uri tree, Uri directory, String prefix, List<RemoteEntry> out, int depth,
                                    int[] visited, Set<String> paths, Set<String> directoryIds) throws Exception {
        interrupted();
        if (depth > MAX_REMOTE_DEPTH) throw new IllegalStateException("Cloud archive nesting is too deep");
        String parentId = DocumentsContract.getDocumentId(directory);
        if (!directoryIds.add(parentId)) throw new IllegalStateException("Cloud archive contains a directory cycle or alias");
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE}, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Cloud archive listing is unavailable");
            int idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
                interrupted(); String name = cursor.getString(nameIndex); requireComponent(name);
                if (prefix.isEmpty() && ("VaultChunks".equals(name) || "Revisions".equals(name))) continue;
                if (name.endsWith(".partial") || name.endsWith(".backup") || name.endsWith(".cloudtmp")) continue;
                if (++visited[0] > MAX_REMOTE_ENTRIES) throw new IllegalStateException("Cloud archive contains too many entries");
                String relative = prefix.isEmpty() ? name : prefix + "/" + name;
                if (!paths.add(relative)) throw new IllegalStateException("Duplicate provider archive path: " + relative);
                Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idIndex));
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(mimeIndex)))
                    collectRemoteFiles(tree, child, relative, out, depth + 1, visited, paths, directoryIds);
                else {
                    long size = sizeIndex < 0 || cursor.isNull(sizeIndex) ? -1L : cursor.getLong(sizeIndex);
                    out.add(new RemoteEntry(child, relative, size));
                }
            }
        }
    }

    private List<File> localFiles(File root, boolean project) throws Exception {
        ArrayList<File> files = new ArrayList<>();
        if (root == null || !root.exists()) return files;
        if (!root.isDirectory()) throw new IllegalArgumentException("Archive source must be a directory");
        collectLocalFiles(root, root, files, project, 0, new HashSet<>(), new int[]{0});
        return files;
    }

    private void collectLocalFiles(File root, File directory, List<File> files, boolean project,
                                   int depth, Set<String> directories, int[] visited) throws Exception {
        interrupted();
        if (depth > MAX_REMOTE_DEPTH) throw new IllegalArgumentException("Local archive exceeds restore nesting limit");
        String canonical = directory.getCanonicalPath();
        if (!directories.add(canonical)) throw new IllegalArgumentException("Archive contains a directory cycle or alias");
        File[] children = directory.listFiles();
        if (children == null) throw new IllegalStateException("Could not enumerate all local archive files");
        for (File child : children) {
            interrupted(); String name = child.getName(); requireComponent(name);
            if (project && ("temp".equals(name) || "previews".equals(name))) continue;
            if (java.nio.file.Files.isSymbolicLink(child.toPath())) throw new IllegalArgumentException("Archive cannot follow symbolic media links");
            if (++visited[0] > MAX_REMOTE_ENTRIES) throw new IllegalArgumentException("Local archive exceeds restore entry limit");
            relativePath(root, child);
            if (child.isDirectory()) collectLocalFiles(root, child, files, project, depth + 1, directories, visited);
            else if (child.isFile() && child.canRead()) files.add(child);
            else throw new IllegalStateException("Archive contains unreadable local content");
        }
    }

    private InputStream requireInput(Uri uri) throws Exception {
        InputStream in = resolver.openInputStream(uri);
        if (in == null) throw new IllegalStateException("Could not open cloud input stream");
        return in;
    }

    private static File safeTarget(File root, String relative) throws Exception {
        if (relative == null || relative.trim().isEmpty()) throw new IllegalArgumentException("Cloud archive path is empty");
        File target = new File(root, relative.replace('/', File.separatorChar));
        String rootPath = root.getCanonicalPath() + File.separator;
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath)) {
            throw new IllegalArgumentException("Cloud archive attempted to escape local workspace");
        }
        return target;
    }

    private static final class RemoteEntry {
        final Uri uri;
        final String relative;
        final long size;

        RemoteEntry(Uri uri, String relative, long size) {
            this.uri = uri;
            this.relative = relative;
            this.size = size;
        }
    }

    private static final class RemoteStats {
        long bytes;
        int files;
        int directories;
        boolean truncated;

        void add(RemoteStats other) {
            if (other == null) return;
            bytes = StorageBudget.saturatingAdd(bytes, other.bytes);
            files += other.files;
            directories += other.directories;
            truncated |= other.truncated;
        }
    }

    private Uri requireTree(boolean write) { return requireTree(null, write); }

    private Uri requireTree(String selected, boolean write) {
        Uri tree = selected == null || selected.trim().isEmpty() ? treeUri() : Uri.parse(selected);
        if (tree == null) throw new IllegalStateException("No owner-selected archive folder is linked");
        if (!"content".equals(tree.getScheme()) || tree.getAuthority() == null || !DocumentsContract.isTreeUri(tree))
            throw new IllegalArgumentException("Archive storage must use an owner-selected document tree");
        if (!hasPersistedPermission(tree, write)) throw new IllegalStateException(write
                ? "Selected archive folder requires persisted read and write permission"
                : "Selected archive folder requires persisted read permission");
        if (!canReadRoot(tree)) throw new IllegalStateException("Selected archive folder is currently unavailable");
        return tree;
    }

    private Uri treeUri() { return new StorageProfiles(context).activeTree(); }

    private boolean hasPersistedPermission(Uri tree, boolean write) {
        for (android.content.UriPermission permission : resolver.getPersistedUriPermissions())
            if (tree.equals(permission.getUri()) && permission.isReadPermission() && (!write || permission.isWritePermission())) return true;
        return false;
    }

    private Uri pinnedDirectory(Uri tree, String raw) throws Exception {
        Uri directory = Uri.parse(raw);
        if (!"content".equals(directory.getScheme()) || !tree.getAuthority().equals(directory.getAuthority())
                || !DocumentsContract.isTreeUri(directory)
                || !DocumentsContract.getTreeDocumentId(tree).equals(DocumentsContract.getTreeDocumentId(directory)))
            throw new IllegalArgumentException("Pinned archive is outside its owner-selected document tree");
        String id = DocumentsContract.getDocumentId(directory);
        try (Cursor cursor = resolver.query(directory, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || !id.equals(cursor.getString(0))
                    || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1)))
                throw new IllegalStateException("Pinned archive directory is unavailable");
        }
        return directory;
    }

    private boolean canReadRoot(Uri tree) {
        try {
            Uri root = rootDocumentUri(tree);
            try (Cursor cursor = resolver.query(
                    root,
                    new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID},
                    null, null, null)) {
                return cursor != null && cursor.moveToFirst();
            }
        } catch (Exception ignored) {
            return false;
        }
    }

    private Uri rootDocumentUri(Uri tree) {
        String docId = DocumentsContract.getTreeDocumentId(tree);
        return DocumentsContract.buildDocumentUriUsingTree(tree, docId);
    }

    private Uri ensureRelativeDirectories(Uri tree, Uri base, String path) throws Exception {
        Uri current = base;
        if (path == null || path.isEmpty()) return current;
        String[] parts = path.split("/");
        for (String part : parts) {
            if (part == null || part.trim().isEmpty()) continue;
            current = ensureDirectory(tree, current, safeName(part));
        }
        return current;
    }

    private Uri ensureDirectory(Uri tree, Uri parent, String name) throws Exception {
        requireComponent(name);
        Uri existing = findChild(tree, parent, name, true);
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(
                resolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
        );
        if (created == null) throw new IllegalStateException("Could not create cloud directory: " + name);
        verifyCreatedName(created, name, true);
        return created;
    }

    private Uri createGeneration(Uri tree, Uri revisions, String name) throws Exception {
        requireComponent(name);
        if (findChild(tree, revisions, name, true) != null)
            throw new IllegalStateException("Archive generation already exists; it will not be overwritten");
        Uri created = DocumentsContract.createDocument(resolver, revisions, DocumentsContract.Document.MIME_TYPE_DIR, name);
        if (created == null) throw new IllegalStateException("Could not create an immutable archive generation");
        verifyCreatedName(created, name, true);
        return created;
    }

    private void writeBytes(Uri tree,
                            Uri parent,
                            String name,
                            String mime,
                            byte[] bytes) throws Exception {
        Uri doc = ensureFile(tree, parent, name, mime);
        try (InputStream in = new ByteArrayInputStream(bytes);
             OutputStream out = new BufferedOutputStream(requireOutput(doc), BUFFER)) {
            copy(in, out);
        }
    }

    private JSONObject readVaultManifest(Uri uri) throws Exception { return readJson(uri, MAX_MARKER_BYTES); }

    private JSONObject readJson(Uri uri, int limit) throws Exception {
        return new JSONObject(new String(readBytes(uri, limit), StandardCharsets.UTF_8));
    }

    private byte[] readBytes(Uri uri, int limit) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192];
        try (InputStream input = requireInput(uri)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                interrupted();
                if (bytes.size() + count > limit) throw new IllegalArgumentException("Archive metadata exceeds size limit");
                bytes.write(buffer, 0, count);
            }
        }
        return bytes.toByteArray();
    }

    private JSONObject readCommitted(Uri tree, Uri directory, String format, String key, String id, boolean graph) throws Exception {
        JSONObject commit = readVaultManifest(requireChild(tree, directory, "COMMITTED.json", false));
        if (!format.equals(commit.optString("format")) || commit.optInt("version") != 1 || !id.equals(commit.optString(key)))
            throw new IllegalStateException("Pinned archive format or identity does not match the requested content");
        if (commit.getLong("committedAt") < 0L || (graph && commit.getLong("revision") < 0L))
            throw new IllegalArgumentException("Invalid committed archive clock or revision");
        declaredFiles(commit, graph);
        if (graph) {
            String hash = commit.getString("projectSha256"); requireHash(hash);
            Uri graphUri = requireChild(tree, directory, "project.json", false);
            byte[] graphBytes = readBytes(graphUri, MAX_GRAPH_BYTES);
            if (!hash.equals(hashBytes(graphBytes))) throw new IllegalStateException("Committed project graph checksum mismatch");
            JSONObject project = new JSONObject(new String(graphBytes, StandardCharsets.UTF_8));
            if (!id.equals(project.optString("id")) || project.optLong("revision", 0L) != commit.optLong("revision", -1L))
                throw new IllegalStateException("Committed project graph identity or revision mismatch");
        }
        return commit;
    }

    private Map<String, JSONObject> declaredFiles(JSONObject commit, boolean project) throws Exception {
        JSONObject files = commit.optJSONObject("files");
        if (files == null || files.length() > MAX_REMOTE_ENTRIES) throw new IllegalArgumentException("Invalid committed archive file list");
        Map<String, JSONObject> records = new TreeMap<>(); Set<String> entries = new HashSet<>(); long chunks = 0L;
        entries.add("COMMITTED.json"); if (project) entries.add("project.json");
        Iterator<String> keys = files.keys();
        while (keys.hasNext()) {
            String path = keys.next(); validateDataPath(path, project);
            JSONObject record = files.getJSONObject(path);
            long size = record.getLong("size");
            if (size < 0L || (size > 0L && (size - 1L) / StorageVault.CHUNK_BYTES >= 4096L)) throw new IllegalArgumentException("Invalid committed file size");
            requireHash(record.getString("sha256")); boolean vault = record.getBoolean("vault");
            if (vault) {
                if (size <= 0L) throw new IllegalArgumentException("Vault files require a positive size");
                chunks += 1L + (size - 1L) / StorageVault.CHUNK_BYTES;
                if (chunks > MAX_REMOTE_ENTRIES) throw new IllegalArgumentException("Committed archive exceeds total chunk lookup limit");
                entries.add("VaultChunks");
            }
            addEntryPaths(entries, path + (vault ? ".vsvault.json" : ""));
            records.put(path, record);
        }
        if (entries.size() > MAX_REMOTE_ENTRIES) throw new IllegalArgumentException("Committed archive exceeds restore entry limit");
        return records;
    }

    private Uri latestCommittedArchive(Uri tree, Uri root, String format, String key, String id, boolean graph) throws Exception {
        Uri revisions = findChild(tree, root, "Revisions", true);
        if (revisions == null) return root; // Owner-created legacy archive layout remains readable.
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(revisions));
        Uri newest = null; long newestRevision = -1L, newestTime = -1L;
        try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Archive generation index is unavailable");
            int visited = 0;
            while (cursor.moveToNext()) {
                interrupted();
                if (++visited > MAX_REMOTE_ENTRIES) throw new IllegalStateException("Archive generation index exceeds scan limit");
                if (!cursor.getString(1).startsWith("revision-") || !DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) continue;
                Uri candidate = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0));
                try {
                    JSONObject commit = readCommitted(tree, candidate, format, key, id, graph);
                    materializedFiles(tree, candidate, declaredFiles(commit, graph), graph);
                    long revision = graph ? commit.getLong("revision") : 0L, time = commit.optLong("committedAt", 0L);
                    if (revision > newestRevision || (revision == newestRevision && time > newestTime)) {
                        newest = candidate; newestRevision = revision; newestTime = time;
                    }
                } catch (Exception incomplete) { interrupted(); /* Incomplete generations never supersede a committed archive. */ }
            }
        }
        if (newest != null) return newest;
        // Existing non-generation archives can coexist with an interrupted first upload.
        if (graph && findChild(tree, root, "project.json", false) != null) return root;
        if (!graph) {
            List<RemoteEntry> legacy = new ArrayList<>(); collectRemoteFiles(tree, root, "", legacy, 0);
            if (!legacy.isEmpty()) return root;
        }
        throw new IllegalStateException("No complete content archive is available");
    }

    private static String fileHash(File source) throws Exception { return streamHash(new FileInputStream(source)); }
    private static String streamHash(InputStream stream) throws Exception {
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER];
        try (InputStream input = stream) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        return hexHash(digest.digest());
    }
    private static String hashBytes(byte[] bytes) throws Exception {
        return hexHash(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
    private static String hexHash(byte[] bytes) {
        StringBuilder output = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) output.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return output.toString();
    }

    private StorageVault.ChunkStore vaultStore(Uri tree, Uri projectDir, boolean create) throws Exception {
        Uri directory = create ? ensureDirectory(tree, projectDir, "VaultChunks") : findChild(tree, projectDir, "VaultChunks", true);
        if (directory == null) throw new IllegalStateException("Vault chunk directory is missing");
        return new StorageVault.ChunkStore() {
            private void validate(String name) {
                if (name == null || !name.matches("[a-f0-9-]{36}\\.[0-9]{1,4}\\.chunk")) throw new IllegalArgumentException("Unsafe Vault chunk name");
            }
            @Override public OutputStream create(String name) throws Exception {
                validate(name);
                return new BufferedOutputStream(requireOutput(ensureFile(tree, directory, name, "application/octet-stream")), BUFFER);
            }
            @Override public InputStream open(String name) throws Exception {
                validate(name);
                Uri child = findChild(tree, directory, name, false);
                if (child == null) throw new IllegalStateException("Vault chunk is missing");
                return new BufferedInputStream(requireInput(child), BUFFER);
            }
            @Override public void delete(String name) throws Exception {
                validate(name);
                Uri child = findChild(tree, directory, name, false);
                if (child != null) DocumentsContract.deleteDocument(resolver, child);
            }
        };
    }

    private JSONObject copyVerifiedFile(Uri tree, Uri parent, String name, String mime, File source) throws Exception {
        long expected = source.length(), modified = source.lastModified(), count = 0L;
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        Uri doc = ensureFile(tree, parent, name, mime); byte[] buffer = new byte[BUFFER];
        try (InputStream input = new BufferedInputStream(new FileInputStream(source), BUFFER);
             OutputStream output = new BufferedOutputStream(requireOutput(doc), BUFFER)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                interrupted(); count = StorageBudget.saturatingAdd(count, read);
                if (count > expected) throw new IllegalStateException("Source changed during archive upload");
                output.write(buffer, 0, read); digest.update(buffer, 0, read);
            }
            output.flush();
        }
        if (count != expected || source.length() != expected || source.lastModified() != modified)
            throw new IllegalStateException("Source changed during archive upload");
        String hash = hexHash(digest.digest());
        if (!hash.equals(streamHash(requireInput(doc)))) throw new IllegalStateException("Archive file readback checksum failed");
        return new JSONObject().put("size", count).put("sha256", hash);
    }

    private static void copyToFile(InputStream input, File temp, long expected) throws Exception {
        byte[] buffer = new byte[BUFFER]; long count = 0L, spaceCheckAt = 0L;
        try (FileOutputStream output = new FileOutputStream(temp)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                interrupted();
                if (expected >= 0L && read > expected - count) throw new IllegalStateException("Archive file is larger than declared");
                if (count >= spaceCheckAt) {
                    if (temp.getParentFile().getUsableSpace() < StorageBudget.DEFAULT_TRANSFER_RESERVE_BYTES + buffer.length)
                        throw new IllegalStateException("Storage reserve reached during archive restore");
                    spaceCheckAt = count + 4L * 1024L * 1024L;
                }
                output.write(buffer, 0, read); count += read;
            }
            if (expected >= 0L && count != expected) throw new IllegalStateException("Archive file is incomplete");
            output.getFD().sync();
        }
    }

    private Uri ensureFile(Uri tree, Uri parent, String name, String mime) throws Exception {
        requireComponent(name);
        Uri existing = findChild(tree, parent, name, false);
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(
                resolver,
                parent,
                mime == null || mime.isEmpty() ? "application/octet-stream" : mime,
                name
        );
        if (created == null) throw new IllegalStateException("Could not create cloud file: " + name);
        verifyCreatedName(created, name, false);
        return created;
    }

    private void verifyCreatedName(Uri document, String name, boolean directory) throws Exception {
        try (Cursor cursor = resolver.query(document, new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst() || !name.equals(cursor.getString(0))
                    || DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1)) != directory)
                throw new IllegalStateException("Provider changed an archive filename; previous archives were preserved");
        }
    }

    private Uri findChild(Uri tree, Uri parent, String name, boolean directory) throws Exception {
        requireComponent(name);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        Uri found = null;
        try (Cursor cursor = resolver.query(children, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME, DocumentsContract.Document.COLUMN_MIME_TYPE}, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Archive folder listing is unavailable");
            int idIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int visited = 0;
            while (cursor.moveToNext()) {
                interrupted();
                if (++visited > MAX_REMOTE_ENTRIES) throw new IllegalStateException("Archive folder exceeds lookup limit");
                if (name.equals(cursor.getString(nameIndex))) {
                    boolean isDirectory = DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(mimeIndex));
                    if (isDirectory != directory) throw new IllegalStateException("Archive file/directory name collision: " + name);
                    if (found != null) throw new IllegalStateException("Provider returned duplicate archive names: " + name);
                    found = DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(idIndex));
                }
            }
        }
        return found;
    }

    private OutputStream requireOutput(Uri uri) throws Exception {
        OutputStream out = resolver.openOutputStream(uri, "w");
        if (out == null) throw new IllegalStateException("Could not open cloud output stream");
        return out;
    }

    private static String relativePath(File root, File file) throws Exception {
        String rootPath = root.getCanonicalPath();
        String filePath = file.getCanonicalPath();
        if (!filePath.startsWith(rootPath + File.separator)) {
            throw new IllegalArgumentException("Workspace file escaped project root");
        }
        return filePath.substring(rootPath.length() + 1).replace(File.separatorChar, '/');
    }

    private static String parentPath(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? "" : relative.substring(0, slash);
    }

    private static String leafName(String relative) {
        int slash = relative.lastIndexOf('/');
        return slash < 0 ? relative : relative.substring(slash + 1);
    }

    private static String mimeFor(String name) {
        String n = name == null ? "" : name.toLowerCase();
        if (n.endsWith(".json")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".mp4")) return "video/mp4";
        if (n.endsWith(".wav")) return "audio/wav";
        if (n.endsWith(".mp3")) return "audio/mpeg";
        if (n.endsWith(".glb")) return "model/gltf-binary";
        if (n.endsWith(".gltf")) return "model/gltf+json";
        if (n.endsWith(".txt") || n.endsWith(".ms")) return "text/plain";
        return "application/octet-stream";
    }

    private String displayName(Uri uri) {
        try (Cursor cursor = resolver.query(
                uri,
                new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME},
                null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) return cursor.getString(0);
        } catch (Exception ignored) {}
        return "Linked workspace";
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buffer = new byte[BUFFER];
        int n;
        while ((n = in.read(buffer)) >= 0) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            out.write(buffer, 0, n);
        }
        out.flush();
    }

    private static String safeName(String raw) { requireComponent(raw); return raw; }

    private static void requireComponent(String name) {
        if (name == null || name.isEmpty() || name.length() > 255 || ".".equals(name) || "..".equals(name)
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0)
            throw new IllegalArgumentException("Unsafe or unsupported archive filename");
        for (int index = 0; index < name.length(); index++) if (Character.isISOControl(name.charAt(index)))
            throw new IllegalArgumentException("Archive filenames cannot contain control characters");
    }

    private static void validateDataPath(String path, boolean project) {
        if (path == null || path.isEmpty() || path.startsWith("/") || path.endsWith("/")) throw new IllegalArgumentException("Invalid archive path");
        String[] pieces = path.split("/", -1);
        if (pieces.length - 1 > MAX_REMOTE_DEPTH) throw new IllegalArgumentException("Archive path exceeds restore nesting limit");
        for (String component : pieces) {
            requireComponent(component);
            if (component.endsWith(".partial") || component.endsWith(".backup") || component.endsWith(".cloudtmp"))
                throw new IllegalArgumentException("Archive source uses a reserved temporary path");
        }
        String first = pieces[0];
        if ("COMMITTED.json".equals(first) || "VaultChunks".equals(first) || "Revisions".equals(first)
                || (project && "project.json".equals(first)) || path.endsWith(".vsvault.json")
                || path.endsWith(".partial") || path.endsWith(".backup") || path.endsWith(".cloudtmp"))
            throw new IllegalArgumentException("Archive source uses a reserved metadata path");
    }

    private static void addEntryPaths(Set<String> entries, String path) {
        String[] pieces = path.split("/", -1); String current = "";
        for (String piece : pieces) {
            requireComponent(piece); current = current.isEmpty() ? piece : current + "/" + piece; entries.add(current);
        }
    }

    private Uri requireChild(Uri tree, Uri parent, String name, boolean directory) throws Exception {
        Uri child = findChild(tree, parent, name, directory);
        if (child == null) throw new IllegalStateException("Archive entry is missing: " + name);
        return child;
    }

    private static void requireId(String id, String label) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException(label + " is required");
        requireComponent(id);
    }

    private static void requireHash(String hash) {
        if (hash == null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Invalid committed archive checksum");
    }

    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Archive operation cancelled");
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024d);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024d * 1024d));
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024d * 1024d * 1024d));
    }
}
