package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;

/** A bounded offline director for procedural scene domains. Neural human synthesis is a separate provider. */
public final class LocalSceneDirector {
    private LocalSceneDirector() {}
    public static JSONObject fromPrompt(String prompt,int shot) throws Exception {
        String text=prompt.toLowerCase(Locale.US);
        if(text.matches("(?s).*(photoreal|realistic human|real person|lip.?sync|talking person).*") || text.contains("woman") || text.contains("man walking")) {
            throw new IllegalArgumentException("Photorealistic human synthesis requires an executable local image/video model. Use imported portrait assets with animate_images, or supply a procedural sceneGraph for 2D/3D rendering.");
        }
        JSONObject graph=new JSONObject(); JSONArray objects=new JSONArray(); graph.put("version",1); graph.put("objects",objects);
        if(text.contains("3d") || text.contains("space") || text.contains("orbit") || text.contains("geometry")) {
            graph.put("background","#080d21"); graph.put("cameraOrbit",text.contains("orbit")?8:0); graph.put("fov",48);
            for(int i=0;i<32;i++) objects.put(object("circle",fract(i*.618+.17),fract(i*.414+.1),.003,"#aec9e8"));
            for(int i=0;i<5;i++) {
                JSONObject o=object(i%2==0?"cube":"pyramid",Math.sin(i*2.4)*1.5,Math.cos(i*2.4)*.8,.1,i%2==0?"#61d9eb":"#d892ef");
                o.put("z",4.8+i*.22);o.put("size",.3+i*.07);o.put("spin",12+i*7);o.put("rotation",shot*25+i*30); objects.put(o);
            }
        } else if(text.contains("forest") || text.contains("rain") || text.contains("nature") || text.contains("landscape")) {
            graph.put("background",text.contains("night")?"#071323":"#214b66");
            objects.put(object("circle",.75,.22,.2,"#f1d79a"));
            for(int i=0;i<25;i++) {
                double x=fract(i*.618),y=.65+fract(i*.414)*.35;
                objects.put(object("rectangle",x,y,.018,"#294234"));
                JSONObject crown=object("polygon",x,y-.12,.16,i%2==0?"#236449":"#34725c");
                crown.put("vertices",new JSONArray("[[0,-1],[-0.6,0.5],[0.6,0.5]]")); crown.put("height",.2); crown.put("amplitude",.006); crown.put("phase",i*.3); objects.put(crown);
            }
            if(text.contains("rain")) for(int i=0;i<45;i++) {
                JSONObject drop=object("line",fract(i*.718),fract(i*.413),.001,"#91b8c6");
                drop.put("height",.025);drop.put("toY",drop.getDouble("y")+7);drop.put("wrap",true);drop.put("rotation",12);objects.put(drop);
            }
        } else {
            graph.put("background","#0b1428");
            for(int i=0;i<18;i++) {
                JSONObject o=object(i%3==0?"rectangle":"circle",fract(i*.618+.2),fract(i*.414+.1),.06+fract(i*.271)*.09,i%2==0?"#49ccdc":"#d794f2");
                o.put("toX",fract(i*.414+.4));o.put("toY",fract(i*.718+.3));o.put("spin",i%3==0?25:0);o.put("amplitude",.03);o.put("phase",i);objects.put(o);
            }
        }
        graph.put("director","local-procedural-v1");graph.put("sourcePrompt",prompt);return graph;
    }
    private static JSONObject object(String type,double x,double y,double width,String color) throws Exception {
        JSONObject o=new JSONObject();o.put("type",type);o.put("x",x);o.put("y",y);o.put("width",width);o.put("height",width);o.put("color",color);return o;
    }
    private static double fract(double x) { return x-Math.floor(x); }
}
