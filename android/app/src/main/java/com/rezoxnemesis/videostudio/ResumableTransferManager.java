package com.rezoxnemesis.videostudio;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Locale;

/** Small tested primitives used by the resumable transfer engine. */
public final class ResumableTransferManager {
    public static final int BUFFER_BYTES = 256 * 1024;

    public interface ProgressListener {
        void onProgress(long totalBytes);
    }

    private ResumableTransferManager() {}

    public static File partialFileFor(File target) {
        if (target == null) throw new IllegalArgumentException("Target file is required");
        return new File(target.getParentFile(), target.getName() + ".partial");
    }

    public static int progressPercent(long completed, long expected) {
        if (expected <= 0L) return -1;
        if (completed <= 0L) return 0;
        double ratio = Math.min(1d, Math.max(0d, completed / (double) expected));
        return (int) Math.floor(ratio * 100d);
    }

    public static boolean canAppendResume(long completed, int statusCode, String contentRange) {
        if (completed <= 0L || statusCode != 206 || contentRange == null) return false;
        String value = contentRange.trim().toLowerCase(Locale.US);
        if (!value.startsWith("bytes ")) return false;
        int dash = value.indexOf('-', 6);
        if (dash < 0) return false;
        try {
            long start = Long.parseLong(value.substring(6, dash).trim());
            return start == completed;
        } catch (NumberFormatException ignored) {
            return false;
        }
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
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            if (count == 0) continue;
            output.write(buffer, 0, count);
            copied += count;
            if (listener != null) listener.onProgress(startingOffset + copied);
        }
        return copied;
    }
}
