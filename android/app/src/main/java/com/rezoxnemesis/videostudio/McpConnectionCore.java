package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Locale;

/**
 * Persistent compatibility core for the device-owned VideoStudio MCP link.
 *
 * The private owner credential and device id live outside this class and keep
 * their existing v1 preference/Keystore namespace. This class owns only the
 * connection profile that is safe to evolve across APK updates.
 *
 * Protocol v3 is the durable compatibility lane. Future APKs may add new
 * capabilities and internal runtimes without changing the user's MCP URL.
 * Additive actions travel through the typed tools or app_execute bridge.
 */
public final class McpConnectionCore {
    public static final int CORE_VERSION = 3;
    public static final int WIRE_SCHEMA_VERSION = 1;
    public static final int FEATURE_LEVEL = 3;
    public static final int COMPAT_PROTOCOL = 3;
    public static final int MIN_SUPPORTED_PROTOCOL = 3;
    public static final int MAX_SUPPORTED_PROTOCOL = 3;

    /** Permanent public alias. Do not version this path with APK releases. */
    public static final String STABLE_MCP_PATH = "/app-mcp-v3/";

    /** Permanent bootstrap route. Server negotiation may tune the profile. */
    public static final String BOOTSTRAP_API_PREFIX = "/api/v3/app";

    private static final String PREFS = "videostudio_native_v1";
    private static final String KEY_LAST_APP_VERSION = "mcp_core_last_app_version";
    private static final String KEY_APP_GENERATION = "mcp_core_app_generation";
    private static final String KEY_SELECTED_PROTOCOL = "mcp_core_selected_protocol";
    private static final String KEY_API_PREFIX = "mcp_core_api_prefix";
    private static final String KEY_HEARTBEAT_MS = "mcp_core_heartbeat_ms";
    private static final String KEY_COMMAND_WAIT_MS = "mcp_core_command_wait_ms";
    private static final String KEY_REQUEST_TIMEOUT_MS = "mcp_core_request_timeout_ms";
    private static final String KEY_SERVER_EPOCH = "mcp_core_server_epoch";
    private static final String KEY_ENDPOINT_MODE = "mcp_core_endpoint_mode";
    private static final String KEY_NEGOTIATED_AT = "mcp_core_negotiated_at";

    private final SharedPreferences prefs;
    private final String appVersion;
    private final long appGeneration;

    public McpConnectionCore(Context context, String appVersion) {
        this.prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        this.appVersion = cleanVersion(appVersion);

        String previous = prefs.getString(KEY_LAST_APP_VERSION, "");
        long generation = Math.max(0, prefs.getLong(KEY_APP_GENERATION, 0));
        if (!this.appVersion.equals(previous)) {
            generation++;
            prefs.edit()
                    .putString(KEY_LAST_APP_VERSION, this.appVersion)
                    .putLong(KEY_APP_GENERATION, generation)
                    .apply();
        }
        appGeneration = Math.max(1, generation);

        // Corrupt or future-unsupported cached negotiation must never strand the app.
        int selected = prefs.getInt(KEY_SELECTED_PROTOCOL, COMPAT_PROTOCOL);
        if (!supportsProtocol(selected)) resetNegotiation();
        String prefix = prefs.getString(KEY_API_PREFIX, BOOTSTRAP_API_PREFIX);
        if (!safeApiPrefix(prefix)) resetNegotiation();
    }

    public long appGeneration() {
        return appGeneration;
    }

    public int selectedProtocol() {
        int selected = prefs.getInt(KEY_SELECTED_PROTOCOL, COMPAT_PROTOCOL);
        return supportsProtocol(selected) ? selected : COMPAT_PROTOCOL;
    }

    public String apiPrefix() {
        String prefix = prefs.getString(KEY_API_PREFIX, BOOTSTRAP_API_PREFIX);
        return safeApiPrefix(prefix) ? prefix : BOOTSTRAP_API_PREFIX;
    }

    public long heartbeatMs() {
        return bounded(
                prefs.getLong(KEY_HEARTBEAT_MS, 15000L),
                5000L,
                60000L,
                15000L
        );
    }

    public long commandWaitMs() {
        return bounded(
                prefs.getLong(KEY_COMMAND_WAIT_MS, 18000L),
                3000L,
                20000L,
                18000L
        );
    }

    public int requestTimeoutMs() {
        return (int) bounded(
                prefs.getLong(KEY_REQUEST_TIMEOUT_MS, 28000L),
                10000L,
                65000L,
                28000L
        );
    }

    public JSONObject registrationMeta() {
        JSONObject out = new JSONObject();
        try {
            out.put("connectionCoreVersion", CORE_VERSION);
            out.put("appGeneration", appGeneration);
            out.put("protocolFamily", "videostudio-native");
            out.put("protocolMin", MIN_SUPPORTED_PROTOCOL);
            out.put("protocolMax", MAX_SUPPORTED_PROTOCOL);
            out.put("preferredProtocol", COMPAT_PROTOCOL);
            out.put("stableMcpEndpoint", true);
            out.put("stableMcpPath", STABLE_MCP_PATH);
            out.put("bootstrapApiPrefix", BOOTSTRAP_API_PREFIX);
            out.put("additiveActionBridge", true);
            out.put("wireSchemaVersion", WIRE_SCHEMA_VERSION);
            out.put("featureLevel", FEATURE_LEVEL);
            out.put("compatibilityPolicy", "stable-major-additive-features");
            out.put("transportDecoupledFromApkVersion", true);
            out.put("unknownAdditiveActionsMayUseExecuteBridge", true);
            out.put("upgradeKeepsDeviceIdentity", true);
            out.put("upgradeKeepsOwnerCredential", true);
            out.put("hybridBindingCompatible", true);
            out.put("hybridBindingSurvivesApkUpgrade", true);
            out.put("hybridMcpPath", "/mcp-v06/");
            out.put("hybridBindingPolicy", "stable-private-capability");
        } catch (Exception ignored) {}
        return out;
    }

