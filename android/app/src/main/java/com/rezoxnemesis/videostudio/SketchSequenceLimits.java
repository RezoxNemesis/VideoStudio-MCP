package com.rezoxnemesis.videostudio;

/** Sequence capacity is independent of the bounded, single-cel document format. */
final class SketchSequenceLimits {
    static final int MAX_CELS = 1_000_000;
    static final int MAX_FRAMES = 1_000_000;
    static final int LEGACY_EXPORT_FRAMES = 1200;
    static final long SNAPSHOT_BYTES = 8L * 1024 * 1024 * 1024;
    static final long STORAGE_RESERVE = 128L * 1024 * 1024;
    private SketchSequenceLimits() {}
}
