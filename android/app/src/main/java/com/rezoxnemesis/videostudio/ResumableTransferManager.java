package com.rezoxnemesis.videostudio;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Bounded-memory resumable transfer engine.
 *
 * Only metadata and byte offsets are retained in the journal. Media bytes are
 * streamed directly into a .partial file and atomically promoted on success.
 */
public final class ResumableTransferManager {
    public static final int BUFFER_BYTES = 256 * 1024;
    public static final long STORAGE_RESERVE_BYTES = 256L * 1024L * 1024L;
    private static final Pattern CONTENT_RANGE =
            Pattern.compile("bytes\\s+(\\d+)-(\\d+)/(\\d+|\\*)", Pattern.CASE_INSENSITIVE);

    public interface ProgressListener {
        void onProgress(long totalBytes);
    }

    public interface DownloadProgress {
        void onProgress(long completedBytes, long expectedBytes);
    }

    public interface ConnectionOpener {
        HttpURLConnection open(String sourceUrl, long offset, String etag, String lastModified) throws Exception;
    }

    public static final class Request {
        public final String id;
        public final String sourceUrl;
        public final File target;
        public final long expectedBytes;
        public final String sha256;

        public Request(String id, String sourceUrl, File target, long expectedBytes, String sha256) {
            if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("Transfer ID is required");
            if (sourceUrl == null || sourceUrl.trim().isEmpty()) throw new IllegalArgumentException("Source URL is required");
            if (target == null) throw new IllegalArgumentException("Target file is required");
            this.id = id;
            this.sourceUrl = sourceUrl;
            this.target = target;
            this.expectedBytes = expectedBytes > 0 ? expectedBytes : -1L;
            this.sha256 = sha256 == null ? "" : sha256.trim().toLowerCase(Locale.US);
        }
    }

    public static final class Result {
        public final File file;
        public final long bytes;
        public final long expectedBytes;
        public final boolean resumed;
        public final String contentType;

        Result(File file, long bytes, long expectedBytes, boolean resumed, String contentType) {
            this.file = file;
            this.bytes = bytes;
            this.expectedBytes = expectedBytes;
            this.resumed = resumed;
            this.contentType = contentType == null ? "" : contentType;
        }
    }

    private final TransferJournal journal;

    public ResumableTransferManager(TransferJournal journal) {
        if (journal == null) throw new IllegalArgumentException("Transfer journal is required");
        this.journal = journal;
    }

    public Result download(Request request,
                           ConnectionOpener opener,
                           DownloadProgress progress) throws Exception {
        if (request == null) throw new IllegalArgumentException("Transfer request is required");
        if (opener == null) throw new IllegalArgumentException("Connection opener is required");

        File partial = partialFileFor(request.target);
        File parent = partial.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IllegalStateException("Could not create transfer directory");
        }

        TransferJournal.Entry checkpoint = journal.get(request.id);
        long offset = partial.isFile() ? partial.length() : 0L;
        String etag = checkpoint == null ? "" : checkpoint.etag;
        String lastModified = checkpoint == null ? "" : checkpoint.lastModified;
        long expected = request.expectedBytes;

        if (checkpoint != null) {
            boolean sameSource = request.sourceUrl.equals(checkpoint.sourceUrl);
            boolean samePartial = partial.getAbsolutePath().equals(checkpoint.partialPath);
            boolean sameOffset = checkpoint.completedBytes == offset;
            boolean changedChecksum = !checkpoint.sha256.isEmpty() && !request.sha256.isEmpty()
                    && !checkpoint.sha256.equalsIgnoreCase(request.sha256);
            if (!sameSource || !samePartial || !sameOffset || changedChecksum
                    || "failed_integrity".equals(checkpoint.state)) {
                truncate(partial);
                offset = 0L;
                etag = "";
                lastModified = "";
            } else if (expected <= 0 && checkpoint.expectedBytes > 0) {
                expected = checkpoint.expectedBytes;
            }
        } else if (offset > 0) {
            // A stray partial with no durable provenance is unsafe to append.
            truncate(partial);
            offset = 0L;
        }

        // Weak ETags cannot be used with If-Range. A timestamp must be a real HTTP date.
        etag = isStrongEtag(etag) ? etag.trim() : "";
        lastModified = isUsableLastModified(lastModified) ? lastModified.trim() : "";
        if (offset > 0L && request.sha256.isEmpty() && etag.isEmpty() && lastModified.isEmpty()) {
            truncate(partial);
            offset = 0L;
            expected = request.expectedBytes;
        }

