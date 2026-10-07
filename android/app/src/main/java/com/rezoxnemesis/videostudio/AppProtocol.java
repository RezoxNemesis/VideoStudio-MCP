package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class AppProtocol {
    public static final String BASE = "https://wispy-queen-f9b5.prakasharuntandon634.workers.dev";
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_DEVICE = "native_device_id";
    private static final String KEY_SECRET = "native_owner_secret";
    private static final String KEY_SEQ = "native_last_seq";
    private static final String KEY_ALIAS = "videostudio_owner_key_v1";
    private static final String KEY_PAUSED = "chatgpt_control_paused";

    public interface Callback {
        void onConnection(boolean connected, String detail);
        void onCommand(JSONObject command);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private volatile boolean running;
    private volatile String permissionMode = "all_tools";
    private volatile JSONObject projectSummary = new JSONObject();
    private volatile int consecutiveFailures = 0;
    private final String deviceId;
    private final String ownerKey;
    private final String connectionSession = UUID.randomUUID().toString();

    public AppProtocol(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_DEVICE, "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE, id).apply();
        }
        deviceId = id;
        ownerKey = loadOrCreateOwnerKey();
    }

    public String deviceId() { return deviceId; }

    public boolean isControlPaused() {
        return prefs.getBoolean(KEY_PAUSED, false);
    }

    public void setControlPaused(boolean paused) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply();
        if (!paused) {
            consecutiveFailures = 0;
            registerNow();
        }
    }

    public String privateMcpUrl() {
        return BASE + "/app-mcp/" + ownerKey;
    }

    public String pairingMessage() {
        return "Connect to my private VideoStudio Android App MCP.\n"
                + "MCP endpoint: " + privateMcpUrl() + "\n"
                + "This endpoint is the device-owned private connection. Use app_status first, then use the native app tools autonomously. Gallery browsing is never permitted.";
    }

    public void setLocalState(String permissionMode, JSONObject projectSummary) {
        if (permissionMode != null) this.permissionMode = permissionMode;
        if (projectSummary != null) this.projectSummary = projectSummary;
    }

    public void start() {
        if (running) return;
        running = true;
        io.execute(() -> {
            register();
            commandLoop();
        });
        io.execute(this::heartbeatLoop);
    }

    public void stop() {
        running = false;
        io.shutdownNow();
    }

    public void registerNow() {
        io.execute(this::register);
    }

    public HttpURLConnection openPrivateHandoff(String handoffId) throws Exception {
        if (handoffId == null || handoffId.trim().isEmpty()) throw new IllegalArgumentException("Missing handoff ID");
        String path = BASE + "/api/app/handoffs/" + enc(handoffId) + "/content?deviceId=" + enc(deviceId);
        HttpURLConnection c = (HttpURLConnection) new URL(path).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(45000);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Authorization", "Bearer " + ownerKey);
        c.setRequestProperty("User-Agent", "VideoStudio-Android/1.1.0");
        return c;
    }

    public void complete(JSONObject command, JSONObject result, String status) {
        io.execute(() -> {
            try {
                String id = command.optString("id");
                JSONObject body = new JSONObject();
                body.put("deviceId", deviceId);
                body.put("status", status == null ? "completed" : status);
                body.put("result", result == null ? new JSONObject() : result);
                request("POST", "/api/app/commands/" + id + "/complete", body, true, 15000);
                long seq = command.optLong("seq", 0);
                synchronized (prefs) {
                    long current = prefs.getLong(KEY_SEQ, 0);
                    if (seq > current) prefs.edit().putLong(KEY_SEQ, seq).apply();
                }
            } catch (Exception ignored) {}
        });
    }

    private void commandLoop() {
        long backoffMs = 900;
        while (running) {
            try {
                if (isControlPaused()) {
                    main.post(() -> callback.onConnection(false, "ChatGPT control paused"));
                    sleep(900);
                    continue;
                }
                long seq = prefs.getLong(KEY_SEQ, 0);
                String path = "/api/app/commands?deviceId=" + enc(deviceId) + "&after=" + seq + "&wait=18000";
                JSONObject data = request("GET", path, null, true, 25000);
                JSONArray commands = data.optJSONArray("commands");
                if (commands == null) continue;
                for (int i = 0; i < commands.length(); i++) {
                    JSONObject cmd = commands.optJSONObject(i);
                    if (cmd == null) continue;
                    String commandStatus = cmd.optString("status");
                    if (!"queued".equals(commandStatus) && !"claimed".equals(commandStatus)) continue;
                    JSONObject dispatch = cmd;
                    if (callback != null) main.post(() -> callback.onCommand(dispatch));
                }
                consecutiveFailures = 0;
                main.post(() -> callback.onConnection(true, "Private App MCP online"));
            } catch (Exception error) {
                consecutiveFailures = Math.min(6, consecutiveFailures + 1);
                long delay = Math.min(30000L, 900L * (1L << consecutiveFailures));
                main.post(() -> callback.onConnection(false, "Reconnecting securely"));
                sleep(delay);
            }
        }
    }

    private void heartbeatLoop() {
        while (running) {
            if (!isControlPaused()) register();
            sleep(20000);
        }
    }

    private void register() {
        try {
            JSONObject meta = new JSONObject();
            meta.put("name", "VideoStudio Android");
            meta.put("platform", "android-native");
            meta.put("appVersion", "1.1.0");
            meta.put("permissionMode", permissionMode);
            meta.put("controlPaused", isControlPaused());
            meta.put("connectionSession", connectionSession);
            meta.put("galleryAccess", false);
            meta.put("projects", projectSummary.optJSONArray("projects") == null ? new JSONArray() : projectSummary.optJSONArray("projects"));

            JSONObject body = new JSONObject();
            body.put("deviceId", deviceId);
            body.put("ownerKey", ownerKey);
            body.put("meta", meta);
            JSONObject result = request("POST", "/api/app/register", body, false, 15000);
            boolean ok = result.optBoolean("ok", false);
            if (callback != null) main.post(() -> callback.onConnection(ok, ok ? "Private App MCP online" : "MCP registration failed"));
        } catch (Exception error) {
            if (callback != null) main.post(() -> callback.onConnection(false, "Offline"));
        }
    }

    private JSONObject request(String method, String path, JSONObject body, boolean authenticated, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setRequestProperty("Accept", "application/json");
        if (authenticated) c.setRequestProperty("Authorization", "Bearer " + ownerKey);
        if (body != null) {
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = c.getOutputStream()) { out.write(bytes); }
        }
        int code = c.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
        String text = read(stream);
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code + " " + text);
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder b = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) b.append(line);
        }
        return b.toString();
    }

    private String loadOrCreateOwnerKey() {
        String encrypted = prefs.getString(KEY_SECRET, "");
        if (!encrypted.isEmpty()) {
            try { return decrypt(encrypted); } catch (Exception ignored) {}
        }
        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String secret = Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        try {
            prefs.edit().putString(KEY_SECRET, encrypt(secret)).apply();
        } catch (Exception error) {
            // Keystore failures are rare. Keep the key only for this process rather than storing plaintext.
        }
        return secret;
    }

    private SecretKey keystoreKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(KEY_ALIAS)) {
            return ((KeyStore.SecretKeyEntry) store.getEntry(KEY_ALIAS, null)).getSecretKey();
        }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return generator.generateKey();
    }

    private String encrypt(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, keystoreKey());
        byte[] iv = cipher.getIV();
        byte[] enc = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] packed = new byte[1 + iv.length + enc.length];
        packed[0] = (byte) iv.length;
        System.arraycopy(iv, 0, packed, 1, iv.length);
        System.arraycopy(enc, 0, packed, 1 + iv.length, enc.length);
        return Base64.encodeToString(packed, Base64.NO_WRAP);
    }

    private String decrypt(String value) throws Exception {
        byte[] packed = Base64.decode(value, Base64.NO_WRAP);
        int ivLen = packed[0] & 0xff;
        byte[] iv = new byte[ivLen];
        byte[] enc = new byte[packed.length - 1 - ivLen];
        System.arraycopy(packed, 1, iv, 0, ivLen);
        System.arraycopy(packed, 1 + ivLen, enc, 0, enc.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, keystoreKey(), new GCMParameterSpec(128, iv));
        return new String(cipher.doFinal(enc), StandardCharsets.UTF_8);
    }

    private static String enc(String value) {
        try { return java.net.URLEncoder.encode(value, "UTF-8"); }
        catch (Exception ignored) { return value; }
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
