package com.rezoxnemesis.studio;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.provider.Settings;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Background agent mode. A small foreground service ("VideoStudio is ready for your agents") that:
 *  - keeps the app process alive and un-frozen, so the in-app agent link keeps working behind other apps and with
 *    the screen off (MainActivity keeps the WebView running while this mode is on);
 *  - when the app has been closed, checks the person's own relay for queued agent work, holds that work, and
 *    wakes the app to run it: it opens the app directly when "Display over other apps" is allowed, otherwise
 *    it shows a notification that opens the app with one tap.
 * It talks only to the relay URL the person configured. It reads nothing from the gallery.
 */
public class AgentService extends Service {
    static final String PREFS = "agent";
    static final String CH_READY = "agent_ready";
    static final String CH_WORK = "agent_work";
    static volatile boolean running = false;
    private Thread thread;
    private volatile boolean stop = false;
    private PowerManager.WakeLock busyLock;

    static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static void start(Context c) {
        Intent i = new Intent(c, AgentService.class);
        if (Build.VERSION.SDK_INT >= 26) c.startForegroundService(i);
        else c.startService(i);
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, AgentService.class));
    }

    private void channels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ready = new NotificationChannel(CH_READY, "Agent ready", NotificationManager.IMPORTANCE_MIN);
        ready.setDescription("Shown while VideoStudio stays ready for your AI agents in the background.");
        nm.createNotificationChannel(ready);
        NotificationChannel work = new NotificationChannel(CH_WORK, "Agent tasks", NotificationManager.IMPORTANCE_HIGH);
        work.setDescription("An agent sent work while VideoStudio was closed.");
        nm.createNotificationChannel(work);
    }

    private PendingIntent openApp() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this, 1, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    private Notification readyNote() {
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_READY) : new Notification.Builder(this);
        return b.setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("VideoStudio is ready for your agents")
            .setContentText("Agent work starts even when the app is in the background.")
            .setOngoing(true)
            .setContentIntent(openApp())
            .build();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        channels();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(7, readyNote(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(7, readyNote());
        } catch (Throwable e) {
            // Android refused a background (re)start; the app starts the service again when it next opens.
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        if (thread == null || !thread.isAlive()) {
            stop = false;
            thread = new Thread(this::loop, "agent-poll");
            thread.start();
        }
        return START_STICKY;
    }

    /** Held while the app runs an agent job in the background (rendering with the screen off). */
    void setBusy(boolean on) {
        if (on) {
            if (busyLock == null) {
                PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
                busyLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "VideoStudio:agent");
                busyLock.setReferenceCounted(false);
            }
            busyLock.acquire(30 * 60 * 1000L);
        } else if (busyLock != null && busyLock.isHeld()) busyLock.release();
    }

    static AgentService instance;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
    }

    private void loop() {
        long backoff = 0;
        while (!stop) {
            try {
                if (backoff > 0) Thread.sleep(backoff);
                SharedPreferences p = prefs(this);
                String relay = p.getString("relay", "");
                String key = p.getString("key", "");
                // While the app itself is alive its own link collects work; only poll when it is gone.
                if (relay.isEmpty() || key.isEmpty() || MainActivity.alive || !p.getString("held", "").isEmpty()) {
                    Thread.sleep(5000);
                    backoff = 0;
                    continue;
                }
                String base = relay.replaceAll("/+$", "");
                HttpURLConnection c = (HttpURLConnection) new URL(base + "/device/" + key + "/next?wait=20").openConnection();
                c.setConnectTimeout(15000);
                c.setReadTimeout(35000);
                int code = c.getResponseCode();
                if (code == 200) {
                    String body = read(c.getInputStream());
                    JSONArray jobs = new JSONObject(body).optJSONArray("jobs");
                    if (jobs != null && jobs.length() > 0) {
                        // Hand the claimed jobs to the app (it runs them as soon as it opens) and wake it.
                        p.edit().putString("held", jobs.toString()).apply();
                        wake(jobs.length());
                    }
                }
                c.disconnect();
                backoff = 0;
            } catch (InterruptedException e) {
                return;
            } catch (Throwable e) {
                backoff = Math.min(60000, Math.max(5000, backoff * 2));
            }
        }
    }

    private void wake(int n) {
        boolean canOpen = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(this);
        if (canOpen) {
            try {
                Intent i = new Intent(this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                startActivity(i);
                return;
            } catch (Throwable ignored) {
                // Fall through to the notification.
            }
        }
        Notification.Builder b = Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, CH_WORK) : new Notification.Builder(this);
        Notification note = b.setSmallIcon(R.drawable.ic_stat_notify)
            .setContentTitle("Your agent sent work")
            .setContentText(n == 1 ? "Tap to open VideoStudio and start it." : n + " tasks are waiting. Tap to start them.")
            .setAutoCancel(true)
            .setContentIntent(openApp())
            .setPriority(Notification.PRIORITY_HIGH)
            .build();
        ((NotificationManager) getSystemService(NOTIFICATION_SERVICE)).notify(8, note);
    }

    private static String read(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        in.close();
        return o.toString("UTF-8");
    }

    @Override
    public void onDestroy() {
        stop = true;
        running = false;
        if (thread != null) thread.interrupt();
        setBusy(false);
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