        // A crash between fsync and rename can leave a complete partial. Never request EOF:
        // a known checksum can prove completion; other checkpoints restart from zero.
        boolean completePartial = false;
        if (offset > 0L && expected > 0L && offset >= expected) {
            completePartial = offset == expected && !request.sha256.isEmpty()
                    && sha256(partial).equalsIgnoreCase(request.sha256);
            if (!completePartial) {
                truncate(partial);
                offset = 0L;
                expected = request.expectedBytes;
                etag = "";
                lastModified = "";
            }
        }
        if (offset == 0L) {
            // A full response must provide its own validators; never label new bytes with old ones.
            etag = "";
            lastModified = "";
        }

        File budgetRoot = parent == null ? request.target.getAbsoluteFile().getParentFile() : parent;
        long freeBytes = budgetRoot == null ? request.target.getUsableSpace() : budgetRoot.getUsableSpace();
        StorageBudget.Check storage = StorageBudget.checkTransfer(
                freeBytes,
                expected,
                offset,
                STORAGE_RESERVE_BYTES
        );
        if (!storage.allowed) {
            journal.save(new TransferJournal.Entry(
                    request.id, request.sourceUrl, partial.getAbsolutePath(),
                    expected, offset, etag, lastModified, request.sha256, "waiting_storage"
            ));
            throw new IllegalStateException(
                    "Insufficient storage for media transfer: need "
                            + storage.requiredWithReserveBytes
                            + " bytes including reserve, have "
                            + storage.freeBytes
            );
        }

        if (completePartial) {
            // The interrupted writer may have died before its fsync despite all bytes being present.
            try (RandomAccessFile completed = new RandomAccessFile(partial, "rw")) {
                if (completed.length() != offset) throw new IllegalStateException("Completed partial changed during recovery");
                completed.getFD().sync();
            }
            return promote(request, partial, offset, expected, true, "", progress);
        }

        boolean resumed = offset > 0;
        HttpURLConnection connection = null;
        try {
            connection = opener.open(request.sourceUrl, offset, etag, lastModified);
            int code = connection.getResponseCode();

            if (offset > 0 && (!canAppendResume(offset, code, connection.getHeaderField("Content-Range"))
                    || !resumeIdentityMatches(request.sha256, etag, lastModified, connection))) {
                connection.disconnect();
                connection = null;
                truncate(partial);
                offset = 0L;
                expected = request.expectedBytes;
                resumed = false;
                etag = "";
                lastModified = "";
                connection = opener.open(request.sourceUrl, 0L, "", "");
                code = connection.getResponseCode();
            }

            if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
                throw new IllegalStateException("Remote media transfer failed: HTTP " + code);
            }
            if (offset == 0L && code == HttpURLConnection.HTTP_PARTIAL
                    && !contentRangeStartsAt(connection.getHeaderField("Content-Range"), 0L)) {
                throw new IllegalStateException("Remote media returned an invalid Content-Range");
            }

            ContentRange range = code == HttpURLConnection.HTTP_PARTIAL
                    ? parseContentRange(connection.getHeaderField("Content-Range")) : null;
            if (code == HttpURLConnection.HTTP_PARTIAL) {
                if (range == null) throw new IllegalStateException("Remote media returned an invalid Content-Range");
                if (range.total > 0L) {
                    if (expected > 0L && expected != range.total) {
                        throw new IllegalStateException("Remote media total differs from its expected byte count");
                    }
                    expected = range.total;
                } else if (expected <= 0L) {
                    throw new IllegalStateException("Partial media response has no known complete byte count");
                }
                if (range.end >= expected) {
                    throw new IllegalStateException("Remote media range exceeds its expected byte count");
                }
                long segmentLength = range.end - range.start + 1L;
                long announcedLength = connection.getContentLengthLong();
                if (announcedLength >= 0L && announcedLength != segmentLength) {
                    throw new IllegalStateException("Remote media segment length does not match Content-Range");
                }
            } else if (connection.getContentLengthLong() > 0L) {
                if (expected > 0L && expected != connection.getContentLengthLong()) {
                    throw new IllegalStateException("Remote media length differs from its expected byte count");
                }
                expected = connection.getContentLengthLong();
            }

