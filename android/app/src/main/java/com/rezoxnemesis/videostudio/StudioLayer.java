package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.util.UUID;

/**
 * Independent overlay lane on VideoStudio's linear video edit.
 *
 * This is not a fake "effect metadata" label: title lanes are drawn in the
 * Media3 video pipeline and audio lanes are mixed as separate Media3
 * EditedMediaItemSequences, with time offsets.
 */
public final class StudioLayer {
    public static final String AUDIO="audio", TITLE="title";
    public String id=UUID.randomUUID().toString();
    public String type=TITLE;
    public String assetId="";
    public String text="";
    public long startMs=0;
    public long durationMs=2500;
    public long sourceInMs=0;
    public float gain=1f;
    public int color=0xffffffff;
    public int position=1; // 0 = upper, 1 = center, 2 = lower

    public StudioLayer copy() {return fromJson(toJson());}

    public void validate(){
        if(!AUDIO.equals(type)&&!TITLE.equals(type))
            throw new IllegalArgumentException("Only real audio and caption lanes are supported");
        if(startMs<0||durationMs<100||durationMs>86_400_000L||sourceInMs<0)
            throw new IllegalArgumentException("Invalid layer time or duration");
        if(!Float.isFinite(gain)||gain<0||gain>2)
            throw new IllegalArgumentException("Audio level must be 0..200%");
        if(position<0||position>2)
            throw new IllegalArgumentException("Invalid text position");
        if(TITLE.equals(type)&& (text==null||text.trim().isEmpty()||text.length()>160))
            throw new IllegalArgumentException("Title must contain 1–160 characters");
        if(AUDIO.equals(type)&& (assetId==null||assetId.trim().isEmpty()))
            throw new IllegalArgumentException("Audio lane needs an imported audio asset");
    }

    public JSONObject toJson(){
        JSONObject obj=new JSONObject();
        try{
            obj.put("id",id);obj.put("type",type);obj.put("assetId",assetId);
            obj.put("text",text);obj.put("startMs",startMs);
            obj.put("durationMs",durationMs);obj.put("sourceInMs",sourceInMs);
            obj.put("gain",gain);obj.put("color",color);obj.put("position",position);
        }catch(Exception ignored){}
        return obj;
    }

    public static StudioLayer fromJson(JSONObject o){
        if(o==null)throw new IllegalArgumentException("Layer data required");
        StudioLayer l=new StudioLayer();
        l.id=o.optString("id",UUID.randomUUID().toString());
        l.type=o.optString("type",TITLE);
        l.assetId=o.optString("assetId","");
        l.text=o.optString("text","");
        l.startMs=o.optLong("startMs",0);
        l.durationMs=o.optLong("durationMs",2500);
        l.sourceInMs=o.optLong("sourceInMs",0);
        l.gain=(float)o.optDouble("gain",1f);
        l.color=o.optInt("color",0xffffffff);
        l.position=o.optInt("position",1);
        l.validate();
        return l;
    }
}
