package com.rezoxnemesis.videostudio;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import org.json.JSONObject;

/** A private invitation to the durable native live session; never gates drawing. */
public final class SketchLiveNotification {
    public static final String CHANNEL_ID = "sketch_live_drawing_v1";
    public static final String ACTION_WATCH = "com.rezoxnemesis.videostudio.WATCH_SKETCH_LIVE";
    public static final String EXTRA_SKETCH_ID = "sketchId";
    public static final String EXTRA_SESSION_ID = "liveSessionId";
    private static final String PREFS = "sketch_live_notifications_v1";
    private static final int NOTIFICATION_ID = 73010;

    private SketchLiveNotification() {}

    /** Does not request permission. Only a visible Activity may offer that system prompt. */
    public static JSONObject status(Context context) {
        JSONObject result = new JSONObject();
        try {
            result.put("posted", false).put("deliveryVerified", false)
                    .put("channelId", CHANNEL_ID);
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return result.put("available", false).put("status", "unavailable");
            ensureChannel(manager);
            boolean permission = Build.VERSION.SDK_INT < 33
                    || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED;
            boolean enabled = Build.VERSION.SDK_INT < 24 || manager.areNotificationsEnabled();
            boolean channelEnabled = true;
            if (Build.VERSION.SDK_INT >= 26) {
                NotificationChannel channel = manager.getNotificationChannel(CHANNEL_ID);
                channelEnabled = channel != null && channel.getImportance() != NotificationManager.IMPORTANCE_NONE;
            }
            return result.put("permissionGranted", permission)
                    .put("appNotificationsEnabled", enabled).put("channelEnabled", channelEnabled)
                    .put("available", permission && enabled && channelEnabled)
                    .put("status", !permission ? "blocked_permission" : !enabled ? "blocked_app_notifications"
                            : !channelEnabled ? "blocked_channel" : "ready");
        } catch (Exception failure) {
            try { result.put("available", false).put("status", "unavailable"); } catch (Exception ignored) {}
            return result;
        }
    }

    /** Call only after baseline and session metadata are durable. Retries never spam an invitation. */
    public static JSONObject drawingStarted(Context context, String sketchId, String sessionId) {
        // Historical drawing did not declare original-stroke provenance.
        return drawingStarted(context, sketchId, sessionId, SketchDrawingPolicy.ASSISTED);
    }

    public static synchronized JSONObject drawingStarted(Context context, String sketchId, String sessionId, String method) {
        JSONObject result = status(context);
        try {
            SketchDrawingPolicy.requireMethod(method);
            result.put("drawingMethod", method);
            SketchDocument.id(new JSONObject().put("id", sketchId), "id");
            SketchDocument.id(new JSONObject().put("id", sessionId), "id");
            SharedPreferences preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            String key = "started:" + sketchId + ":" + sessionId;
            if (preferences.getBoolean(key, false))
                return result.put("status", "already_posted").put("posted", false);
            if (!result.optBoolean("available")) return result;
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null) return result.put("status", "unavailable").put("available", false);
            Intent intent = new Intent(context, SketchStudioActivity.class)
                    .setAction(ACTION_WATCH)
                    .setData(new Uri.Builder().scheme("videostudio-live").authority("session")
                            .appendPath(sketchId).appendPath(sessionId).build())
                    .putExtra(EXTRA_SKETCH_ID, sketchId).putExtra(EXTRA_SESSION_ID, sessionId)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent open = PendingIntent.getActivity(context, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
            Notification publicVersion = builder(context).setSmallIcon(android.R.drawable.ic_menu_edit)
                    .setContentTitle("Video studio").setContentText("Live drawing is available").build();
            boolean original = SketchDrawingPolicy.ORIGINAL.equals(method);
            Notification notification = builder(context).setSmallIcon(android.R.drawable.ic_menu_edit)
                    .setContentTitle(original ? "Original stroke drawing started" : "Reference-assisted painting started")
                    .setContentText(original ? "Tap to watch ChatGPT's original strokes in Sketch Studio"
                            : "Tap to watch reference-assisted brush steps in Sketch Studio")
                    .setCategory(Notification.CATEGORY_PROGRESS).setContentIntent(open)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setPublicVersion(publicVersion)
                    .setOnlyAlertOnce(true).setAutoCancel(true).build();
            manager.notify("live-drawing:" + sketchId + ":" + sessionId, NOTIFICATION_ID, notification);
            // A stable tag + onlyAlertOnce also protects the notify/commit crash boundary.
            boolean durable = preferences.edit().putBoolean(key, true).commit();
            return result.put("status", "posted").put("posted", true).put("deduplicationPersisted", durable);
        } catch (SecurityException denied) {
            try { result.put("status", "blocked_permission").put("posted", false).put("available", false); } catch (Exception ignored) {}
        } catch (Exception failure) {
            // The accepted native session and drawing remain valid even if Android refuses the alert.
            try { result.put("status", "failed").put("posted", false); } catch (Exception ignored) {}
        }
        return result;
    }

    private static Notification.Builder builder(Context context) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(context, CHANNEL_ID) : new Notification.Builder(context);
    }

    private static void ensureChannel(NotificationManager manager) {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID, "Live drawing", NotificationManager.IMPORTANCE_DEFAULT);
        channel.setDescription("Invitations to watch native ChatGPT drawing steps in Sketch Studio.");
        channel.setLockscreenVisibility(Notification.VISIBILITY_PRIVATE);
        manager.createNotificationChannel(channel);
    }
}
