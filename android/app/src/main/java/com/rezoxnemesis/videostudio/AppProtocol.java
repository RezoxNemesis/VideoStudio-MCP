package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
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
    public static final String APP_VERSION = "3.4.7";
    /** Stable compatibility URL. APK updates must not change this path. */
    public static final String MCP_PATH = McpConnectionCore.STABLE_MCP_PATH;
    /** Stable registration bootstrap. Runtime requests use the negotiated profile. */
    public static final String API_PREFIX = McpConnectionCore.BOOTSTRAP_API_PREFIX;

    // Keep the existing preference/Keystore namespace to preserve the device identity on update.
    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_DEVICE = "native_device_id";
    private static final String KEY_SECRET = "native_owner_secret";
    private static final String KEY_SEQ = "native_v3_last_seq";
    private static final String KEY_ALIAS = "videostudio_owner_key_v1";
    private static final String KEY_PAUSED = "chatgpt_control_paused";
    private static final String KEY_IDENTITY_RECOVERY_REQUIRED = "native_identity_recovery_required";

    public interface Callback {
        void onConnection(boolean connected, String detail);
        void onCommand(JSONObject command);
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final ExecutorService io = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Callback callback;
    private final McpConnectionCore connectionCore;
    private final CommandOutbox outbox;
    private final OwnerAccessPolicy ownerAccess;
    private final Object outboxLock = new Object();
    private ConnectivityManager connectivity;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean running;
    private volatile String permissionMode = "everything";
    private volatile JSONObject projectSummary = new JSONObject();
    private volatile int consecutiveFailures = 0;
    private final String deviceId;
    private final String ownerKey;
    private final boolean identityRecoveryRequired;
    private final String connectionSession = UUID.randomUUID().toString();

    public AppProtocol(Context context, Callback callback) {
        this.context = context.getApplicationContext();
        this.callback = callback;
        prefs = this.context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        connectionCore = new McpConnectionCore(this.context, APP_VERSION);
        outbox = new CommandOutbox(this.context);
        ownerAccess = new OwnerAccessPolicy(this.context,new ProjectStore(this.context));
        String id = prefs.getString(KEY_DEVICE, "");
        if (id.isEmpty()) {
            id = UUID.randomUUID().toString();
            prefs.edit().putString(KEY_DEVICE, id).apply();
        }
        deviceId = id;
        OwnerLoadResult identity = loadOrCreateOwnerKey();
        ownerKey = identity.ownerKey;
        identityRecoveryRequired = identity.recoveryRequired;
    }

    public String deviceId() { return deviceId; }
    public int protocolVersion() { return connectionCore.selectedProtocol(); }
    public String appVersion() { return APP_VERSION; }
    public long appGeneration() { return connectionCore.appGeneration(); }
    public JSONObject connectionStatus() {
        JSONObject status = connectionCore.status();
        try {
            status.put("pendingResultDeliveries", outbox.count());
            status.put("durableResultDelivery", true);
            status.put("identityRecoveryRequired", identityRecoveryRequired);
        } catch (Exception ignored) {}
        return status;
    }

    public boolean isControlPaused() {
        return prefs.getBoolean(KEY_PAUSED, false);
    }

    public void setControlPaused(boolean paused) {
        prefs.edit().putBoolean(KEY_PAUSED, paused).apply();
        if (!paused) consecutiveFailures = 0;
    }

    public String privateMcpUrl() {
        if (identityRecoveryRequired || ownerKey == null || ownerKey.isEmpty()) return "";
        return BASE + MCP_PATH + ownerKey;
    }

    public String pairingMessage() {
        if (identityRecoveryRequired || ownerKey == null || ownerKey.isEmpty()) {
            return "VideoStudio MCP identity recovery is required. The existing owner credential was not rotated, so no replacement endpoint was created.";
        }
        return "Connect to my private VideoStudio Android Native Agent MCP.\n"
                + "Stable MCP endpoint: " + privateMcpUrl() + "\n"
                + "This device-owned endpoint survives compatible VideoStudio APK upgrades and remains available as the durable control plane while Android sleeps. "
                + "Use app_status first, then use the native app tools autonomously. Native-only work is queued safely and resumes when the executor reconnects. "
                + "All editing/import/analysis/rendering must execute through VideoStudio surfaces. Gallery browsing is never permitted.";
    }

    public JSONObject createHybridBinding(String webDeviceId) throws Exception {
        String id = webDeviceId == null ? "" : webDeviceId.trim();
        if (id.length() < 8) throw new IllegalArgumentException("Studio Web device ID is required");
        JSONObject body = new JSONObject();
        body.put("deviceId", deviceId);
        body.put("webDeviceId", id);
        body.put("appGeneration", connectionCore.appGeneration());
        body.put("protocolVersion", connectionCore.selectedProtocol());
        JSONObject result = request(
                "POST",
                connectionCore.apiPrefix() + "/hybrid/challenge",
                body,
                true,
                connectionCore.requestTimeoutMs()
        );
        if (!result.optBoolean("ok", false) || result.optJSONObject("challenge") == null) {
            throw new IllegalStateException("Hybrid binding challenge was rejected");
        }
        return result;
    }

    public void setLocalState(String permissionMode, JSONObject projectSummary) {
        if (permissionMode != null) this.permissionMode = permissionMode;
        if (projectSummary != null) this.projectSummary = projectSummary;
    }

    public void start() {
        if (running) return;
        if (identityRecoveryRequired || ownerKey == null || ownerKey.isEmpty()) {
            notifyConnection(false, "MCP identity recovery required • existing owner credential was not rotated");
            return;
        }
        running = true;
        try {
            connectivity = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onAvailable(Network network) { registerNow(); }
            };
            if (connectivity != null) connectivity.registerDefaultNetworkCallback(networkCallback);
        } catch (Exception ignored) { networkCallback = null; }
        io.execute(() -> {
            register();
            commandLoop();
        });
        io.execute(this::heartbeatLoop);
    }

    public void stop() {
        running = false;
        if (connectivity != null && networkCallback != null) {
            try { connectivity.unregisterNetworkCallback(networkCallback); } catch (Exception ignored) {}
            networkCallback = null;
        }
        io.shutdownNow();
    }

    public void registerNow() {
        if (identityRecoveryRequired || ownerKey == null || ownerKey.isEmpty()) return;
        if (!running || io.isShutdown()) return;
        io.execute(() -> { register(); flushOutbox(); });
    }

    /**
     * Force the transport back to the permanent bootstrap profile without
     * changing device identity, owner credential, command cursor or projects.
     * Useful when a cached negotiated profile becomes stale after deployment.
     */
    public void forceReconnect() {
        if (identityRecoveryRequired || ownerKey == null || ownerKey.isEmpty()) {
            notifyConnection(false, "MCP identity recovery required • refusing to rotate owner credential");
            return;
        }
        connectionCore.resetNegotiation();
        consecutiveFailures = 0;
        if (!running || io.isShutdown()) return;
        io.execute(() -> { register(); flushOutbox(); });
    }

    /**
     * Legacy-relay fallback only. v3's primary attachment path is direct
     * app ingestion from the temporary source URL supplied to the MCP tool.
     */
    public HttpURLConnection openPrivateHandoff(String handoffId) throws Exception {
        if (handoffId == null || handoffId.trim().isEmpty()) throw new IllegalArgumentException("Missing handoff ID");
        String path = BASE + connectionCore.apiPrefix() + "/handoffs/" + enc(handoffId)
                + "/content?deviceId=" + enc(deviceId)
                + "&appGeneration=" + connectionCore.appGeneration();
        HttpURLConnection c = (HttpURLConnection) new URL(path).openConnection();
        c.setRequestMethod("GET");
        c.setConnectTimeout(15000);
        c.setReadTimeout(60000);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", "*/*");
        c.setRequestProperty("Authorization", "Bearer " + ownerKey);
        c.setRequestProperty("User-Agent", "VideoStudio-Android/" + APP_VERSION + " MCPv3");
        return c;
    }

    public void complete(JSONObject command, JSONObject result, String status) {
        if (command == null) return;
        try {
            if (!outbox.contains(command.optString("id"))) outbox.put(command, result, status);
        } catch (Exception error) {
            notifyConnection(false, "Result persistence failed; command will be retried from its journal");
            return;
        }
        if (!io.isShutdown()) io.execute(this::flushOutbox);
    }

    private void flushOutbox() {
        synchronized (outboxLock) {
            if (!running) return;
            try {
                JSONArray pending = outbox.pending();
                for (int i = 0; i < pending.length() && running; i++) {
                    JSONObject entry = pending.getJSONObject(i);
                    String id = entry.getString("id");
                    JSONObject body = new JSONObject();
                    body.put("deviceId", deviceId);
                    body.put("protocolVersion", connectionCore.selectedProtocol());
                    body.put("appGeneration", connectionCore.appGeneration());
                    body.put("status", entry.getString("status"));
                    body.put("result", entry.getJSONObject("result"));
                    JSONObject receipt = request("POST", connectionCore.apiPrefix() + "/commands/" + enc(id) + "/complete",
                            body, true, connectionCore.requestTimeoutMs());
                    // A 2xx response alone is insufficient: require an actual matching command receipt.
                    JSONObject acknowledged = receipt.optJSONObject("command");
                    if (acknowledged == null || !id.equals(acknowledged.optString("id"))) return;
                    advanceSequence(entry.optLong("seq"));
                    outbox.acknowledge(id);
                }
            } catch (Exception ignored) {
                // Retry durable results on the next heartbeat, including after process death.
            }
        }
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
                long seq = 0; // Reconcile all nonterminal leases, including gaps before a later ack.
                long waitMs = connectionCore.commandWaitMs();
                String path = connectionCore.apiPrefix() + "/commands?deviceId=" + enc(deviceId)
                        + "&after=" + seq + "&wait=" + waitMs
                        + "&appGeneration=" + connectionCore.appGeneration();
                JSONObject data = request("GET", path, null, true, connectionCore.requestTimeoutMs());
                JSONArray commands = data.optJSONArray("commands");
                if (commands != null) {
                    for (int i = 0; i < commands.length(); i++) {
                        JSONObject cmd = commands.optJSONObject(i);
                        if (cmd == null) continue;
                        if (!connectionCore.acceptsCommand(cmd)) continue;
                        String commandStatus = cmd.optString("status");
                        if (!"queued".equals(commandStatus) && !"claimed".equals(commandStatus)) continue;
                        if (callback != null) {
                            JSONObject dispatch = cmd;
                            main.post(() -> {
                                if(running&&!isControlPaused())callback.onCommand(dispatch);
                            });
                        }
                    }
                }
                consecutiveFailures = 0;
                notifyConnection(true, "VideoStudio MCP online • app " + APP_VERSION
                        + " • compat v" + connectionCore.selectedProtocol());
            } catch (Exception error) {
                consecutiveFailures = Math.min(6, consecutiveFailures + 1);
                if (consecutiveFailures >= 3) connectionCore.resetNegotiation();
                long delay = Math.min(30000L, 750L * (1L << consecutiveFailures));
                notifyConnection(false, "MCP reconnecting securely • stable compatibility lane");
                sleep(delay);
            }
        }
    }

    private void heartbeatLoop() {
        while (running) {
            if (!isControlPaused()) { register(); flushOutbox(); }
            sleep(connectionCore.heartbeatMs());
        }
    }

    private JSONObject registrationMeta() throws Exception {
        JSONObject meta = new JSONObject();
        meta.put("name", "VideoStudio Android v3");
        meta.put("platform", "android-native");
        meta.put("appVersion", APP_VERSION);
        meta.put("protocolVersion", connectionCore.selectedProtocol());
        meta.put("featureProtocolMax", 4);
        meta.put("editorSchemaVersion", EditorProtocol.SCHEMA_VERSION);
        meta.put("nativeAgent", "videostudio-v3");
        JSONObject connectionMeta = connectionCore.registrationMeta();
        JSONArray connectionNames = connectionMeta.names();
        if (connectionNames != null) {
            for (int i = 0; i < connectionNames.length(); i++) {
                String key = connectionNames.optString(i);
                meta.put(key, connectionMeta.opt(key));
            }
        }
        meta.put("permissionMode", permissionMode);
        JSONObject ownerScope=ownerAccess.metadata();
        java.util.Iterator<String> scopeKeys=ownerScope.keys();while(scopeKeys.hasNext()){String key=scopeKeys.next();meta.put(key,ownerScope.get(key));}
        meta.put("controlPaused", isControlPaused());
        meta.put("connectionSession", connectionSession);
        meta.put("galleryAccess", false);
        meta.put("directAttachmentIngest", true);
        meta.put("localEngineOwnsProjects", true);
        meta.put("portraitAnimationEngine", "v3.2-articulated-parallax");
        meta.put("onDevicePortraitAi", true);
        meta.put("creativeRuntime", "v3.3");
        meta.put("motionScriptVersion", MotionScriptCompiler.MOTION_SCRIPT_VERSION);
        meta.put("creativeIrVersion", MotionScriptCompiler.CREATIVE_IR_VERSION);
        meta.put("capabilityRegistry", true);
        meta.put("modelPacks", true);
        meta.put("computePlanner", true);
        meta.put("folderScopedCloudWorkspace", true);
        meta.put("projects", projectSummary.optJSONArray("projects") == null ? new JSONArray() : projectSummary.optJSONArray("projects"));
        return meta;
    }

    public JSONObject redeemRebindNow(String token) throws Exception {
        if (token == null || token.trim().length() < 30) {
            throw new IllegalArgumentException("Invalid MCP rebind token");
        }
        if (identityRecoveryRequired || ownerKey == null || ownerKey.isEmpty()) {
            throw new IllegalStateException("MCP owner identity recovery is required");
        }
        JSONObject body = new JSONObject();
        body.put("token", token.trim());
        body.put("deviceId", deviceId);
        body.put("ownerKey", ownerKey);
        body.put("meta", registrationMeta());
        JSONObject result = request("POST", McpConnectionCore.BOOTSTRAP_API_PREFIX + "/rebind",
                body, false, connectionCore.requestTimeoutMs());
        connectionCore.applyRegistrationResponse(result);
        boolean ok = result.optBoolean("ok", false) && result.optBoolean("rebound", false);
        result.put("identityPreserved", true);
        result.put("ownerCredentialPreserved", true);
        result.put("deviceId", deviceId);
        result.put("appVersion", APP_VERSION);
        notifyConnection(ok, ok
                ? "Stable MCP endpoint rebound • app " + APP_VERSION + " • gen " + connectionCore.appGeneration()
                : "Stable MCP rebind was rejected");
        if (!ok) throw new IllegalStateException(result.optString("error", "Stable MCP rebind was rejected"));
        register();
        return result;
    }

    public void redeemRebind(String token) {
        if (token == null || token.trim().length() < 30 || io.isShutdown()) return;
        io.execute(() -> {
            try {
                redeemRebindNow(token);
            } catch (Exception error) {
                notifyConnection(false, "Stable MCP rebind failed");
            }
        });
    }

    private synchronized void register() {
        if (!running || isControlPaused()) return;
        try {
            JSONObject meta = registrationMeta();

            JSONObject body = new JSONObject();
            body.put("deviceId", deviceId);
            body.put("ownerKey", ownerKey);
            body.put("meta", meta);
            JSONObject result = request("POST", McpConnectionCore.BOOTSTRAP_API_PREFIX + "/register",
                    body, false, connectionCore.requestTimeoutMs());
            if (result.optBoolean("staleClient", false)) {
                notifyConnection(false, "Older Native Agent generation rejected • reopen current APK");
                return;
            }
            connectionCore.applyRegistrationResponse(result);
            boolean ok = result.optBoolean("ok", false)
                    && result.optInt("protocolVersion", 0) == connectionCore.selectedProtocol();
            notifyConnection(ok, ok
                    ? "VideoStudio MCP online • app " + APP_VERSION
                        + " • generation " + connectionCore.appGeneration()
                    : "MCP stable registration rejected");
        } catch (Exception error) {
            notifyConnection(false, "MCP stable connection offline");
        }
    }

    private void notifyConnection(boolean connected, String detail) {
        if (callback != null) main.post(() -> callback.onConnection(connected, detail));
    }

    private JSONObject request(String method, String path, JSONObject body, boolean authenticated, int timeoutMs) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(BASE + path).openConnection();
        try {
        c.setRequestMethod(method);
        c.setConnectTimeout(timeoutMs);
        c.setReadTimeout(timeoutMs);
        c.setInstanceFollowRedirects(false);
        c.setRequestProperty("Accept", "application/json");
        c.setRequestProperty("X-VideoStudio-Protocol", String.valueOf(connectionCore.selectedProtocol()));
        c.setRequestProperty("X-VideoStudio-App-Generation", String.valueOf(connectionCore.appGeneration()));
        c.setRequestProperty("X-VideoStudio-Connection-Core", String.valueOf(McpConnectionCore.CORE_VERSION));
        c.setRequestProperty("User-Agent", "VideoStudio-Android/" + APP_VERSION
                + " MCPCompat/" + connectionCore.selectedProtocol());
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
        if (code < 200 || code >= 300) throw new IllegalStateException("HTTP " + code);
        return text.isEmpty() ? new JSONObject() : new JSONObject(text);
        } finally { c.disconnect(); }
    }

    private static String read(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder b = new StringBuilder();
        try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
            char[] chunk = new char[8192]; int count;
            while ((count = reader.read(chunk)) != -1) {
                if (b.length() + count > 8 * 1024 * 1024) throw new IllegalStateException("Relay response exceeds limit");
                b.append(chunk, 0, count);
            }
        }
        return b.toString();
    }

    private static final class OwnerLoadResult {
        final String ownerKey;
        final boolean recoveryRequired;
        OwnerLoadResult(String ownerKey, boolean recoveryRequired) {
            this.ownerKey = ownerKey == null ? "" : ownerKey;
            this.recoveryRequired = recoveryRequired;
        }
    }

    private OwnerLoadResult loadOrCreateOwnerKey() {
        String encrypted = prefs.getString(KEY_SECRET, "");
        if (!encrypted.isEmpty()) {
            try {
                String secret = decrypt(encrypted);
                prefs.edit().putBoolean(KEY_IDENTITY_RECOVERY_REQUIRED, false).apply();
                return new OwnerLoadResult(secret, false);
            } catch (Exception decryptFailure) {
                // Critical rule: an unreadable existing identity must never be
                // replaced silently. Rotation would strand every existing MCP URL.
                prefs.edit().putBoolean(KEY_IDENTITY_RECOVERY_REQUIRED, true).apply();
                return new OwnerLoadResult("", true);
            }
        }

        if (prefs.getBoolean(KEY_IDENTITY_RECOVERY_REQUIRED, false)) {
            return new OwnerLoadResult("", true);
        }

        byte[] raw = new byte[32];
        new SecureRandom().nextBytes(raw);
        String secret = Base64.encodeToString(raw, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        try {
            String stored = encrypt(secret);
            prefs.edit()
                    .putString(KEY_SECRET, stored)
                    .putBoolean(KEY_IDENTITY_RECOVERY_REQUIRED, false)
                    .commit();
            return new OwnerLoadResult(secret, false);
        } catch (Exception encryptionFailure) {
            prefs.edit().putBoolean(KEY_IDENTITY_RECOVERY_REQUIRED, true).apply();
            return new OwnerLoadResult("", true);
        }
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

