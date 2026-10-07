package com.rezoxnemesis.videostudio;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

/**
 * Re-arms the VideoStudio Native Agent after an in-place APK replacement or
 * device reboot. The stable MCP owner identity remains in the existing
 * app-private preferences/Android Keystore, so no connector recreation is
 * required for compatible upgrades.
 */
public final class NativeAgentRecoveryReceiver extends BroadcastReceiver {
    private static final String PREFS = "videostudio_native_v1";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (context == null) return;
        String action = intent == null ? "" : intent.getAction();
        if (!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                && !Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            return;
        }

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        prefs.edit()
                .putBoolean("control_service_online", false)
                .putString("control_service_detail",
                        Intent.ACTION_MY_PACKAGE_REPLACED.equals(action)
                                ? "APK updated • rebinding stable MCP Native Agent"
                                : "Device restarted • restoring stable MCP Native Agent")
                .putString("control_service_requested_app_version", AppProtocol.APP_VERSION)
                .putLong("control_service_recovery_requested_at", System.currentTimeMillis())
                .apply();

        try {
            Intent service = new Intent(context, ControlService.class)
                    .setAction(ControlService.ACTION_SYNC);
            context.startForegroundService(service);
        } catch (Exception error) {
            prefs.edit()
                    .putBoolean("control_service_online", false)
                    .putString("control_service_detail",
                            "Stable MCP recovery deferred until VideoStudio opens")
                    .putLong("control_service_heartbeat", System.currentTimeMillis())
                    .apply();
        }
    }
}
