package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;

/** The same bounded result is used on first delivery and every durable replay. */
public final class NativeCommandResults {
    public static final int MAX_RESULT_BYTES = 512 * 1024;
    public static final int MAX_JSON_DEPTH = 64;
    private NativeCommandResults() { }

    public static String canonical(Object value) throws Exception {
        CanonicalSink sink = new CanonicalSink(MAX_RESULT_BYTES + 4096);
        writeCanonical(value, 0, MAX_JSON_DEPTH + 2, sink);
        if (sink.bytes > sink.captureLimit) throw new IllegalArgumentException("Canonical receipt exceeds its bounded comparison budget");
        return sink.captured.toString();
    }

    private static final class CanonicalSink {
        final int captureLimit;
        final MessageDigest digest;
        final StringBuilder captured = new StringBuilder();
        long bytes, visited;
        CanonicalSink(int captureLimit) throws Exception { this.captureLimit = captureLimit; digest = MessageDigest.getInstance("SHA-256"); }
        void append(String text) {
            byte[] encoded = text.getBytes(StandardCharsets.UTF_8);
            bytes += encoded.length;
            if (bytes > 16L * 1024 * 1024) throw new IllegalArgumentException("Full result encoding exceeds its 16 MiB hashing budget");
            digest.update(encoded);
            if (bytes <= captureLimit) captured.append(text);
        }
        void quoted(String value) {
            if (value.length() > 16 * 1024 * 1024) throw new IllegalArgumentException("Result string exceeds its bounded hashing budget");
            append(JSONObject.quote(value));
        }
    }

    private static void writeCanonical(Object value, int depth, int maximumDepth, CanonicalSink sink) throws Exception {
        if (++sink.visited > 1024 * 1024) throw new IllegalArgumentException("Result JSON exceeds its bounded value count");
        if (value == null || value == JSONObject.NULL) { sink.append("null"); return; }
        if ((value instanceof JSONObject || value instanceof JSONArray) && depth >= maximumDepth)
            throw new IllegalArgumentException("Command JSON exceeds the 64-level nesting budget");
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            ArrayList<String> keys = new ArrayList<>();
            Iterator<String> iterator = object.keys();
            while (iterator.hasNext()) keys.add(iterator.next());
            Collections.sort(keys);
            sink.append("{"); boolean first = true;
            for (String key : keys) {
                if (!first) sink.append(","); first = false;
                sink.quoted(key); sink.append(":"); writeCanonical(object.get(key), depth + 1, maximumDepth, sink);
            }
            sink.append("}"); return;
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            sink.append("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) sink.append(",");
                writeCanonical(array.get(i), depth + 1, maximumDepth, sink);
            }
            sink.append("]"); return;
        }
        if (value instanceof String) { sink.quoted((String) value); return; }
        if (value instanceof Number) { sink.append(JSONObject.numberToString((Number) value)); return; }
        if (value instanceof Boolean) { sink.append(value.toString()); return; }
        throw new IllegalArgumentException("Command results must contain JSON values");
    }

    public static JSONObject bounded(JSONObject value, String actualStatus) throws Exception {
        JSONObject result = value == null ? new JSONObject() : value;
        CanonicalSink sink = new CanonicalSink(MAX_RESULT_BYTES); String encodingError = "";
        try { writeCanonical(result, 0, MAX_JSON_DEPTH, sink); }
        catch (Exception error) { encodingError = error.getMessage() == null ? "Result encoding failed" : error.getMessage(); }
        boolean encoded = encodingError.isEmpty();
        if (encoded && sink.bytes <= MAX_RESULT_BYTES) return new JSONObject(sink.captured.toString());
        boolean finalWork = result.optBoolean("completed", false) || result.optBoolean("verifiedPlayableOutput", false)
                || result.optBoolean("actualRendered", false);
        JSONObject receipt = new JSONObject().put("ok", result.optBoolean("ok", false))
                .put("resultTruncated", true).put("resultDeliveryComplete", false)
                .put("code", encoded ? "result_payload_limit" : "result_encoding_limit").put("maximumResultBytes", MAX_RESULT_BYTES)
                .put("actualCommandProcessed", true).put("actualCommandStatus", actualStatus == null ? "completed" : actualStatus)
                .put("actualOperationStatus", result.optBoolean("queued", false) ? "queued"
                        : result.optString("jobState", finalWork ? "completed" : "unknown"))
                .put("actualOperationCompleted", "completed".equals(actualStatus) && result.optBoolean("ok", false)
                        && finalWork && !result.optBoolean("queued", false) && !result.optBoolean("accepted", false))
                .put("message", "The command was processed, but its full result exceeds the 512 KiB transport budget. Read a smaller project page or compact diagnostics; the operation has not been repeated.");
        if (encoded) {
            byte[] digest = sink.digest.digest();
            StringBuilder sha = new StringBuilder(64);
            for (byte part : digest) sha.append(String.format(java.util.Locale.US, "%02x", part & 255));
            receipt.put("fullResultBytes", sink.bytes).put("fullResultSha256", sha.toString());
        } else receipt.put("encodingError", encodingError).put("fullResultSha256Available", false)
                .put("message", "The command was processed, but its full result exceeds the bounded JSON nesting/encoding contract. The bounded outcome receipt is retained; the operation has not been repeated.");
        for (String key : new String[]{"projectId", "revision", "assetId", "clipId", "jobId", "queued", "accepted", "outputUri", "outputName", "renderer", "error"}) {
            Object item = result.opt(key);
            if (item instanceof Number || item instanceof Boolean) receipt.put(key, item);
            else if (item instanceof String && ((String) item).length() <= 2048) receipt.put(key, item);
        }
        return receipt;
    }
}
