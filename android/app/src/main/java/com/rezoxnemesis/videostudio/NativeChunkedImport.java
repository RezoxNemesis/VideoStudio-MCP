package com.rezoxnemesis.videostudio;

import android.graphics.BitmapFactory;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/**
 * Owner-authenticated v3 MCP image transport. Chunks are bounded and private:
 * neither Gallery enumeration nor a public staging URL is ever used.
 * Resume/retry is idempotent at the exact byte offset. Finalization verifies
 * complete length, SHA-256 and actual image bytes before accepting an asset.
 */
public final class NativeChunkedImport {
    private static final long MAX_TOTAL = 12L * 1024 * 1024;
    private static final int MAX_CHUNK = 24 * 1024;
    private final File root;

    public static final class Result {
        public final File file;
        public final String name, mime;
        public final long bytes;
        public final int width, height;
        Result(File f, String name, String mime, long bytes, int w, int h) {
            this.file=f;this.name=name;this.mime=mime;this.bytes=bytes;
            this.width=w;this.height=h;
        }
    }

    public NativeChunkedImport(File appFilesDirectory) {
        root = new File(appFilesDirectory, "private_mcp_frame_chunks");
    }

    public synchronized JSONObject append(String projectId, String transferId,
                                          String name, String mime, long totalBytes,
                                          String sha256, long offset,
                                          String chunkBase64) throws Exception {
        Meta m = new Meta(projectId, transferId, name, mime, totalBytes, sha256);
        byte[] chunk;
        try { chunk = Base64.getDecoder().decode(chunkBase64); }
        catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid frame chunk encoding"); }
        if (chunk.length < 1 || chunk.length > MAX_CHUNK
                || chunkBase64.length() > 32768) {
            throw new IllegalArgumentException("Frame chunk exceeds private 24 KiB limit");
        }
        if (offset < 0 || offset + chunk.length > totalBytes) {
            throw new IllegalArgumentException("Frame chunk offset outside expected file");
        }
        File part = partFile(m);
        File manifest = manifestFile(m);
        if (!manifest.exists()) {
            if (offset != 0) throw new IllegalArgumentException("Unknown transfer must begin at offset zero");
            writeManifest(manifest, m);
        } else verifyManifest(manifest, m);

        try (RandomAccessFile raf = new RandomAccessFile(part, "rw")) {
            long length = raf.length();
            if (offset > length) throw new IllegalArgumentException("Frame chunk contains a gap");
            if (offset < length) {
                if (offset + chunk.length > length) throw new IllegalArgumentException("Frame chunk overlaps another write");
                byte[] existing = new byte[chunk.length];
                raf.seek(offset);
                raf.readFully(existing);
                if (!MessageDigest.isEqual(existing, chunk))
                    throw new IllegalArgumentException("Frame chunk retry content mismatch");
            } else {
                raf.seek(offset);
                raf.write(chunk);
                raf.getFD().sync();
            }
            JSONObject result = new JSONObject();
            result.put("ok", true);
            result.put("transferId", m.transferId);
            result.put("bytesReceived", raf.length());
            result.put("expectedBytes", totalBytes);
            result.put("completedBytes", raf.length() == totalBytes);
            return result;
        }
    }

    public synchronized Result finalizeImage(String projectId, String transferId,
                                             String name, String mime, long totalBytes,
                                             String sha256, File targetDirectory) throws Exception {
        Meta m = new Meta(projectId, transferId, name, mime, totalBytes, sha256);
        File manifest = manifestFile(m);
        File part = partFile(m);
        verifyManifest(manifest, m);
        if (!part.isFile() || part.length() != totalBytes)
            throw new IllegalArgumentException("Incomplete private image transfer");
        if (!sha256(part).equals(m.sha256))
            throw new IllegalArgumentException("Private image SHA-256 verification failed");
        verifySignature(part, m.mime);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(part.getAbsolutePath(), opts);
        if (opts.outWidth < 1 || opts.outHeight < 1
                || opts.outWidth > 4096 || opts.outHeight > 4096
                || (long) opts.outWidth * opts.outHeight > 12_000_000) {
            throw new IllegalArgumentException("Private image cannot be safely decoded");
        }
        if (!targetDirectory.exists() && !targetDirectory.mkdirs())
            throw new IllegalStateException("Private image destination unavailable");
        File dest = new File(targetDirectory, m.transferId + "_" + m.name);
        if (dest.exists()) throw new IllegalArgumentException("A completed image with this transfer ID already exists");
        if (!part.renameTo(dest)) {
            // Atomic first; on filesystems without same-volume rename, fail closed.
            throw new IllegalStateException("Cannot atomically commit private image");
        }
        if (!manifest.delete()) { /* stale manifest is harmless */ }
        return new Result(dest, m.name, m.mime, totalBytes, opts.outWidth, opts.outHeight);
    }

