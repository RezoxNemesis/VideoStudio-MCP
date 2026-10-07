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

/**
 * VideoStudio Native Agent protocol v3.
 *
 * Important: the app keeps its existing device id and Keystore-protected owner
 * secret during the v1 -> v3 upgrade. Only the protocol namespace changes.
 * That gives v3 a clean command queue without breaking the device-owned trust
 * relationship or requiring a new Gallery permission.
 */
public final class AppProtocol {
    public static final String BASE = "https://wispy-queen-f9b5.prakasharuntandon634.workers.dev";
    public static final int PROTOCOL_VERSION = 3;
    public static final String APP_VERSION = "3.2.0";
    public static final String MCP_PATH = "/app-mcp-v3/";
    public static final String API_PREFIX = "/api/v3/app";

    // Keep the existing preference/Keystore namespace to preserve the device identity on update.
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_DEVICE = "native_device_id";
    private static final String KEY_SECRET = "native_owner_secret";
    private static final String KEY_SEQ = "native_v3_last_seq";
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
    private volatile String permissionMode = "everything";
    private volatile JSONObject projectSummary = new JSONObject();
    private volatile int consecutiveFailures = 0;
    private final String deviceId;
    private final String ownerKey;
    private final String connectionSession = UUID.randomUUID().toString();

    public AppProtocol(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        String id = prefs.getString(KEY_DEVICE, "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE, id).apply();
        }
        deviceId = id;
        ownerKey = loadOrCreateOwnerKey();
    }

    public String deviceId() { return deviceId; }
    public int protocolVersion() { return PROTOCOL_VERSION; }
    public String appVersion() { return APP_VERSION; }

    public boolean isControlPaused() {
        return prefs.getBoolean(KEY_PAUSED, false);
    }

