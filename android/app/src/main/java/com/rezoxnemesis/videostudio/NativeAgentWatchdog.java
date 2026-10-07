package com.rezoxnemesis.videostudio;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

/**
 * Best-effort self-rearm for the Native Agent.
 *
 * The cloud MCP control plane remains reachable independently. This watchdog
 * only restores the local execution engine after Android/OEM lifecycle events.
 * It intentionally uses an inexact allow-while-idle alarm so no exact-alarm
 * permission is required.
 */
public final class NativeAgentWatchdog {
    public static final String ACTION_REARM =
            "com.rezoxnemesis.videostudio.REARM_NATIVE_AGENT";
    public static final long HEALTHY_INTERVAL_MS = 15L * 60L * 1000L;
    public static final long RETRY_INTERVAL_MS = 90L * 1000L;
    public static final long TASK_REMOVED_DELAY_MS = 10L * 1000L;
    static final long MIN_DELAY_MS = 5_000L;
    static final long MAX_DELAY_MS = 30L * 60L * 1000L;
    private static final int REQUEST_CODE = 6102;

    private NativeAgentWatchdog() {}

    public static long normalizeDelay(long delayMs) {
        return Math.max(MIN_DELAY_MS, Math.min(MAX_DELAY_MS, delayMs));
    }

    public static boolean shouldRearm(boolean controlPaused) {
        return !controlPaused;
    }

    public static void scheduleHealthy(Context context, String reason) {
        schedule(context, HEALTHY_INTERVAL_MS, reason);
    }

    public static void scheduleRetry(Context context, String reason) {
        schedule(context, RETRY_INTERVAL_MS, reason);
    }

    public static void scheduleTaskRemoved(Context context) {
        schedule(context, TASK_REMOVED_DELAY_MS, "task_removed");
    }

    public static void schedule(Context context, long delayMs, String reason) {
        if (context == null) return;
        try {
            AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            if (alarm == null) return;
            Intent intent = new Intent(context, NativeAgentRecoveryReceiver.class)
                    .setAction(ACTION_REARM)
                    .putExtra("reason", reason == null ? "watchdog" : reason);
            PendingIntent pending = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
            );
            alarm.setAndAllowWhileIdle(
                    AlarmManager.ELAPSED_REALTIME_WAKEUP,
                    SystemClock.elapsedRealtime() + normalizeDelay(delayMs),
                    pending
            );
        } catch (Exception ignored) {}
    }

    public static void cancel(Context context) {
        if (context == null) return;
        try {
            AlarmManager alarm = (AlarmManager) context.getSystemService(Context.ALARM_SERVICE);
            Intent intent = new Intent(context, NativeAgentRecoveryReceiver.class)
                    .setAction(ACTION_REARM);
            PendingIntent pending = PendingIntent.getBroadcast(
                    context,
                    REQUEST_CODE,
                    intent,
                    PendingIntent.FLAG_NO_CREATE | PendingIntent.FLAG_IMMUTABLE
            );
            if (alarm != null && pending != null) {
                alarm.cancel(pending);
                pending.cancel();
            }
        } catch (Exception ignored) {}
    }
}
