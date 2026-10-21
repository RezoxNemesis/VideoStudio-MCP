package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import java.util.*;

/** Shared, original eye authoring. Native curve intersections bake to normal editable vector cels. */
final class SketchEyeAnimation {
    private SketchEyeAnimation(){}
    static final String[] CHANNELS={"gazeX","gazeY","leftOpen","rightOpen","pupilScale","vergence"};
    static JSONObject defaults()throws Exception {
        return new JSONObject().put("style","almond").put("centerX",.5).put("centerY",.5).put("width",.22).put("height",.24)
                .put("spacing",.32).put("irisSize",.95).put("pupilSize",.40).put("irisColor","#FF338F9C")
                .put("scleraColor","#FFFFFCF4").put("ink","#FF253444").put("outlinePx",3).put("tilt",0).put("gloss",true);
    }
    static void apply(JSONObject doc,JSONObject operation)throws Exception {
        apply(doc,operation,false);
    }
    static void applyFace(JSONObject doc,JSONObject operation)throws Exception {
        apply(doc,operation,true);
    }
    private static void apply(JSONObject doc,JSONObject operation,boolean compact)throws Exception {
        SketchDocument.fields(operation,"type,layerId,eyeId,settings,preset,poses");
        String id=SketchDocument.id(operation,"eyeId");if(id.length()>36)throw SketchDocument.bad("Eye ID must fit 36 characters");
        JSONObject layer=SketchDocument.layer(doc,SketchDocument.id(operation,"layerId"));if(layer.getBoolean("locked"))throw SketchDocument.bad("Layer is locked");
        JSONArray layers=doc.getJSONArray("layers");for(int l=0;l<layers.length();l++){
            JSONArray objects=layers.getJSONObject(l).getJSONArray("objects");for(int n=0;n<objects.length();n++)
                if(objects.getJSONObject(n).getString("id").startsWith(id+"_"))throw SketchDocument.bad("Eye ID already exists; choose a new identity");
        }
        JSONObject settings=settings(operation.getJSONObject("settings"));int frames=doc.getInt("frameCount");
        if(frames<24||frames>240)throw SketchDocument.bad("Eye construction needs 24..240 frames; existing timing is preserved");
        String preset=operation.optString("preset","look_blink");
        if(!Set.of("look_blink","tracking","double_blink").contains(preset))throw SketchDocument.bad("Unknown eye motion preset");
        JSONArray poses=operation.has("poses")?operation.getJSONArray("poses"):preset(doc,preset);validatePoses(poses,frames);
        JSONObject candidate=SketchDocument.copy(doc);JSONArray additions=SketchDocument.layer(candidate,layer.getString("id")).getJSONArray("objects");
        double w=doc.getInt("width"),h=doc.getInt("height"),ew=settings.getDouble("width")*w,eh=settings.getDouble("height")*h;
        Matrix tilt=new Matrix();tilt.setRotate((float)settings.getDouble("tilt"),(float)(settings.getDouble("centerX")*w),(float)(settings.getDouble("centerY")*h));
        for(int side=0;side<2;side++){
            double cx=(settings.getDouble("centerX")+(side==0?-.5:.5)*settings.getDouble("spacing"))*w,cy=settings.getDouble("centerY")*h;
            ArrayList<JSONObject> prior=new ArrayList<>();double[] previous=null;
            for(int f=0;f<frames;f++){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Eye construction cancelled");
                double[] state=sample(poses,f);
                if(previous!=null&&Arrays.equals(previous,state)){for(JSONObject object:prior)object.put("exposure",object.getInt("exposure")+1);continue;}
                prior=new ArrayList<>();previous=state;String prefix=id+"_"+(side==0?"left":"right")+"_f"+f;
                double open=state[side==0?2:3];
                if(open<.001){
                    Path closed=new Path();closed.moveTo((float)(cx-ew/2),(float)cy);
                    if("round".equals(settings.getString("style")))closed.lineTo((float)(cx+ew/2),(float)cy);
                    else closed.cubicTo((float)(cx-ew*.24),(float)(cy+eh*.4),(float)(cx+ew*.24),(float)(cy+eh*.4),(float)(cx+ew/2),(float)cy);
                    add(additions,prior,prefix+"_rim",closed,false,settings.getString("ink"),settings,w,h,f,tilt);continue;
                }
                Path aperture=aperture(settings.getString("style"),cx,cy,ew,eh,open);
                add(additions,prior,prefix+"_sclera",aperture,true,settings.getString("scleraColor"),settings,w,h,f,tilt);
                // Iris and pupil remain circular (or a creature slit) while lids occlude them.
                // Neither is scaled vertically to fake a blink.
                double radius=eh*settings.getDouble("irisSize")/2;
                double ix=cx+ew*(state[0]*.27+(side==0?1:-1)*state[5]*.17),iy=cy+eh*state[1]*.30;
                Path iris=oval(ix,iy,radius,radius);
                JSONObject irisObject=add(additions,prior,prefix+"_iris",intersect(iris,aperture),true,settings.getString("irisColor"),settings,w,h,f,tilt);
                if(irisObject!=null){
                    float[] light={(float)ix,(float)iy};tilt.mapPoints(light);
                    irisObject.put("gradient",new JSONObject().put("type","radial").put("cx",light[0]/w).put("cy",light[1]/h)
                            .put("radius",radius/Math.min(w,h)).put("colors",new JSONArray().put(tone(settings.getString("irisColor"),1.45)).put(settings.getString("irisColor")).put(tone(settings.getString("irisColor"),.48)))
                            .put("stops",new JSONArray("[0,0.68,1]")));
                }
                double pr=radius*settings.getDouble("pupilSize")*state[4];
                Path pupil=oval(ix,iy,"slit".equals(settings.getString("style"))?pr*.26:pr,pr);
                add(additions,prior,prefix+"_pupil",intersect(pupil,aperture),true,"#FF132C36",settings,w,h,f,tilt);
                if(settings.getBoolean("gloss"))add(additions,prior,prefix+"_glint",intersect(oval(ix-radius*.31,iy-radius*.36,radius*.15,radius*.15),aperture),true,"#FFFFFEF5",settings,w,h,f,tilt);
                // A thin curved upper-lid shadow, rather than a rectangular band
                // across the sclera and iris.
                Path shifted=new Path(aperture);shifted.offset(0,(float)(eh*.10*open));Path shade=new Path();
                if(!shade.op(aperture,shifted,Path.Op.DIFFERENCE))throw SketchDocument.bad("Native lid shadow intersection failed");
                add(additions,prior,prefix+"_shade",shade,true,"#22323D58",settings,w,h,f,tilt);
                add(additions,prior,prefix+"_rim",aperture,false,settings.getString("ink"),settings,w,h,f,tilt);
            }
        }
        if(compact){additions=SketchFacialCelSharing.eyes(additions,id);SketchDocument.layer(candidate,layer.getString("id")).put("objects",additions);}
        SketchDocument.validate(candidate);layer.put("objects",additions);
    }
    static JSONObject settings(JSONObject input)throws Exception {
        SketchDocument.fields(input,"style,centerX,centerY,width,height,spacing,irisSize,pupilSize,irisColor,scleraColor,ink,outlinePx,tilt,gloss");
        JSONObject s=defaults();Iterator<String> fields=input.keys();while(fields.hasNext()){String key=fields.next();s.put(key,input.get(key));}
        if(!Set.of("round","almond","slit").contains(s.getString("style")))throw SketchDocument.bad("Choose round, almond or slit eyes");
        for(String key:new String[]{"centerX","centerY"})SketchDocument.number(s,key,.05,.95);
        SketchDocument.number(s,"width",.04,.35);SketchDocument.number(s,"height",.03,.45);SketchDocument.number(s,"spacing",.06,.6);
        SketchDocument.number(s,"irisSize",.4,1.5);SketchDocument.number(s,"pupilSize",.15,.6);SketchDocument.number(s,"outlinePx",1,8);SketchDocument.number(s,"tilt",-35,35);SketchDocument.bool(s,"gloss");
        for(String key:new String[]{"irisColor","scleraColor","ink"})SketchDocument.color(s.getString(key));return s;
    }
    static void validatePoses(JSONArray poses,int frames)throws Exception {
        if(poses.length()<1||poses.length()>240)throw SketchDocument.bad("Use 1..240 eye pose keys");int previous=-1;
        for(int n=0;n<poses.length();n++){
            JSONObject p=poses.getJSONObject(n);SketchDocument.fields(p,"frame,gazeX,gazeY,leftOpen,rightOpen,pupilScale,vergence,interpolation,curve");
            int frame=(int)SketchDocument.integer(p,"frame",0,frames-1);if(frame<=previous||n==0&&frame!=0)throw SketchDocument.bad("Eye keys start at frame zero and strictly increase");previous=frame;
            for(int c=0;c<CHANNELS.length;c++)if(p.has(CHANNELS[c]))SketchDocument.number(p,CHANNELS[c],c<2?-1:c==4?.5:0,c==4?1.5:c==5?.5:1);
            SketchEasing.validate(p);
        }
    }
    static double[] sample(JSONArray poses,int frame)throws Exception {
        return sample(poses,(double)frame);
    }
    static double[] sample(JSONArray poses,double frame)throws Exception {
        double[] a={0,0,1,1,1,0},b=a.clone();JSONObject left=null,right=null;
        for(int i=0;i<poses.length();i++){
            JSONObject key=poses.getJSONObject(i);double[] resolved=a.clone();for(int c=0;c<CHANNELS.length;c++)if(key.has(CHANNELS[c]))resolved[c]=key.getDouble(CHANNELS[c]);
            if(key.getInt("frame")<=frame){left=key;a=resolved;}else{right=key;b=resolved;break;}
        }
        double time=right==null||left==null?0:SketchEasing.sample(left.optString("interpolation","smooth"),left.optJSONArray("curve"),(frame-left.getInt("frame"))/(double)(right.getInt("frame")-left.getInt("frame")));
        for(int c=0;c<a.length;c++){a[c]+=time*(b[c]-a[c]);if(a[c]==0)a[c]=0;}return a;
    }
    static JSONArray preset(JSONObject document,String preset)throws Exception {
        int frames=document.getInt("frameCount"),fps=document.getInt("fps");JSONArray keys=new JSONArray();
        if("tracking".equals(preset)){
            for(int f=0;f<frames;f++){
                double seconds=f/(double)fps,angle=2*Math.PI*seconds/3.2,blink=Math.max(blink(seconds,1.65),blink(seconds,3.25));
                keys.put(pose(f,.78*Math.sin(angle),-.60*Math.cos(angle),1-blink,1-blink,1,0).put("interpolation","linear"));
            }
            return keys;
        }
        if("double_blink".equals(preset)){
            for(int f=0;f<frames;f++){
                double seconds=f/(double)fps,b=Math.max(blink(seconds,.9),blink(seconds,1.22)),wink=blink(seconds,2.65);
                double gaze=.75*Math.sin(Math.min(1,seconds/2.2)*Math.PI*2);
                keys.put(pose(f,gaze,-.12,1-Math.max(b,wink),1-b,1+.24*Math.sin(seconds*Math.PI/2),0).put("interpolation","linear"));
            }
            return keys;
        }
        double[][] states={{0,0,0,1,1,1,0},{.35,0,0,1,1,1,0},{.45,-.85,-.12,1,1,1,0},{.95,-.85,-.12,1,1,1,0},
                {1.05,.85,.05,1,1,1,0},{1.50,.85,.05,1,1,1,0},{1.6,.85,.05,0,0,1,0},{1.67,.85,.05,0,0,1,0},
                {1.85,0,-.65,1,1,1,0},{2.2,0,-.65,1,1,1,0},{2.3,0,.65,1,1,1,0},{2.65,0,.65,1,1,1,0},
                {2.8,0,0,1,1,1.25,.5},{3.1,0,0,1,1,1.25,.5},{3.23,0,0,1,1,1,0},{3.40,0,0,0,1,1,0},{3.62,0,0,1,1,1,0}};
        int previous=-1;
        for(double[] row:states){int frame=(int)Math.round(row[0]*fps);if(frame>=frames||frame<=previous)continue;keys.put(pose(frame,row[1],row[2],row[3],row[4],row[5],row[6]));previous=frame;}
        if(previous<frames-1)keys.put(pose(frames-1,0,0,1,1,1,0));return keys;
    }
    static JSONObject pose(int frame,double x,double y,double left,double right,double pupil,double vergence)throws Exception {
        return new JSONObject().put("frame",frame).put("gazeX",x).put("gazeY",y).put("leftOpen",left).put("rightOpen",right).put("pupilScale",pupil).put("vergence",vergence).put("interpolation","smooth");
    }
    private static double blink(double time,double peak){
        double close=peak-.10,hold=peak+.045,end=hold+.17;
        if(time<=close||time>=end)return 0;if(time<=peak)return ease((time-close)/.10);if(time<=hold)return 1;return 1-ease((time-hold)/.17);
    }
    private static double ease(double t){return t*t*(3-2*t);}
    static Path aperture(String style,double x,double y,double w,double h,double open){
        Path p=new Path();if("round".equals(style)){p.addOval((float)(x-w/2),(float)(y-h*open/2),(float)(x+w/2),(float)(y+h*open/2),Path.Direction.CW);return p;}
        // Fixed corners; 80% of closure travel belongs to the upper lid.
        double meet=h*.3*(1-open),top=(meet-h*open/2)*4/3,bottom=(meet+h*open/2)*4/3;
        p.moveTo((float)(x-w/2),(float)y);p.cubicTo((float)(x-w*.24),(float)(y+top),(float)(x+w*.24),(float)(y+top),(float)(x+w/2),(float)y);
        p.cubicTo((float)(x+w*.24),(float)(y+bottom),(float)(x-w*.24),(float)(y+bottom),(float)(x-w/2),(float)y);p.close();return p;
    }
    private static Path oval(double x,double y,double rx,double ry){Path p=new Path();p.addOval((float)(x-rx),(float)(y-ry),(float)(x+rx),(float)(y+ry),Path.Direction.CW);return p;}
    private static Path intersect(Path first,Path mask){Path p=new Path();if(!p.op(first,mask,Path.Op.INTERSECT))throw SketchDocument.bad("Native eye contour intersection failed");return p;}
    private static JSONObject add(JSONArray objects,List<JSONObject> current,String id,Path input,boolean fill,String color,JSONObject settings,double width,double height,int frame,Matrix matrix)throws Exception {
        if(input.isEmpty())return null;Path shape=new Path(input);shape.transform(matrix);RectF bounds=new RectF();shape.computeBounds(bounds,true);
        if(bounds.left<0||bounds.top<0||bounds.right>width||bounds.bottom>height)throw SketchDocument.bad("Eye geometry leaves the canvas; adjust position, spacing or tilt");
        float[] points=shape.approximate(.35f);if(points.length<6)return null;JSONArray commands=new JSONArray();
        for(int i=0;i<points.length;i+=3)commands.put(new JSONArray().put(i==0||i>0&&points[i]==points[i-3]?"M":"L").put(round(points[i+1]/width)).put(round(points[i+2]/height)));
        if(fill)commands.put(new JSONArray().put("Z"));
        JSONObject object=SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind","path").put("frame",frame).put("exposure",1)
                .put("commands",commands).put("fill",fill).put("color",color).put("width",settings.getDouble("outlinePx")/Math.min(width,height)));
        objects.put(object);current.add(object);return object;
    }
    private static double round(double value){return Math.rint(value*1e6)/1e6;}
    private static String tone(String color,double scale){int c=Color.parseColor(color);return String.format(Locale.ROOT,"#FF%02X%02X%02X",Math.min(255,(int)(Color.red(c)*scale)),Math.min(255,(int)(Color.green(c)*scale)),Math.min(255,(int)(Color.blue(c)*scale)));}
}
