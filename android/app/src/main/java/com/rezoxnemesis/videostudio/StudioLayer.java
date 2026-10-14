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
    public static final String AUDIO="audio", TITLE="title", IMAGE="image";
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
    public int fontSize=32; // DIP, matching Media3 AbsoluteSizeSpan(..., true).
    // Media3's legacy multiline titles use StaticLayout's normal (leading-edge) alignment.
    public String fontFamily="sans-serif",fontStyle="bold",textAlign="left";
    public int backgroundColor=0; // Signed Android ARGB; zero preserves legacy transparency.
    public float x=.5f,y=.5f,width=.35f,height=.35f,rotation=0f,opacity=1f;
    private static final String[] CAPTION_FIELDS={"fontSize","fontFamily","fontStyle","textAlign","backgroundColor"};

    public StudioLayer copy() {return fromJson(toJson());}

    public void validate(){
        if(id==null||id.isEmpty()||id.length()>128)throw new IllegalArgumentException("Layer ID must contain 1..128 characters");
        if(!AUDIO.equals(type)&&!TITLE.equals(type)&&!IMAGE.equals(type))
            throw new IllegalArgumentException("Only real audio, caption and image lanes are supported");
        if(startMs<0||durationMs<100||durationMs>86_400_000L||sourceInMs<0)
            throw new IllegalArgumentException("Invalid layer time or duration");
        if(!Float.isFinite(gain)||gain<0||gain>1)
            throw new IllegalArgumentException("Audio level must be 0..100%");
        if(position<0||position>2)
            throw new IllegalArgumentException("Invalid text position");
        if(TITLE.equals(type)&& (text==null||text.trim().isEmpty()||text.length()>160))
            throw new IllegalArgumentException("Title must contain 1–160 characters");
        if(TITLE.equals(type)){
            if(fontSize<12||fontSize>96)throw new IllegalArgumentException("Caption font size must be 12..96 DIP");
            requireChoice("fontFamily",fontFamily,"sans-serif","sans-serif-condensed","serif","monospace","cursive");
            requireChoice("fontStyle",fontStyle,"normal","bold","italic","bold_italic");
            requireChoice("textAlign",textAlign,"left","center","right");
        }else if(fontSize!=32||!"sans-serif".equals(fontFamily)||!"bold".equals(fontStyle)
                ||!"left".equals(textAlign)||backgroundColor!=0)
            throw new IllegalArgumentException("Caption styles are only supported on title layers");
        if(AUDIO.equals(type)&& (assetId==null||assetId.trim().isEmpty()))
            throw new IllegalArgumentException("Audio lane needs an imported audio asset");
        if(IMAGE.equals(type)){
            if(assetId==null||assetId.trim().isEmpty())throw new IllegalArgumentException("Image lane needs an imported image asset");
            if(!Float.isFinite(x)||!Float.isFinite(y)||x<0||x>1||y<0||y>1
                    ||!Float.isFinite(width)||!Float.isFinite(height)||width<.01f||width>2||height<.01f||height>2
                    ||!Float.isFinite(rotation)||rotation<-360||rotation>360||!Float.isFinite(opacity)||opacity<0||opacity>1)
                throw new IllegalArgumentException("Invalid image layer position, size, rotation or opacity");
        }
    }

    public JSONObject toJson(){
        JSONObject obj=new JSONObject();
        try{
            obj.put("id",id);obj.put("type",type);obj.put("assetId",assetId);
            obj.put("text",text);obj.put("startMs",startMs);
            obj.put("durationMs",durationMs);obj.put("sourceInMs",sourceInMs);
            obj.put("gain",gain);obj.put("color",color);obj.put("position",position);
            if(TITLE.equals(type)){
                obj.put("fontSize",fontSize);obj.put("fontFamily",fontFamily);obj.put("fontStyle",fontStyle);
                obj.put("textAlign",textAlign);obj.put("backgroundColor",backgroundColor);
            }
            if(IMAGE.equals(type)){obj.put("x",x);obj.put("y",y);obj.put("width",width);obj.put("height",height);
                obj.put("rotation",rotation);obj.put("opacity",opacity);}
        }catch(Exception ignored){}
        return obj;
    }

    public static StudioLayer fromJson(JSONObject o){
        if(o==null)throw new IllegalArgumentException("Layer data required");
        for(String field:new String[]{"id","type","assetId","text"})if(o.has(field)&&!(o.opt(field) instanceof String))
            throw new IllegalArgumentException(field+" must be a string");
        StudioLayer l=new StudioLayer();
        l.id=o.optString("id",UUID.randomUUID().toString());
        l.type=o.optString("type",TITLE);
        if(!TITLE.equals(l.type))for(String field:CAPTION_FIELDS)if(o.has(field))
            throw new IllegalArgumentException(field+" is only supported on title layers");
        l.assetId=o.optString("assetId","");
        l.text=o.optString("text","");
        l.startMs=o.has("startMs")?StudioClipKeyframes.integer(o,"startMs",0,86_400_000):0;
        l.durationMs=o.has("durationMs")?StudioClipKeyframes.integer(o,"durationMs",100,86_400_000):2500;
        l.sourceInMs=o.has("sourceInMs")?StudioClipKeyframes.integer(o,"sourceInMs",0,86_400_000):0;
        l.gain=(float)StudioClipKeyframes.number(o,"gain",1,0,1);
        l.color=o.optInt("color",0xffffffff);
        l.position=o.has("position")?(int)StudioClipKeyframes.integer(o,"position",0,2):1;
        l.fontSize=o.has("fontSize")?(int)StudioClipKeyframes.integer(o,"fontSize",12,96):32;
        l.fontFamily=captionChoice(o,"fontFamily","sans-serif","sans-serif","sans-serif-condensed","serif","monospace","cursive");
        l.fontStyle=captionChoice(o,"fontStyle","bold","normal","bold","italic","bold_italic");
        l.textAlign=captionChoice(o,"textAlign","left","left","center","right");
        l.backgroundColor=o.has("backgroundColor")?(int)StudioClipKeyframes.integer(o,"backgroundColor",Integer.MIN_VALUE,0xffffffffL):0;
        l.x=(float)StudioClipKeyframes.number(o,"x",.5,0,1);l.y=(float)StudioClipKeyframes.number(o,"y",.5,0,1);
        l.width=(float)StudioClipKeyframes.number(o,"width",.35,.01,2);l.height=(float)StudioClipKeyframes.number(o,"height",.35,.01,2);
        l.rotation=(float)StudioClipKeyframes.number(o,"rotation",0,-360,360);l.opacity=(float)StudioClipKeyframes.number(o,"opacity",1,0,1);
        l.validate();
        return l;
    }

    private static String captionChoice(JSONObject object,String name,String fallback,String... choices){
        if(!object.has(name))return fallback;
        Object raw=object.opt(name);
        if(!(raw instanceof String))throw new IllegalArgumentException(name+" must be a string");
        requireChoice(name,(String)raw,choices);return (String)raw;
    }
    private static void requireChoice(String name,String value,String... choices){
        for(String choice:choices)if(choice.equals(value))return;
        throw new IllegalArgumentException("Unsupported caption "+name);
    }
}
