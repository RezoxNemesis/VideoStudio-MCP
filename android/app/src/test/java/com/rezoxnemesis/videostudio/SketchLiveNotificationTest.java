package com.rezoxnemesis.videostudio;

import android.Manifest;
import android.app.Application;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowNotificationManager;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public class SketchLiveNotificationTest {
    private Application context;
    private NotificationManager manager;
    private ShadowNotificationManager notifications;

    @Before public void setUp() {
        context = RuntimeEnvironment.getApplication();
        manager = context.getSystemService(NotificationManager.class);
        notifications = shadowOf(manager);
        notifications.setNotificationsEnabled(true);
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        context.getSharedPreferences("sketch_live_notifications_v1", 0).edit().clear().commit();
    }

    @Test public void aPostedNotificationOpensItsExactPrivateSessionAndDoesNotExposeArtworkNames() throws Exception {
        JSONObject result = SketchLiveNotification.drawingStarted(context, "portrait", "live_one");
        assertEquals("posted", result.getString("status"));
        assertTrue(result.getBoolean("posted"));
        assertFalse(result.getBoolean("deliveryVerified"));
        assertEquals(1, notifications.size());
        Notification notification = notifications.getAllNotifications().get(0);
        Intent intent = shadowOf(notification.contentIntent).getSavedIntent();
        assertEquals(SketchStudioActivity.class.getName(), intent.getComponent().getClassName());
        assertEquals(SketchLiveNotification.ACTION_WATCH, intent.getAction());
        assertEquals("portrait", intent.getStringExtra(SketchLiveNotification.EXTRA_SKETCH_ID));
        assertEquals("live_one", intent.getStringExtra(SketchLiveNotification.EXTRA_SESSION_ID));
        assertTrue(shadowOf(notification.contentIntent).isImmutable());
        assertEquals(Notification.VISIBILITY_PRIVATE, notification.visibility);
        assertNotNull(notification.publicVersion);
        assertFalse(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString().contains("portrait"));
        assertEquals("Reference-assisted painting started", notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals(NotificationManager.IMPORTANCE_DEFAULT,
                manager.getNotificationChannel(SketchLiveNotification.CHANNEL_ID).getImportance());
    }

    @Test public void separateSessionsHaveSeparatePendingIntentIdentitiesAndRetryDoesNotRepost() throws Exception {
        SketchLiveNotification.drawingStarted(context, "portrait", "live_one");
        Notification first = notificationFor("live_one");
        SketchLiveNotification.drawingStarted(context, "portrait", "live_two");
        Notification second = notificationFor("live_two");
        assertNotEquals(first.contentIntent, second.contentIntent);
        assertNotEquals(shadowOf(first.contentIntent).getSavedIntent().getData(),
                shadowOf(second.contentIntent).getSavedIntent().getData());
        assertEquals("live_one", shadowOf(first.contentIntent).getSavedIntent()
                .getStringExtra(SketchLiveNotification.EXTRA_SESSION_ID));
        JSONObject retry = SketchLiveNotification.drawingStarted(context, "portrait", "live_one");
        assertEquals("already_posted", retry.getString("status"));
        assertFalse(retry.getBoolean("posted"));
        assertEquals(2, notifications.size());
        manager.cancelAll();
        assertEquals("already_posted", SketchLiveNotification.drawingStarted(context, "portrait", "live_one").getString("status"));
        assertEquals("Retry must not resurrect a dismissed session notification", 0, notifications.size());
    }

    @Test public void permissionDenialDoesNotPostOrConsumeTheSessionNotification() throws Exception {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        JSONObject denied = SketchLiveNotification.drawingStarted(context, "portrait", "live_one");
        assertEquals("blocked_permission", denied.getString("status"));
        assertFalse(denied.getBoolean("posted"));
        assertEquals(0, notifications.size());
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertEquals("posted", SketchLiveNotification.drawingStarted(context, "portrait", "live_one").getString("status"));
    }

    @Test public void appOrChannelDisablementIsReportedWithoutClaimingAPost() throws Exception {
        notifications.setNotificationsEnabled(false);
        assertEquals("blocked_app_notifications", SketchLiveNotification.drawingStarted(context, "portrait", "live_one").getString("status"));
        notifications.setNotificationsEnabled(true);
        manager.createNotificationChannel(new NotificationChannel(SketchLiveNotification.CHANNEL_ID,
                "Live drawing", NotificationManager.IMPORTANCE_NONE));
        assertEquals("blocked_channel", SketchLiveNotification.drawingStarted(context, "portrait", "live_one").getString("status"));
        assertEquals(0, notifications.size());
    }

    @Test @Config(sdk = 29) public void preAndroidThirteenDoesNotRequirePostNotificationPermission() throws Exception {
        shadowOf(context).denyPermissions(Manifest.permission.POST_NOTIFICATIONS);
        assertEquals("posted", SketchLiveNotification.drawingStarted(context, "portrait", "live_one").getString("status"));
    }

    @Test public void originalDrawingIsLabelledOnlyWhenItsDeclaredMethodIsOriginalStrokes() throws Exception {
        JSONObject result = SketchLiveNotification.drawingStarted(context, "portrait", "original", SketchDrawingPolicy.ORIGINAL);
        assertEquals(SketchDrawingPolicy.ORIGINAL, result.getString("drawingMethod"));
        assertEquals("Original stroke drawing started", notificationFor("original").extras
                .getCharSequence(Notification.EXTRA_TITLE).toString());
        assertEquals("failed", SketchLiveNotification.drawingStarted(context, "portrait", "unknown", "neural_original").getString("status"));
        assertEquals(1, notifications.size());
    }

    private Notification notificationFor(String sessionId) {
        for (Notification notification : notifications.getAllNotifications()) {
            Intent intent = shadowOf(notification.contentIntent).getSavedIntent();
            if (sessionId.equals(intent.getStringExtra(SketchLiveNotification.EXTRA_SESSION_ID))) return notification;
        }
        throw new AssertionError("Missing session notification");
    }
}
