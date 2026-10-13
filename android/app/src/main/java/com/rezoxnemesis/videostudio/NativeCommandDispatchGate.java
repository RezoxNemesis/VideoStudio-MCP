package com.rezoxnemesis.videostudio;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Prevents poll/lease retries from posting the same unacknowledged command
 * over and over to the Android UI thread. The server and durable command
 * journal remain the authoritative source; this is a bounded in-memory guard.
 */
public final class NativeCommandDispatchGate {
    public static final long RETRY_AFTER_MS = 120_000L;
    private static final int MAX = 256;
    private final LinkedHashMap<String, Long> dispatched = new LinkedHashMap<>();

    public synchronized boolean shouldDispatch(String id, long now) {
        if (id == null || id.isEmpty() || now < 0) return false;
        Long previous = dispatched.get(id);
        if (previous != null && now >= previous
                && now - previous < RETRY_AFTER_MS) return false;
        if (previous != null) dispatched.remove(id);
        while (dispatched.size() >= MAX) {
            Iterator<Map.Entry<String, Long>> iterator =
                    dispatched.entrySet().iterator();
            if (!iterator.hasNext()) break;
            iterator.next();
            iterator.remove();
        }
        dispatched.put(id, now);
        return true;
    }

    /** Used only after the relay acknowledges durable command completion. */
    public synchronized void acknowledged(String id) {
        if (id != null) dispatched.remove(id);
    }

    /** A callback could not be posted: the server may redeliver it immediately. */
    public synchronized void undelivered(String id) {
        acknowledged(id);
    }

    synchronized int pendingForTesting() {
        return dispatched.size();
    }
}