    public synchronized JSONObject status(String projectId, String transferId) throws Exception {
        validId(projectId);validId(transferId);
        File part = partFile(projectId, transferId);
        JSONObject o = new JSONObject();
        o.put("ok", true);
        o.put("transferId", transferId);
        o.put("bytesReceived", part.exists() ? part.length() : 0);
        return o;
    }

    private File projectRoot(String id) {
        validId(id);
        File folder = new File(root, id);
        if (!folder.exists() && !folder.mkdirs())
            throw new IllegalStateException("Private transfer directory unavailable");
        return folder;
    }
    private File partFile(Meta m) { return partFile(m.projectId, m.transferId); }
    private File partFile(String pid, String tid) {
        validId(tid);
        return new File(projectRoot(pid), tid + ".part");
    }
    private File manifestFile(Meta m) {
        return new File(projectRoot(m.projectId), m.transferId + ".manifest");
    }

    private void writeManifest(File f, Meta m) throws Exception {
        if (f.exists()) throw new IllegalArgumentException("Private transfer already exists");
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(m.serialized().getBytes(StandardCharsets.UTF_8));
            out.getFD().sync();
        }
    }

    private void verifyManifest(File f, Meta m) throws Exception {
        if (!f.isFile() || f.length() > 400) throw new IllegalArgumentException("Private transfer manifest missing or invalid");
        byte[] bytes = new byte[(int) f.length()];
        try (FileInputStream input = new FileInputStream(f)) {
            if (input.read(bytes) != bytes.length) throw new IllegalArgumentException("Incomplete transfer manifest");
        }
        if (!MessageDigest.isEqual(bytes, m.serialized().getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Private transfer metadata mismatch");
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[32768];
        try (FileInputStream in = new FileInputStream(file)) {
            int n;while ((n = in.read(buffer)) > 0) digest.update(buffer,0,n);
        }
        StringBuilder out = new StringBuilder(64);
        for (byte b : digest.digest()) out.append(String.format(Locale.US,"%02x",b & 255));
        return out.toString();
    }

    private static void verifySignature(File file, String mime) throws Exception {
        byte[] first = new byte[12];
        try (FileInputStream in = new FileInputStream(file)) {
            if (in.read(first) < 12) throw new IllegalArgumentException("Truncated image");
        }
        boolean jpeg = (first[0]&255)==255 && (first[1]&255)==216 && (first[2]&255)==255;
        boolean png = (first[0]&255)==137 && first[1]==80 && first[2]==78 && first[3]==71;
        boolean webp = first[0]=='R' && first[1]=='I' && first[2]=='F' && first[3]=='F'
                    && first[8]=='W' && first[9]=='E' && first[10]=='B' && first[11]=='P';
        if (!(mime.equals("image/jpeg") && jpeg
                || mime.equals("image/png") && png
                || mime.equals("image/webp") && webp)) {
            throw new IllegalArgumentException("Image contents do not match declared MIME type");
        }
    }

    private static void validId(String value) {
        if (value==null || !value.matches("[A-Za-z0-9_-]{8,80}"))
            throw new IllegalArgumentException("Invalid private transfer identifier");
    }

    private static final class Meta {
        final String projectId,transferId,name,mime,sha256;
        final long totalBytes;
        Meta(String projectId, String transferId, String name,String mime,long totalBytes,String sha256) {
            validId(projectId);validId(transferId);
            if (name==null || !name.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,94}")
                    || name.contains("..")) throw new IllegalArgumentException("Unsafe image filename");
            if (!"image/jpeg".equals(mime) && !"image/png".equals(mime)
                    && !"image/webp".equals(mime)) throw new IllegalArgumentException("Only image frames may be imported");
            if (totalBytes < 12 || totalBytes > MAX_TOTAL)
                throw new IllegalArgumentException("Image size exceeds private transfer budget");
            if (sha256==null || !sha256.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Private transfer requires SHA-256");
            this.projectId=projectId;this.transferId=transferId;
            this.name=name;this.mime=mime;this.totalBytes=totalBytes;this.sha256=sha256;
        }
        String serialized() {return projectId+"\n"+transferId+"\n"+name+"\n"+mime+"\n"+totalBytes+"\n"+sha256+"\n";}
    }
}
