package com.rezoxnemesis.videostudio;

import android.os.PowerManager;

/**
 * Thermal scheduling for VideoStudio's native queues.
 *
 * This does not change Android's thermal status or bypass OS throttling.
 * User-directed rendering continues at SEVERE under Android throttling.
 * No app-level thermal queue is imposed; Android retains final thermal control.
 * Interactive drawing and ordinary edits never enter this thermal scheduler.
 */
public final class ThermalQueuePolicy {
    private ThermalQueuePolicy() {}

    public static boolean shouldPause(JobManager.Kind kind, int status) {
        // The owner selected direct execution. Android owns throttling and shutdown.
        // Never turn a sensor reading into an app-level parked render or edit.
        return false;
    }

    public static boolean canRunRender(int status) {
        return !shouldPause(JobManager.Kind.HEAVY, status);
    }

    public static boolean canRunTransfer(int status) {
        return !shouldPause(JobManager.Kind.TRANSFER, status);
    }

    public static String waitingMessage(JobManager.Kind kind, int status) {
        return "Android thermal level "+status+"; native work continues under system throttling";
    }

    public static String schedulingMode(int status) {
        if (status >= PowerManager.THERMAL_STATUS_CRITICAL)
            return "system_throttled";
        if (status >= PowerManager.THERMAL_STATUS_SEVERE)
            return "system_throttled";
        if (status >= PowerManager.THERMAL_STATUS_MODERATE)
            return "adaptive_moderate";
        return "full_speed";
    }
}
