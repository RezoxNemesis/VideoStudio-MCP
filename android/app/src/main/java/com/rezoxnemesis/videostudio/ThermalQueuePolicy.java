package com.rezoxnemesis.videostudio;

import android.os.PowerManager;

/**
 * Thermal scheduling for VideoStudio's native queues.
 *
 * This does not change Android's thermal status or bypass OS throttling.
 * Rendering, GPU animation, model inference and encoding remain guarded from
 * SEVERE onward. Bounded file/archive transfers need no GPU and can run until
 * Android reports CRITICAL, so heavy render waits never block ordinary storage
 * work unnecessarily.
 */
public final class ThermalQueuePolicy {
    private ThermalQueuePolicy() {}

    public static boolean shouldPause(JobManager.Kind kind, int status) {
        if (kind == JobManager.Kind.LIGHT) return false;
        if (kind == JobManager.Kind.TRANSFER) {
            return status >= PowerManager.THERMAL_STATUS_CRITICAL;
        }
        return status >= PowerManager.THERMAL_STATUS_SEVERE;
    }

    public static boolean canRunRender(int status) {
        return !shouldPause(JobManager.Kind.HEAVY, status);
    }

    public static boolean canRunTransfer(int status) {
        return !shouldPause(JobManager.Kind.TRANSFER, status);
    }

    public static String waitingMessage(JobManager.Kind kind, int status) {
        if (kind == JobManager.Kind.TRANSFER) {
            return "Android critical thermal level " + status
                    + ": protected file transfer paused; checkpoint preserved";
        }
        return "Android thermal level " + status
                + ": GPU/encoding paused at severe or above; "
                + "interactive editing and eligible file transfers stay available";
    }

    public static String schedulingMode(int status) {
        if (status >= PowerManager.THERMAL_STATUS_CRITICAL)
            return "critical_hold";
        if (status >= PowerManager.THERMAL_STATUS_SEVERE)
            return "render_hold_background_transfer";
        if (status >= PowerManager.THERMAL_STATUS_MODERATE)
            return "adaptive_moderate";
        return "full_speed";
    }
}
