package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
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
        OutputStream out = resolver.openOutputStream(uri, "wt");
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
