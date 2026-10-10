package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Checksummed bounded-I/O archive for large project-owned workspace files. */
public final class StorageVault {
    public static final long CHUNK_BYTES = 256L * 1024L * 1024L;
    public static final int BUFFER_BYTES = 256 * 1024;
    private static final int MAX_CHUNKS = 4096;
    public interface ChunkStore {
        OutputStream create(String name) throws Exception;
        InputStream open(String name) throws Exception;
        void delete(String name) throws Exception;
    }
    public interface Progress { void transferred(long completed, long total) throws Exception; }
    private StorageVault() {}

    public static JSONObject archive(File source, ChunkStore chunks, Progress progress) throws Exception {
        if (source == null || !source.isFile()) throw new IllegalArgumentException("Readable source file required");
        long expected = source.length();
        long modifiedAt = source.lastModified();
        if (expected <= 0 || (expected - 1L) / CHUNK_BYTES >= MAX_CHUNKS) throw new IllegalArgumentException("Vault source size exceeds archive limits");
        String archiveId = UUID.randomUUID().toString();
        List<String> created = new ArrayList<>();
        JSONArray entries = new JSONArray();
        MessageDigest whole = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_BYTES];
        long completed = 0;
        try (FileInputStream input = new FileInputStream(source)) {
            while (completed < expected) {
                interrupted();
                String name = archiveId + "." + entries.length() + ".chunk";
                created.add(name);
                long wanted = Math.min(CHUNK_BYTES, expected - completed);
                long count = 0;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (OutputStream output = chunks.create(name)) {
                    while (count < wanted) {
                        interrupted();
                        int read = input.read(buffer, 0, (int) Math.min(buffer.length, wanted - count));
                        if (read < 0) throw new IllegalStateException("Source changed during Vault archive");
                        if (read == 0) continue;
                        output.write(buffer, 0, read);
                        digest.update(buffer, 0, read);
                        whole.update(buffer, 0, read);
                        count += read;
                    }
                    output.flush();
                }
                String hash = hex(digest.digest());
                // Readback catches provider write truncation before a manifest is published.
                verify(chunks.open(name), wanted, hash);
                JSONObject entry = new JSONObject();
                entry.put("name", name);
                entry.put("offset", completed);
                entry.put("size", count);
                entry.put("sha256", hash);
                entries.put(entry);
                completed += count;
                if (progress != null) progress.transferred(completed, expected);
            }
            if (input.read() != -1 || source.length() != expected || source.lastModified() != modifiedAt) throw new IllegalStateException("Source changed during Vault archive");
        } catch (Exception failure) {
            for (String name : created) try { chunks.delete(name); } catch (Exception ignored) {}
            throw failure;
        }
        JSONObject manifest = new JSONObject();
        manifest.put("format", "videostudio-vault");
        manifest.put("version", 1);
        manifest.put("archiveId", archiveId);
        manifest.put("size", expected);
        manifest.put("chunkSize", CHUNK_BYTES);
        manifest.put("sha256", hex(whole.digest()));
        manifest.put("chunks", entries);
        return manifest;
    }

    public static long restore(JSONObject manifest, ChunkStore chunks, File target, Progress progress) throws Exception {
        if (manifest == null || !"videostudio-vault".equals(manifest.optString("format")) || manifest.optInt("version") != 1)
            throw new IllegalArgumentException("Unsupported Vault manifest");
        JSONArray entries = manifest.optJSONArray("chunks");
        long expected = manifest.optLong("size", -1L);
        if (expected <= 0 || entries == null || entries.length() == 0 || entries.length() > MAX_CHUNKS)
            throw new IllegalArgumentException("Invalid Vault manifest size");
        String finalHash = requireHash(manifest.optString("sha256"));
        if (target == null) throw new IllegalArgumentException("Restore target required");
        File parent = target.getAbsoluteFile().getParentFile();
        if (!parent.exists() && !parent.mkdirs()) throw new IllegalStateException("Could not prepare restore directory");
        StorageBudget.Check budget = StorageBudget.check(parent.getUsableSpace(), expected, StorageBudget.DEFAULT_TRANSFER_RESERVE_BYTES);
        if (!budget.allowed) throw new IllegalStateException("Not enough storage to restore Vault media safely");
        File temp = new File(parent, target.getName() + "." + UUID.randomUUID() + ".partial");
        MessageDigest whole = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_BYTES];
        long completed = 0;
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                for (int i = 0; i < entries.length(); i++) {
                    interrupted();
                    JSONObject entry = entries.getJSONObject(i);
                    String name = entry.optString("name");
                    if (!name.matches("[a-f0-9-]{36}\\.[0-9]{1,4}\\.chunk")) throw new IllegalArgumentException("Unsafe Vault chunk name");
                    long length = entry.optLong("size", -1L);
                    if (entry.optLong("offset", -1L) != completed || length <= 0 || length > CHUNK_BYTES || length > expected - completed)
                        throw new IllegalArgumentException("Invalid Vault chunk order/length");
                    String wantedHash = requireHash(entry.optString("sha256"));
                    MessageDigest chunkHash = MessageDigest.getInstance("SHA-256");
                    long count = 0;
                    try (InputStream input = chunks.open(name)) {
                        while (count < length) {
                            interrupted();
                            int read = input.read(buffer, 0, (int) Math.min(buffer.length, length - count));
                            if (read < 0) throw new IllegalStateException("Incomplete Vault chunk");
                            if (read == 0) continue;
                            output.write(buffer, 0, read);
                            chunkHash.update(buffer, 0, read);
                            whole.update(buffer, 0, read);
                            count += read;
                        }
                        if (input.read() != -1) throw new IllegalStateException("Vault chunk is larger than declared");
                    }
                    if (!wantedHash.equals(hex(chunkHash.digest()))) throw new IllegalStateException("Vault chunk checksum mismatch");
                    completed += count;
                    if (progress != null) progress.transferred(completed, expected);
                }
                if (completed != expected || !finalHash.equals(hex(whole.digest()))) throw new IllegalStateException("Vault media checksum mismatch");
                output.getFD().sync();
            }
            commit(temp, target);
            return completed;
        } finally {
            if (temp.exists()) temp.delete();
        }
    }
    /** Preserve the old file until the replacement is complete and committed. */
    public static void commit(File complete, File target) throws Exception {
        if (complete == null || !complete.isFile() || target == null
                || !complete.getCanonicalFile().getParentFile().equals(target.getCanonicalFile().getParentFile())) {
            throw new IllegalArgumentException("Restore must stage a complete file beside its destination");
        }
        // Same-filesystem atomic replacement is supported on the app-private
        // Android filesystem. Fail with the previous file intact if a filesystem
        // cannot provide it; never expose a delete/rename crash window.
        java.nio.file.Files.move(complete.toPath(), target.toPath(),
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    private static void verify(InputStream stream, long expected, String hash) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long count = 0;
        byte[] buffer = new byte[BUFFER_BYTES];
        try (InputStream input = stream) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                interrupted();
                if (read == 0) continue;
                count += read;
                if (count > expected) throw new IllegalStateException("Vault chunk write exceeded expected size");
                digest.update(buffer, 0, read);
            }
        }
        if (count != expected || !hash.equals(hex(digest.digest()))) throw new IllegalStateException("Vault chunk readback failed integrity verification");
    }
    private static String requireHash(String hash) {
        if (hash == null || !hash.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("Invalid Vault checksum");
        return hash.toLowerCase(java.util.Locale.ROOT);
    }
    private static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) value.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
        return value.toString();
    }
    private static void interrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Vault transfer cancelled");
    }
}
