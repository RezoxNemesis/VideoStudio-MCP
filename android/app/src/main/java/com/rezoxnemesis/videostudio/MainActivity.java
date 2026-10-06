package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class MainActivity extends Activity {
    private static final String APP_URL = "https://wispy-queen-f9b5.prakasharuntandon634.workers.dev/?android=1";
    private static final int FILE_CHOOSER_REQUEST = 1201;
    private WebView webView;
    private ValueCallback<Uri[]> filePathCallback;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        webView = new WebView(this);
        setContentView(webView);

        WebSettings settings = webView.getSettings();
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setMediaPlaybackRequiresUserGesture(false);
        settings.setAllowContentAccess(true);
        settings.setAllowFileAccess(true);
        settings.setUserAgentString(settings.getUserAgentString() + " VideoStudioAndroid/0.6.0");

        webView.addJavascriptInterface(new AndroidBridge(), "AndroidNative");
        webView.setWebViewClient(new WebViewClient());
        webView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onShowFileChooser(WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
                if (filePathCallback != null) filePathCallback.onReceiveValue(null);
                filePathCallback = callback;
                try {
                    Intent intent = params.createIntent();
                    intent.addCategory(Intent.CATEGORY_OPENABLE);
                    intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
                    startActivityForResult(intent, FILE_CHOOSER_REQUEST);
                    return true;
                } catch (Exception error) {
                    filePathCallback = null;
                    return false;
                }
            }
        });

        webView.loadUrl(APP_URL);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == FILE_CHOOSER_REQUEST && filePathCallback != null) {
            ArrayList<Uri> uris = new ArrayList<>();
            if (resultCode == RESULT_OK && data != null) {
                if (data.getClipData() != null) {
                    for (int i = 0; i < data.getClipData().getItemCount(); i++) {
                        uris.add(data.getClipData().getItemAt(i).getUri());
                    }
                } else if (data.getData() != null) {
                    uris.add(data.getData());
                }
            }
            filePathCallback.onReceiveValue(uris.isEmpty() ? null : uris.toArray(new Uri[0]));
            filePathCallback = null;
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    @Override
    public void onBackPressed() {
        if (webView != null && webView.canGoBack()) webView.goBack();
        else super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (webView != null) {
            webView.removeJavascriptInterface("AndroidNative");
            webView.destroy();
        }
        super.onDestroy();
    }

    public class AndroidBridge {
        private final Map<String, PendingSave> pending = new ConcurrentHashMap<>();

        @JavascriptInterface
        public String getVersion() {
            return "0.6.0";
        }

        @JavascriptInterface
        public String beginSave(String requestedName, String mimeType) {
            try {
                String safeName = requestedName == null ? "videostudio-render.mp4" : requestedName.replaceAll("[\\/:*?\"<>|]", "-");
                if (safeName.trim().isEmpty()) safeName = "videostudio-render.mp4";
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, safeName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, mimeType == null || mimeType.isEmpty() ? "video/mp4" : mimeType);
                values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/VideoStudio");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                Uri uri = getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) return "";
                OutputStream stream = getContentResolver().openOutputStream(uri, "w");
                if (stream == null) return "";
                String token = UUID.randomUUID().toString();
                pending.put(token, new PendingSave(uri, stream));
                return token;
            } catch (Exception error) {
                return "";
            }
        }

        @JavascriptInterface
        public boolean appendChunk(String token, String base64) {
            PendingSave save = pending.get(token);
            if (save == null) return false;
            try {
                save.stream.write(Base64.decode(base64, Base64.DEFAULT));
                return true;
            } catch (Exception error) {
                abortSave(token);
                return false;
            }
        }

        @JavascriptInterface
        public boolean finishSave(String token) {
            PendingSave save = pending.remove(token);
            if (save == null) return false;
            try {
                save.stream.flush();
                save.stream.close();
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.IS_PENDING, 0);
                getContentResolver().update(save.uri, values, null, null);
                return true;
            } catch (Exception error) {
                try { getContentResolver().delete(save.uri, null, null); } catch (Exception ignored) {}
                return false;
            }
        }

        private void abortSave(String token) {
            PendingSave save = pending.remove(token);
            if (save == null) return;
            try { save.stream.close(); } catch (Exception ignored) {}
            try { getContentResolver().delete(save.uri, null, null); } catch (Exception ignored) {}
        }
    }

    private static class PendingSave {
        final Uri uri;
        final OutputStream stream;
        PendingSave(Uri uri, OutputStream stream) {
            this.uri = uri;
            this.stream = stream;
        }
    }
}
