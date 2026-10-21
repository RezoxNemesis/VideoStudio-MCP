package com.rezoxnemesis.videostudio;

import org.json.*;
import java.util.*;

/** Bounded native one-axis impact model. Both bodies bake to ordinary editable cels. */
public final class SketchSolidCollision {
    private SketchSolidCollision(){}
    public static JSONObject defaults()throws Exception{
        return new JSONObject().put("ballDiameter",150).put("boxSize",new JSONArray("[170,190,150]"))
            .put("startX",-360).put("boxX",100).put("speed",260).put("ballMass",1).put("boxMass",3)
            .put("restitution",.7).put("friction",100).put("squash",.22)
            .put("camera",new JSONObject("{\"focal\":1000,\"distance\":1100,\"height\":310,\"horizon\":0.2}"));
    }
    static final class Motion {
        final JSONObject settings;final int frames,fps,contact;final double radius,halfWidth,start,boxStart,approach,ballVelocity,boxVelocity,friction,squash;
        Motion(JSONObject document,JSONObject input)throws Exception{
            SketchDocument.fields(input,"ballDiameter,boxSize,startX,boxX,speed,ballMass,boxMass,restitution,friction,squash,camera");
            settings=defaults();Iterator<String> fields=input.keys();while(fields.hasNext()){String field=fields.next();settings.put(field,input.get(field));}
            frames=document.getInt("frameCount");fps=document.getInt("fps");if(frames<48||frames>240)throw SketchDocument.bad("Impact scenes require 48..240 frames; use separate shots for longer stories");NativeFrameClock.requireFps(fps);
            radius=SketchDocument.number(settings,"ballDiameter",20,400)/2;JSONArray size=settings.getJSONArray("boxSize");if(size.length()!=3)throw SketchDocument.bad("Box size requires three dimensions");for(int n=0;n<3;n++)SketchDocument.scalar(size.get(n),20,400,"Box size");halfWidth=size.getDouble(0)/2;
            start=SketchDocument.number(settings,"startX",-2048,2048);boxStart=SketchDocument.number(settings,"boxX",-2048,2048);double gap=boxStart-halfWidth-radius-start;
            if(gap<=0)throw SketchDocument.bad("Ball must start clear and to the left of the box");
            double speed=SketchDocument.number(settings,"speed",20,1000);long contactFrame=Math.round(gap/speed*fps);if(contactFrame<1||contactFrame>=frames-8)throw SketchDocument.bad("Contact must occur inside the shot with at least eight recovery frames");contact=(int)contactFrame;approach=gap*fps/contact;
            double ballMass=SketchDocument.number(settings,"ballMass",.1,100),boxMass=SketchDocument.number(settings,"boxMass",.1,100),restitution=SketchDocument.number(settings,"restitution",0,1);
            ballVelocity=(ballMass-restitution*boxMass)/(ballMass+boxMass)*approach;boxVelocity=(1+restitution)*ballMass/(ballMass+boxMass)*approach;
            friction=SketchDocument.number(settings,"friction",20,1200);squash=SketchDocument.number(settings,"squash",0,.35);
        }
        double displacement(double velocity,double seconds){double t=Math.min(Math.max(0,seconds),Math.abs(velocity)/friction);return Math.signum(velocity)*(Math.abs(velocity)*t-.5*friction*t*t);}
        double compression(int frame){double time=(frame-contact)/(double)fps;return time<=0||time>=.24?0:Math.sin(Math.PI*time/.24);}
        double scaleX(boolean ball,int frame){return 1-squash*(ball?1:.35)*compression(frame);}
        double x(boolean ball,int frame){
            if(frame<=contact)return ball?start+approach*frame/fps:boxStart;
            double elapsed=(frame-contact)/(double)fps,base=ball?boxStart-halfWidth-radius:boxStart;
            double compressionOffset=ball?radius*(1-scaleX(true,frame)):halfWidth*(scaleX(false,frame)-1);
            return base+displacement(ball?ballVelocity:boxVelocity,elapsed)+compressionOffset;
        }
    }
    /** Returns native programs, also used by tests to inspect actual compiled geometry. */
    static JSONArray programs(JSONObject document,JSONObject settings)throws Exception{
        Motion motion=new Motion(document,settings);TreeSet<Integer> samples=new TreeSet<>();int step=(int)Math.ceil((motion.frames-1)/44d);
        for(int frame=0;frame<motion.frames;frame+=step)samples.add(frame);samples.add(motion.frames-1);samples.add(motion.contact-1);samples.add(motion.contact);
        for(int f=motion.contact+1;f<=Math.min(motion.frames-1,motion.contact+(int)Math.ceil(.24*motion.fps));f++)samples.add(f);
        for(double velocity:new double[]{motion.ballVelocity,motion.boxVelocity}){int stop=motion.contact+(int)Math.ceil(Math.abs(velocity)/motion.friction*motion.fps);if(stop<motion.frames){samples.add(stop);if(stop>0)samples.add(stop-1);}}
        // Sampling stays below the existing 64-pose domain even at 60 FPS.
        if(samples.size()>64)throw SketchDocument.bad("Impact pose sampling exceeds 64 keys; shorten the shot or reduce FPS");
        JSONArray result=new JSONArray();
        for(boolean ball:new boolean[]{true,false}){
            JSONObject p=new JSONObject().put("shape",ball?"sphere":"box").put("size",ball?new JSONArray().put(motion.radius*2).put(motion.radius*2).put(motion.radius*2):motion.settings.getJSONArray("boxSize"))
                .put("camera",motion.settings.getJSONObject("camera")).put("ground",true).put("groundLock",true).put("softShadow",true).put("lighting",true).put("scaleSpace","world").put("preserveVolume",true).put("holdLast",true).put("mergeHolds",true).put("ink","#FF183342").put("strokePx",2);
            if(ball)p.put("meshDetail","fine").put("rollingRadius",motion.radius).put("colors",new JSONArray("[\"#FFFFC65C\",\"#FFFFE7A0\",\"#FF30B6AC\",\"#FF8DE2D2\",\"#FF376496\",\"#FF95B9DF\"]"));
            else p.put("colors",new JSONArray("[\"#FF278F98\",\"#FF66C9BC\",\"#FF224E75\",\"#FF447A9A\",\"#FFA5E1CE\",\"#FF36677B\"]"));
            JSONArray keys=new JSONArray();for(int f:samples){double sx=motion.scaleX(ball,f);keys.put(new JSONObject().put("frame",f).put("x",motion.x(ball,f)).put("elevation",0).put("depth",0).put("scaleX",sx).put("scaleY",1/sx).put("scaleZ",1).put("interpolation","linear"));}
            // Exact quadratic deceleration between sparse keys avoids velocity steps.
            double velocity=ball?motion.ballVelocity:motion.boxVelocity,stopTime=Math.abs(velocity)/motion.friction;
            for(int n=0;n+1<keys.length();n++){
                JSONObject a=keys.getJSONObject(n),b=keys.getJSONObject(n+1);double startTime=(a.getInt("frame")-motion.contact)/(double)motion.fps,endTime=(b.getInt("frame")-motion.contact)/(double)motion.fps;
                double delta=b.getDouble("x")-a.getDouble("x");if(startTime<.24||endTime>stopTime||Math.abs(delta)<1e-9)continue;
                double duration=endTime-startTime,startVelocity=Math.signum(velocity)*(Math.abs(velocity)-motion.friction*startTime),endVelocity=Math.signum(velocity)*(Math.abs(velocity)-motion.friction*endTime);
                double first=Math.max(0,Math.min(1,startVelocity*duration/(3*delta))),second=Math.max(0,Math.min(1,1-endVelocity*duration/(3*delta)));
                a.put("interpolation","bezier").put("curve",new JSONArray().put(1d/3).put(first).put(2d/3).put(second)).put("scaleInterpolation","linear");
            }
            p.put("keys",keys);result.put(p);
        }
        return result;
    }
    public static void apply(JSONObject document,JSONObject operation)throws Exception{
        SketchDocument.fields(operation,"type,layerId,sceneId,settings");String id=SketchDocument.id(operation,"sceneId");if(id.length()>36)throw SketchDocument.bad("Scene ID must fit 36 characters");String layerId=SketchDocument.id(operation,"layerId");JSONObject target=SketchDocument.layer(document,layerId);if(target.getBoolean("locked"))throw SketchDocument.bad("Layer is locked");
        JSONArray programs=programs(document,operation.getJSONObject("settings"));JSONObject candidate=SketchDocument.copy(document);JSONObject layer=SketchDocument.layer(candidate,layerId);JSONArray prior=layer.getJSONArray("objects");
        // Compile in a detached candidate. Failure of either actor never mutates the caller.
        layer.put("objects",new JSONArray());SketchSolidOps.apply(candidate,new JSONObject().put("type","add_solid").put("layerId",layerId).put("solidId",id+"_ball").put("program",programs.getJSONObject(0)));
        SketchSolidOps.apply(candidate,new JSONObject().put("type","add_solid").put("layerId",layerId).put("solidId",id+"_box").put("program",programs.getJSONObject(1)));
        JSONArray compiled=layer.getJSONArray("objects"),ordered=new JSONArray();for(int n=0;n<prior.length();n++)ordered.put(prior.get(n));
        // Floor shadows belong behind all scene surfaces; omit duplicate floor grids.
        for(int pass=0;pass<2;pass++)for(int n=0;n<compiled.length();n++){JSONObject object=compiled.getJSONObject(n);String objectId=object.getString("id");if(objectId.endsWith("_ground"))continue;boolean shadow=objectId.endsWith("_shadow");if(shadow==(pass==0))ordered.put(object);}
        layer.put("objects",ordered);SketchDocument.validate(candidate);document.put("paletteStyles",candidate.getJSONArray("paletteStyles"));target.put("objects",ordered);
    }
}
