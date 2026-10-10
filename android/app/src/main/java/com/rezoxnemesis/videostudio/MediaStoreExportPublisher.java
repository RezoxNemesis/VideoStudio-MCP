package com.rezoxnemesis.videostudio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Publishes one validated native MP4 through a durable, verified pending row.
 * All methods perform blocking streaming I/O and belong on the export worker.
 * They never browse MediaStore: only an inserted or receipted exact URI is read.
 *
 * A copy and a complete MediaStore readback each use a 256 KiB buffer. Full SHA
 * verification is deliberately real; its cost on large exports is unbenchmarked.
 * The caller supplies trusted request proof, including the pinned graph/revision,
 * output profile and durable operation identity. This helper verifies bytes and
 * publication, while the caller remains responsible for native codec evidence.
 */
public final class MediaStoreExportPublisher {
    private static final int RECEIPT_VERSION = 1;
    private static final int BUFFER_BYTES = 256 * 1024;
    private static final int MAX_RECEIPT_BYTES = 64 * 1024;
    private static final int MAX_PROOF_BYTES = 16 * 1024;
    private static final String MIME = "video/mp4";
    private static final String RELATIVE_PATH = Environment.DIRECTORY_MOVIES + "/VideoStudio/";

    public interface ReceiptRecorder {
        /** Return only after this owned hidden row allocation is durable. */
        void persistAllocated(JSONObject receipt) throws Exception;

        /** Return only after this exact fully verified pending URI is durable. */
        void persistPending(JSONObject receipt) throws Exception;

        /** Return only after the exact visible URI and receipt are durable. */
        void persistPublished(JSONObject receipt) throws Exception;
    }

    /** A retained URI must be reconciled, never replaced by a second copy. */
    public static final class PublicationException extends Exception {
        public final JSONObject receipt;
        public final boolean recoveryUriRetained;

        private PublicationException(String message, Exception cause, JSONObject receipt) {
            super(message, cause);
            this.receipt = receipt;
            this.recoveryUriRetained = true;
        }
    }

    private MediaStoreExportPublisher() {}

