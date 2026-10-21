package com.rezoxnemesis.videostudio;

import android.graphics.Matrix;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Iterator;

/**
 * Original props baked to ordinary editable ink. Held props sample actual retained-human
 * finger/thumb pinch coordinates, including the actor object transform, and share its parent layer.
 * Lanterns hang in world-down orientation before the common layer/camera transform.
 * Settings x/y position floor props only; held props use the exact pinch point. Scale is
 * relative to the actor's construction size when held, and the canvas view box on the floor.
 * Subsequent artist edits to the actor do not automatically rebake these independent keys.
 * One selected hand is split into a retained foreground overlay, so flesh covers the handle.
 * A second held prop on an already split actor is rejected rather than layering ambiguous grips.
 */
final class SketchActorProp {
    private SketchActorProp(){}
    static JSONObject defaults()throws Exception{return new JSONObject().put("kind","lantern").put("attachment","near").put("scale",1).put("x",.5).put("y",.8);}
    static JSONObject settings(JSONObject input)throws Exception {
        SketchDocument.fields(input,"kind,attachment,scale,x,y");JSONObject s=defaults();Iterator<String> keys=input.keys();while(keys.hasNext()){String k=keys.next();s.put(k,input.get(k));}
        SketchStoryStage.choice(s,"kind","lantern","parcel","staff");SketchStoryStage.choice(s,"attachment","near","far","floor");
        SketchDocument.number(s,"scale",.4,1.5);SketchDocument.number(s,"x",0,1);SketchDocument.number(s,"y",0,1);return s;
    }
    static void apply(JSONObject doc,JSONObject op)throws Exception {
        SketchDocument.fields(op,"type,layerId,propId,actorId,settings");if(!"add_actor_prop".equals(op.getString("type")))throw SketchDocument.bad("Expected add_actor_prop");
        String id=SketchDocument.id(op,"propId"),layerId=SketchDocument.id(op,"layerId");SketchStoryStage.requireTarget(doc,layerId,id);
        JSONObject s=settings(op.getJSONObject("settings")),actor=null;boolean floor=s.getString("attachment").equals("floor");
        if(op.has("actorId"))SketchDocument.id(op,"actorId");
        if(!floor){
            String actorId=SketchDocument.id(op,"actorId")+"_rig";actor=SketchDocument.object(doc,actorId);
            if(!"human".equals(actor.getString("kind")))throw SketchDocument.bad("Held props require a retained native human actor");
            if(!layerId.equals(SketchDocument.owner(doc,actorId).getString("id")))throw SketchDocument.bad("Held prop must target the SAME_ACTOR_LAYER");
            JSONObject h=actor.getJSONObject("human");
            if(!"all".equals(h.optString("parts","all")))throw SketchDocument.bad("Held actor already has split rendering parts; a second held prop is unsupported");
            if(h.getInt("fps")!=doc.getInt("fps")||actor.getInt("frame")!=0||actor.getInt("exposure")<doc.getInt("frameCount"))throw SketchDocument.bad("Actor must cover this complete frame clock; no implicit hold or retiming");
        }
        int frames=doc.getInt("frameCount"),w=doc.getInt("width"),h=doc.getInt("height");
        double size=s.getDouble("scale")*(floor?1:actor.getJSONObject("human").getJSONObject("settings").getDouble("size"));
        SketchStoryStage.Ink ink=new SketchStoryStage.Ink(id,frames);JungleCelInk d=ink.d.at(640,140,size,0);
        if(s.getString("kind").equals("lantern"))lantern(ink,d);
        else if(s.getString("kind").equals("parcel"))parcel(ink,d);
        else staff(ink,d);
        JSONArray objects=ink.layer.getJSONArray("objects");
        if(!floor&&existingKeys(doc)+(long)objects.length()*frames+actor.getJSONArray("keys").length()>SketchDocument.MAX_KEYS)throw SketchDocument.bad("Prop exact attachment exceeds 4096 transform keys; shorten the shot or use fewer props");
        double fit=Math.min(w/1280d,h/720d),originX=w/2d,originY=(h-720*fit)/2+140*fit;
        JSONArray positions=new JSONArray();
        if(!floor)for(int frame=0;frame<frames;frame++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Actor prop construction cancelled");
            float[] point=hand(doc,actor,s.getString("attachment"),frame);
            positions.put(new JSONArray().put((point[0]-originX)/w).put((point[1]-originY)/h));
        }
        for(int i=0;i<objects.length();i++){
            JSONObject object=objects.getJSONObject(i);SketchStoryStage.fit(object,w,h);
            if(floor)object.getJSONObject("transform").put("x",(s.getDouble("x")*w-originX)/w).put("y",(s.getDouble("y")*h-originY)/h);
            else {
                JSONArray keys=new JSONArray();for(int frame=0;frame<frames;frame++){
                    JSONArray point=positions.getJSONArray(frame);keys.put(new JSONObject().put("frame",frame).put("interpolation","hold")
                            .put("transform",SketchDocument.transform().put("x",point.getDouble(0)).put("y",point.getDouble(1))));
                }
                object.put("keys",keys);
            }
        }
        if(floor)SketchStoryStage.append(doc,layerId,objects);
        else appendHeld(doc,layerId,actor,objects,id,s.getString("attachment"));
    }
    /** Validate the body split, ink and retained hand overlay together before touching caller state. */
    private static void appendHeld(JSONObject doc,String layerId,JSONObject actor,JSONArray ink,String id,String side)throws Exception {
        JSONObject candidate=SketchDocument.copy(doc),layer=SketchDocument.layer(candidate,layerId);
        JSONObject body=SketchDocument.object(candidate,actor.getString("id"));body.getJSONObject("human").put("parts","without_"+side+"_hand");
        JSONArray objects=layer.getJSONArray("objects");int first=objects.length();for(int i=0;i<ink.length();i++)objects.put(ink.getJSONObject(i));
        // Placement checks consume ordinary curve commands; check them before the retained overlay.
        SketchStoryStage.validatePlacement(candidate,layerId,first);
        JSONObject overlay=SketchDocument.copy(actor).put("id",id+"_grip");overlay.getJSONObject("human").put("parts",side+"_hand");objects.put(overlay);
        SketchDocument.validate(candidate);
        SketchDocument.layer(doc,layerId).put("objects",objects);
    }
    /** Parent layer transform deliberately stays out: both human and prop inherit it once. */
    private static float[] hand(JSONObject doc,JSONObject actor,String attachment,int frame)throws Exception {
        JSONObject s=actor.getJSONObject("human").getJSONObject("settings");
        double[] projected=SketchHumanFigure.grip(s,SketchHumanAnimation.pose(actor,frame),attachment.equals("near"));
        int w=doc.getInt("width"),h=doc.getInt("height");double scale=Math.min(w/1280d,h/720d)*s.getDouble("size");
        float[] point={(float)(w*s.getDouble("x")+projected[0]*scale),(float)(h*s.getDouble("floorY")+projected[1]*scale)};
        Matrix objectTransform=SketchFrameRenderer.matrix(SketchDocument.transformAt(actor,frame),w,h);objectTransform.mapPoints(point);return point;
    }
    private static long existingKeys(JSONObject doc)throws Exception {
        long count=0;JSONArray layers=doc.getJSONArray("layers");for(int l=0;l<layers.length();l++){JSONObject layer=layers.getJSONObject(l);count+=layer.getJSONArray("keys").length();JSONArray objects=layer.getJSONArray("objects");for(int i=0;i<objects.length();i++)count+=objects.getJSONObject(i).getJSONArray("keys").length();}return count;
    }
    private static void lantern(SketchStoryStage.Ink a,JungleCelInk d)throws Exception {
        a.part("glow",()->d.ellipse("#20FFC775",null,1,0,61,39,52));
        a.part("handle",()->d.ink(null,"#FF49514B",2.4).m(0,0).c(-13,0,-20,14,-16,29).m(0,0).c(13,0,20,14,16,29).end());
        a.part("glass",()->d.ink("#FFF7CA79",null,1).m(-19,33).q(0,28,19,33).l(17,86).q(0,94,-17,86).z().end());
        JSONObject glass=a.layer.getJSONArray("objects").getJSONObject(a.layer.getJSONArray("objects").length()-1);
        JSONArray commands=glass.getJSONArray("commands");double x=commands.getJSONArray(0).getDouble(1),top=commands.getJSONArray(0).getDouble(2);
        glass.put("gradient",new JSONObject().put("startColor","#FFFFEBA9").put("endColor","#FFE89D43").put("x0",x).put("y0",top).put("x1",x).put("y1",commands.getJSONArray(2).getDouble(2)));
        a.part("cage_caps",()->d.ink("#FF566057","#FF283E3B",1.3).m(-23,30).l(0,20).l(23,30).l(21,35).l(-21,35).z().m(-20,86).q(0,92,20,86).l(22,94).q(0,101,-22,94).z().end());
        a.part("cage",()->d.ink(null,"#FF344840",1.6).m(-19,34).l(-17,86).m(19,34).l(17,86).m(0,33).l(0,91).m(-17,86).q(0,92,17,86).end());
    }
    private static void parcel(SketchStoryStage.Ink a,JungleCelInk d)throws Exception {
        a.part("handle",()->d.ink(null,"#FF7C6648",1.8).m(0,0).q(-14,-6,-7,9).q(0,15,0,0).q(14,-6,7,9).q(1,15,0,0).end());
        a.part("paper",()->d.ink("#FFC3AC83","#FF645B48",1.4).m(-30,12).l(18,8).l(32,22).l(29,59).l(-24,65).l(-32,52).z().end());
        a.part("fold",()->d.poly("#FFA38C65",null,1,18,8,32,22,29,59,12,48));
        a.part("ribbon",()->d.ink(null,"#FF7D6848",2.1).m(0,10).l(-2,61).m(-31,32).l(30,28).m(-24,15).l(13,12).end());
    }
    private static void staff(SketchStoryStage.Ink a,JungleCelInk d)throws Exception {
        a.part("shaft",()->d.ink(null,"#FF705A42",7).m(0,-25).q(4,47,1,98).l(3,163).end());
        a.part("grain",()->d.ink(null,"#FFC1A17A",1.3).m(-1,-20).q(2,46,0,93).m(1,112).l(2,153).end());
        a.part("ferrule",()->d.ink("#FF6D7974","#FF374C45",1).m(-1,152).l(6,152).l(7,166).q(3,171,-1,166).z().end());
        a.part("handle",()->d.ink(null,"#FF394C42",1.6).m(0,0).l(4,0).m(-4,-8).l(3,-9).m(-3,-3).l(4,-4).m(-3,4).l(4,3).m(-3,9).l(4,8).end());
    }
}
