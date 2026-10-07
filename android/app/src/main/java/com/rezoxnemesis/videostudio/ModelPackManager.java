package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.StatFs;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Transactional installer for optional VideoStudio model/capability packs.
 *
 * Packs are installed only from an explicitly imported VideoStudio asset.
 * Gallery enumeration is never involved. Install happens in app-private
 * storage through staging -> validation -> atomic activation.
 */
public final class ModelPackManager {
    public interface Progress {
        void onProgress(int progress, String detail) throws Exception;
    }

    private static final int BUFFER = 256 * 1024;
    private static final int MAX_ENTRIES = 4096;
    private static final long MAX_SINGLE_ENTRY = 6L * 1024L * 1024L * 1024L;
    private static final long MAX_TOTAL_EXPANDED = 16L * 1024L * 1024L * 1024L;
    private static final long MAX_MANIFEST = 256L * 1024L;

    private final Context context;
    private final ContentResolver resolver;
    private final File modelsRoot;
    private final File installedRoot;
    private final File stagingRoot;
    private final File cloudRestoreRoot;

    public ModelPackManager(Context context) {
        this.context = context.getApplicationContext();
        this.resolver = this.context.getContentResolver();
        File workspace = new File(this.context.getFilesDir(), "creative_workspace");
        this.modelsRoot = new File(workspace, "models");
        this.installedRoot = new File(modelsRoot, "installed");
        this.stagingRoot = new File(modelsRoot, "staging");
        this.cloudRestoreRoot = new File(modelsRoot, "cloud_restore");
        ensure(modelsRoot);
        ensure(installedRoot);
        ensure(stagingRoot);
        ensure(cloudRestoreRoot);
        cleanupStaleStaging();
        cleanupStaleCloudRestore();
    }

    public JSONObject install(ProjectStore.Asset source,
                              String expectedSha256,
                              Progress progress) throws Exception {
        if (source == null) throw new IllegalArgumentException("Model-pack source asset is required");
        if (source.uri == null || source.uri.trim().isEmpty()) throw new IllegalArgumentException("Model-pack asset URI is missing");
        if (progress == null) progress = (p, d) -> {};

        String transactionId = UUID.randomUUID().toString();
        File txRoot = new File(stagingRoot, transactionId);
        File archive = new File(txRoot, "pack.zip");
        File unpacked = new File(txRoot, "unpacked");
        ensure(txRoot);
        ensure(unpacked);

        boolean committed = false;
        File backup = null;
        try {
            progress.onProgress(3, "Copying model pack into protected staging");
            String actualSha = copyAndHash(Uri.parse(source.uri), archive, progress);
            String expected = normalizeSha(expectedSha256);
            if (!expected.isEmpty() && !expected.equals(actualSha)) {
                throw new IllegalArgumentException("Model-pack SHA-256 does not match the expected digest");
            }

            progress.onProgress(36, "Expanding model pack with path and size guards");
            unpackSafely(archive, unpacked, progress);

            progress.onProgress(70, "Validating model-pack manifest");
            JSONObject manifest = loadManifest(unpacked);
            String packId = safeId(manifest.optString("id", ""));
            if (packId.isEmpty()) throw new IllegalArgumentException("Model-pack manifest requires a valid id");
            String version = manifest.optString("version", "").trim();
            if (version.isEmpty()) throw new IllegalArgumentException("Model-pack manifest requires version");
            String license = manifest.optString("license", "").trim();
            if (license.isEmpty()) throw new IllegalArgumentException("Model-pack manifest requires license metadata");
            JSONArray capabilities = manifest.optJSONArray("capabilities");
            if (capabilities == null || capabilities.length() == 0) {
                throw new IllegalArgumentException("Model-pack manifest requires at least one capability");
            }

            verifyDeclaredFiles(unpacked, manifest, progress);

            JSONObject installedManifest = new JSONObject(manifest.toString());
            installedManifest.put("archiveSha256", actualSha);
            installedManifest.put("sourceAssetId", source.id);
            installedManifest.put("installedAt", System.currentTimeMillis());
            installedManifest.put("transactionalInstall", true);
            installedManifest.put("integrity", expected.isEmpty() ? "computed-archive-sha256" : "expected-archive-sha256-verified");
            writeUtf8(new File(unpacked, "manifest.json"), installedManifest.toString(2));

            ensureSpaceFor(sizeOf(unpacked));
            File target = new File(installedRoot, packId);
            if (target.exists()) {
                backup = new File(stagingRoot, ".backup_" + packId + "_" + System.currentTimeMillis());
                if (!target.renameTo(backup)) {
                    throw new IllegalStateException("Could not stage previous model-pack version for replacement");
                }
            }

            progress.onProgress(94, "Activating model pack");
            if (!unpacked.renameTo(target)) {
                if (backup != null && backup.exists()) backup.renameTo(target);
                throw new IllegalStateException("Could not atomically activate model pack");
            }
            committed = true;
            if (backup != null) deleteTree(backup);

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("id", packId);
            result.put("version", version);
            result.put("license", license);
            result.put("capabilities", capabilities);
            result.put("sha256", actualSha);
            result.put("installedBytes", sizeOf(target));
            result.put("path", target.getAbsolutePath());
            result.put("integrity", installedManifest.optString("integrity"));
            progress.onProgress(100, "Model pack installed and ready for capability discovery");
            return result;
        } finally {
            if (!committed) {
                deleteTree(unpacked);
                if (backup != null && backup.exists()) {
                    File manifest = new File(backup, "manifest.json");
                    String id = "";
                    try { id = safeId(new JSONObject(readSmall(manifest)).optString("id", "")); }
                    catch (Exception ignored) {}
                    File restore = id.isEmpty() ? null : new File(installedRoot, id);
                    if (restore != null && !restore.exists()) backup.renameTo(restore);
                }
            }
            if (archive.exists()) archive.delete();
            deleteTree(txRoot);
        }
    }

