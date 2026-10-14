package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Limits replicated relay metadata; complete native results remain in CommandOutbox's archive. */
final class RelayTransportPolicy {
    static final int RESULT_BYTES=2048;
    static final int RESULT_PAGE_BYTES=768;
    static final int PROJECT_BYTES=32*1024;
    private RelayTransportPolicy() {}

    static byte[] bytes(String value) {return value.getBytes(StandardCharsets.UTF_8);}
    static String sha256(byte[] value) throws Exception {
        byte[] hash=MessageDigest.getInstance("SHA-256").digest(value);
        StringBuilder out=new StringBuilder(64);
        for(byte b:hash) out.append(Character.forDigit((b>>>4)&15,16)).append(Character.forDigit(b&15,16));
        return out.toString();
    }

    static JSONObject result(String commandId,String json) throws Exception {
        byte[] encoded=bytes(json);
        if(encoded.length<=RESULT_BYTES) return new JSONObject(json);
        return externalizedResult(commandId,json);
    }

    static JSONObject externalizedResult(String commandId,String json) throws Exception {
        JSONObject full=new JSONObject(json);byte[] encoded=bytes(json);
        JSONObject receipt=new JSONObject();
        // Preserve useful operation identity and success/failure without copying
        // nested state, images, journals or arbitrary owner text into every ACK.
        for(String key:new String[]{"ok","projectId","sketchId","bookId","assetId","jobId","programId",
                "revision","documentSha256","sourceSha256","persisted","canUndo","canRedo","outputVerified","error","code"}) {
            Object value=full.opt(key);
            if(value instanceof Boolean || value instanceof Number
                    || value instanceof String && bytes((String)value).length<=128) receipt.put(key,value);
        }
        receipt.put("resultExternalized",true);
        receipt.put("resultBytes",encoded.length);
        receipt.put("resultSha256",sha256(encoded));
        receipt.put("resultEncoding","utf-8-json");
        receipt.put("resultLocator",new JSONObject().put("action","get_native_result_page")
                .put("commandId",commandId).put("pageIndex",0));
        if(bytes(receipt.toString()).length>RESULT_BYTES) {
            // Optional scalars are summaries only; the complete, hash-addressed
            // result remains available through the explicit locator.
            for(String key:new String[]{"projectId","sketchId","bookId","assetId","jobId","programId","error","code"}) receipt.remove(key);
        }
        if(bytes(receipt.toString()).length>RESULT_BYTES) throw new IllegalArgumentException("Result locator exceeds its transport budget");
        return receipt;
    }

    static String safeHttpError(int status,String body) {
        String message="HTTP "+status;
        // Never propagate a server body, URL, owner credential or arbitrary
        // error string into persistent diagnostics. Only known constant causes.
        String prefix=body==null?"":body.substring(0,Math.min(4096,body.length()));
        if(prefix.contains("SQLITE_TOOBIG")) return message+" SQLITE_TOOBIG";
        if(prefix.contains("Conflicting terminal command acknowledgement")
                || prefix.contains("Conflicting compact terminal receipt")) return message+" TERMINAL_ACK_CONFLICT";
        return message;
    }

    static JSONArray projects(JSONArray source) throws Exception {
        JSONArray out=new JSONArray();
        if(source==null) return out;
        for(int i=0;i<source.length() && out.length()<100;i++) {
            JSONObject full=source.optJSONObject(i);
            if(full==null) continue;
            JSONObject summary=new JSONObject();
            summary.put("id",boundedText(full.optString("id",""),128));
            summary.put("name",boundedText(full.optString("name",""),160));
            for(String key:new String[]{"assetCount","clipCount","durationMs","updatedAt","latestExportAt"}) {
                Object value=full.opt(key);
                if(value instanceof Number) summary.put(key,value);
            }
            // The registration lane is an index, never a backup of project
            // prompts, content URIs or exported files. Local projects are untouched.
            int nextBytes=bytes(summary.toString()).length;
            if(bytes(out.toString()).length+nextBytes+1>PROJECT_BYTES) break;
            out.put(summary);
        }
        return out;
    }

    private static String boundedText(String text,int maximumBytes) {
        if(bytes(text).length<=maximumBytes) return text;
        StringBuilder out=new StringBuilder();int size=0;
        for(int i=0;i<text.length();) {
            int point=text.codePointAt(i);String next=new String(Character.toChars(point));
            int count=bytes(next).length;
            if(size+count>maximumBytes) break;
            out.append(next);size+=count;i+=Character.charCount(point);
        }
        return out.toString();
    }
}
