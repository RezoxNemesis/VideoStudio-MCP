package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import java.util.*;

/** Authored 2D facial views, with attached native eye/mouth anatomy and editable cels. */
final class SketchFaceAnimation {
    private SketchFaceAnimation(){}
    static final String[] CHANNELS={"gazeX","gazeY","leftOpen","rightOpen","pupilScale","vergence","open","wide","smile","jawX","tongue","upperTeeth","lowerTeeth","roundness","browRaise","browTilt"};
    static JSONObject defaults()throws Exception{return new JSONObject().put("view","front").put("shape","oval").put("eyeStyle","almond").put("mouthStyle","soft")
            .put("centerX",.5).put("centerY",.43).put("width",.34).put("height",.7).put("jawWidth",.68).put("noseWidth",.13).put("noseLength",.16).put("eyeSize",1).put("mouthSize",1)
            .put("skinColor","#FFD6A078").put("hairColor","#FF30334B").put("irisColor","#FF338F9C").put("lipColor","#FFAE5266").put("ink","#FF332B3C").put("outlinePx",2.5).put("tilt",0).put("hair",true);}
    static JSONObject settings(JSONObject input)throws Exception {
        SketchDocument.fields(input,"view,shape,eyeStyle,mouthStyle,centerX,centerY,width,height,jawWidth,noseWidth,noseLength,eyeSize,mouthSize,skinColor,hairColor,irisColor,lipColor,ink,outlinePx,tilt,hair");
        JSONObject s=defaults();Iterator<String> i=input.keys();while(i.hasNext()){String k=i.next();s.put(k,input.get(k));}
        if(!Set.of("front","left","right","back","high","low").contains(s.getString("view")))throw SketchDocument.bad("Unknown facial view");
        if(!Set.of("oval","round","angular").contains(s.getString("shape")))throw SketchDocument.bad("Unknown facial structure");
        if(!Set.of("round","almond","slit").contains(s.getString("eyeStyle"))||!Set.of("soft","cartoon","fang").contains(s.getString("mouthStyle")))throw SketchDocument.bad("Unknown facial anatomy style");
        SketchDocument.number(s,"centerX",.15,.85);SketchDocument.number(s,"centerY",.2,.75);SketchDocument.number(s,"width",.28,.5);SketchDocument.number(s,"height",.45,.85);
        SketchDocument.number(s,"jawWidth",.45,.9);SketchDocument.number(s,"noseWidth",.08,.25);SketchDocument.number(s,"noseLength",.08,.22);
        for(String k:new String[]{"eyeSize","mouthSize"})SketchDocument.number(s,k,.8,1.2);SketchDocument.number(s,"outlinePx",1,6);SketchDocument.number(s,"tilt",-20,20);SketchDocument.bool(s,"hair");
        for(String k:new String[]{"skinColor","hairColor","irisColor","lipColor","ink"})SketchDocument.color(s.getString(k));return s;
    }
    static void validatePoses(JSONArray poses,int frames)throws Exception {
        if(poses.length()<1||poses.length()>240)throw SketchDocument.bad("Use 1..240 face poses");int previous=-1;
        for(int n=0;n<poses.length();n++){JSONObject p=poses.getJSONObject(n);SketchDocument.fields(p,"frame,gazeX,gazeY,leftOpen,rightOpen,pupilScale,vergence,open,wide,smile,jawX,tongue,upperTeeth,lowerTeeth,roundness,browRaise,browTilt,interpolation,curve");
            int frame=(int)SketchDocument.integer(p,"frame",0,frames-1);if(frame<=previous||n==0&&frame!=0)throw SketchDocument.bad("Face keys start at zero and strictly increase");previous=frame;
            for(int c=0;c<16;c++)if(p.has(CHANNELS[c])){double min=c<2||c==8||c==9||c>=14?-1:c==4||c==7?.5:0,max=c==4||c==7?1.5:c==5?.5:1;SketchDocument.number(p,CHANNELS[c],min,max);}SketchEasing.validate(p);
        }
    }
    static double[] sample(JSONArray poses,int frame)throws Exception {
        double[] a={0,0,1,1,1,0,0,1,0,0,0,.5,.15,0,0,0},b=a.clone();JSONObject left=null,right=null;
        for(int n=0;n<poses.length();n++){JSONObject p=poses.getJSONObject(n);double[] v=a.clone();for(int c=0;c<16;c++)if(p.has(CHANNELS[c]))v[c]=p.getDouble(CHANNELS[c]);if(p.getInt("frame")<=frame){a=v;left=p;}else{b=v;right=p;break;}}
        double t=left==null||right==null?0:SketchEasing.sample(left.optString("interpolation","smooth"),left.optJSONArray("curve"),(frame-left.getInt("frame"))/(double)(right.getInt("frame")-left.getInt("frame")));
        for(int c=0;c<16;c++){a[c]+=t*(b[c]-a[c]);if(a[c]==0)a[c]=0;}return a;
    }
    static JSONObject pose(int frame,double[] values)throws Exception {JSONObject p=new JSONObject().put("frame",frame).put("interpolation","smooth");for(int c=0;c<16;c++)p.put(CHANNELS[c],values[c]);return p;}
    static JSONArray preset(JSONObject doc,String name)throws Exception {
        // Centisecond cue time keeps eye events identical at shared physical
        // instants. This clock does not change the output frame rate or count.
        int fps=doc.getInt("fps"),frames=doc.getInt("frameCount");JSONObject cueClock=new JSONObject().put("fps",100).put("frameCount",(int)Math.ceil(frames*100d/fps));
        JSONArray eyes=SketchEyeAnimation.preset(cueClock,"playful".equals(name)?"double_blink":"look_blink"),mouth=SketchMouthAnimation.preset(doc,"playful".equals(name)?"tongue":"chew".equals(name)?"chew":"articulation"),keys=new JSONArray();
        for(int f=0;f<frames;f++){double[] v=new double[16],e=SketchEyeAnimation.sample(eyes,f*100d/fps),m=SketchMouthAnimation.sample(mouth,f);System.arraycopy(e,0,v,0,6);System.arraycopy(m,0,v,6,8);
            v[14]=Math.max(-1,Math.min(1,m[0]*.8-m[2]*.2));v[15]=-m[2]*.6;keys.put(pose(f,v).put("interpolation","linear"));}return keys;
    }
    static void apply(JSONObject doc,JSONObject operation)throws Exception {
        SketchDocument.fields(operation,"type,layerId,faceId,settings,preset,poses,speech");String id=SketchDocument.id(operation,"faceId");if(id.length()>28)throw SketchDocument.bad("Face ID must fit 28 characters");
        JSONObject original=SketchDocument.layer(doc,SketchDocument.id(operation,"layerId"));if(original.getBoolean("locked"))throw SketchDocument.bad("Layer is locked");JSONArray layers=doc.getJSONArray("layers");
        for(int l=0;l<layers.length();l++){JSONArray objects=layers.getJSONObject(l).getJSONArray("objects");for(int n=0;n<objects.length();n++)if(objects.getJSONObject(n).getString("id").startsWith(id+"_"))throw SketchDocument.bad("Face identity already exists");}
        int frames=doc.getInt("frameCount");if(frames<24||frames>240)throw SketchDocument.bad("Face construction supports 24..240 frames without changing timing");JSONObject s=settings(operation.getJSONObject("settings"));
        String name=operation.optString("preset","reaction");if(!Set.of("reaction","playful","chew").contains(name))throw SketchDocument.bad("Unknown facial motion preset");
        if(operation.has("speech")&&operation.has("poses"))throw SketchDocument.bad("Choose speech cues or pose keys, not both");
        JSONArray keys=operation.has("poses")?operation.getJSONArray("poses"):preset(doc,name);
        if(operation.has("speech"))keys=SketchSpeechTiming.facePoses(doc,operation.getJSONObject("speech"),keys);validatePoses(keys,frames);
        JSONObject candidate=SketchDocument.copy(doc);String layerId=original.getString("id");JSONArray objects=SketchDocument.layer(candidate,layerId).getJSONArray("objects");int start=objects.length();
        double w=doc.getInt("width"),h=doc.getInt("height"),cx=s.getDouble("centerX")*w,cy=s.getDouble("centerY")*h,hw=s.getDouble("width")*w,hh=s.getDouble("height")*h;
        String view=s.getString("view");boolean profile=Set.of("left","right").contains(view),back="back".equals(view);double direction="left".equals(view)?-1:1;
        if("high".equals(view))hh*=.86;else if("low".equals(view))hh*=.91;
        double mouthY=cy+hh*("high".equals(view)?.26:"low".equals(view)?.19:.22);
        double[] leadingAnchors=profile?new double[frames]:null;
        Matrix identity=new Matrix();List<JSONObject> fixed=new ArrayList<>();
        Path neck=new Path();neck.moveTo((float)(cx-hw*.15),(float)(cy+hh*.34));neck.lineTo((float)(cx-hw*.17),(float)(cy+hh*.64));neck.quadTo((float)cx,(float)(cy+hh*.73),(float)(cx+hw*.17),(float)(cy+hh*.64));neck.lineTo((float)(cx+hw*.15),(float)(cy+hh*.34));neck.close();
        whole(objects,fixed,id+"_neck",neck,true,tone(s.getString("skinColor"),.82),s,w,h,frames);
        Path shoulders=new Path();shoulders.moveTo((float)(cx-hw*.17),(float)(cy+hh*.60));shoulders.quadTo((float)(cx-hw*.46),(float)(cy+hh*.65),(float)(cx-hw*.53),(float)(cy+hh*.76));shoulders.lineTo((float)(cx+hw*.53),(float)(cy+hh*.76));shoulders.quadTo((float)(cx+hw*.46),(float)(cy+hh*.65),(float)(cx+hw*.17),(float)(cy+hh*.60));shoulders.quadTo((float)cx,(float)(cy+hh*.72),(float)(cx-hw*.17),(float)(cy+hh*.60));shoulders.close();
        whole(objects,fixed,id+"_shoulders",shoulders,true,"#FF3C6876",s,w,h,frames);
        for(int side:new int[]{-1,1})if(!profile){
            double ex=cx+side*hw*.47,ey=cy+hh*.01;Path ear=SketchMouthAnimation.oval(ex,ey,hw*.075,hh*.105);whole(objects,fixed,id+"_ear_"+side,ear,true,s.getString("skinColor"),s,w,h,frames);whole(objects,fixed,id+"_ear_rim_"+side,ear,false,tone(s.getString("skinColor"),.65),s,w,h,frames);
            Path fold=new Path();fold.moveTo((float)(ex+side*hw*.015),(float)(ey-hh*.055));fold.quadTo((float)(ex-side*hw*.07),(float)ey,(float)(ex+side*hw*.015),(float)(ey+hh*.05));whole(objects,fixed,id+"_ear_fold_"+side,fold,false,tone(s.getString("skinColor"),.62),s,w,h,frames);
        }
        double[] prior=null;List<JSONObject> held=new ArrayList<>();
        for(int f=0;f<frames;f++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Face construction cancelled");double[] v=sample(keys,f),state={v[6],v[9]};
            if(prior!=null&&Arrays.equals(prior,state)){for(JSONObject o:held)o.put("exposure",o.getInt("exposure")+1);if(profile)leadingAnchors[f]=leadingAnchors[f-1];continue;}prior=state;held=new ArrayList<>();
            Path head=profile?profileHead(cx,cy,hw,hh,state[0],state[1],direction,s):frontHead(cx,cy,hw,hh,state[0],state[1],s);
            if(profile)leadingAnchors[f]=profileLipAnchor(head,mouthY-hh*.16*s.getDouble("mouthSize")*state[0]*.18,direction);
            JSONObject base=SketchMouthAnimation.add(objects,held,id+"_f"+f+"_head",head,true,s.getString("skinColor"),s,w,h,f,identity);
            if(base!=null)base.put("gradient",new JSONObject().put("startColor",tone(s.getString("skinColor"),1.12)).put("endColor",tone(s.getString("skinColor"),.88)).put("x0",(cx-hw*.4)/w).put("y0",(cy-hh*.3)/h).put("x1",(cx+hw*.45)/w).put("y1",(cy+hh*.3)/h));
            SketchMouthAnimation.add(objects,held,id+"_f"+f+"_head_rim",head,false,s.getString("ink"),s,w,h,f,identity);
        }
        if(profile){
            double ex=cx-direction*hw*.14,ey=cy+hh*.01;Path ear=SketchMouthAnimation.oval(ex,ey,hw*.065,hh*.105);
            whole(objects,fixed,id+"_near_ear",ear,true,s.getString("skinColor"),s,w,h,frames);whole(objects,fixed,id+"_near_ear_rim",ear,false,tone(s.getString("skinColor"),.65),s,w,h,frames);
            Path fold=new Path();fold.moveTo((float)(ex+direction*hw*.01),(float)(ey-hh*.055));fold.quadTo((float)(ex+direction*hw*.065),(float)ey,(float)(ex-direction*hw*.01),(float)(ey+hh*.05));whole(objects,fixed,id+"_near_ear_fold",fold,false,tone(s.getString("skinColor"),.62),s,w,h,frames);
        }
        // Heads are behind the same shared mouth/eye controllers, never flattened screenshots.
        JSONArray eyeKeys=split(keys,SketchEyeAnimation.CHANNELS),mouthKeys=split(keys,SketchMouthAnimation.CHANNELS);
        double eyeY=cy-hh*("high".equals(view)?.045:"low".equals(view)?.16:.10);
        if(!back){
            if(profile)profileEyes(objects,id,s,keys,cx,eyeY,hw,hh,w,h,direction,frames);
            else {
                JSONObject eyeSettings=new JSONObject().put("style",s.getString("eyeStyle")).put("centerX",cx/w).put("centerY",eyeY/h).put("width",hw*.25*s.getDouble("eyeSize")/w).put("height",hh*.115*s.getDouble("eyeSize")/h).put("spacing",hw*.43/w).put("irisSize",.90).put("irisColor",s.getString("irisColor")).put("ink",s.getString("ink")).put("outlinePx",s.getDouble("outlinePx"));
                SketchEyeAnimation.applyFace(candidate,new JSONObject().put("type","add_eye_animation").put("layerId",layerId).put("eyeId",id+"_eyes").put("settings",eyeSettings).put("poses",eyeKeys));objects=SketchDocument.layer(candidate,layerId).getJSONArray("objects");
            }
            JSONObject mouthSettings=new JSONObject().put("style",s.getString("mouthStyle")).put("view",view).put("centerX",(cx+(profile?direction*hw*.39:0))/w).put("centerY",mouthY/h)
                    .put("width",hw*.42*s.getDouble("mouthSize")/w).put("height",hh*.16*s.getDouble("mouthSize")/h).put("outlinePx",s.getDouble("outlinePx")).put("lipColor",s.getString("lipColor")).put("ink",s.getString("ink"));
            SketchMouthAnimation.applyFace(candidate,new JSONObject().put("type","add_mouth_animation").put("layerId",layerId).put("mouthId",id+"_mouth").put("settings",mouthSettings).put("poses",mouthKeys),leadingAnchors);objects=SketchDocument.layer(candidate,layerId).getJSONArray("objects");
            nose(objects,id,s,cx,cy,hw,hh,w,h,direction,profile,view,frames);
            brows(objects,id,s,keys,cx,eyeY,hw,hh,w,h,direction,profile,frames);
        }
        if(s.getBoolean("hair")){Path hair=hair(cx,cy,hw,hh,direction,profile,back);whole(objects,fixed,id+"_hair",hair,true,s.getString("hairColor"),s,w,h,frames);whole(objects,fixed,id+"_hair_rim",hair,false,s.getString("ink"),s,w,h,frames);
            Path lock=new Path();lock.moveTo((float)(cx-hw*.31),(float)(cy-hh*.32));lock.quadTo((float)(cx-hw*.06),(float)(cy-hh*.52),(float)(cx+hw*.25),(float)(cy-hh*.39));whole(objects,fixed,id+"_hair_light",lock,false,tone(s.getString("hairColor"),1.5),s,w,h,frames);
        }
        Matrix tilt=new Matrix();tilt.setRotate((float)s.getDouble("tilt"),(float)cx,(float)cy);
        for(int i=start;i<objects.length();i++){JSONObject o=objects.getJSONObject(i);Path p=SketchFrameRenderer.path(o.getJSONArray("commands"),(int)w,(int)h);p.transform(tilt);RectF b=new RectF();p.computeBounds(b,true);
            if(b.left<0||b.top<0||b.right>w||b.bottom>h)throw SketchDocument.bad("Face roll leaves canvas; move or resize the face");o.put("transform",SketchDocument.transform().put("anchorX",cx/w).put("anchorY",cy/h).put("rotation",s.getDouble("tilt")));}
        SketchDocument.validate(candidate);original.put("objects",objects);
    }
    /** Follow the front of the actual head curve at upper-lip height. This also
     * covers the quadratic above the straight lip segment for wide-open mouths,
     * and automatically follows nose, jaw and head-shape changes. */
    private static double profileLipAnchor(Path head,double y,double direction){
        float[] edge=head.approximate(.025f);double leading=direction<0?Double.POSITIVE_INFINITY:Double.NEGATIVE_INFINITY;
        for(int i=3;i<edge.length;i+=3){
            double ax=edge[i-2],ay=edge[i-1],bx=edge[i+1],by=edge[i+2];
            if(y<Math.min(ay,by)||y>Math.max(ay,by))continue;
            double x;
            if(Math.abs(by-ay)<.000001)x=direction<0?Math.min(ax,bx):Math.max(ax,bx);
            else x=ax+(bx-ax)*(y-ay)/(by-ay);
            leading=direction<0?Math.min(leading,x):Math.max(leading,x);
        }
        if(!Double.isFinite(leading))throw SketchDocument.bad("The profile lip height leaves the head contour");
        return leading;
    }
    private static JSONArray split(JSONArray poses,String[] names)throws Exception {JSONArray out=new JSONArray();for(int n=0;n<poses.length();n++){JSONObject p=poses.getJSONObject(n),q=new JSONObject().put("frame",p.getInt("frame"));for(String channel:names)if(p.has(channel))q.put(channel,p.get(channel));if(p.has("interpolation"))q.put("interpolation",p.get("interpolation"));if(p.has("curve"))q.put("curve",p.get("curve"));out.put(q);}return out;}
    private static Path frontHead(double x,double y,double w,double h,double open,double jaw,JSONObject s)throws Exception {
        double chinX=x+jaw*w*.055*open,chinY=y+h*(.49+.055*open),j=s.getDouble("jawWidth")*.5;String shape=s.getString("shape");double cheek="round".equals(shape)?.56:.49;
        Path p=new Path();p.moveTo((float)x,(float)(y-h*.50));p.cubicTo((float)(x+w*.37),(float)(y-h*.52),(float)(x+w*.54),(float)(y-h*.27),(float)(x+w*.49),(float)(y+h*.06));
        if("angular".equals(shape)){p.lineTo((float)(x+w*j),(float)(y+h*.34+open*h*.03));p.lineTo((float)(chinX+w*.13),(float)chinY);p.lineTo((float)(chinX-w*.13),(float)chinY);p.lineTo((float)(x-w*j),(float)(y+h*.34+open*h*.03));}
        else{p.cubicTo((float)(x+w*cheek),(float)(y+h*.30),(float)(chinX+w*j),(float)chinY,(float)chinX,(float)chinY);p.cubicTo((float)(chinX-w*j),(float)chinY,(float)(x-w*cheek),(float)(y+h*.30),(float)(x-w*.49),(float)(y+h*.06));}
        p.cubicTo((float)(x-w*.54),(float)(y-h*.27),(float)(x-w*.37),(float)(y-h*.52),(float)x,(float)(y-h*.50));p.close();return p;
    }
    private static Path profileHead(double x,double y,double w,double h,double open,double jaw,double d,JSONObject s)throws Exception {
        double nose=s.getDouble("noseLength"),chin=s.getDouble("jawWidth")-.68,skull="round".equals(s.getString("shape"))?.59:.54;
        Path p=new Path();p.moveTo((float)(x+d*w*.16),(float)(y-h*.47));p.cubicTo((float)(x+d*w*.36),(float)(y-h*.39),(float)(x+d*w*.37),(float)(y-h*.20),(float)(x+d*w*.36),(float)(y-h*.065));
        p.lineTo((float)(x+d*w*(.48+s.getDouble("noseWidth")*.65)),(float)(y+h*nose*.47));p.quadTo((float)(x+d*w*.58),(float)(y+h*nose*.81),(float)(x+d*w*.45),(float)(y+h*nose*.75));
        p.quadTo((float)(x+d*w*.46),(float)(y+h*.17),(float)(x+d*w*.53),(float)(y+h*.205));p.lineTo((float)(x+d*w*.55+jaw*w*.03),(float)(y+h*(.27+.08*open)));
        if("angular".equals(s.getString("shape"))){p.lineTo((float)(x+d*w*(.34+chin*.2)),(float)(y+h*(.46+.06*open)));p.lineTo((float)(x-d*w*(.08+chin*.2)),(float)(y+h*(.44+.04*open)));}
        else p.cubicTo((float)(x+d*w*(.48+chin*.2)),(float)(y+h*(.42+.06*open)),(float)(x+d*w*(.16+chin*.2)),(float)(y+h*(.51+.055*open)),(float)(x-d*w*(.07+chin*.2)),(float)(y+h*(.45+.04*open)));
        p.cubicTo((float)(x-d*w*skull),(float)(y+h*.29),(float)(x-d*w*(skull+.02)),(float)(y-h*.19),(float)(x-d*w*.34),(float)(y-h*.38));p.quadTo((float)(x-d*w*.10),(float)(y-h*.54),(float)(x+d*w*.16),(float)(y-h*.47));p.close();return p;
    }
    private static void profileEyes(JSONArray objects,String id,JSONObject s,JSONArray keys,double cx,double cy,double hw,double hh,double w,double h,double d,int frames)throws Exception {
        double ex=cx+d*hw*.26,ew=hw*.17*s.getDouble("eyeSize"),eh=hh*.105*s.getDouble("eyeSize");double[] previous=null;List<JSONObject> held=new ArrayList<>();Matrix matrix=new Matrix();
        for(int f=0;f<frames;f++){double[] all=sample(keys,f),v=Arrays.copyOf(all,6);if(previous!=null&&Arrays.equals(previous,v)){for(JSONObject o:held)o.put("exposure",o.getInt("exposure")+1);continue;}previous=v;held=new ArrayList<>();String prefix=id+"_near_f"+f;double opening=v[d<0?2:3];
            Path mask=SketchEyeAnimation.aperture(s.getString("eyeStyle"),ex,cy,ew,eh,opening);if(opening<.001){Path seam=new Path();seam.moveTo((float)(ex-ew/2),(float)cy);seam.quadTo((float)ex,(float)(cy+eh*.35),(float)(ex+ew/2),(float)cy);SketchMouthAnimation.add(objects,held,prefix+"_rim",seam,false,s.getString("ink"),s,w,h,f,matrix);continue;}
            SketchMouthAnimation.add(objects,held,prefix+"_sclera",mask,true,"#FFFFFCF4",s,w,h,f,matrix);double ix=ex+d*ew*.18+v[0]*ew*.12,iy=cy+v[1]*eh*.20,r=eh*.45;
            SketchMouthAnimation.add(objects,held,prefix+"_iris",SketchMouthAnimation.intersection(SketchMouthAnimation.oval(ix,iy,r*.67,r),mask),true,s.getString("irisColor"),s,w,h,f,matrix);
            SketchMouthAnimation.add(objects,held,prefix+"_pupil",SketchMouthAnimation.intersection(SketchMouthAnimation.oval(ix+d*r*.18,iy,r*("slit".equals(s.getString("eyeStyle"))?.12:.29)*v[4],r*.40*v[4]),mask),true,"#FF132C36",s,w,h,f,matrix);
            SketchMouthAnimation.add(objects,held,prefix+"_glint",SketchMouthAnimation.intersection(SketchMouthAnimation.oval(ix-r*.15,iy-r*.28,r*.12,r*.12),mask),true,"#FFFFFEF5",s,w,h,f,matrix);
            SketchMouthAnimation.add(objects,held,prefix+"_rim",mask,false,s.getString("ink"),s,w,h,f,matrix);
        }
    }
    private static void nose(JSONArray objects,String id,JSONObject s,double x,double y,double w,double h,double cw,double ch,double d,boolean profile,String view,int frames)throws Exception {
        List<JSONObject> fixed=new ArrayList<>();double nw=s.getDouble("noseWidth")*w,nl=s.getDouble("noseLength")*h;
        Path nose=new Path();if(profile){nose.moveTo((float)(x+d*w*.36),(float)(y-h*.045));nose.lineTo((float)(x+d*w*(.48+s.getDouble("noseWidth")*.65)),(float)(y+nl*.47));nose.quadTo((float)(x+d*w*.51),(float)(y+nl*.875),(float)(x+d*w*.43),(float)(y+nl*.65625));nose.close();}
        else {double ny=y+("high".equals(view)?.10:"low".equals(view)?.035:.07)*h;nose.moveTo((float)(x-nw*.18),(float)(ny-nl*.65));nose.cubicTo((float)(x-nw*.12),(float)(ny-nl*.20),(float)(x-nw*.64),(float)(ny+nl*.34),(float)(x-nw*.35),(float)(ny+nl*.40));nose.quadTo((float)x,(float)(ny+nl*.62),(float)(x+nw*.40),(float)(ny+nl*.34));nose.quadTo((float)(x+nw*.22),(float)(ny-nl*.20),(float)(x+nw*.14),(float)(ny-nl*.65));nose.close();}
        whole(objects,fixed,id+"_nose",nose,true,tone(s.getString("skinColor"),.86),s,cw,ch,frames);
        Path underside=new Path();if(profile){underside.moveTo((float)(x+d*w*.47),(float)(y+nl*.625));underside.quadTo((float)(x+d*w*.51),(float)(y+nl*.53125),(float)(x+d*w*.53),(float)(y+nl*.65625));}
        else {double ny=y+("high".equals(view)?.10:"low".equals(view)?.035:.07)*h;underside.moveTo((float)(x-nw*.38),(float)(ny+nl*.34));underside.quadTo((float)x,(float)(ny+nl*.62),(float)(x+nw*.38),(float)(ny+nl*.34));if("low".equals(view)){underside.addOval((float)(x-nw*.31),(float)(ny+nl*.26),(float)(x-nw*.08),(float)(ny+nl*.4),Path.Direction.CW);underside.addOval((float)(x+nw*.08),(float)(ny+nl*.26),(float)(x+nw*.31),(float)(ny+nl*.4),Path.Direction.CW);}}
        whole(objects,fixed,id+"_nose_underside",underside,false,tone(s.getString("skinColor"),.54),s,cw,ch,frames);
    }
    private static void brows(JSONArray objects,String id,JSONObject s,JSONArray keys,double x,double eyeY,double hw,double hh,double w,double h,double direction,boolean profile,int frames)throws Exception {
        double[] previous=null;List<JSONObject> held=new ArrayList<>();Matrix matrix=new Matrix();
        for(int f=0;f<frames;f++){double[] v=sample(keys,f),state={v[14],v[15]};if(previous!=null&&Arrays.equals(previous,state)){for(JSONObject o:held)o.put("exposure",o.getInt("exposure")+1);continue;}previous=state;held=new ArrayList<>();
            for(int side:new int[]{-1,1}){if(profile&&side!=direction)continue;double ex=x+side*hw*(profile?.26:.215),ey=eyeY-hh*(.095+.04*v[14]),ew=hw*(profile?.14:.21),tilt=side*v[15]*hh*.035;Path p=new Path();p.moveTo((float)(ex-ew/2),(float)(ey-tilt));p.quadTo((float)ex,(float)(ey-hh*.025),(float)(ex+ew/2),(float)(ey+tilt));
                JSONObject brow=SketchMouthAnimation.add(objects,held,id+"_"+side+"_f"+f+"_brow",p,false,s.getString("hairColor"),s,w,h,f,matrix);if(brow!=null)brow.put("width",Math.max(.0005,s.getDouble("outlinePx")*2/Math.min(w,h)));}
        }
    }
    private static Path hair(double x,double y,double w,double h,double d,boolean profile,boolean back){
        Path p=new Path();if(back){p.addOval((float)(x-w*.49),(float)(y-h*.51),(float)(x+w*.49),(float)(y+h*.32),Path.Direction.CW);return p;}
        if(profile){p.moveTo((float)(x-d*w*.41),(float)(y+h*.12));p.cubicTo((float)(x-d*w*.65),(float)(y-h*.18),(float)(x-d*w*.37),(float)(y-h*.56),(float)(x+d*w*.11),(float)(y-h*.52));p.quadTo((float)(x+d*w*.45),(float)(y-h*.49),(float)(x+d*w*.39),(float)(y-h*.27));p.quadTo((float)(x+d*w*.27),(float)(y-h*.23),(float)(x+d*w*.12),(float)(y-h*.30));p.quadTo((float)(x-d*w*.12),(float)(y-h*.18),(float)(x-d*w*.20),(float)(y-h*.06));p.lineTo((float)(x-d*w*.31),(float)(y+h*.13));p.close();return p;}
        p.moveTo((float)(x-w*.48),(float)(y+h*.02));p.cubicTo((float)(x-w*.64),(float)(y-h*.36),(float)(x-w*.30),(float)(y-h*.57),(float)(x+w*.10),(float)(y-h*.52));p.cubicTo((float)(x+w*.48),(float)(y-h*.55),(float)(x+w*.60),(float)(y-h*.27),(float)(x+w*.48),(float)(y+h*.02));
        p.lineTo((float)(x+w*.40),(float)(y-h*.22));p.quadTo((float)(x+w*.23),(float)(y-h*.27),(float)(x+w*.17),(float)(y-h*.34));p.quadTo((float)(x-w*.04),(float)(y-h*.22),(float)(x-w*.35),(float)(y-h*.26));p.lineTo((float)(x-w*.40),(float)(y+h*.02));p.close();return p;
    }
    private static void whole(JSONArray objects,List<JSONObject> fixed,String id,Path p,boolean fill,String color,JSONObject s,double w,double h,int frames)throws Exception {JSONObject o=SketchMouthAnimation.add(objects,fixed,id,p,fill,color,s,w,h,0,new Matrix());if(o!=null)o.put("exposure",frames);}
    private static String tone(String color,double gain){int c=Color.parseColor(color);return String.format(Locale.ROOT,"#FF%02X%02X%02X",Math.min(255,(int)(Color.red(c)*gain)),Math.min(255,(int)(Color.green(c)*gain)),Math.min(255,(int)(Color.blue(c)*gain)));}
}
