package com.rezoxnemesis.videostudio;
import android.content.Context;
import org.json.*;
import java.nio.charset.StandardCharsets;

/** Hardware-aware deterministic compiler plan. Cache identities exclude revision IDs and temperature. */
public final class NativeScenePlanner {
    public static JSONObject plan(Context context,JSONObject scene,String requestedQuality) throws Exception {
        JSONObject profile=new DeviceComputeProfile(context).snapshot();
        long heap=profile.optLong("javaHeapHeadroomMb"),budget=profile.optLong("activeWorkingSetBudgetMb");
        String quality=requestedQuality==null||requestedQuality.isEmpty()?(heap>=96&&budget>=256?"1080p":"720p"):requestedQuality;
        if(!quality.equals("720p")&&!quality.equals("1080p")) throw new IllegalArgumentException("Scene quality must be 720p or 1080p");
        JSONArray nodes=new JSONArray(),objects=scene.getJSONObject("graph").getJSONArray("objects");
        JSONObject global=new JSONObject(scene.getJSONObject("graph").toString());global.remove("objects");
        String globalKey=SceneMemoryStore.canonical(global)+scene.getLong("durationMs")+":"+scene.getInt("fps")+":"+quality;
        JSONArray dependencies=new JSONArray();
        for(int i=0;i<objects.length();i++) {
            JSONObject entity=objects.getJSONObject(i);String id="entity."+entity.getString("id");dependencies.put(id);
            nodes.put(new JSONObject().put("nodeId",id).put("runtimeTarget","android_native").put("provider","native.scene.canvas")
                    .put("scope",entity.getString("id")).put("cacheKey",SceneMemoryStore.hash((SceneMemoryStore.canonical(entity)+globalKey+":renderer1").getBytes(StandardCharsets.UTF_8)))
                    .put("dependencies",new JSONArray()).put("semanticValidation","unchecked"));
        }
        nodes.put(new JSONObject().put("nodeId","render.final").put("provider","native.media3").put("dependencies",dependencies).put("scope","full_output"));
        return new JSONObject().put("runtimeTarget","android_native").put("compiler","deterministic-capability-planner")
                .put("quality",quality).put("hardware",profile).put("nodes",nodes).put("progressiveStages",new JSONArray(new int[]{320,512,720}))
                .put("boundedRepairs",scene.getJSONObject("policy").optInt("maxRepairs",2)).put("missingCapabilities",scene.getJSONArray("missingCapabilities"));
    }
}
