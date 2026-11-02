package com.rezoxnemesis.studio;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.RandomAccessFile;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Write-only access to shared storage. Renders are written to a private temp file (random access, so the MP4
 * muxer can patch headers), then published into Movies/VideoStudio (or Music, Pictures, Documents) through
 * MediaStore. The app requests no permission to read the gallery and this plugin never reads it.
 */
@CapacitorPlugin(name = "StudioStorage")
public class StudioStoragePlugin extends Plugin {

    private final Map<String, RandomAccessFile> open = new HashMap<>();
    private final Map<String, File> files = new HashMap<>();

    private File tempDir() {
        File d = new File(getContext().getCacheDir(), "renders");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    @PluginMethod
    public void open(PluginCall call) {
        try {
            String id = UUID.randomUUID().toString();
            File f = new File(tempDir(), id + ".part");
            RandomAccessFile raf = new RandomAccessFile(f, "rw");
            raf.setLength(0);
            synchronized (open) {
                open.put(id, raf);
                files.put(id, f);
            }
            JSObject r = new JSObject();
            r.put("id", id);
            call.resolve(r);
        } catch (Throwable e) {
            call.reject("Could not start the file: " + e.getMessage());
        }
    }

    @PluginMethod
    public void write(PluginCall call) {
        String id = call.getString("id");
        // JSON numbers arrive as Integer, Long or Double; PluginCall.getLong only accepts Long.
        Object pv = call.getData().opt("position");
        long position = pv instanceof Number ? ((Number) pv).longValue() : -1L;
        String data = call.getString("data");
        RandomAccessFile raf;
        synchronized (open) {
            raf = open.get(id);
        }
        if (raf == null) {
            call.reject("Unknown or closed file");
            return;
        }
        if (position < 0 || data == null) {
            call.reject("Bad write: position " + pv + ", data " + (data == null ? "missing" : data.length() + " chars"));
            return;
        }
        try {
            byte[] bytes = Base64.decode(data, Base64.DEFAULT);
            synchronized (raf) {
                raf.seek(position);
                raf.write(bytes);
            }
            call.resolve();
        } catch (Throwable e) {
            call.reject("Write failed: " + e.getMessage());
        }
    }

    @PluginMethod
    public void finish(PluginCall call) {
        String id = call.getString("id");
        String name = sanitize(call.getString("name", "VideoStudio.mp4"));
        String mime = call.getString("mime", "video/mp4");
        String collection = call.getString("collection", "Movies");
        RandomAccessFile raf;
        File src;
        synchronized (open) {
            raf = open.remove(id);
            src = files.remove(id);
        }
        if (raf == null || src == null) {
            call.reject("Unknown file");
            return;
        }
        try {
            raf.close();
        } catch (Throwable ignored) {}
        final File source = src;
        getBridge().execute(() -> {
            try {
                JSObject r = publish(source, name, mime, collection);
                source.delete();
                call.resolve(r);
            } catch (Throwable e) {
                source.delete();
                call.reject("Could not save to " + collection + ": " + e.getMessage());
            }
        });
    }

    @PluginMethod
    public void abort(PluginCall call) {
        String id = call.getString("id");
        RandomAccessFile raf;
        File f;
        synchronized (open) {
            raf = open.remove(id);
            f = files.remove(id);
        }
        try {
            if (raf != null) raf.close();
        } catch (Throwable ignored) {}
        if (f != null) f.delete();
        call.resolve();
    }

    /**
     * Download an https file into the app's private cache (no CORS limits, streamed to disk). Used to bring
     * files the agent was given in chat into the project. Never touches shared storage.
     */
    @PluginMethod
    public void download(PluginCall call) {
        final String url = call.getString("url", "");
        if (!url.startsWith("https://")) {
            call.reject("Only https links can be downloaded.");
            return;
        }
        final JSObject headers = call.getObject("headers", new JSObject());
        getBridge().execute(() -> {
            HttpURLConnection c = null;
            try {
                File d = new File(getContext().getCacheDir(), "downloads");
                if (!d.exists()) d.mkdirs();
                File out = new File(d, UUID.randomUUID().toString() + ".bin");
                String next = url;
                for (int hop = 0; hop < 6; hop++) {
                    c = (HttpURLConnection) new URL(next).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(20000);
                    c.setReadTimeout(60000);
                    // Only send our headers to the host we were asked for (redirect targets are signed URLs).
                    if (hop == 0) {
                        java.util.Iterator<String> keys = headers.keys();
                        while (keys.hasNext()) {
                            String k = keys.next();
                            c.setRequestProperty(k, headers.getString(k));
                        }
                    }
                    int code = c.getResponseCode();
                    if (code >= 300 && code < 400) {
                        String loc = c.getHeaderField("Location");
                        c.disconnect();
                        if (loc == null) throw new Exception("Bad redirect");
                        // Hugging Face (and others) answer with relative redirects like "/api/resolve-cache/…".
                        String abs = new URL(new URL(next), loc).toString();
                        if (!abs.startsWith("https://")) throw new Exception("Bad redirect");
                        next = abs;
                        continue;
                    }
                    if (code != 200) throw new Exception("HTTP " + code);
                    long total = c.getContentLengthLong();
                    try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(out)) {
                        byte[] buf = new byte[1 << 16];
                        long got = 0;
                        long lastNote = 0;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            os.write(buf, 0, n);
                            got += n;
                            if (got - lastNote > (4 << 20)) {
                                lastNote = got;
                                JSObject ev = new JSObject();
                                ev.put("bytes", got);
                                ev.put("total", total);
                                notifyListeners("downloadProgress", ev);
                            }
                        }
                    }
                    JSObject r = new JSObject();
                    r.put("path", out.getAbsolutePath());
                    r.put("bytes", out.length());
                    call.resolve(r);
                    return;
                }
                throw new Exception("Too many redirects");
            } catch (Throwable e) {
                call.reject("Download failed: " + e.getMessage());
            } finally {
                if (c != null) c.disconnect();
            }
        });
    }

    @PluginMethod
    public void removeDownload(PluginCall call) {
        String path = call.getString("path", "");
        File f = new File(path);
        File d = new File(getContext().getCacheDir(), "downloads");
        try {
            if (f.getCanonicalPath().startsWith(d.getCanonicalPath())) f.delete();
        } catch (Throwable ignored) {
        }
        call.resolve();
    }

    /** Background agent mode on/off, with the relay the service may use while the app is closed. */
    @PluginMethod
    public void agentMode(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        android.content.SharedPreferences.Editor e = AgentService.prefs(getContext()).edit();
        e.putBoolean("on", on);
        if (call.getString("relay") != null) e.putString("relay", call.getString("relay"));
        if (call.getString("key") != null) e.putString("key", call.getString("key"));
        e.apply();
        if (on) AgentService.start(getContext());
        else AgentService.stop(getContext());
        JSObject r = new JSObject();
        r.put("running", on);
        r.put("canAutoOpen", Build.VERSION.SDK_INT < 23 || android.provider.Settings.canDrawOverlays(getContext()));
        call.resolve(r);
    }

    /** Jobs the background service collected while the app was closed (handed over once). */
    @PluginMethod
    public void takeHeldJobs(PluginCall call) {
        android.content.SharedPreferences p = AgentService.prefs(getContext());
        String held = p.getString("held", "");
        p.edit().remove("held").apply();
        JSObject r = new JSObject();
        r.put("jobs", held);
        call.resolve(r);
    }

    @PluginMethod
    public void agentBusy(PluginCall call) {
        AgentService s = AgentService.instance;
        if (s != null) s.setBusy(Boolean.TRUE.equals(call.getBoolean("on", false)));
        call.resolve();
    }

    /** Opens the system page where the person can allow VideoStudio to open itself for agent work. */
    @PluginMethod
    public void openAutoOpenSettings(PluginCall call) {
        if (Build.VERSION.SDK_INT >= 23) {
            android.content.Intent i = new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:" + getContext().getPackageName()));
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(i);
        }
        call.resolve();
    }

    @PluginMethod
    public void keepAwake(PluginCall call) {
        boolean on = Boolean.TRUE.equals(call.getBoolean("on", false));
        getActivity().runOnUiThread(() -> {
            if (on) getActivity().getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getActivity().getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            call.resolve();
        });
    }

    private JSObject publish(File src, String name, String mime, String collection) throws Exception {
        String dir;
        Uri base;
        switch (collection) {
            case "Music":
                dir = Environment.DIRECTORY_MUSIC;
                base = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
                break;
            case "Pictures":
                dir = Environment.DIRECTORY_PICTURES;
                base = MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                break;
            case "Documents":
                dir = Environment.DIRECTORY_DOCUMENTS;
                base = Build.VERSION.SDK_INT >= 29 ? MediaStore.Files.getContentUri("external") : null;
                break;
            default:
                dir = Environment.DIRECTORY_MOVIES;
                base = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        }
        JSObject r = new JSObject();
        if (Build.VERSION.SDK_INT >= 29 && base != null) {
            ContentResolver cr = getContext().getContentResolver();
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH, dir + "/VideoStudio");
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri uri = cr.insert(base, v);
            if (uri == null) throw new Exception("MediaStore refused the file");
            try (InputStream in = new FileInputStream(src); OutputStream out = cr.openOutputStream(uri)) {
                if (out == null) throw new Exception("Cannot open the destination");
                copy(in, out);
            } catch (Exception e) {
                cr.delete(uri, null, null);
                throw e;
            }
            ContentValues done = new ContentValues();
            done.put(MediaStore.MediaColumns.IS_PENDING, 0);
            cr.update(uri, done, null, null);
            r.put("uri", uri.toString());
            r.put("path", dir + "/VideoStudio/" + name);
        } else {
            // Android 9 and older: the app's own external folder needs no permission.
            File outDir = new File(getContext().getExternalFilesDir(dir), "VideoStudio");
            if (!outDir.exists()) outDir.mkdirs();
            File dest = new File(outDir, name);
            try (InputStream in = new FileInputStream(src); OutputStream out = new java.io.FileOutputStream(dest)) {
                copy(in, out);
            }
            r.put("uri", Uri.fromFile(dest).toString());
            r.put("path", dest.getAbsolutePath());
        }
        return r;
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[1024 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        out.flush();
    }

    private static String sanitize(String n) {
        String s = n.replaceAll("[\\\\/:*?\"<>|]", "_").trim();
        return s.isEmpty() ? "VideoStudio" : s;
    }

    @Override
    protected void handleOnDestroy() {
        synchronized (open) {
            for (RandomAccessFile raf : open.values()) {
                try {
                    raf.close();
                } catch (Throwable ignored) {}
            }
            for (File f : files.values()) f.delete();
            open.clear();
            files.clear();
        }
    }
}
