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
import java.io.OutputStream;
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
        Long position = call.getLong("position");
        String data = call.getString("data");
        RandomAccessFile raf;
        synchronized (open) {
            raf = open.get(id);
        }
        if (raf == null || position == null || data == null) {
            call.reject("Unknown or closed file");
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
