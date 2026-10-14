package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.function.BooleanSupplier;
import static com.rezoxnemesis.videostudio.Cinematic3DTimeline.*;

/** Shared inert camera authoring. Baked actors, props and source proofs are never rewritten. */
public final class Cinematic3DCameraEditOps {
    public static final int MAX_OPERATIONS=128;
    private Cinematic3DCameraEditOps() {}

    /** Apply the complete batch to a copy; no caller-owned JSON changes on success or failure. */
    public static JSONObject apply(JSONObject original,JSONArray operations) throws Exception {
        return apply(original,operations,()->false);
    }
    public static JSONObject apply(JSONObject original,JSONArray operations,BooleanSupplier cancel) throws Exception {
        cancelled(cancel);
        if(operations==null||operations.length()<1||operations.length()>MAX_OPERATIONS)
            throw new IllegalArgumentException("Use 1..128 camera edit operations");
        JSONObject result=validate(original);
        for(int index=0;index<operations.length();index++) {
            cancelled(cancel);JSONObject operation=object(operations,index);String type=text(operation,"type",32);
            switch(type) {
                case "upsert_camera_key":fields(operation,"type","shotId","phase","position","target","up");break;
                case "remove_camera_key":fields(operation,"type","shotId","phase");break;
                case "clear_camera_keys":fields(operation,"type","shotId");break;
                default:throw new IllegalArgumentException("Unknown cinematic camera edit operation");
            }
            String shotId=id(text(operation,"shotId",96));JSONObject shot=findShot(result,shotId),camera=shot.getJSONObject("camera");
            if("clear_camera_keys".equals(type)){camera.remove("keyframes");continue;}
            double phase=number(operation,"phase",0,1);JSONArray keys=camera.optJSONArray("keyframes"),next=new JSONArray();boolean found=false,inserted=false;
            JSONObject replacement=null;
            if("upsert_camera_key".equals(type)) {
                replacement=new JSONObject().put("phase",phase).put("position",cameraVector(operation,"position")).put("target",cameraVector(operation,"target"));
                if(operation.has("up"))replacement.put("up",cameraVector(operation,"up"));
            }
            for(int keyIndex=0;keys!=null&&keyIndex<keys.length();keyIndex++) {
                JSONObject key=keys.getJSONObject(keyIndex);double existing=key.getDouble("phase");
                if(replacement!=null&&!inserted&&phase<existing){next.put(replacement);inserted=true;}
                if(existing==phase){found=true;if(replacement!=null){next.put(replacement);inserted=true;}}
                else next.put(key);
            }
            if(replacement!=null&&!inserted)next.put(replacement);
            if(replacement==null&&!found)throw new IllegalArgumentException("Select an existing camera key phase to remove");
            if(next.length()==0)camera.remove("keyframes");else camera.put("keyframes",next);
            // Keep every intermediate data structure bounded, even if a later operation clears it.
            if(next.length()>128)throw new IllegalArgumentException("Use 1..128 camera keys");
        }
        JSONObject accepted=validate(result);preflight(accepted,cancel);return accepted;
    }

    /** Validate the exact output frames, including interpolation, default up and impact shake. */
    public static void preflight(JSONObject timeline) throws Exception {
        preflight(timeline,()->false);
    }
    public static void preflight(JSONObject timeline,BooleanSupplier cancel) throws Exception {
        cancelled(cancel);
        JSONObject accepted=validate(timeline);int fps=accepted.getInt("fps");JSONArray shots=accepted.getJSONArray("shots");
        for(int index=0;index<shots.length();index++) {
            cancelled(cancel);JSONObject shot=shots.getJSONObject(index);
            if(!shot.getJSONObject("camera").has("keyframes"))continue;
            // Do not repeatedly copy dense pose/contact/source metadata to inspect a camera clock.
            JSONObject clock=new JSONObject();for(String field:new String[]{"id","action","durationFrames","hitStopFrames","camera","vfx"})clock.put(field,shot.get(field));
            for(int frame=0;frame<shot.getInt("durationFrames");frame++) {
                cancelled(cancel);try{sampleShot(clock,frame,fps);}catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid camera in shot "+shot.getString("id")+" at output frame "+frame+": "+invalid.getMessage(),invalid);}
            }
        }
    }

    private static JSONObject findShot(JSONObject timeline,String shotId) throws Exception {
        JSONArray shots=timeline.getJSONArray("shots");for(int i=0;i<shots.length();i++){JSONObject shot=shots.getJSONObject(i);if(shotId.equals(shot.getString("id")))return shot;}
        throw new IllegalArgumentException("Select an existing cinematic shot id");
    }
    private static JSONArray cameraVector(JSONObject operation,String name) throws Exception {
        JSONArray input=array(operation,name);if(input.length()!=3)throw new IllegalArgumentException("Camera vectors require exactly three components");JSONArray result=new JSONArray();
        for(int i=0;i<3;i++){Object value=input.get(i);if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue())||Math.abs(((Number)value).doubleValue())>10000)throw new IllegalArgumentException("Camera vectors must be finite and within 10000 metres/components");result.put(((Number)value).doubleValue());}return result;
    }
    private static void cancelled(BooleanSupplier cancel) throws InterruptedException {if(Thread.currentThread().isInterrupted()||cancel.getAsBoolean())throw new InterruptedException("Camera authoring cancelled");}
}