    public static JSONObject publish(Context context, File input, String displayName,
                                     JSONObject priorReceipt, JSONObject requestProof,
                                     ReceiptRecorder recorder) throws Exception {
        requirePlatform(context, recorder);
        JSONObject proof = requestProof(requestProof);
        String requestedName = displayName(displayName);
        JSONObject allocatedReceipt = null;
        if (priorReceipt != null) {
            JSONObject prior = checkedReceipt(priorReceipt, proof);
            if (!requestedName.equals(prior.getString("requestedDisplayName")))
                throw new IllegalArgumentException("Export receipt belongs to a different output name");
            if ("allocated".equals(prior.getString("phase"))) {
                allocatedReceipt = prior;
            } else {
                if (input != null) {
                    InputIdentity identity = InputIdentity.capture(input);
                    Digest actual = digestFile(input, identity.bytes);
                    identity.assertUnchanged(input);
                    requireDigest(prior, actual);
                }
                return resumePending(context, prior, proof, recorder);
            }
        }

        InputIdentity identity = InputIdentity.capture(input);
        checkInterrupted();
        ContentResolver resolver = context.getApplicationContext().getContentResolver();
        Uri uri;
        if (allocatedReceipt != null) {
            uri = Uri.parse(allocatedReceipt.getString("uri"));
        } else {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Video.Media.DISPLAY_NAME, requestedName);
            values.put(MediaStore.Video.Media.MIME_TYPE, MIME);
            values.put(MediaStore.Video.Media.RELATIVE_PATH, RELATIVE_PATH);
            values.put(MediaStore.Video.Media.IS_PENDING, 1);
            uri = resolver.insert(MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
            if (uri == null) throw new IllegalStateException("Could not allocate a pending exported video");
        }

        JSONObject receipt = allocatedReceipt;
        // A callback may commit and then throw. Once it receives this URI, keep
        // the owned hidden row even when its durable outcome cannot be determined.
        boolean pendingHandoffStarted = allocatedReceipt != null;
        try {
            requireUri(uri);
            Row allocated = readOwnedRow(context, uri);
            if (!allocated.pending) throw new IllegalStateException("Allocated export row is already visible; partial output cannot be overwritten");
            requireFormat(allocated);
            if (allocatedReceipt != null) requireRowIdentity(context, allocated, allocatedReceipt);

            receipt = allocatedReceipt == null ? new JSONObject() : copyJson(allocatedReceipt);
            receipt.put("version", RECEIPT_VERSION);
            receipt.put("uri", uri.toString());
            receipt.put("requestedDisplayName", requestedName);
            receipt.put("displayName", allocated.name);
            receipt.put("name", allocated.name);
            receipt.put("mimeType", MIME);
            receipt.put("relativePath", RELATIVE_PATH);
            receipt.put("ownerPackage", context.getPackageName());
            receipt.put("expectedBytes", identity.bytes);
            receipt.put("inputIdentity", identity.toJson(null));
            receipt.put("requestProof", proof);
            if (!receipt.has("createdAt")) receipt.put("createdAt", System.currentTimeMillis());
            receipt.put("allocatedAt", System.currentTimeMillis());
            receipt.put("pending", true);
            receipt.put("published", false);
            receipt.put("phase", "allocated");
            receipt.put("reused", allocatedReceipt != null);
            checkInterrupted();
            pendingHandoffStarted = true;
            recorder.persistAllocated(copyJson(receipt));
            identity.assertUnchanged(input);
            Digest copied = copy(resolver, input, uri, identity.bytes);
            identity.assertUnchanged(input);
            Digest readback = digestUri(resolver, uri, copied.bytes);
            if (copied.bytes != readback.bytes || !copied.sha256.equals(readback.sha256))
                throw new IllegalStateException("MediaStore export readback differs from the rendered input");
            identity.assertUnchanged(input);
            Row verified = readOwnedRow(context, uri);
            if (!verified.pending) throw new IllegalStateException("Export became visible before its pending receipt");
            requireFormat(verified);
            requireActualBytes(context, verified, copied.bytes);

            receipt.put("displayName", verified.name);
            receipt.put("name", verified.name);
            receipt.put("bytes", copied.bytes);
            receipt.put("sha256", copied.sha256);
            receipt.put("inputProof", identity.toJson(copied.sha256));
            receipt.put("verifiedAt", System.currentTimeMillis());
            receipt.put("verification", "full copied-input SHA-256 and complete pending MediaStore readback");
            receipt.put("pending", true);
            receipt.put("published", false);
            receipt.put("phase", "verified_pending");
            checkInterrupted();
            recorder.persistPending(copyJson(receipt));
            return makeVisible(context, uri, receipt, recorder);
        } catch (Exception error) {
            if (pendingHandoffStarted && receipt != null)
                throw retained(error, receipt);
            // Only this invocation's newly inserted, still pending, owned row
            // is disposable. A visible or ownership-uncertain row is retained.
            cleanupOwnPending(context, uri);
            throw error;
        }
    }

    /**
     * Reconciles an already durable pending or published receipt, even when the
     * temporary render file no longer exists. No insert or recopy is performed.
     */
    public static JSONObject resumePending(Context context, JSONObject priorReceipt,
                                           JSONObject requestProof, ReceiptRecorder recorder) throws Exception {
        requirePlatform(context, recorder);
        JSONObject receipt = checkedReceipt(priorReceipt, requestProof(requestProof));
        Uri uri = Uri.parse(receipt.getString("uri"));
        try {
            if ("allocated".equals(receipt.getString("phase")))
                throw new IllegalStateException("Allocated export is not fully verified; restart its copy into the same pending URI with publish");
            checkInterrupted();
            Row before = readOwnedRow(context, uri);
            requireRowReceipt(context, before, receipt);
            Digest readback = digestUri(context.getContentResolver(), uri, receipt.getLong("bytes"));
            requireDigest(receipt, readback);
            Row after = readOwnedRow(context, uri);
            requireRowReceipt(context, after, receipt);
            if ((before.statBytes >= 0L && after.statBytes >= 0L && before.statBytes != after.statBytes)
                    || (!before.pending && !after.pending && before.modifiedSeconds != after.modifiedSeconds))
                throw new IllegalStateException("MediaStore export changed during receipt verification");
            if (!before.pending && after.pending)
                throw new IllegalStateException("Published export became pending during receipt verification");
            receipt.put("verifiedAt", System.currentTimeMillis());
            receipt.put("reused", true);
            if (after.pending) {
                if (receipt.getBoolean("published"))
                    throw new IllegalStateException("A published export receipt unexpectedly refers to a pending row");
                receipt.put("pending", true);
                receipt.put("published", false);
                receipt.put("phase", "verified_pending");
                checkInterrupted();
                recorder.persistPending(copyJson(receipt));
                return makeVisible(context, uri, receipt, recorder);
            }
            // A previous process may have flipped visibility and died before
            // recording it. Persist the exact already-visible row now.
            return recordPublished(receipt, recorder);
        } catch (Exception error) {
            throw retained(error, receipt);
        }
    }

