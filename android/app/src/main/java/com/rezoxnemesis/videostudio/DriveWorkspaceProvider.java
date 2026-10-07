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
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

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
        prefs.edit()
                .putString(KEY_TREE, treeUri.toString())
                .putLong(KEY_LINKED_AT, System.currentTimeMillis())
                .apply();
    }

    public synchronized void unlink() {
        prefs.edit().remove(KEY_TREE).remove(KEY_LINKED_AT).apply();
    }

    public synchronized boolean isLinked() {
        Uri tree = treeUri();
        return tree != null && canReadRoot(tree);
    }

    public synchronized JSONObject status() {
        JSONObject out = new JSONObject();
        Uri tree = treeUri();
        try {
            out.put("linked", tree != null && canReadRoot(tree));
            out.put("scope", "single-user-selected-document-tree");
            out.put("broadDrivePermission", false);
            out.put("galleryAccess", false);
            out.put("providerAuthority", tree == null ? "" : String.valueOf(tree.getAuthority()));
            out.put("linkedAt", prefs.getLong(KEY_LINKED_AT, 0));
            if (tree != null) {
                out.put("displayName", displayName(rootDocumentUri(tree)));
                out.put("persistedReadWrite", hasPersistedPermission(tree));
            }
        } catch (Exception ignored) {}
        return out;
    }

    public JSONObject syncProject(ProjectStore.Project project,
                                  File projectWorkspace,
                                  Progress progress) throws Exception {
        if (project == null) throw new IllegalArgumentException("Project is required");
        Uri tree = requireTree();
        if (progress == null) progress = (p, d) -> {};

        progress.onProgress(2, "Opening folder-scoped cloud workspace");
        Uri root = rootDocumentUri(tree);
        Uri projects = ensureDirectory(tree, root, "Projects");
        Uri projectDir = ensureDirectory(tree, projects, safeName(project.id));

        progress.onProgress(6, "Writing project manifest");
        byte[] manifest = project.toJson().toString(2).getBytes(StandardCharsets.UTF_8);
        writeBytes(tree, projectDir, "project.json", "application/json", manifest);

        List<File> files = new ArrayList<>();
        if (projectWorkspace != null && projectWorkspace.isDirectory()) {
            collectArchiveFiles(projectWorkspace, files);
        }

        long totalBytes = manifest.length;
        for (File file : files) totalBytes += Math.max(0, file.length());
        long copiedBytes = manifest.length;
        int copiedFiles = 1;

        for (File file : files) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String relative = relativePath(projectWorkspace, file);
            Uri parent = ensureRelativeDirectories(tree, projectDir, parentPath(relative));
            String name = leafName(relative);
            String mime = mimeFor(name);
            copyFile(tree, parent, name, mime, file);
            copiedBytes += Math.max(0, file.length());
            copiedFiles++;
            int p = totalBytes <= 0
                    ? Math.min(96, 8 + copiedFiles)
                    : 8 + (int) Math.min(88, 88d * copiedBytes / totalBytes);
            progress.onProgress(p, "Archived " + copiedFiles + " item(s) • " + humanBytes(copiedBytes));
        }

        JSONObject result = new JSONObject();
        result.put("ok", true);
        result.put("projectId", project.id);
        result.put("filesWritten", copiedFiles);
        result.put("bytesWritten", copiedBytes);
        result.put("scope", "single-user-selected-document-tree");
        result.put("providerAuthority", String.valueOf(tree.getAuthority()));
        result.put("broadDrivePermission", false);
        result.put("galleryAccess", false);
        progress.onProgress(100, "Project workspace archived to linked cloud folder");
        return result;
    }

    public JSONObject inventory() throws Exception {
        Uri tree = requireTree();
        Uri root = rootDocumentUri(tree);
        JSONArray areas = new JSONArray();
        areas.put(inventoryArea(tree, root, "Projects"));
        areas.put(inventoryArea(tree, root, "ModelPacks"));

        long bytes = 0;
        int files = 0;
        int directories = 0;
        for (int i = 0; i < areas.length(); i++) {
            JSONObject area = areas.optJSONObject(i);
            if (area == null) continue;
            bytes += area.optLong("bytes", 0);
            files += area.optInt("files", 0);
            directories += area.optInt("directories", 0);
        }

        JSONObject out = status();
        out.put("areas", areas);
        out.put("workspaceBytesVisible", bytes);
        out.put("workspaceFilesVisible", files);
        out.put("workspaceDirectoriesVisible", directories);
        out.put("scanLimit", MAX_REMOTE_ENTRIES);
        out.put("storageRole", "cold-and-warm-project-model-archive");
        return out;
    }

    public JSONObject restoreProjectWorkspace(String projectId,
                                              File projectWorkspace,
                                              Progress progress) throws Exception {
        return restoreProjectWorkspace(projectId, projectWorkspace, false, progress);
    }

    public JSONObject restoreProjectWorkspace(String projectId,
                                              File projectWorkspace,
                                              boolean preserveLocalControlMetadata,
                                              Progress progress) throws Exception {
        if (projectId == null || projectId.trim().isEmpty()) {
            throw new IllegalArgumentException("projectId is required");
        }
        if (projectWorkspace == null) throw new IllegalArgumentException("Project workspace destination is required");
        Uri tree = requireTree();
        if (progress == null) progress = (p, d) -> {};

        Uri root = rootDocumentUri(tree);
        Uri projects = findChild(tree, root, "Projects", true);
        if (projects == null) throw new IllegalStateException("Cloud Projects archive does not exist");
        Uri projectDir = findChild(tree, projects, safeName(projectId), true);
        if (projectDir == null) throw new IllegalStateException("Cloud archive for project was not found");

        List<RemoteEntry> remote = new ArrayList<>();
        collectRemoteFiles(tree, projectDir, "", remote, 0);
        long totalBytes = 0;
        for (RemoteEntry entry : remote) totalBytes += Math.max(0, entry.size);

        long copied = 0;
        int files = 0;
        progress.onProgress(2, "Restoring project creative workspace from linked cloud folder");
        for (RemoteEntry entry : remote) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if ("project.json".equals(entry.relative)) continue;
            if (preserveLocalControlMetadata
                    && (entry.relative.startsWith("checkpoints/")
                    || entry.relative.startsWith("scenes/")
                    || "cloud_offload.json".equals(entry.relative))) {
                continue;
            }
            File target = safeTarget(projectWorkspace, entry.relative);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                throw new IllegalStateException("Could not create restored workspace directory");
            }
            File temp = new File(parent, target.getName() + ".cloudtmp");
            try (InputStream in = new BufferedInputStream(requireInput(entry.uri), BUFFER);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(temp), BUFFER)) {
                copy(in, out);
            }
            if (target.exists() && !target.delete()) {
                temp.delete();
                throw new IllegalStateException("Could not replace local workspace file");
            }
            if (!temp.renameTo(target)) {
                temp.delete();
                throw new IllegalStateException("Could not commit restored workspace file");
            }
            copied += Math.max(0, entry.size);
            files++;
            int p = totalBytes <= 0
                    ? Math.min(98, 4 + files)
                    : 4 + (int) Math.min(94, 94d * copied / totalBytes);
            progress.onProgress(p, "Restored " + files + " item(s) • " + humanBytes(copied));
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("projectId", projectId);
        out.put("filesRestored", files);
        out.put("bytesRestored", copied);
        out.put("scope", "single-user-selected-document-tree");
        out.put("preservedLocalControlMetadata", preserveLocalControlMetadata);
        out.put("galleryAccess", false);
        progress.onProgress(100, "Project creative workspace restored");
        return out;
    }

    public JSONObject syncModelPack(String packId,
                                    File packDirectory,
                                    Progress progress) throws Exception {
        if (packId == null || packId.trim().isEmpty()) throw new IllegalArgumentException("Model pack id is required");
        if (packDirectory == null || !packDirectory.isDirectory()) {
            throw new IllegalArgumentException("Installed model pack directory is missing");
        }
        Uri tree = requireTree();
        if (progress == null) progress = (p, d) -> {};

        Uri root = rootDocumentUri(tree);
        Uri modelPacks = ensureDirectory(tree, root, "ModelPacks");
        Uri packDir = ensureDirectory(tree, modelPacks, safeName(packId));

        List<File> files = new ArrayList<>();
        collectAllFiles(packDirectory, files);
        long totalBytes = 0;
        for (File file : files) totalBytes += Math.max(0, file.length());
        long copied = 0;
        int count = 0;

        progress.onProgress(2, "Opening cloud model-pack archive");
        for (File file : files) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String relative = relativePath(packDirectory, file);
            Uri parent = ensureRelativeDirectories(tree, packDir, parentPath(relative));
            copyFile(tree, parent, leafName(relative), mimeFor(relative), file);
            copied += Math.max(0, file.length());
            count++;
            int p = totalBytes <= 0
                    ? Math.min(98, 4 + count)
                    : 4 + (int) Math.min(94, 94d * copied / totalBytes);
            progress.onProgress(p, "Archived model pack • " + count + " files • " + humanBytes(copied));
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("id", packId);
        out.put("filesWritten", count);
        out.put("bytesWritten", copied);
        out.put("scope", "single-user-selected-document-tree");
        out.put("galleryAccess", false);
        progress.onProgress(100, "Model pack archived to linked cloud folder");
        return out;
    }

    public JSONObject restoreModelPack(String packId,
                                       File destination,
                                       Progress progress) throws Exception {
        if (packId == null || packId.trim().isEmpty()) throw new IllegalArgumentException("Model pack id is required");
        if (destination == null) throw new IllegalArgumentException("Model pack restore destination is required");
        Uri tree = requireTree();
        if (progress == null) progress = (p, d) -> {};

        Uri root = rootDocumentUri(tree);
        Uri modelPacks = findChild(tree, root, "ModelPacks", true);
        if (modelPacks == null) throw new IllegalStateException("Cloud ModelPacks archive does not exist");
        Uri packDir = findChild(tree, modelPacks, safeName(packId), true);
        if (packDir == null) throw new IllegalStateException("Cloud model pack was not found");

        List<RemoteEntry> remote = new ArrayList<>();
        collectRemoteFiles(tree, packDir, "", remote, 0);
        long totalBytes = 0;
        for (RemoteEntry entry : remote) totalBytes += Math.max(0, entry.size);

        long copied = 0;
        int files = 0;
        progress.onProgress(2, "Restoring cloud model pack to protected staging");
        for (RemoteEntry entry : remote) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            File target = safeTarget(destination, entry.relative);
            File parent = target.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs() && !parent.exists()) {
                throw new IllegalStateException("Could not create model-pack staging directory");
            }
            try (InputStream in = new BufferedInputStream(requireInput(entry.uri), BUFFER);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER)) {
                copy(in, out);
            }
            copied += Math.max(0, entry.size);
            files++;
            int p = totalBytes <= 0
                    ? Math.min(98, 4 + files)
                    : 4 + (int) Math.min(94, 94d * copied / totalBytes);
            progress.onProgress(p, "Restored model pack • " + files + " files • " + humanBytes(copied));
        }

        JSONObject out = new JSONObject();
        out.put("ok", true);
        out.put("id", packId);
        out.put("filesRestored", files);
        out.put("bytesRestored", copied);
        out.put("path", destination.getAbsolutePath());
        out.put("galleryAccess", false);
        progress.onProgress(100, "Cloud model pack restored to protected staging");
        return out;
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
            if (cursor == null) return stats;
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
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
                    stats.bytes += sizeIndex >= 0 && !cursor.isNull(sizeIndex)
                            ? Math.max(0, cursor.getLong(sizeIndex)) : 0;
                }
            }
        }
        return stats;
    }

    private void collectRemoteFiles(Uri tree,
                                    Uri directory,
                                    String prefix,
                                    List<RemoteEntry> out,
                                    int depth) throws Exception {
        if (depth > MAX_REMOTE_DEPTH) throw new IllegalStateException("Cloud archive nesting is too deep");
        if (out.size() >= MAX_REMOTE_ENTRIES) throw new IllegalStateException("Cloud archive contains too many files");

        String parentId = DocumentsContract.getDocumentId(directory);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE,
                DocumentsContract.Document.COLUMN_SIZE
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) return;
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            int sizeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE);
            while (cursor.moveToNext()) {
                String id = cursor.getString(idIndex);
                String name = safeName(nameIndex >= 0 ? cursor.getString(nameIndex) : "item");
                String mime = mimeIndex >= 0 ? cursor.getString(mimeIndex) : "";
                Uri child = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                String relative = prefix.isEmpty() ? name : prefix + "/" + name;
                if (DocumentsContract.Document.MIME_TYPE_DIR.equals(mime)) {
                    collectRemoteFiles(tree, child, relative, out, depth + 1);
                } else {
                    long size = sizeIndex >= 0 && !cursor.isNull(sizeIndex)
                            ? Math.max(0, cursor.getLong(sizeIndex)) : 0;
                    out.add(new RemoteEntry(child, relative, size));
                    if (out.size() > MAX_REMOTE_ENTRIES) {
                        throw new IllegalStateException("Cloud archive contains too many files");
                    }
                }
            }
        }
    }

    private void collectAllFiles(File root, List<File> out) {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectAllFiles(child, out);
            else if (child.isFile()) out.add(child);
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
            bytes += other.bytes;
            files += other.files;
            directories += other.directories;
            truncated |= other.truncated;
        }
    }

    private Uri requireTree() {
        Uri uri = treeUri();
        if (uri == null) throw new IllegalStateException("No cloud workspace folder is linked");
        if (!hasPersistedPermission(uri)) {
            throw new IllegalStateException("Linked cloud workspace permission is no longer available");
        }
        if (!canReadRoot(uri)) {
            throw new IllegalStateException("Linked cloud workspace is currently unavailable");
        }
        return uri;
    }

    private Uri treeUri() {
        String raw = prefs.getString(KEY_TREE, "");
        if (raw == null || raw.trim().isEmpty()) return null;
        try { return Uri.parse(raw); }
        catch (Exception ignored) { return null; }
    }

    private boolean hasPersistedPermission(Uri tree) {
        for (android.content.UriPermission permission : resolver.getPersistedUriPermissions()) {
            if (permission.getUri() != null
                    && permission.getUri().equals(tree)
                    && permission.isReadPermission()
                    && permission.isWritePermission()) {
                return true;
            }
        }
        return false;
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
        Uri existing = findChild(tree, parent, name, true);
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(
                resolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                name
        );
        if (created == null) throw new IllegalStateException("Could not create cloud directory: " + name);
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

    private void copyFile(Uri tree,
                          Uri parent,
                          String name,
                          String mime,
                          File source) throws Exception {
        Uri doc = ensureFile(tree, parent, name, mime);
        try (InputStream in = new BufferedInputStream(new FileInputStream(source), BUFFER);
             OutputStream out = new BufferedOutputStream(requireOutput(doc), BUFFER)) {
            copy(in, out);
        }
    }

    private Uri ensureFile(Uri tree, Uri parent, String name, String mime) throws Exception {
        Uri existing = findChild(tree, parent, name, false);
        if (existing != null) return existing;
        Uri created = DocumentsContract.createDocument(
                resolver,
                parent,
                mime == null || mime.isEmpty() ? "application/octet-stream" : mime,
                name
        );
        if (created == null) throw new IllegalStateException("Could not create cloud file: " + name);
        return created;
    }

    private Uri findChild(Uri tree, Uri parent, String name, boolean directory) throws Exception {
        String parentId = DocumentsContract.getDocumentId(parent);
        Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId);
        String[] projection = {
                DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE
        };
        try (Cursor cursor = resolver.query(children, projection, null, null, null)) {
            if (cursor == null) return null;
            int idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID);
            int nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME);
            int mimeIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_MIME_TYPE);
            while (cursor.moveToNext()) {
                String childName = nameIndex >= 0 ? cursor.getString(nameIndex) : "";
                String childMime = mimeIndex >= 0 ? cursor.getString(mimeIndex) : "";
                boolean isDir = DocumentsContract.Document.MIME_TYPE_DIR.equals(childMime);
                if (name.equals(childName) && isDir == directory) {
                    String childId = cursor.getString(idIndex);
                    return DocumentsContract.buildDocumentUriUsingTree(tree, childId);
                }
            }
        }
        return null;
    }

    private OutputStream requireOutput(Uri uri) throws Exception {
        OutputStream out = resolver.openOutputStream(uri, "w");
        if (out == null) throw new IllegalStateException("Could not open cloud output stream");
        return out;
    }

    private void collectArchiveFiles(File root, List<File> out) {
        File[] children = root.listFiles();
        if (children == null) return;
        for (File child : children) {
            String name = child.getName();
            if ("temp".equals(name) || "previews".equals(name)) continue;
            if (child.isDirectory()) collectArchiveFiles(child, out);
            else if (child.isFile()) out.add(child);
        }
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

    private static String safeName(String raw) {
        String value = raw == null ? "item" : raw.trim();
        if (value.isEmpty()) value = "item";
        value = value.replaceAll("[\\/:*?\"<>|\\p{Cntrl}]+", "_");
        if (value.length() > 120) value = value.substring(0, 120);
        return value;
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f KB", bytes / 1024d);
        if (bytes < 1024L * 1024L * 1024L) return String.format(java.util.Locale.US, "%.1f MB", bytes / (1024d * 1024d));
        return String.format(java.util.Locale.US, "%.2f GB", bytes / (1024d * 1024d * 1024d));
    }
}
