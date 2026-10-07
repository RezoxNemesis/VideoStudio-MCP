package com.rezoxnemesis.videostudio;

import org.json.JSONObject;

/** Immutable-enough serialisable editor playback state used across UI rebuilds. */
public final class LivePlaybackState {
    public final String mediaUri;
    public final long positionMs;
    public final boolean playWhenReady;
    public final String snapshotId;
    public final String clipId;

    public LivePlaybackState(String mediaUri,
                             long positionMs,
                             boolean playWhenReady,
                             String snapshotId,
                             String clipId) {
        this.mediaUri = mediaUri == null ? "" : mediaUri;
        this.positionMs = Math.max(0L, positionMs);
        this.playWhenReady = playWhenReady;
        this.snapshotId = snapshotId == null ? "" : snapshotId;
        this.clipId = clipId == null ? "" : clipId;
    }

    public JSONObject toJson() {
        JSONObject out = new JSONObject();
        try {
            out.put("mediaUri", mediaUri);
            out.put("positionMs", positionMs);
            out.put("playWhenReady", playWhenReady);
            out.put("snapshotId", snapshotId);
            out.put("clipId", clipId);
        } catch (Exception ignored) {}
        return out;
    }

    public static LivePlaybackState fromJson(JSONObject raw) {
        JSONObject o = raw == null ? new JSONObject() : raw;
        return new LivePlaybackState(
                o.optString("mediaUri", ""),
                o.optLong("positionMs", 0L),
                o.optBoolean("playWhenReady", false),
                o.optString("snapshotId", ""),
                o.optString("clipId", "")
        );
    }

    public LivePlaybackState copy() {
        return new LivePlaybackState(mediaUri, positionMs, playWhenReady, snapshotId, clipId);
    }
}
