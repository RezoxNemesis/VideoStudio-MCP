package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.media3.common.util.UnstableApi;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

/** A bounded live still composite, using exactly the supplied native-render
 * portrait layer URIs and roles. It performs no segmentation or model inference. */
@UnstableApi
public final class StillProgramRenderer implements AutoCloseable {
    public interface Listener extends StillGpuRenderer.Listener {}
    private final FrameLayout view;
    private final StillGpuRenderer.Listener listener;
    private final List<StillGpuRenderer> layers=new ArrayList<>();
    private final List<String> roles=new ArrayList<>();
    private final int expectedLayers;
    private boolean closed,failed,readyNotified;
    private String failure="";

    /** Construct, place and close on the UI thread. All layer clocks are output
     * time relative to this clip; authored split offsets are applied by effects. */
    public StillProgramRenderer(Context context,ProjectStore.Clip clip,String sourceUri,String aspect,StillGpuRenderer.Listener listener) {
        if(Looper.myLooper()!=Looper.getMainLooper())throw new IllegalStateException("Still composite must be created on the UI thread");
        this.listener=listener;view=new FrameLayout(context);
        ProjectStore.Clip snapshot=ProjectStore.Clip.fromJson(clip.toJson());
        JSONObject fx=snapshot.effects==null?new JSONObject():snapshot.effects;
        boolean layered=fx.optBoolean("animatedScene",false);
        boolean articulated=layered&&!fx.optString("headUri","").isEmpty()&&!fx.optString("torsoUri","").isEmpty()&&!fx.optString("lowerUri","").isEmpty();
        if(articulated){roles.add("background");roles.add("lower");roles.add("torso");roles.add("head");}
        else if(layered){roles.add("background");roles.add("foreground");}
        else roles.add("flat");
        expectedLayers=roles.size();
        try {
            // Four simultaneous output surfaces keep their fixed 720p canvas;
            // source decode is smaller to leave room inside the shared GPU budget.
            int bitmapEdge=expectedLayers==4?1024:StillGpuRenderer.MAX_BITMAP_EDGE;
            for(String role:roles) {
                String uri=layered?fx.optString(role+"Uri",""):sourceUri;
                if(uri==null||uri.isEmpty())throw new IllegalArgumentException("Missing "+role+" image for the native still composite");
                StillGpuRenderer layer=new StillGpuRenderer(context,snapshot,uri,aspect,role,bitmapEdge,new StillGpuRenderer.Listener(){
                    @Override public void onReady(){notifyReady();}
                    @Override public void onError(String detail){fail(role+" layer: "+detail);}
                });
                layers.add(layer);
                view.addView(layer.view(),new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.MATCH_PARENT));
            }
        }catch(IllegalArgumentException|IllegalStateException error){
            for(StillGpuRenderer layer:layers)layer.close();layers.clear();throw error;
        }
    }
    public FrameLayout view(){return view;}
    public boolean isReady(){
        if(closed||failed||layers.size()!=expectedLayers)return false;
        for(StillGpuRenderer layer:layers)if(!layer.isReady())return false;
        return true;
    }
    public boolean isFailed(){return failed;}
    public void renderAtUs(long outputLocalUs){if(!closed&&!failed)for(StillGpuRenderer layer:layers)layer.renderAtUs(outputLocalUs);}
    private void notifyReady(){if(!readyNotified&&isReady()){readyNotified=true;if(listener!=null)listener.onReady();}}
    private void fail(String detail){
        if(closed||failed)return;failed=true;failure=detail;view.setVisibility(View.GONE);
        for(StillGpuRenderer layer:layers)layer.close();
        if(listener!=null)listener.onError("Native still composite unavailable: "+detail);
    }
    public JSONObject diagnostics(){
        JSONObject result=new JSONObject();JSONArray diagnostics=new JSONArray();
        try{result.put("pipeline","supplied native bitmap layers with shared output clock");result.put("ready",isReady());result.put("failed",failed);
            result.put("layerCount",expectedLayers);result.put("layerOrder",new JSONArray(roles));result.put("failure",failure);
            for(StillGpuRenderer layer:layers)diagnostics.put(layer.diagnostics());result.put("layers",diagnostics);
            result.put("verification","source API review only; device verification deferred");}catch(Exception ignored){}
        return result;
    }
    @Override public void close(){if(closed)return;closed=true;for(StillGpuRenderer layer:layers)layer.close();layers.clear();}
}
