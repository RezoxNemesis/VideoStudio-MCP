package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Set;

/** Author-once, native source-free shape/motion recipes for the Android editor. */
public final class StudioShapePresets {
    private static final Set<String> STYLES=Set.of("drift","pulse","spin","slide","hold");
    private StudioShapePresets(){}

    public static StudioLayer create(String geometry, String style,
                                     long startMs,long durationMs) throws Exception {
        if(!STYLES.contains(style))throw new IllegalArgumentException("Unknown original shape motion style");
        if(startMs<0||durationMs<100||durationMs>86_400_000L)
            throw new IllegalArgumentException("Native shape needs valid bounded timing");
        StudioLayer layer=new StudioLayer();
        layer.type=StudioLayer.SHAPE;
        layer.shapeKind=geometry;
        layer.startMs=startMs;
        layer.durationMs=durationMs;
        layer.x=.5f;layer.y=.58f;
        layer.width=.58f;layer.height=.3f;
        layer.color=0x4476caff;
        layer.strokeColor=0xff8edafa;
        layer.strokeWidth=.027f;
        layer.opacity=1f;
        if("grid".equals(geometry)){
            layer.x=.5f;layer.y=.70f;layer.width=1f;layer.height=.64f;
            layer.color=0x00000000;layer.strokeColor=0xff66b8ff;
            layer.strokeWidth=.018f;layer.opacity=.65f;
        }else if("ring".equals(geometry)){
            layer.width=.45f;layer.height=.26f;
            layer.color=0x00000000;layer.strokeWidth=.055f;
        }else if("line".equals(geometry)){
            layer.width=.65f;layer.height=.15f;layer.strokeWidth=.07f;
        }else if("ellipse".equals(geometry)){
            layer.color=0x33ffffff;layer.strokeWidth=.015f;
        }else if("diamond".equals(geometry)){
            layer.color=0x448ad9ff;layer.strokeWidth=.035f;
        }
        layer.motionKeyframes=motion(style,durationMs);
        layer.validate();
        return layer;
    }

    public static JSONArray motion(String style,long duration) throws Exception {
        if(!STYLES.contains(style)||duration<100||duration>86_400_000L)
            throw new IllegalArgumentException("Unsupported style/duration");
        if("hold".equals(style))return new JSONArray();
        long entrance=Math.max(20,Math.min(210,duration/5));
        long tail=duration-entrance;
        if(tail<=entrance)throw new IllegalArgumentException("Shape is too short to interpolate");
        JSONArray keys=new JSONArray();
        double offset="slide".equals(style)?-.55:"drift".equals(style)?-.13:0;
        double startScale="pulse".equals(style)?.4:"spin".equals(style)?.75:1;
        keys.put(key(0,offset,0,startScale,0,0,"ease_out"));
        keys.put(key(entrance,0,0,1,0,1,"ease_out"));
        keys.put(key(tail,"drift".equals(style)?.13:0,0,1,
                "spin".equals(style)?300:0,1,"ease_in_out"));
        keys.put(key(duration,"slide".equals(style)?.55:"drift".equals(style)?.20:0,
                0,"pulse".equals(style)?1.16:1,"spin".equals(style)?360:0,0,"ease_in"));
        return keys;
    }

    private static JSONObject key(long t,double x,double y,double scale,
                                  double rotation,double opacity,String easing) throws Exception {
        return new JSONObject().put("timeMs",t).put("x",x).put("y",y)
                .put("scale",scale).put("rotate",rotation)
                .put("opacity",opacity).put("easing",easing);
    }
}
