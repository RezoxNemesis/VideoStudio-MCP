package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Set;

/** Incremental clip-local edits retain a split clip's authored clock and all outside keys. */
public final class StudioKeyframeEdits {
    private StudioKeyframeEdits(){}

    public static JSONObject apply(JSONObject effects,long durationMs,JSONArray edits)throws Exception{
        if(edits==null||edits.length()<1||edits.length()>64)throw new IllegalArgumentException("Use 1..64 keyframe edits");
        JSONObject result=new JSONObject(effects==null?"{}":effects.toString());
        JSONArray canonical=StudioClipKeyframes.authoredKeys(result,durationMs);
        result.put("keyframes",canonical);
        long offset=result.has("studioKeyframeOffsetMs")?StudioClipKeyframes.integer(result,"studioKeyframeOffsetMs",0,86_400_000L):0;
        for(int i=0;i<edits.length();i++){
            JSONObject edit=edits.optJSONObject(i);
            if(edit==null||!(edit.opt("action") instanceof String))throw new IllegalArgumentException("Each keyframe edit needs an action");
            String action=edit.getString("action");
            Set<String> allowed="upsert".equals(action)?Set.of("action","keyframe"):Set.of("action","timeMs");
            java.util.Iterator<String> names=edit.keys();while(names.hasNext())if(!allowed.contains(names.next()))throw new IllegalArgumentException("Unsupported keyframe edit field");
            JSONObject patch=null;long local;
            if("upsert".equals(action)){
                patch=edit.optJSONObject("keyframe");if(patch==null)throw new IllegalArgumentException("Upsert needs a keyframe object");
                StudioClipKeyframes.parse(new JSONArray().put(patch),durationMs);
                local=StudioClipKeyframes.integer(patch,"timeMs",0,durationMs);
            }else if("delete".equals(action))local=StudioClipKeyframes.integer(edit,"timeMs",0,durationMs);
            else throw new IllegalArgumentException("Use upsert or delete for keyframe edits");
            long authored=offset+local;ArrayList<JSONObject> ordered=new ArrayList<>();JSONObject previous=null;
            JSONArray keys=result.getJSONArray("keyframes");
            for(int k=0;k<keys.length();k++){
                JSONObject key=keys.getJSONObject(k);
                if(key.getLong("timeMs")==authored)previous=key;else ordered.add(key);
            }
            if("delete".equals(action)){
                if(previous==null)throw new IllegalArgumentException("No keyframe at entered clip time");
            }else{
                JSONObject key=previous==null?values(StudioClipKeyframes.fromEffects(result,durationMs).at(local)):
                        new JSONObject(previous.toString());
                if(previous==null)key.put("easing","linear");
                if(patch.has("scale")){
                    if(!patch.has("scaleX"))key.remove("scaleX");
                    if(!patch.has("scaleY"))key.remove("scaleY");
                }
                // A previous custom curve cannot accompany a non-cubic easing.
                // Remove obsolete handles before validating the merged key.
                if(patch.has("easing")&&!"cubic_bezier".equals(patch.optString("easing")))
                    key.remove("bezier");
                names=patch.keys();while(names.hasNext()){String name=names.next();key.put(name,patch.get(name));}
                key.put("timeMs",authored);ordered.add(key);
            }
            ordered.sort(Comparator.comparingLong(key->key.optLong("timeMs")));
            result.put("keyframes",new JSONArray(ordered));
            StudioClipKeyframes.fromEffects(result,durationMs);
        }
        return result;
    }

    public static JSONArray localKeys(JSONObject effects,long durationMs)throws Exception{
        JSONArray keys=StudioClipKeyframes.authoredKeys(effects,durationMs),visible=new JSONArray();
        long offset=effects!=null&&effects.has("studioKeyframeOffsetMs")?StudioClipKeyframes.integer(effects,"studioKeyframeOffsetMs",0,86_400_000L):0;
        for(int i=0;i<keys.length();i++){
            JSONObject key=keys.getJSONObject(i);long local=key.getLong("timeMs")-offset;
            if(local>=0&&local<=durationMs)visible.put(new JSONObject(key.toString()).put("timeMs",local));
        }
        return visible;
    }

    public static JSONObject values(StudioClipKeyframes.Values value)throws Exception{
        return new JSONObject().put("x",value.x).put("y",value.y).put("scaleX",value.scaleX).put("scaleY",value.scaleY)
                .put("rotate",value.rotate).put("anchorX",value.anchorX).put("anchorY",value.anchorY).put("opacity",value.opacity);
    }
}