    public File installedDirectory(String packId) throws Exception {
        String id = safeId(packId);
        if (id.isEmpty()) throw new IllegalArgumentException("Invalid model-pack id");
        File target = new File(installedRoot, id);
        String rootPath = installedRoot.getCanonicalPath() + File.separator;
        String targetPath = target.getCanonicalPath();
        if (!targetPath.startsWith(rootPath)) throw new IllegalArgumentException("Unsafe model-pack id");
        if (!target.isDirectory()) throw new IllegalArgumentException("Installed model pack not found: " + id);
        return target;
    }

    public File createCloudRestoreDirectory(String packId) throws Exception {
        String id = safeId(packId);
        if (id.isEmpty()) throw new IllegalArgumentException("Invalid model-pack id");
        File dir = new File(cloudRestoreRoot, id + "_" + UUID.randomUUID());
        String rootPath = cloudRestoreRoot.getCanonicalPath() + File.separator;
        String targetPath = dir.getCanonicalPath();
        if (!targetPath.startsWith(rootPath)) throw new IllegalArgumentException("Unsafe cloud restore path");
        ensure(dir);
        return dir;
    }

    public JSONObject activateRestoredDirectory(File restored,
                                                String sourceLabel,
                                                Progress progress) throws Exception {
        if (restored == null || !restored.isDirectory()) {
            throw new IllegalArgumentException("Restored model-pack directory is missing");
        }
        if (progress == null) progress = (p, d) -> {};

        String cloudRoot = cloudRestoreRoot.getCanonicalPath() + File.separator;
        String restoredPath = restored.getCanonicalPath();
        if (!restoredPath.startsWith(cloudRoot)) {
            throw new IllegalArgumentException("Restored model pack is outside protected staging");
        }

        long restoredBytes = sizeOf(restored);
        if (restoredBytes <= 0 || restoredBytes > MAX_TOTAL_EXPANDED) {
            throw new IllegalArgumentException("Restored model-pack size is invalid");
        }

        progress.onProgress(68, "Validating restored model-pack manifest");
        JSONObject manifest = loadManifest(restored);
        String packId = safeId(manifest.optString("id", ""));
        if (packId.isEmpty()) throw new IllegalArgumentException("Model-pack manifest requires a valid id");
        String version = manifest.optString("version", "").trim();
        if (version.isEmpty()) throw new IllegalArgumentException("Model-pack manifest requires version");
        String license = manifest.optString("license", "").trim();
        if (license.isEmpty()) throw new IllegalArgumentException("Model-pack manifest requires license metadata");
        JSONArray capabilities = manifest.optJSONArray("capabilities");
        if (capabilities == null || capabilities.length() == 0) {
            throw new IllegalArgumentException("Model-pack manifest requires at least one capability");
        }

        verifyDeclaredFiles(restored, manifest, progress);
        ensureSpaceFor(restoredBytes);

        String transactionId = UUID.randomUUID().toString();
        File txRoot = new File(stagingRoot, transactionId);
        File unpacked = new File(txRoot, "unpacked");
        ensure(txRoot);
        ensure(unpacked);

        boolean committed = false;
        File backup = null;
        try {
            progress.onProgress(88, "Copying restored model pack into transactional activation");
            copyTreeWithLimits(restored, unpacked);

            JSONObject installedManifest = new JSONObject(manifest.toString());
            installedManifest.put("installedAt", System.currentTimeMillis());
            installedManifest.put("transactionalInstall", true);
            installedManifest.put("source", sourceLabel == null ? "cloud-workspace" : sourceLabel);
            installedManifest.put("restoredFromCloudWorkspace", true);
            installedManifest.put("integrity",
                    manifest.optJSONObject("files") == null
                            ? "cloud-folder-manifest-validated"
                            : "declared-file-sha256-verified");
            writeUtf8(new File(unpacked, "manifest.json"), installedManifest.toString(2));

            File target = new File(installedRoot, packId);
            if (target.exists()) {
                backup = new File(stagingRoot, ".backup_" + packId + "_" + System.currentTimeMillis());
                if (!target.renameTo(backup)) {
                    throw new IllegalStateException("Could not stage previous model-pack version for replacement");
                }
            }

            progress.onProgress(96, "Activating restored model pack");
            if (!unpacked.renameTo(target)) {
                if (backup != null && backup.exists()) backup.renameTo(target);
                throw new IllegalStateException("Could not atomically activate restored model pack");
            }
            committed = true;
            if (backup != null) deleteTree(backup);

            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("id", packId);
            result.put("version", version);
            result.put("license", license);
            result.put("capabilities", capabilities);
            result.put("installedBytes", sizeOf(target));
            result.put("path", target.getAbsolutePath());
            result.put("restoredFromCloudWorkspace", true);
            result.put("integrity", installedManifest.optString("integrity"));
            progress.onProgress(100, "Cloud model pack activated");
            return result;
        } finally {
            if (!committed) {
                deleteTree(unpacked);
                if (backup != null && backup.exists()) {
                    File restoreTarget = new File(installedRoot, packId);
                    if (!restoreTarget.exists()) backup.renameTo(restoreTarget);
                }
            }
            deleteTree(txRoot);
            deleteTree(restored);
        }
    }

