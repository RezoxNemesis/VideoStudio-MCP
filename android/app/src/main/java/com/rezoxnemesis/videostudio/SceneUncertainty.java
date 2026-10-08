package com.rezoxnemesis.videostudio;
import android.graphics.Rect;
import org.json.*;

/** Conservative execution routing map, not a calibrated semantic confidence score. */
public final class SceneUncertainty {
    public static JSONObject describe(JSONObject scene,double before,double after) throws Exception {
        int width=scene.getInt("width"),height=scene.getInt("height");
        Rect changed=NativeSceneRenderer.dirtyRegion(scene.getJSONObject("graph"),before,after,scene.getLong("durationMs")/1000d,width,height);
        boolean missing=scene.getJSONArray("missingCapabilities").length()>0;
        JSONArray tiles=new JSONArray();int[] counts=new int[3];
        for(int row=0;row<16;row++) for(int column=0;column<16;column++) {
            Rect tile=new Rect(column*width/16,row*height/16,(column+1)*width/16,(row+1)*height/16);
            int route=missing?2:Rect.intersects(tile,changed)?1:0;tiles.put(route);counts[route]++;
        }
        return new JSONObject().put("columns",16).put("rows",16).put("tiles",tiles)
                .put("legend",new JSONArray(new String[]{"preserve","reconstruct","missing_provider"}))
                .put("preserveTiles",counts[0]).put("reconstructTiles",counts[1]).put("missingProviderTiles",counts[2])
                .put("confidenceCalibrated",false).put("semanticValidation","unchecked").put("conservative",true);
    }
}