            long freeAfterHeaders = parent == null ? 0L : Math.max(0L, parent.getUsableSpace());
            StorageBudget.Check budget = StorageBudget.checkTransfer(
                    freeAfterHeaders,
                    expected,
                    offset,
                    StorageBudget.DEFAULT_TRANSFER_RESERVE_BYTES
            );
            if (!budget.allowed) {
                journal.save(new TransferJournal.Entry(
                        request.id, request.sourceUrl, partial.getAbsolutePath(),
                        expected, offset, etag, lastModified, request.sha256, "waiting_storage"
                ));
                throw new IllegalStateException(
                        "Insufficient storage for remote media: need "
                                + budget.requiredWithReserveBytes
                                + " bytes including reserve, free "
                                + budget.freeBytes
                );
            }

            String responseEtag = safe(connection.getHeaderField("ETag"));
            String responseLastModified = safe(connection.getHeaderField("Last-Modified"));
            if (!responseEtag.isEmpty()) etag = responseEtag;
            if (!responseLastModified.isEmpty()) lastModified = responseLastModified;
            final String durableEtag = etag;
            final String durableLastModified = lastModified;
            final long expectedForCopy = expected;
            final long responseEnd = range == null ? -1L : range.end;

            journal.save(new TransferJournal.Entry(
                    request.id, request.sourceUrl, partial.getAbsolutePath(),
                    expected, offset, durableEtag, durableLastModified, request.sha256, "running"
            ));

            try (InputStream input = connection.getInputStream();
                 RandomAccessFile output = new RandomAccessFile(partial, "rw")) {
                if (offset == 0L) output.setLength(0L);
                output.seek(offset);
                final long baseOffset = offset;
                copyStream(input, output, baseOffset, total -> {
                    if (expectedForCopy > 0L && total > expectedForCopy)
                        throw new IllegalStateException("Remote media exceeded its declared byte count");
                    if (responseEnd >= 0L && total > responseEnd + 1L)
                        throw new IllegalStateException("Remote media exceeded its declared Content-Range");
                    journal.save(new TransferJournal.Entry(
                            request.id, request.sourceUrl, partial.getAbsolutePath(),
                            expectedForCopy, total, durableEtag, durableLastModified,
                            request.sha256, "running"
                    ));
                    if (parent != null && parent.getUsableSpace() < STORAGE_RESERVE_BYTES)
                        throw new IllegalStateException("Insufficient storage while streaming media; partial transfer retained safely");
                    if (progress != null) progress.onProgress(total, expectedForCopy);
                });
                output.getFD().sync();
            }

            long finalBytes = partial.length();
            if (range != null && finalBytes != range.end + 1L) {
                journal.save(new TransferJournal.Entry(
                        request.id, request.sourceUrl, partial.getAbsolutePath(),
                        expected, finalBytes, durableEtag, durableLastModified,
                        request.sha256, "paused"
                ));
                throw new IllegalStateException("Remote media segment ended before its declared Content-Range");
            }
            if (expected > 0 && finalBytes != expected) {
                journal.save(new TransferJournal.Entry(
                        request.id, request.sourceUrl, partial.getAbsolutePath(),
                        expected, finalBytes, durableEtag, durableLastModified,
                        request.sha256, "paused"
                ));
                throw new IllegalStateException("Remote media transfer ended before the expected byte count");
            }

            if (!request.sha256.isEmpty() && !sha256(partial).equalsIgnoreCase(request.sha256)) {
                journal.save(new TransferJournal.Entry(
                        request.id, request.sourceUrl, partial.getAbsolutePath(),
                        expected, finalBytes, durableEtag, durableLastModified,
                        request.sha256, "failed_integrity"
                ));
                throw new IllegalStateException("Remote media SHA-256 mismatch");
            }