    public JSONObject uninstall(String packId) {
        JSONObject out = new JSONObject();
        String id = safeId(packId);
        boolean removed = false;
        long bytes = 0;
        if (!id.isEmpty()) {
            File target = new File(installedRoot, id);
            try {
                String rootPath = installedRoot.getCanonicalPath() + File.separator;
                String targetPath = target.getCanonicalPath();
                if (targetPath.startsWith(rootPath)) {
                    bytes = sizeOf(target);
                    removed = deleteTree(target);
                }
            } catch (Exception ignored) {}
        }
        try {
            out.put("ok", removed);
            out.put("id", id);
            out.put("removedBytes", removed ? bytes : 0);
            if (!removed) out.put("error", "Installed model pack not found or could not be removed");
        } catch (Exception ignored) {}
        return out;
    }

    private String copyAndHash(Uri uri, File target, Progress progress) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long copied = 0;
        try (InputStream raw = resolver.openInputStream(uri);
             BufferedInputStream in = raw == null ? null : new BufferedInputStream(raw, BUFFER);
             OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER)) {
            if (in == null) throw new IllegalArgumentException("Could not open model-pack asset");
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                out.write(buffer, 0, n);
                digest.update(buffer, 0, n);
                copied += n;
                if ((copied & ((8L * 1024L * 1024L) - 1)) < BUFFER) {
                    progress.onProgress(8 + (int) Math.min(24, copied / (64L * 1024L * 1024L)), "Staged " + humanBytes(copied));
                }
            }
        }
        if (target.length() <= 0) throw new IllegalArgumentException("Model-pack archive is empty");
        return hex(digest.digest());
    }

    private void unpackSafely(File archive, File destination, Progress progress) throws Exception {
        String root = destination.getCanonicalPath() + File.separator;
        long total = 0;
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new BufferedInputStream(new FileInputStream(archive), BUFFER))) {
            ZipEntry entry;
            byte[] buffer = new byte[BUFFER];
            while ((entry = zip.getNextEntry()) != null) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (++entries > MAX_ENTRIES) throw new IllegalArgumentException("Model pack contains too many files");
                String name = entry.getName() == null ? "" : entry.getName().replace('\\', '/');
                if (name.isEmpty() || name.startsWith("/") || name.contains("../") || name.equals("..")) {
                    throw new IllegalArgumentException("Unsafe model-pack path");
                }
                File outFile = new File(destination, name);
                String canonical = outFile.getCanonicalPath();
                if (!canonical.startsWith(root)) throw new IllegalArgumentException("Model pack attempted path traversal");

                if (entry.isDirectory()) {
                    ensure(outFile);
                    continue;
                }
                ensure(outFile.getParentFile());

                long entryBytes = 0;
                try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile), BUFFER)) {
                    int n;
                    while ((n = zip.read(buffer)) >= 0) {
                        entryBytes += n;
                        total += n;
                        if (entryBytes > MAX_SINGLE_ENTRY) throw new IllegalArgumentException("One model-pack file exceeds the safe size limit");
                        if (total > MAX_TOTAL_EXPANDED) throw new IllegalArgumentException("Expanded model pack exceeds the safe size limit");
                        out.write(buffer, 0, n);
                    }
                }
                if (entries % 12 == 0) {
                    progress.onProgress(Math.min(68, 38 + entries / 3), "Expanded " + entries + " files • " + humanBytes(total));
                }
            }
        }
        if (entries == 0) throw new IllegalArgumentException("Model-pack archive contains no files");
    }

    private JSONObject loadManifest(File root) throws Exception {
        File manifest = new File(root, "manifest.json");
        if (!manifest.isFile()) throw new IllegalArgumentException("Model-pack archive requires top-level manifest.json");
        if (manifest.length() <= 0 || manifest.length() > MAX_MANIFEST) {
            throw new IllegalArgumentException("Model-pack manifest has an invalid size");
        }
        return new JSONObject(readSmall(manifest));
    }

    private void verifyDeclaredFiles(File root, JSONObject manifest, Progress progress) throws Exception {
        JSONObject files = manifest.optJSONObject("files");
        if (files == null || files.length() == 0) return;
        JSONArray names = files.names();
        if (names == null) return;
        String rootPath = root.getCanonicalPath() + File.separator;

        for (int i = 0; i < names.length(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String relative = names.optString(i);
            String expected = normalizeSha(files.optString(relative));
            if (expected.isEmpty()) throw new IllegalArgumentException("Invalid declared SHA-256 for " + relative);
            File file = new File(root, relative);
            String canonical = file.getCanonicalPath();
            if (!canonical.startsWith(rootPath) || !file.isFile()) {
                throw new IllegalArgumentException("Declared model file is missing or unsafe: " + relative);
            }
            String actual = sha256(file);
            if (!expected.equals(actual)) throw new IllegalArgumentException("Model file checksum mismatch: " + relative);
            progress.onProgress(Math.min(90, 72 + (int) (18d * (i + 1) / names.length())), "Verified model file " + (i + 1) + "/" + names.length());
        }
    }

    private void ensureSpaceFor(long needed) {
        StatFs stats = new StatFs(modelsRoot.getAbsolutePath());
        long reserve = Math.max(512L * 1024L * 1024L, stats.getTotalBytes() / 20);
        if (needed <= 0 || stats.getAvailableBytes() - reserve < needed) {
            throw new IllegalStateException("Not enough app-private storage to activate this model pack safely");
        }
    }

    private void cleanupStaleCloudRestore() {
        File[] children = cloudRestoreRoot.listFiles();
        if (children == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (File child : children) {
            if (child.lastModified() > 0 && child.lastModified() < cutoff) deleteTree(child);
        }
    }

    private static void copyTreeWithLimits(File source, File destination) throws Exception {
        String sourceRoot = source.getCanonicalPath() + File.separator;
        String destinationRoot = destination.getCanonicalPath() + File.separator;
        ArrayList<File> stack = new ArrayList<>();
        stack.add(source);
        long total = 0;
        int entries = 0;

        while (!stack.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            File current = stack.remove(stack.size() - 1);
            File[] children = current.listFiles();
            if (children == null) continue;
            for (File child : children) {
                if (++entries > MAX_ENTRIES) throw new IllegalArgumentException("Restored model pack contains too many files");
                String childPath = child.getCanonicalPath();
                if (!childPath.startsWith(sourceRoot)) {
                    throw new IllegalArgumentException("Restored model pack attempted source path escape");
                }
                String relative = childPath.substring(sourceRoot.length());
                File target = new File(destination, relative);
                String targetPath = target.getCanonicalPath();
                if (!targetPath.startsWith(destinationRoot)) {
                    throw new IllegalArgumentException("Restored model pack attempted destination path escape");
                }

                if (child.isDirectory()) {
                    ensure(target);
                    stack.add(child);
                } else if (child.isFile()) {
                    long length = Math.max(0, child.length());
                    if (length > MAX_SINGLE_ENTRY) throw new IllegalArgumentException("One restored model file exceeds the safe size limit");
                    total += length;
                    if (total > MAX_TOTAL_EXPANDED) throw new IllegalArgumentException("Restored model pack exceeds the safe size limit");
                    ensure(target.getParentFile());
                    try (InputStream in = new BufferedInputStream(new FileInputStream(child), BUFFER);
                         OutputStream out = new BufferedOutputStream(new FileOutputStream(target), BUFFER)) {
                        byte[] buffer = new byte[BUFFER];
                        int n;
                        while ((n = in.read(buffer)) >= 0) {
                            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                            out.write(buffer, 0, n);
                        }
                    }
                }
            }
        }
    }

    private void cleanupStaleStaging() {
        File[] children = stagingRoot.listFiles();
        if (children == null) return;
        long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (File child : children) {
            if (child.lastModified() > 0 && child.lastModified() < cutoff) deleteTree(child);
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new BufferedInputStream(new FileInputStream(file), BUFFER)) {
            byte[] buffer = new byte[BUFFER];
            int n;
            while ((n = in.read(buffer)) >= 0) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }

    private static String normalizeSha(String value) {
        if (value == null) return "";
        String v = value.trim().toLowerCase(Locale.US);
        return v.matches("[a-f0-9]{64}") ? v : "";
    }

    private static String safeId(String value) {
        if (value == null) return "";
        String v = value.trim().toLowerCase(Locale.US);
        return v.matches("[a-z0-9][a-z0-9._-]{2,119}") ? v : "";
    }

    private static String readSmall(File file) throws Exception {
        if (file == null || !file.isFile() || file.length() > MAX_MANIFEST) throw new IllegalArgumentException("Invalid manifest");
        byte[] data = new byte[(int) file.length()];
        try (InputStream in = new FileInputStream(file)) {
            int offset = 0;
            while (offset < data.length) {
                int n = in.read(data, offset, data.length - offset);
                if (n < 0) break;
                offset += n;
            }
            if (offset != data.length) throw new IllegalStateException("Manifest read was incomplete");
        }
        return new String(data, StandardCharsets.UTF_8);
    }

    private static void writeUtf8(File file, String value) throws Exception {
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(value.getBytes(StandardCharsets.UTF_8));
            out.flush();
            out.getFD().sync();
        }
    }

    private static void ensure(File dir) {
        if (dir == null || dir.exists()) return;
        if (!dir.mkdirs() && !dir.exists()) throw new IllegalStateException("Could not create model-pack directory");
    }

    private static long sizeOf(File file) {
        if (file == null || !file.exists()) return 0;
        if (file.isFile()) return file.length();
        long total = 0;
        File[] children = file.listFiles();
        if (children != null) for (File child : children) total += sizeOf(child);
        return total;
    }

    private static boolean deleteTree(File file) {
        if (file == null || !file.exists()) return true;
        boolean ok = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) ok &= deleteTree(child);
        }
        return file.delete() && ok;
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) out.append(String.format(Locale.US, "%02x", b & 0xff));
        return out.toString();
    }

    private static String humanBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.US, "%.1f KB", bytes / 1024d);
        if (bytes < 1024L * 1024L * 1024L) return String.format(Locale.US, "%.1f MB", bytes / (1024d * 1024d));
        return String.format(Locale.US, "%.2f GB", bytes / (1024d * 1024d * 1024d));
    }
}
