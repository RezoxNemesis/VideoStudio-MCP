package com.rezoxnemesis.videostudio;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
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
            if (!sameSource || !samePartial || !sameOffset) {
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

        boolean resumed = offset > 0;
        HttpURLConnection connection = null;
        try {
            connection = opener.open(request.sourceUrl, offset, etag, lastModified);
            int code = connection.getResponseCode();

            if (offset > 0 && !canAppendResume(offset, code, connection.getHeaderField("Content-Range"))) {
                connection.disconnect();
                connection = null;
                truncate(partial);
                offset = 0L;
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

            long rangeTotal = totalFromContentRange(connection.getHeaderField("Content-Range"));
            if (rangeTotal > 0) {
                expected = rangeTotal;
            } else if (offset == 0L && connection.getContentLengthLong() > 0) {
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
                    journal.save(new TransferJournal.Entry(
                            request.id, request.sourceUrl, partial.getAbsolutePath(),
                            expectedForCopy, total, durableEtag, durableLastModified,
                            request.sha256, "running"
                    ));
                    if (progress != null) progress.onProgress(total, expectedForCopy);
                });
                output.getFD().sync();
            }

            long finalBytes = partial.length();
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

            if (request.target.exists()) {
                throw new IllegalStateException("Completed transfer target already exists");
            }
            if (!partial.renameTo(request.target)) {
                throw new IllegalStateException("Could not atomically promote completed transfer");
            }
            journal.remove(request.id);
            if (progress != null) progress.onProgress(finalBytes, expected);
            return new Result(
                    request.target,
                    finalBytes,
                    expected,
                    resumed,
                    safe(connection.getContentType())
            );
        } finally {
            if (connection != null) connection.disconnect();
        }
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
        if (value == null) return false;
        Matcher matcher = CONTENT_RANGE.matcher(value.trim());
        if (!matcher.matches()) return false;
        try {
            long start = Long.parseLong(matcher.group(1));
            long end = Long.parseLong(matcher.group(2));
            return start == expectedStart && end >= start;
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private static long totalFromContentRange(String value) {
        if (value == null) return -1L;
        Matcher matcher = CONTENT_RANGE.matcher(value.trim());
        if (!matcher.matches()) return -1L;
        String total = matcher.group(3);
        if ("*".equals(total)) return -1L;
        try { return Long.parseLong(total); }
        catch (NumberFormatException ignored) { return -1L; }
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