            return promote(request, partial, finalBytes, expected, resumed,
                    safe(connection.getContentType()), progress);
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    private Result promote(Request request, File partial, long bytes, long expected,
                           boolean resumed, String contentType, DownloadProgress progress) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Transfer cancelled");
        if (request.target.exists()) {
            throw new IllegalStateException("Completed transfer target already exists");
        }
        if (!partial.renameTo(request.target)) {
            throw new IllegalStateException("Could not atomically promote completed transfer");
        }
        journal.remove(request.id);
        if (progress != null) progress.onProgress(bytes, expected);
        return new Result(request.target, bytes, expected, resumed, contentType);
    }

    private static boolean resumeIdentityMatches(String checksum, String etag, String lastModified,
                                                  HttpURLConnection connection) {
        String responseEtag = safe(connection.getHeaderField("ETag")).trim();
        String responseLastModified = safe(connection.getHeaderField("Last-Modified")).trim();
        if (!etag.isEmpty() && !responseEtag.isEmpty() && !etag.equals(responseEtag)) return false;
        if (!lastModified.isEmpty() && !responseLastModified.isEmpty()
                && !lastModified.equals(responseLastModified)) return false;
        // A checksum proves the final body even if the server omits response validators.
        if (!checksum.isEmpty()) return true;
        // Match the validator used by If-Range, not a weaker secondary timestamp.
        return !etag.isEmpty() ? etag.equals(responseEtag)
                : !lastModified.isEmpty() && lastModified.equals(responseLastModified);
    }

    private static boolean isStrongEtag(String value) {
        String tag = safe(value).trim();
        if (tag.length() < 2 || tag.charAt(0) != '"' || tag.charAt(tag.length() - 1) != '"') return false;
        for (int i = 1; i < tag.length() - 1; i++) {
            char c = tag.charAt(i);
            if (c == '"' || c < 0x21 || c == 0x7f || c > 0xff) return false;
        }
        return true;
    }

    private static boolean isUsableLastModified(String value) {
        try {
            ZonedDateTime.parse(safe(value).trim(), DateTimeFormatter.RFC_1123_DATE_TIME);
            return true;
        } catch (DateTimeParseException ignored) { return false; }
    }

    public static File partialFileFor(File target) {
        if (target == null) throw new IllegalArgumentException("Target file is required");
        File parent = target.getParentFile();
        return parent == null
                ? new File(target.getPath() + ".partial")
                : new File(parent, target.getName() + ".partial");
    }

    public static int progressPercent(long completed, long expected) {
        if (expected <= 0L) return -1;
        if (completed <= 0L) return 0;
        double ratio = Math.min(1d, Math.max(0d, completed / (double) expected));
        return (int) Math.floor(ratio * 100d);
    }

    public static boolean canAppendResume(long completed, int statusCode, String contentRange) {
        return completed > 0L
                && statusCode == HttpURLConnection.HTTP_PARTIAL
                && contentRangeStartsAt(contentRange, completed);
    }

    public static long copyStream(InputStream input,
                                  RandomAccessFile output,
                                  long startingOffset,
                                  ProgressListener listener) throws Exception {
        if (input == null || output == null) throw new IllegalArgumentException("Input and output are required");
        long copied = 0L;
        byte[] buffer = new byte[BUFFER_BYTES];
        int count;
        while ((count = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Transfer cancelled");
            if (count == 0) continue;
            output.write(buffer, 0, count);
            copied += count;
            if (listener != null) listener.onProgress(startingOffset + copied);
        }
        return copied;
    }

    private static boolean contentRangeStartsAt(String value, long expectedStart) {
        ContentRange range = parseContentRange(value);
        return range != null && range.start == expectedStart;
    }

    private static final class ContentRange {
        final long start;
        final long end;
        final long total;
        ContentRange(long start, long end, long total) {
            this.start = start; this.end = end; this.total = total;
        }
    }

    private static ContentRange parseContentRange(String value) {
        if (value == null) return null;
        Matcher matcher = CONTENT_RANGE.matcher(value.trim());
        if (!matcher.matches()) return null;
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            String rawTotal = matcher.group(3);
            long total = "*".equals(rawTotal) ? -1L : Long.parseLong(rawTotal);
            if (end < start || end == Long.MAX_VALUE
                    || (!"*".equals(rawTotal) && (total <= 0L || end >= total))) return null;
            return new ContentRange(start, end, total);
        } catch (NumberFormatException ignored) { return null; }
    }

    private static void truncate(File file) throws Exception {
        if (!file.exists()) return;
        try (RandomAccessFile output = new RandomAccessFile(file, "rw")) {
            output.setLength(0L);
            output.getFD().sync();
        }
    }

    private static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] buffer = new byte[BUFFER_BYTES];
        try (FileInputStream input = new FileInputStream(file)) {
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Transfer cancelled");
                if (count > 0) digest.update(buffer, 0, count);
            }
        }
        StringBuilder out = new StringBuilder();
        for (byte value : digest.digest()) out.append(String.format(Locale.US, "%02x", value & 0xff));
        return out.toString();
    }

    private static String safe(String value) {
        return value == null ? "" : value;
    }
}