    /** Exact receipt-owned hidden row cleanup for terminal cancellation. */
    public static boolean discardOwnedPending(Context context, JSONObject priorReceipt) throws Exception {
        if (context == null) throw new IllegalArgumentException("Publication context is required");
        if (priorReceipt == null) throw new IllegalArgumentException("A receipt-owned pending output is required");
        JSONObject receipt = checkedReceipt(priorReceipt, requestProof(priorReceipt.getJSONObject("requestProof")));
        if (receipt.getBoolean("published")) return false;
        Uri uri = Uri.parse(receipt.getString("uri"));
        Row row = readOwnedRow(context, uri);
        requireRowIdentity(context, row, receipt);
        if (!row.pending) return false;
        int deleted = context.getContentResolver().delete(uri, MediaStore.Video.Media.IS_PENDING + "=?", new String[]{"1"});
        if (deleted > 1) throw new IllegalStateException("Exact pending export cleanup affected more than one row");
        return deleted == 1;
    }

    private static JSONObject makeVisible(Context context, Uri uri, JSONObject receipt,
                                          ReceiptRecorder recorder) throws Exception {
        checkInterrupted();
        Row before = readOwnedRow(context, uri);
        requireRowReceipt(context, before, receipt);
        if (before.pending) {
            ContentValues done = new ContentValues();
            done.put(MediaStore.Video.Media.IS_PENDING, 0);
            checkInterrupted();
            int updated = context.getContentResolver().update(uri, done, null, null);
            if (updated != 1) throw new IllegalStateException("MediaStore did not acknowledge exactly one export publication update");
        }
        Row visible = readOwnedRow(context, uri);
        requireRowReceipt(context, visible, receipt);
        if (visible.pending) throw new IllegalStateException("MediaStore export remains pending after publication");
        // Visibility is committed. Finish recording it even if cancellation
        // arrives now; a visible user output must never be deleted as cleanup.
        return recordPublished(receipt, recorder);
    }

    private static JSONObject recordPublished(JSONObject receipt, ReceiptRecorder recorder) throws Exception {
        receipt.put("pending", false);
        receipt.put("published", true);
        receipt.put("phase", "published");
        if (!receipt.has("publishedAt")) receipt.put("publishedAt", System.currentTimeMillis());
        recorder.persistPublished(copyJson(receipt));
        return copyJson(receipt);
    }

