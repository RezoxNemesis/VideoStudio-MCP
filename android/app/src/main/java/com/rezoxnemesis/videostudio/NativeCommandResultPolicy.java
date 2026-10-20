package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Honest command completion for native read-only capability discovery.
 *
 * The legacy CapabilityRegistry returns structured data, not an 'ok' field.
 * Only the explicitly listed, independently verifiable read-only result
 * shapes may be accepted without 'ok'. Every edit, transfer, render and
 * unknown action still requires its explicit successful native receipt.
 */
final class NativeCommandResultPolicy {
    private NativeCommandResultPolicy() {}

    static boolean successful(String action, JSONObject result) {
        if (result == null) return false;
        if (result.has("ok")) return Boolean.TRUE.equals(result.opt("ok"));
        if (result.has("error") || result.optBoolean("queued",false)
                || result.has("jobId") || result.has("pending")) return false;
        if (action == null) return false;
        switch (NativeWorkbenchBridge.canonicalAction(action)) {
            case "capability_registry": {
                Object count=result.opt("providerCount");
                JSONArray providers=result.optJSONArray("providers");
                return count instanceof Number && ((Number)count).intValue()>=0
                        && providers!=null && providers.length()==((Number)count).intValue();
            }
            case "resolve_capability": {
                Object resolved=result.opt("resolved");
                if (!(resolved instanceof Boolean) || result.optString("capability","").isEmpty())
                    return false;
                return !((Boolean)resolved) || result.optJSONObject("provider")!=null;
            }
            case "model_pack_status": {
                Object count=result.opt("installedProviderCount");
                return count instanceof Number && ((Number)count).intValue()>=0
                        && result.optJSONArray("providers")!=null;
            }
            default:
                return false;
        }
    }

    /** Ensure Activity log, journal and remote receipt agree on the result. */
    static JSONObject normalizedResult(String action, JSONObject result) {
        if (result == null) return new JSONObject();
        if (!result.has("ok") && successful(action,result)) {
            try { result.put("ok",true); } catch(Exception ignored) {}
        }
        return result;
    }
}
