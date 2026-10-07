package com.rezoxnemesis.videostudio;

import java.io.File;

/**
 * Validates encoded output and publishes it through an idempotent target.
 * The target is responsible for platform-specific atomic visibility, such as
 * MediaStore IS_PENDING. Existing committed output can be reused after restart.
 */
public final class AtomicMediaPublisher {
    public interface PublishTarget {
        String existingPublishedUri();
        String publish(File source) throws Exception;
        String displayName();
    }

    public static final class PublishResult {
        public final String uri;
        public final String displayName;
        public final long bytes;
        public final boolean reused;

        PublishResult(String uri, String displayName, long bytes, boolean reused) {
            this.uri = uri == null ? "" : uri;
            this.displayName = displayName == null ? "" : displayName;
            this.bytes = Math.max(0L, bytes);
            this.reused = reused;
        }
    }

    private AtomicMediaPublisher() {}

    public static PublishResult publish(File encoded, PublishTarget target) throws Exception {
        if (encoded == null || !encoded.isFile()) {
            throw new IllegalArgumentException("Encoded render file is missing");
        }
        if (encoded.length() <= 0L) {
            throw new IllegalArgumentException("Encoded render is empty");
        }
        if (target == null) throw new IllegalArgumentException("Publish target is required");

        String existing = clean(target.existingPublishedUri());
        if (!existing.isEmpty()) {
            return new PublishResult(existing, target.displayName(), encoded.length(), true);
        }

        String uri = clean(target.publish(encoded));
        if (uri.isEmpty()) throw new IllegalStateException("Publish target returned no committed URI");
        return new PublishResult(uri, target.displayName(), encoded.length(), false);
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
