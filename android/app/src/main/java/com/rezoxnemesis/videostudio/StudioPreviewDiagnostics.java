package com.rezoxnemesis.videostudio;

import android.content.Context;
import androidx.media3.common.PlaybackException;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Source-free diagnostic receipt for a real Media3 edited-preview failure.
 *
 * Never stores media URIs, source names, lyric text, file paths, bearer keys,
 * decoded pixels, device-identifying data or raw exception messages.
 */
public final class StudioPreviewDiagnostics {
    private static final String PREF = "videostudio_edited_preview_diagnostics_v1";
    private static final String KEY = "last_failure";
    private StudioPreviewDiagnostics() {}

    public static void failed(Context context, ProjectStore.Project project,
                              Throwable error, String phase) {
        if (context == null || project == null || error == null) return;
        try {
            JSONObject receipt = new JSONObject()
                    .put("schema", "videostudio.edited-preview-diagnostics.v1")
                    .put("status", "failed")
                    .put("phase", safePhase(phase))
                    .put("atMs", System.currentTimeMillis())
                    .put("projectId", project.id)
                    .put("projectUpdatedAt", project.updatedAt)
                    .put("clipCount", project.clips.size())
                    .put("studioLayerCount", project.studioLayers.size())
                    .put("media3ErrorCodeName", error instanceof PlaybackException
                            ? ((PlaybackException) error).getErrorCodeName() : "not_playback_exception")
                    .put("media3ErrorCode", error instanceof PlaybackException
                            ? ((PlaybackException) error).errorCode : -1);
            int titles=0, images=0, audio=0, videos=0;
            for (StudioLayer lane : project.studioLayers) {
                if (StudioLayer.TITLE.equals(lane.type)) titles++;
                else if (StudioLayer.IMAGE.equals(lane.type)) images++;
                else if (StudioLayer.AUDIO.equals(lane.type)) audio++;
                else if (StudioLayer.VIDEO.equals(lane.type)) videos++;
            }
            receipt.put("titleLayers", titles).put("imageLayers", images)
                    .put("audioLayers", audio).put("videoLayers", videos);
            JSONArray classes = new JSONArray();
            boolean videoFrames = false, decoder = false, gl = false;
            Throwable cursor = error;
            for (int i = 0; i < 6 && cursor != null; i++, cursor = cursor.getCause()) {
                String type = cursor.getClass().getSimpleName();
                if (type.length() > 80) type = type.substring(0, 80);
                classes.put(type);
                videoFrames |= type.contains("VideoFrameProcessing");
                decoder |= type.contains("Decoder") || type.contains("Codec");
                gl |= type.contains("Gl") || type.contains("Shader") || type.contains("OpenGl");
            }
            String message = error.getMessage();
            videoFrames |= message != null && message.toLowerCase(java.util.Locale.ROOT)
                    .contains("video frames");
            receipt.put("exceptionTypes", classes);
            receipt.put("failureCategory", gl ? "gpu_shader" :
                    videoFrames ? "video_frame_processing" :
                    decoder ? "decoder_or_codec" : "media3_unknown");
            receipt.put("needsOnDeviceReproduction", true);
            persist(context, receipt);
        } catch (Exception ignored) {
            // Diagnostic persistence cannot fail preview or background work.
        }
    }

    /** A decoded first frame is evidence of recovery; preparation alone is not. */
    public static void renderedFirstFrame(Context context, String projectId) {
        if (context == null || projectId == null) return;
        try {
            JSONObject old = last(context);
            if (!"failed".equals(old.optString("status"))
                    || !projectId.equals(old.optString("projectId"))) return;
            old.put("status", "first_frame_recovered");
            old.put("recoveredAtMs", System.currentTimeMillis());
            persist(context, old);
        } catch (Exception ignored) {}
    }

    public static JSONObject last(Context context) {
        if (context == null) return new JSONObject();
        String raw = context.getApplicationContext()
                .getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "");
        if (raw == null || raw.isEmpty()) return new JSONObject();
        try { return new JSONObject(raw); }
        catch (Exception error) { return new JSONObject(); }
    }

    private static void persist(Context context, JSONObject receipt) {
        String encoded = receipt.toString();
        if (encoded.length() > 4096) return;
        context.getApplicationContext().getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY, encoded).apply();
    }

    private static String safePhase(String value) {
        if ("prepare".equals(value) || "asynchronous_playback".equals(value))
            return value;
        return "unknown";
    }
}
