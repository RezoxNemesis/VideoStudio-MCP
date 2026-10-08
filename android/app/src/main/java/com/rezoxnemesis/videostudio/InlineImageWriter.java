package com.rezoxnemesis.videostudio;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Locale;

/** Bounded private attachment transfer without another full payload allocation. */
final class InlineImageWriter {
    static long write(String encoded, File file, long limit, String expectedSha) throws Exception {
        StringInput source = new StringInput(encoded);
        boolean success = false;
        try (InputStream decoded = Base64.getDecoder().wrap(source);
             FileOutputStream output = new FileOutputStream(file)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] chunk = new byte[32 * 1024];
            long size = 0;
            int count;
            while ((count = decoded.read(chunk)) != -1) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                size += count;
                if (size > limit) throw new IllegalArgumentException("Inline image exceeds decoded transfer limit");
                output.write(chunk, 0, count);
                digest.update(chunk, 0, count);
            }
            if (size == 0 || source.position != encoded.length())
                throw new IllegalArgumentException("Invalid base64 image payload");
            StringBuilder actual = new StringBuilder(64);
            for (byte value : digest.digest()) actual.append(String.format(Locale.US, "%02x", value & 0xff));
            if (!expectedSha.isEmpty() && !actual.toString().equalsIgnoreCase(expectedSha))
                throw new IllegalArgumentException("Inline image SHA-256 mismatch");
            output.flush();
            output.getFD().sync();
            success = true;
            return size;
        } catch (IOException invalid) {
            throw new IOException("Inline image transfer failed", invalid);
        } finally {
            if (!success) file.delete();
        }
    }

    private static final class StringInput extends InputStream {
        private final String text;
        int position;
        StringInput(String text) { this.text = text; }
        @Override public int read() throws IOException {
            if (position == text.length()) return -1;
            char value = text.charAt(position++);
            if (value > 127) throw new IOException("Invalid base64 character");
            return value;
        }
    }
}
