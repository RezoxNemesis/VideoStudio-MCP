package com.rezoxnemesis.videostudio;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;

/** Pin executable model semantics while excluding installation timestamps. */
public final class NativeModelContract {
    public static String fingerprint(JSONObject manifest) throws Exception {
        JSONObject pinned=new JSONObject();
        for(String key:new String[]{"id","version","backend","license","files","raft","neural","motion"}) if(manifest.has(key)) pinned.put(key,manifest.get(key));
        return SceneMemoryStore.hash(SceneMemoryStore.canonical(pinned).getBytes(StandardCharsets.UTF_8));
    }
    public static void verify(JSONObject manifest,String expected) throws Exception {
        if(expected==null||!fingerprint(manifest).equals(expected)) throw new IllegalStateException("Model contract changed since this job was queued; replan the job");
    }
}