    private static Digest copy(ContentResolver resolver, File source, Uri target, long expectedBytes) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = 0L;
        byte[] buffer = new byte[BUFFER_BYTES];
        checkInterrupted();
        // A recovered allocation can contain a partial previous attempt. Always
        // restart the copy by truncating this same owned, still hidden row.
        ParcelFileDescriptor descriptor = resolver.openFileDescriptor(target, "rwt");
        if (descriptor == null) throw new IllegalStateException("Could not open the pending export destination");
        try (ParcelFileDescriptor ownedDescriptor = descriptor;
             ParcelFileDescriptor.AutoCloseOutputStream output = new ParcelFileDescriptor.AutoCloseOutputStream(ownedDescriptor);
             InputStream input = new FileInputStream(source)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                checkInterrupted();
                if (read == 0) continue;
                if (read > expectedBytes - total) throw new IllegalStateException("Rendered input grew during publication");
                output.write(buffer, 0, read);
                digest.update(buffer, 0, read);
                total += read;
            }
            checkInterrupted();
            if (total != expectedBytes) throw new IllegalStateException("Rendered input was truncated during publication");
            output.flush();
            ownedDescriptor.getFileDescriptor().sync();
        }
        return new Digest(total, hex(digest.digest()));
    }

    private static Digest digestFile(File file, long expectedBytes) throws Exception {
        checkInterrupted();
        try (InputStream input = new FileInputStream(file)) { return digest(input, expectedBytes); }
    }

    private static Digest digestUri(ContentResolver resolver, Uri uri, long expectedBytes) throws Exception {
        checkInterrupted();
        try (InputStream input = resolver.openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Could not reopen the exact MediaStore export");
            return digest(input, expectedBytes);
        }
    }

    private static Digest digest(InputStream input, long expectedBytes) throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_BYTES];
        long total = 0L;
        int read;
        while ((read = input.read(buffer)) != -1) {
            checkInterrupted();
            if (read == 0) continue;
            if (read > expectedBytes - total) throw new IllegalStateException("Export bytes exceed their verified receipt");
            sha.update(buffer, 0, read);
            total += read;
        }
        checkInterrupted();
        if (total != expectedBytes) throw new IllegalStateException("Export byte count differs from its verified receipt");
        return new Digest(total, hex(sha.digest()));
    }

    private static final class Digest {
        final long bytes;
        final String sha256;
        Digest(long bytes, String sha256) { this.bytes = bytes; this.sha256 = sha256; }
    }

    private static final class InputIdentity {
        final String canonicalPath;
        final long bytes;
        final long modifiedMs;
        InputIdentity(String canonicalPath, long bytes, long modifiedMs) {
            this.canonicalPath = canonicalPath; this.bytes = bytes; this.modifiedMs = modifiedMs;
        }
        static InputIdentity capture(File file) throws Exception {
            if (file == null || !file.isFile() || !file.canRead() || file.length() <= 0L)
                throw new IllegalArgumentException("A readable completed native render file is required");
            return new InputIdentity(file.getCanonicalPath(), file.length(), file.lastModified());
        }
        void assertUnchanged(File file) throws Exception {
            if (!file.isFile() || !file.canRead() || !canonicalPath.equals(file.getCanonicalPath())
                    || bytes != file.length() || modifiedMs != file.lastModified())
                throw new IllegalStateException("Rendered input identity changed during publication");
        }
        JSONObject toJson(String sha) throws Exception {
            JSONObject proof = new JSONObject().put("path", canonicalPath).put("bytes", bytes).put("modifiedMs", modifiedMs);
            if (sha != null) proof.put("sha256", sha);
            return proof;
        }
    }

    private static final class Row {
        final Uri uri;
        final String name, mime, relativePath, owner;
        final boolean pending;
        final long indexedBytes, modifiedSeconds;
        long statBytes = -1L;
        Row(Cursor cursor, Uri uri) throws Exception {
            this.uri = uri;
            name = string(cursor, MediaStore.Video.Media.DISPLAY_NAME);
            mime = string(cursor, MediaStore.Video.Media.MIME_TYPE);
            relativePath = string(cursor, MediaStore.Video.Media.RELATIVE_PATH);
            owner = string(cursor, MediaStore.Video.Media.OWNER_PACKAGE_NAME);
            int state = cursor.getInt(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.IS_PENDING));
            if (state != 0 && state != 1) throw new IllegalStateException("MediaStore returned an invalid export visibility state");
            pending = state == 1;
            indexedBytes = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE));
            modifiedSeconds = cursor.getLong(cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED));
        }
    }

    private static Row readOwnedRow(Context context, Uri uri) throws Exception {
        requireUri(uri);
        String[] columns = {MediaStore.Video.Media.DISPLAY_NAME, MediaStore.Video.Media.MIME_TYPE,
                MediaStore.Video.Media.RELATIVE_PATH, MediaStore.Video.Media.OWNER_PACKAGE_NAME,
                MediaStore.Video.Media.IS_PENDING, MediaStore.Video.Media.SIZE, MediaStore.Video.Media.DATE_MODIFIED};
        Row row;
        try (Cursor cursor = context.getContentResolver().query(uri, columns, null, null, null)) {
            if (cursor == null || !cursor.moveToFirst()) throw new IllegalStateException("Exact MediaStore export row is unavailable");
            row = new Row(cursor, uri);
            if (cursor.moveToNext()) throw new IllegalStateException("Export URI unexpectedly resolves to more than one row");
            if (!context.getPackageName().equals(row.owner)) throw new IllegalStateException("MediaStore export row is not owned by VideoStudio");
        }
        return row;
    }

    private static void requireUri(Uri uri) throws Exception {
        if (uri == null || !"content".equals(uri.getScheme()) || !MediaStore.AUTHORITY.equals(uri.getAuthority())
                || uri.getQuery() != null || uri.getFragment() != null)
            throw new IllegalArgumentException("Export receipt is not an exact MediaStore video URI");
        List<String> parts = uri.getPathSegments();
        if (parts.size() != 4 || !(MediaStore.VOLUME_EXTERNAL_PRIMARY.equals(parts.get(0)) || "external".equals(parts.get(0)))
                || !"video".equals(parts.get(1)) || !"media".equals(parts.get(2)) || !parts.get(3).matches("[1-9][0-9]*"))
            throw new IllegalArgumentException("Export receipt does not identify one external video row");
        Long.parseLong(parts.get(3));
    }

    private static void requireFormat(Row row) throws Exception {
        if (!MIME.equals(row.mime) || !RELATIVE_PATH.equals(row.relativePath) || row.name.isEmpty())
            throw new IllegalStateException("MediaStore export row has an unexpected format or VideoStudio destination");
    }

    private static void requireRowReceipt(Context context, Row row, JSONObject receipt) throws Exception {
        requireRowIdentity(context, row, receipt);
        requireActualBytes(context, row, receipt.getLong("bytes"));
    }

    private static void requireActualBytes(Context context, Row row, long expected) throws Exception {
        // SIZE is indexed provider metadata and can remain stale across a
        // pending rewrite. Inspect the descriptor after a complete copy exists;
        // a newly allocated row may not have a physical file yet.
        try (ParcelFileDescriptor descriptor = context.getContentResolver().openFileDescriptor(row.uri, "r")) {
            if (descriptor == null) throw new IllegalStateException("Could not inspect the exact owned export file");
            row.statBytes = descriptor.getStatSize();
        }
        if ((row.statBytes >= 0L && row.statBytes != expected)
                || (row.statBytes < 0L && !row.pending && row.indexedBytes > 0L && row.indexedBytes != expected))
            throw new IllegalStateException("Exact MediaStore row byte count does not match its export receipt");
    }

    private static void requireRowIdentity(Context context, Row row, JSONObject receipt) throws Exception {
        requireFormat(row);
        if (!context.getPackageName().equals(receipt.getString("ownerPackage"))
                || !row.name.equals(receipt.getString("displayName")))
            throw new IllegalStateException("Exact MediaStore row does not match its export receipt");
    }

    private static JSONObject checkedReceipt(JSONObject prior, JSONObject proof) throws Exception {
        if (prior == null) throw new IllegalArgumentException("A verified pending or published receipt is required");
        JSONObject receipt = boundedCopy(prior, MAX_RECEIPT_BYTES, "Export receipt");
        if (receipt.optInt("version", 0) != RECEIPT_VERSION || !MIME.equals(receipt.getString("mimeType"))
                || !RELATIVE_PATH.equals(receipt.getString("relativePath")))
            throw new IllegalArgumentException("Export receipt has no supported byte and format proof");
        requireUri(Uri.parse(receipt.getString("uri")));
        if (!(receipt.opt("pending") instanceof Boolean) || !(receipt.opt("published") instanceof Boolean)
                || receipt.getBoolean("pending") == receipt.getBoolean("published"))
            throw new IllegalArgumentException("Export receipt has an invalid publication phase");
        String phase = receipt.getString("phase");
        if (!"allocated".equals(phase) && !"verified_pending".equals(phase) && !"published".equals(phase))
            throw new IllegalArgumentException("Export receipt phase is unsupported");
        if (receipt.getBoolean("published") != "published".equals(phase))
            throw new IllegalArgumentException("Export receipt phase contradicts its visibility");
        displayName(receipt.getString("requestedDisplayName"));
        if (receipt.getString("displayName").isEmpty() || receipt.getString("ownerPackage").isEmpty())
            throw new IllegalArgumentException("Export receipt has no row identity");
        if ("allocated".equals(phase)) {
            JSONObject identity = receipt.getJSONObject("inputIdentity");
            if (receipt.getLong("expectedBytes") <= 0L || identity.getLong("bytes") != receipt.getLong("expectedBytes")
                    || identity.getString("path").isEmpty() || !(identity.opt("modifiedMs") instanceof Number))
                throw new IllegalArgumentException("Allocated export has no consistent input identity");
        } else {
            JSONObject input = receipt.getJSONObject("inputProof");
            if (receipt.getLong("bytes") <= 0L || !receipt.getString("sha256").matches("[a-f0-9]{64}")
                    || input.getLong("bytes") != receipt.getLong("bytes") || !input.getString("sha256").equals(receipt.getString("sha256")))
                throw new IllegalArgumentException("Export receipt input proof is inconsistent");
        }
        if (!canonical(requestProof(receipt.getJSONObject("requestProof")), 0).equals(canonical(proof, 0)))
            throw new IllegalArgumentException("Export receipt belongs to a different request, graph or output profile");
        return receipt;
    }

    private static JSONObject requestProof(JSONObject proof) throws Exception {
        if (proof == null || proof.length() == 0) throw new IllegalArgumentException("Trusted export request proof is required");
        JSONObject copy = boundedCopy(proof, MAX_PROOF_BYTES, "Export request proof");
        canonical(copy, 0); // Reject pathological nested receipt metadata.
        return copy;
    }

    private static void requireDigest(JSONObject receipt, Digest actual) throws Exception {
        if (receipt.getLong("bytes") != actual.bytes || !receipt.getString("sha256").equals(actual.sha256))
            throw new IllegalStateException("Actual export bytes do not match the durable receipt");
    }

    private static void cleanupOwnPending(Context context, Uri uri) {
        try {
            Row row = readOwnedRow(context, uri);
            if (row.pending) context.getContentResolver().delete(uri, MediaStore.Video.Media.IS_PENDING + "=?", new String[]{"1"});
        } catch (Exception ignored) { /* Unknown ownership/visibility is retained. */ }
    }

    private static PublicationException retained(Exception error, JSONObject receipt) {
        if (error instanceof PublicationException) return (PublicationException) error;
        if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        JSONObject defensive;
        try { defensive = copyJson(receipt); } catch (Exception ignored) { defensive = receipt; }
        return new PublicationException("Export publication needs reconciliation using its retained exact URI: " + error.getMessage(), error, defensive);
    }

    private static void requirePlatform(Context context, ReceiptRecorder recorder) {
        if (context == null || recorder == null) throw new IllegalArgumentException("Publication context and durable receipt recorder are required");
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) throw new IllegalStateException("Durable pending MediaStore export requires Android 10 or newer");
    }

    private static String displayName(String value) {
        if (value == null || value.trim().isEmpty() || value.indexOf('/') >= 0 || value.indexOf('\\') >= 0
                || value.indexOf('\0') >= 0 || value.length() > 240 || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0)
            throw new IllegalArgumentException("A bounded export display name is required");
        return value.trim();
    }

    private static JSONObject boundedCopy(JSONObject value, int limit, String label) throws Exception {
        String encoded = value.toString();
        if (encoded == null || encoded.getBytes(StandardCharsets.UTF_8).length > limit)
            throw new IllegalArgumentException(label + " exceeds its bounded durable size");
        return new JSONObject(encoded);
    }

    private static JSONObject copyJson(JSONObject value) throws Exception { return new JSONObject(value.toString()); }

    private static String string(Cursor cursor, String name) {
        int column = cursor.getColumnIndexOrThrow(name);
        return cursor.isNull(column) ? "" : cursor.getString(column);
    }

    private static String canonical(Object value, int depth) throws Exception {
        if (depth > 32) throw new IllegalArgumentException("Export request proof is too deeply nested");
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>();
            java.util.Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            StringBuilder result = new StringBuilder("{");
            for (int i = 0; i < keys.size(); i++) {
                if (i > 0) result.append(',');
                String key = keys.get(i);
                result.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key), depth + 1));
            }
            return result.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) result.append(',');
                result.append(canonical(array.get(i), depth + 1));
            }
            return result.append(']').toString();
        }
        return value instanceof String ? JSONObject.quote((String) value) : String.valueOf(value);
    }

    private static String hex(byte[] bytes) {
        char[] digits = "0123456789abcdef".toCharArray();
        char[] text = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) { int b = bytes[i] & 255; text[i * 2] = digits[b >>> 4]; text[i * 2 + 1] = digits[b & 15]; }
        return new String(text);
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Export publication interrupted");
    }
}