    public void setControlPaused(boolean paused) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply();
        if (!paused) consecutiveFailures = 0;
    }

    public String privateMcpUrl() {
        return BASE + MCP_PATH + ownerKey;
    }

    public String pairingMessage() {
        return "Connect to my private VideoStudio v3 Android Native Agent MCP.\n"
                + "MCP v3 endpoint: " + privateMcpUrl() + "\n"
                + "This is the device-owned VideoStudio v3 connection. Use app_status first, then use the native app tools autonomously. "
                + "All editing/import/analysis/rendering must execute inside VideoStudio. Gallery browsing is never permitted.";
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
        if (!running || io.isShutdown()) return;
        io.execute(this::register);
    }

    /**
     * Legacy-relay fallback only. v3's primary attachment path is direct
     * app ingestion from the temporary source URL supplied to the MCP tool.
     */
    public HttpURLConnection openPrivateHandoff(String handoffId) throws Exception {
        if (handoffId == null || handoffId.trim().isEmpty()) throw new IllegalArgumentException("Missing handoff ID");
        String path = BASE + API_PREFIX + "/handoffs/" + enc(handoffId) + "/content?deviceId=" + enc(deviceId);
        HttpURLConnection c = (HttpURLConnection) new URL(path).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Authorization", "Bearer " + ownerKey);
        c.setRequestProperty("User-Agent", "VideoStudio-Android/" + APP_VERSION + " MCPv3");
        return c;
    }

    public void complete(JSONObject command, JSONObject result, String status) {
        if (command == null || io.isShutdown()) return;
        io.execute(() -> {
            try {
                String id = command.optString("id");
                JSONObject body = new JSONObject();
                body.put("deviceId", deviceId);
                body.put("protocolVersion", PROTOCOL_VERSION);
                body.put("status", status == null ? "completed" : status);
                body.put("result", result == null ? new JSONObject() : result);
                request("POST", API_PREFIX + "/commands/" + enc(id) + "/complete", body, true, 18000);
                advanceSequence(command.optLong("seq", 0));
            } catch (Exception ignored) {
                // The server lease will make the command available again. The
                // local CommandJournal prevents duplicate execution on retry.
            }
        });
    }

    private void advanceSequence(long seq) {
        if (seq <= 0) return;
        synchronized (prefs) {
            long current = prefs.getLong(KEY_SEQ, 0);
            if (seq > current) prefs.edit().putLong(KEY_SEQ, seq).apply();
        }
    }

    private void commandLoop() {
        while (running) {
            try {
                if (isControlPaused()) {
                    notifyConnection(false, "VideoStudio MCP v3 paused");
                    sleep(900);
                    continue;
                }
                long seq = prefs.getLong(KEY_SEQ, 0);
                String path = API_PREFIX + "/commands?deviceId=" + enc(deviceId)
                        + "&after=" + seq + "&wait=18000";
                JSONObject data = request("GET", path, null, true, 26000);
                JSONArray commands = data.optJSONArray("commands");
                if (commands != null) {
                    for (int i = 0; i < commands.length(); i++) {
                        JSONObject cmd = commands.optJSONObject(i);
                        if (cmd == null) continue;
                        if (cmd.optInt("protocolVersion", PROTOCOL_VERSION) != PROTOCOL_VERSION) continue;
                        String commandStatus = cmd.optString("status");
                        if (!"queued".equals(commandStatus) && !"claimed".equals(commandStatus)) continue;
                        if (callback != null) {
                            JSONObject dispatch = cmd;
                            main.post(() -> callback.onCommand(dispatch));
                        }
                    }
                }
                consecutiveFailures = 0;
                notifyConnection(true, "VideoStudio MCP v3 online");
            } catch (Exception error) {
                consecutiveFailures = Math.min(6, consecutiveFailures + 1);
                long delay = Math.min(30000L, 750L * (1L << consecutiveFailures));
                notifyConnection(false, "MCP v3 reconnecting securely");
                sleep(delay);
            }
        }
    }

    private void heartbeatLoop() {
        while (running) {
            if (!isControlPaused()) register();
            sleep(15000);
        }
    }

    private void register() {
        try {
            JSONObject meta = new JSONObject();
            meta.put("name", "VideoStudio Android v3");
            meta.put("platform", "android-native");
            meta.put("appVersion", APP_VERSION);
            meta.put("protocolVersion", PROTOCOL_VERSION);
            meta.put("nativeAgent", "videostudio-v3");
            meta.put("permissionMode", permissionMode);
            meta.put("controlPaused", isControlPaused());
            meta.put("connectionSession", connectionSession);
            meta.put("galleryAccess", false);
            meta.put("directAttachmentIngest", true);
            meta.put("localEngineOwnsProjects", true);
            meta.put("portraitAnimationEngine", "v3.2-articulated-parallax");
            meta.put("onDevicePortraitAi", true);
            meta.put("projects", projectSummary.optJSONArray("projects") == null ? new JSONArray() : projectSummary.optJSONArray("projects"));

            JSONObject body = new JSONObject();
            body.put("deviceId", deviceId);
            body.put("ownerKey", ownerKey);
            body.put("meta", meta);
            JSONObject result = request("POST", API_PREFIX + "/register", body, false, 18000);
            boolean ok = result.optBoolean("ok", false)
                    && result.optInt("protocolVersion", 0) == PROTOCOL_VERSION;
            notifyConnection(ok, ok ? "VideoStudio MCP v3 online" : "MCP v3 registration rejected");
        } catch (Exception error) {
            notifyConnection(false, "MCP v3 offline");
        }
    }

    private void notifyConnection(boolean connected, String detail) {
        if (callback != null) main.post(() -> callback.onConnection(connected, detail));
    }

    private JSONObject request(String method, String path, JSONObject body, boolean authenticated, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(true);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-VideoStudio-Protocol", "3");
        c.setRequestProperty("User-Agent", "VideoStudio-Android/" + APP_VERSION + " MCPv3");
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
        } catch (Exception ignored) {
            // Never fall back to plaintext persistent storage.
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
        try { Thread.sleep(ms); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
    }
}
