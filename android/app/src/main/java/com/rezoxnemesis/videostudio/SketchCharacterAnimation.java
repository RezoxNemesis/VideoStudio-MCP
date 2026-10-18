package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import java.util.*;

/** Native retained character construction; no reference pixels or external artwork. */
final class SketchCharacterAnimation {
    private SketchCharacterAnimation(){}
    static JSONObject defaults()throws Exception{return new JSONObject().put("view","front").put("build","slender").put("material","wool").put("detail","tailored").put("gesture","pendant").put("armLift",.8).put("headTilt",0).put("skinColor","#FFD6A078").put("hairColor","#FF252D3D").put("coatColor","#FF263847");}
    static JSONObject settings(JSONObject input)throws Exception {
        SketchDocument.fields(input,"view,build,material,detail,gesture,armLift,headTilt,skinColor,hairColor,coatColor");JSONObject s=defaults();Iterator<String> keys=input.keys();while(keys.hasNext()){String k=keys.next();s.put(k,input.get(k));}
        choice(s,"view","front","left","right","back","high","low");choice(s,"build","slender","broad");choice(s,"material","wool","leather");choice(s,"detail","contour","tailored");choice(s,"gesture","pendant","explain");
        SketchDocument.number(s,"armLift",0,1);SketchDocument.number(s,"headTilt",-12,12);for(String k:new String[]{"skinColor","hairColor","coatColor"})SketchDocument.color(s.getString(k));return s;
    }
    private static void choice(JSONObject s,String key,String... values)throws Exception {if(!Arrays.asList(values).contains(s.getString(key)))throw SketchDocument.bad("Unknown character "+key);}
    static void apply(JSONObject document,JSONObject operation)throws Exception {
        SketchDocument.fields(operation,"type,layerId,characterId,settings,speech");String id=SketchDocument.id(operation,"characterId"),layerId=SketchDocument.id(operation,"layerId");if(id.length()>20)throw SketchDocument.bad("Character identity must fit 20 characters");
        JSONObject target=SketchDocument.layer(document,layerId);if(target.getBoolean("locked"))throw SketchDocument.bad("Layer is locked");
        JSONArray layers=document.getJSONArray("layers");for(int l=0;l<layers.length();l++){JSONArray a=layers.getJSONObject(l).getJSONArray("objects");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("id").startsWith(id+"_"))throw SketchDocument.bad("Character identity already exists");}
        int frames=document.getInt("frameCount"),fps=document.getInt("fps");double seconds=frames/(double)fps;if(frames<24||frames>240||seconds<4||seconds>8||fps>60)throw SketchDocument.bad("Character performance needs 4..8 seconds, 24..240 frames, 24/30/60 FPS; timing is preserved");
        JSONObject s=settings(operation.getJSONObject("settings"));
        JSONObject source=SketchDocument.create(new JSONObject().put("sketchId",id+"_source").put("width",1280).put("height",720).put("frameCount",frames).put("fps",fps));
        JSONObject layer=source.getJSONArray("layers").getJSONObject(0);SketchCharacterArt art=new SketchCharacterArt(layer,id,s,frames);art.body();
        JSONObject face=SketchDocument.create(new JSONObject().put("sketchId",id+"_head").put("width",1280).put("height",720).put("frameCount",frames).put("fps",fps));
        JSONObject faceOp=new JSONObject().put("type","add_face_animation").put("layerId","layer_1").put("faceId",id+"_face").put("preset","reaction").put("settings",new JSONObject().put("view",s.getString("view")).put("shape","angular").put("eyeStyle","almond").put("mouthStyle","soft").put("skinColor",s.getString("skinColor")).put("hairColor",s.getString("hairColor")).put("hair",false).put("outlinePx",1.6).put("eyeSize",.8).put("noseWidth",.11).put("noseLength",.19).put("irisColor","#FF668A82").put("lipColor","#FF855D54").put("jawWidth",.62).put("mouthSize",.8));
        if(operation.has("speech"))faceOp.put("speech",operation.getJSONObject("speech"));
        else faceOp.put("poses",new JSONArray().put(new JSONObject().put("frame",0).put("gazeX",0).put("leftOpen",1).put("rightOpen",1).put("smile",.12)));
        SketchFaceAnimation.apply(face,faceOp);JSONArray faceObjects=face.getJSONArray("layers").getJSONObject(0).getJSONArray("objects"),objects=layer.getJSONArray("objects");
        Matrix headFit=new Matrix();headFit.setTranslate(640,205);headFit.preScale(.46f,.46f);headFit.preTranslate(-640,-309.6f);
        for(int i=0;i<faceObjects.length();i++){
            JSONObject o=faceObjects.getJSONObject(i);String name=o.getString("id");if(name.endsWith("_shoulders")||name.endsWith("_neck"))continue;
            map(o,headFit,1280,720,1280,720);objects.put(o);art.groups.computeIfAbsent("head",k->new ArrayList<>()).add(o);
        }
        art.hairAndPlanes();
        Matrix adultHead=new Matrix();adultHead.setScale(.82f,.82f,640,205);
        for(JSONObject o:art.groups.get("head"))map(o,adultHead,1280,720,1280,720);
        for(String group:new String[]{"body","upper","fore"})for(JSONObject o:art.groups.getOrDefault(group,Collections.emptyList()))if(o.getBoolean("fill")&&o.getString("color").equals(s.getString("coatColor"))){
            RectF b=new RectF();SketchFrameRenderer.path(o.getJSONArray("commands"),1280,720).computeBounds(b,true);
            o.put("gradient",new JSONObject().put("startColor",SketchCharacterArt.tone(s.getString("coatColor"),s.getString("material").equals("leather")?1.55:1.25)).put("endColor",SketchCharacterArt.tone(s.getString("coatColor"),.60)).put("x0",b.left/1280).put("y0",b.top/720).put("x1",b.right/1280).put("y1",b.bottom/720));
        }
        // Layering: far body, head, then near arm/hand, so a raised wrist can occlude clothing.
        JSONArray ordered=new JSONArray();for(String group:new String[]{"body","head","upper","fore","prop","hand"})for(JSONObject o:art.groups.getOrDefault(group,Collections.emptyList()))ordered.put(o);layer.put("objects",ordered);
        boolean profile=Set.of("left","right").contains(s.getString("view"));double shoulderX=profile?534:486;
        if(profile)for(String group:new String[]{"upper","fore","hand","prop"})for(JSONObject o:art.groups.getOrDefault(group,Collections.emptyList())){Matrix m=new Matrix();m.setTranslate(48,0);map(o,m,1280,720,1280,720);}
        int last=frames-1;List<Integer> samples=new ArrayList<>();
        // Keep every changing output pose exact. Share the long constant hold,
        // including its final boundary so interpolation cannot start early.
        for(int f=0;f<=last;f++){
            double[] now=pose(f/(double)fps,seconds,s.getDouble("armLift"));
            if(f==0||f==last||!Arrays.equals(now,pose((f-1)/(double)fps,seconds,s.getDouble("armLift")))||!Arrays.equals(now,pose((f+1)/(double)fps,seconds,s.getDouble("armLift"))))samples.add(f);
        }
        for(String group:new String[]{"upper","fore","hand","prop"}){
            for(JSONObject o:art.groups.getOrDefault(group,Collections.emptyList())){
                JSONArray keys=o.getJSONArray("keys");for(int f:samples){double[] p=pose(f/(double)fps,seconds,s.getDouble("armLift"));
                    double anchorY=group.equals("upper")?379:group.equals("fore")?493:group.equals("prop")?637:610;double anchorX=group.equals("prop")?shoulderX+6:shoulderX;
                    double x=shoulderX,y=anchorY,angle=p[0];if(!group.equals("upper")){x=p[2]+shoulderX-486;y=p[3];angle=p[1];}if(group.equals("hand")){x=p[4]+shoulderX-486;y=p[5];}
                    if(group.equals("prop")){double angleRad=Math.toRadians(p[1]);x=p[4]+shoulderX-486+6*Math.cos(angleRad)-27*Math.sin(angleRad);y=p[5]+6*Math.sin(angleRad)+27*Math.cos(angleRad);angle=0;}
                    keys.put(new JSONObject().put("frame",f).put("interpolation","linear").put("transform",SketchDocument.transform().put("anchorX",anchorX/1280).put("anchorY",anchorY/720).put("x",(x-anchorX)/1280).put("y",(y-anchorY)/720).put("rotation",angle)));
                }
            }
        }
        double width=document.getInt("width"),height=document.getInt("height"),scale=Math.min(width/1280,height/720)*.94,dx=(width-1280*scale)/2,dy=(height-720*scale)/2;
        // Right profile is independently constructed facial anatomy; the body uses its opposite near arm.
        boolean mirror=s.getString("view").equals("right");
        Matrix fit=new Matrix();fit.setTranslate((float)dx,(float)dy);fit.preScale((float)scale,(float)scale);
        for(String group:art.groups.keySet())for(JSONObject o:art.groups.getOrDefault(group,Collections.emptyList())){
            if(group.equals("head")){JSONObject tr=o.getJSONObject("transform");tr.put("anchorX",.5).put("anchorY",205d/720).put("rotation",s.getDouble("headTilt"));}
            else if(mirror){mirror(o);}
            fitObject(o,fit,scale,dx,dy,width,height);
        }
        JSONObject candidate=SketchDocument.copy(document);JSONArray out=SketchDocument.layer(candidate,layerId).getJSONArray("objects");for(int i=0;i<ordered.length();i++)out.put(ordered.getJSONObject(i));SketchDocument.validate(candidate);validatePlacement(candidate,layerId,out.length()-ordered.length());target.put("objects",out);
    }
    private static void validatePlacement(JSONObject doc,String layerId,int first)throws Exception {
        JSONObject layer=SketchDocument.layer(doc,layerId);JSONArray objects=layer.getJSONArray("objects");int w=doc.getInt("width"),h=doc.getInt("height"),frames=doc.getInt("frameCount");
        for(int i=first;i<objects.length();i++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Character construction cancelled");
            JSONObject o=objects.getJSONObject(i);Path geometry=SketchFrameRenderer.path(o.getJSONArray("commands"),w,h);
            for(int f=o.getInt("frame");f<o.getInt("frame")+o.getInt("exposure");f++){
                Matrix world=SketchFrameRenderer.matrix(SketchDocument.transformAt(layer,f),w,h);world.preConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(o,f),w,h));
                Path p=new Path(geometry);p.transform(world);RectF b=new RectF();p.computeBounds(b,true);
                if(b.left<0||b.top<0||b.right>w||b.bottom>h)throw SketchDocument.bad("Character pose leaves canvas through the destination layer transform");
            }
        }
    }
    /** Rooted two-segment arm evaluated in physical time. Values are angle deltas and joint centres. */
    static double[] pose(double time,double seconds,double lift){
        double u=time/seconds,a=u<.18?smooth(u/.18):u<.74?1:1-smooth((u-.74)/.26);a*=lift;
        double upper=Math.toRadians(16+24*a),fore=Math.toRadians(0-156*a);
        double ex=486-114*Math.sin(upper),ey=379+114*Math.cos(upper),wx=ex-117*Math.sin(fore),wy=ey+117*Math.cos(fore);
        return new double[]{Math.toDegrees(upper),Math.toDegrees(fore),ex,ey,wx,wy};
    }
    static double smooth(double t){t=Math.max(0,Math.min(1,t));return t*t*(3-2*t);}
    private static void mirror(JSONObject o)throws Exception {
        SketchCharacterArt.scaleX(o,-1,640);
        if(o.has("gradient")){JSONObject g=o.getJSONObject("gradient");if(g.has("type"))g.put("cx",1-g.getDouble("cx"));else{g.put("x0",1-g.getDouble("x0"));g.put("x1",1-g.getDouble("x1"));}}
        JSONArray keys=o.getJSONArray("keys");for(int i=-1;i<keys.length();i++){JSONObject t=i<0?o.getJSONObject("transform"):keys.getJSONObject(i).getJSONObject("transform");t.put("anchorX",1-t.getDouble("anchorX")).put("x",-t.getDouble("x")).put("rotation",-t.getDouble("rotation"));}
    }
    private static void fitObject(JSONObject o,Matrix fit,double scale,double dx,double dy,double width,double height)throws Exception {
        map(o,fit,1280,720,width,height);o.put("width",Math.max(.0005,o.getDouble("width")*720*scale/Math.min(width,height)));
        JSONArray keys=o.getJSONArray("keys");for(int i=-1;i<keys.length();i++){JSONObject t=i<0?o.getJSONObject("transform"):keys.getJSONObject(i).getJSONObject("transform");t.put("anchorX",(t.getDouble("anchorX")*1280*scale+dx)/width).put("anchorY",(t.getDouble("anchorY")*720*scale+dy)/height).put("x",t.getDouble("x")*1280*scale/width).put("y",t.getDouble("y")*720*scale/height);}
        Path path=SketchFrameRenderer.path(o.getJSONArray("commands"),(int)width,(int)height);for(int i=-1;i<keys.length();i++){JSONObject t=i<0?o.getJSONObject("transform"):keys.getJSONObject(i).getJSONObject("transform");Path p=new Path(path);p.transform(SketchFrameRenderer.matrix(t,(int)width,(int)height));RectF b=new RectF();p.computeBounds(b,true);if(b.left<0||b.top<0||b.right>width||b.bottom>height)throw SketchDocument.bad("Character pose leaves canvas");}
    }
    private static void map(JSONObject o,Matrix matrix,int sw,int sh,double dw,double dh)throws Exception {
        JSONArray a=o.getJSONArray("commands");for(int i=0;i<a.length();i++){JSONArray c=a.getJSONArray(i);for(int n=1;n<c.length();n+=2){float[] p={(float)(c.getDouble(n)*sw),(float)(c.getDouble(n+1)*sh)};matrix.mapPoints(p);c.put(n,p[0]/dw).put(n+1,p[1]/dh);}}
        if(o.has("gradient")){JSONObject g=o.getJSONObject("gradient");if(g.has("type")){float[] p={(float)(g.getDouble("cx")*sw),(float)(g.getDouble("cy")*sh)};matrix.mapPoints(p);g.put("cx",p[0]/dw).put("cy",p[1]/dh);g.put("radius",g.getDouble("radius")*Math.min(sw,sh)*matrix.mapRadius(1)/Math.min(dw,dh));}else for(String end:new String[]{"0","1"}){float[] p={(float)(g.getDouble("x"+end)*sw),(float)(g.getDouble("y"+end)*sh)};matrix.mapPoints(p);g.put("x"+end,p[0]/dw).put("y"+end,p[1]/dh);}}
    }
}