    /**
     * Apply only allow-listed server negotiation fields. A compromised or
     * malformed response cannot redirect the app to a different origin.
     */
    public synchronized void applyRegistrationResponse(JSONObject response) {
        if (response == null) return;
        JSONObject connection = response.optJSONObject("connection");
        if (connection == null) return;

        int selected = connection.optInt("selectedProtocol", COMPAT_PROTOCOL);
        if (!supportsProtocol(selected)) return;

        String prefix = connection.optString("apiPrefix", BOOTSTRAP_API_PREFIX);
        if (!safeApiPrefix(prefix)) return;

        long heartbeat = bounded(connection.optLong("heartbeatMs", 15000L),
                5000L, 60000L, 15000L);
        long wait = bounded(connection.optLong("commandWaitMs", 18000L),
                3000L, 20000L, 18000L);
        long timeout = bounded(connection.optLong("requestTimeoutMs", 28000L),
                10000L, 65000L, 28000L);

        prefs.edit()
                .putInt(KEY_SELECTED_PROTOCOL, selected)
                .putString(KEY_API_PREFIX, prefix)
                .putLong(KEY_HEARTBEAT_MS, heartbeat)
                .putLong(KEY_COMMAND_WAIT_MS, wait)
                .putLong(KEY_REQUEST_TIMEOUT_MS, timeout)
                .putString(KEY_SERVER_EPOCH, clean(connection.optString("serverEpoch", ""), 120))
                .putString(KEY_ENDPOINT_MODE, clean(connection.optString("endpointMode", "stable-compatibility"), 80))
                .putLong(KEY_NEGOTIATED_AT, System.currentTimeMillis())
                .apply();
    }

    public synchronized void resetNegotiation() {
        prefs.edit()
                .putInt(KEY_SELECTED_PROTOCOL, COMPAT_PROTOCOL)
                .putString(KEY_API_PREFIX, BOOTSTRAP_API_PREFIX)
                .putLong(KEY_HEARTBEAT_MS, 15000L)
                .putLong(KEY_COMMAND_WAIT_MS, 18000L)
                .putLong(KEY_REQUEST_TIMEOUT_MS, 28000L)
                .putString(KEY_SERVER_EPOCH, "")
                .putString(KEY_ENDPOINT_MODE, "stable-compatibility")
                .putLong(KEY_NEGOTIATED_AT, 0L)
                .apply();
    }

    public boolean acceptsCommand(JSONObject command) {
        if (command == null) return false;
        int version = command.optInt("protocolVersion", selectedProtocol());
        return version == selectedProtocol() && supportsProtocol(version);
    }

    public JSONObject status() {
        JSONObject out = new JSONObject();
        try {
            out.put("coreVersion", CORE_VERSION);
            out.put("appVersion", appVersion);
            out.put("appGeneration", appGeneration);
            out.put("stableMcpEndpoint", true);
            out.put("stableMcpPath", STABLE_MCP_PATH);
            out.put("selectedProtocol", selectedProtocol());
            out.put("protocolMin", MIN_SUPPORTED_PROTOCOL);
            out.put("protocolMax", MAX_SUPPORTED_PROTOCOL);
            out.put("apiPrefix", apiPrefix());
            out.put("heartbeatMs", heartbeatMs());
            out.put("commandWaitMs", commandWaitMs());
            out.put("requestTimeoutMs", requestTimeoutMs());
            out.put("serverEpoch", prefs.getString(KEY_SERVER_EPOCH, ""));
            out.put("endpointMode", prefs.getString(KEY_ENDPOINT_MODE, "stable-compatibility"));
            out.put("negotiatedAt", prefs.getLong(KEY_NEGOTIATED_AT, 0L));
            out.put("wireSchemaVersion", WIRE_SCHEMA_VERSION);
            out.put("featureLevel", FEATURE_LEVEL);
            out.put("compatibilityPolicy", "stable-major-additive-features");
            out.put("transportDecoupledFromApkVersion", true);
            out.put("identitySurvivesAppUpdate", true);
            out.put("hybridBindingCompatible", true);
            out.put("hybridBindingSurvivesApkUpgrade", true);
            out.put("hybridMcpPath", "/mcp-v06/");
            out.put("hybridBindingPolicy", "stable-private-capability");
            out.put("galleryAccess", false);
        } catch (Exception ignored) {}
        return out;
    }

    private static boolean supportsProtocol(int value) {
        return value >= MIN_SUPPORTED_PROTOCOL && value <= MAX_SUPPORTED_PROTOCOL;
    }

    private static boolean safeApiPrefix(String value) {
        if (value == null) return false;
        String v = value.trim();
        return v.matches("^/api/v[0-9]+/app$");
    }

    private static long bounded(long value, long min, long max, long fallback) {
        if (value < min || value > max) return fallback;
        return value;
    }

    private static String cleanVersion(String value) {
        String v = value == null ? "" : value.trim();
        if (v.isEmpty()) return "0.0.0";
        return clean(v.toLowerCase(Locale.US), 40);
    }

    private static String clean(String value, int max) {
        String v = value == null ? "" : value.trim();
        if (v.length() > max) v = v.substring(0, max);
        return v;
    }
}

