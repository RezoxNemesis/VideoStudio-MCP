package com.rezoxnemesis.studio;

import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import com.getcapacitor.BridgeActivity;
import com.getcapacitor.BridgeWebViewClient;
import java.util.HashMap;
import java.util.Map;

public class MainActivity extends BridgeActivity {

    /** True while this activity (and its WebView engine) exists; the agent service polls only when it does not. */
    static volatile boolean alive = false;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(NativeTtsPlugin.class);
        registerPlugin(FileSaverPlugin.class);
        registerPlugin(StudioStoragePlugin.class);
        super.onCreate(savedInstanceState);
        alive = true;
        // The WebView renderer keeps full priority when the app is in the background, so agent work keeps going.
        if (android.os.Build.VERSION.SDK_INT >= 26) getBridge().getWebView().setRendererPriorityPolicy(WebView.RENDERER_PRIORITY_IMPORTANT, false);
        if (AgentService.prefs(this).getBoolean("on", false)) AgentService.start(this);

        // Cross-origin isolation lets the WebAssembly fallback use several threads.
        getBridge()
            .setWebViewClient(
                new BridgeWebViewClient(getBridge()) {
                    @Override
                    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                        WebResourceResponse res = super.shouldInterceptRequest(view, request);
                        if (res != null) {
                            Map<String, String> headers = new HashMap<>();
                            if (res.getResponseHeaders() != null) headers.putAll(res.getResponseHeaders());
                            headers.put("Cross-Origin-Opener-Policy", "same-origin");
                            headers.put("Cross-Origin-Embedder-Policy", "credentialless");
                            res.setResponseHeaders(headers);
                        }
                        return res;
                    }
                }
            );
    }

    @Override
    public void onPause() {
        super.onPause();
        // Background agent mode: keep JavaScript timers and network running behind other apps / screen off.
        if (AgentService.prefs(this).getBoolean("on", false)) {
            getBridge().getWebView().onResume();
            getBridge().getWebView().resumeTimers();
        }
    }

    @Override
    public void onDestroy() {
        alive = false;
        super.onDestroy();
    }
}
