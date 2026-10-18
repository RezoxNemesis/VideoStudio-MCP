package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import java.util.*;

/** Original editable mouth anatomy, sampled and intersected on Android. */
final class SketchMouthAnimation {
    private SketchMouthAnimation(){}
    static final String[] CHANNELS={"open","wide","smile","jawX","tongue","upperTeeth","lowerTeeth","roundness"};
    static JSONObject defaults()throws Exception{return new JSONObject().put("style","soft").put("centerX",.5).put("centerY",.5).put("width",.35).put("height",.35)
            .put("view","front").put("outlinePx",3).put("tilt",0).put("lipColor","#FFAE5266").put("cavityColor","#FF351F35").put("tongueColor","#FFEC8293").put("toothColor","#FFFFF6DF").put("ink","#FF332B3C");}
    static JSONObject settings(JSONObject input)throws Exception {
        return settings(input,false);
    }
    private static JSONObject settings(JSONObject input,boolean facialPart)throws Exception {
        SketchDocument.fields(input,"style,view,centerX,centerY,width,height,outlinePx,tilt,lipColor,cavityColor,tongueColor,toothColor,ink");
        JSONObject s=defaults();Iterator<String> i=input.keys();while(i.hasNext()){String k=i.next();s.put(k,input.get(k));}
        if(!Set.of("soft","cartoon","fang").contains(s.getString("style")))throw SketchDocument.bad("Choose soft, cartoon or fang anatomy");
        if(!Set.of("front","left","right","back","high","low").contains(s.getString("view")))throw SketchDocument.bad("Choose front, left, right, back, high or low view");
        for(String k:new String[]{"centerX","centerY"})SketchDocument.number(s,k,.05,.95);
        SketchDocument.number(s,"width",.08,.65);SketchDocument.number(s,"height",facialPart?.04:.05,.6);SketchDocument.number(s,"outlinePx",1,8);SketchDocument.number(s,"tilt",-35,35);
        for(String k:new String[]{"lipColor","cavityColor","tongueColor","toothColor","ink"})SketchDocument.color(s.getString(k));return s;
    }
    static void apply(JSONObject doc,JSONObject operation)throws Exception {
        apply(doc,operation,false);
    }
    static void applyFace(JSONObject doc,JSONObject operation)throws Exception {
        apply(doc,operation,true);
    }
    private static void apply(JSONObject doc,JSONObject operation,boolean facialPart)throws Exception {
        SketchDocument.fields(operation,"type,layerId,mouthId,settings,preset,poses");String id=SketchDocument.id(operation,"mouthId");
        if(id.length()>36)throw SketchDocument.bad("Mouth ID must fit 36 characters");
        JSONObject layer=SketchDocument.layer(doc,SketchDocument.id(operation,"layerId"));if(layer.getBoolean("locked"))throw SketchDocument.bad("Layer is locked");
        JSONArray layers=doc.getJSONArray("layers");for(int l=0;l<layers.length();l++){JSONArray objects=layers.getJSONObject(l).getJSONArray("objects");for(int n=0;n<objects.length();n++)
            if(objects.getJSONObject(n).getString("id").startsWith(id+"_"))throw SketchDocument.bad("Mouth ID already exists; choose a new identity");}
        JSONObject s=settings(operation.getJSONObject("settings"),facialPart);int frames=doc.getInt("frameCount");if(frames<24||frames>240)throw SketchDocument.bad("Mouth construction needs 24..240 frames; existing timing is preserved");
        String preset=operation.optString("preset","articulation");if(!Set.of("articulation","tongue","chew").contains(preset))throw SketchDocument.bad("Unknown mouth preset");
        JSONArray poses=operation.has("poses")?operation.getJSONArray("poses"):preset(doc,preset);validatePoses(poses,frames);
        JSONObject candidate=SketchDocument.copy(doc);JSONArray objects=SketchDocument.layer(candidate,layer.getString("id")).getJSONArray("objects");
        double w=doc.getInt("width"),h=doc.getInt("height"),cx=s.getDouble("centerX")*w,cy=s.getDouble("centerY")*h;
        Matrix matrix=new Matrix();matrix.setRotate((float)s.getDouble("tilt"),(float)cx,(float)cy);
        List<JSONObject> previousObjects=new ArrayList<>();double[] previous=null;
        for(int frame=0;frame<frames;frame++){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException("Mouth construction cancelled");double[] state=sample(poses,frame);
            if(previous!=null&&Arrays.equals(previous,state)){for(JSONObject o:previousObjects)o.put("exposure",o.getInt("exposure")+1);continue;}
            previous=state;previousObjects=new ArrayList<>();draw(objects,previousObjects,id+"_f"+frame,s,state,w,h,frame,matrix);
        }
        SketchDocument.validate(candidate);layer.put("objects",objects);
    }
    static void validatePoses(JSONArray poses,int frames)throws Exception {
        if(poses.length()<1||poses.length()>240)throw SketchDocument.bad("Use 1..240 mouth pose keys");int previous=-1;
        for(int n=0;n<poses.length();n++){
            JSONObject p=poses.getJSONObject(n);SketchDocument.fields(p,"frame,open,wide,smile,jawX,tongue,upperTeeth,lowerTeeth,roundness,interpolation,curve");
            int frame=(int)SketchDocument.integer(p,"frame",0,frames-1);if(frame<=previous||n==0&&frame!=0)throw SketchDocument.bad("Mouth keys start at zero and strictly increase");previous=frame;
            for(int c=0;c<8;c++)if(p.has(CHANNELS[c]))SketchDocument.number(p,CHANNELS[c],c==1?.5:c==2||c==3?-1:0,c==1?1.5:1);SketchEasing.validate(p);
        }
    }
    static double[] sample(JSONArray poses,int frame)throws Exception {
        double[] a={0,1,0,0,0,.5,.15,0},b=a.clone();JSONObject left=null,right=null;
        for(int n=0;n<poses.length();n++){JSONObject k=poses.getJSONObject(n);double[] resolved=a.clone();for(int c=0;c<8;c++)if(k.has(CHANNELS[c]))resolved[c]=k.getDouble(CHANNELS[c]);
            if(k.getInt("frame")<=frame){left=k;a=resolved;}else{right=k;b=resolved;break;}}
        double t=right==null||left==null?0:SketchEasing.sample(left.optString("interpolation","smooth"),left.optJSONArray("curve"),(frame-left.getInt("frame"))/(double)(right.getInt("frame")-left.getInt("frame")));
        for(int c=0;c<8;c++){a[c]+=t*(b[c]-a[c]);if(a[c]==0)a[c]=0;}return a;
    }
    static JSONObject pose(int f,double open,double wide,double smile,double jawX,double tongue,double upper,double lower,double round)throws Exception {
        JSONObject p=new JSONObject().put("frame",f).put("interpolation","smooth");double[] v={open,wide,smile,jawX,tongue,upper,lower,round};for(int i=0;i<8;i++)p.put(CHANNELS[i],v[i]);return p;
    }
    static JSONArray preset(JSONObject doc,String preset)throws Exception {
        int frames=doc.getInt("frameCount"),fps=doc.getInt("fps");JSONArray keys=new JSONArray();
        if("chew".equals(preset)){
            for(int f=0;f<frames;f++){double t=f/(double)fps,envelope=window(t,.25,3.45,.3),phase=2*Math.PI*(t-.25)*1.65;
                double jaw=envelope*Math.sin(phase),opening=envelope*(.18+.30*(.5+.5*Math.cos(phase)));
                keys.put(pose(f,opening,1+.055*jaw,.12,jaw*.55,0,.45,.25,0).put("interpolation","linear"));}
            return keys;
        }
        double[][] rows="tongue".equals(preset)?new double[][]{
                {0,0,1,.2,0,0,.35,.1,0},{.35,0,1,.2,0,0,.35,.1,0},{.65,.65,1,.25,0,0,.45,.1,0},
                {.95,.65,1,.25,0,.9,.45,.1,0},{1.35,.65,1,.25,0,.9,.45,.1,0},{1.65,.65,1,.25,-.7,.9,.45,.1,0},
                {2.05,.65,1,.25,.7,.9,.45,.1,0},{2.4,.65,1,.25,0,.9,.45,.1,0},{2.75,.5,1,.25,0,0,.45,.1,0},{3.1,0,1,.3,0,0,.35,.1,0},{3.6,0,1,.3,0,0,.35,.1,0}}
                :new double[][]{{0,0,1,0,0,0,.55,.1,0},{.3,0,1,0,0,0,.55,.1,0},{.7,.85,1,0,0,0,.55,.1,.05},{1,.85,1,0,0,0,.55,.1,.05},
                {1.35,.62,.72,0,0,0,.1,.05,1},{1.65,.62,.72,0,0,0,.1,.05,1},{2,.3,1.32,.7,0,0,.85,.3,0},{2.35,.3,1.32,.7,0,0,.85,.3,0},
                {2.7,.15,.8,-.7,0,0,.1,.05,.25},{3,.15,.8,-.7,0,0,.1,.05,.25},{3.4,0,1,0,0,0,.55,.1,0}};
        // Evaluate authored seconds before quantizing to frame keys. Shared 30/60 FPS
        // instants then have the same anatomy, even for sub-frame cue times.
        for(int f=0;f<frames;f++){
            double seconds=f/(double)fps;double[] a=rows[0],b=a;
            for(double[] row:rows){if(row[0]<=seconds){a=row;b=row;}else{b=row;break;}}
            double t=a==b?0:ease(Math.min(1,(seconds-a[0])/(b[0]-a[0])));double[] v=new double[8];
            for(int i=0;i<8;i++)v[i]=a[i+1]+t*(b[i+1]-a[i+1]);
            keys.put(pose(f,v[0],v[1],v[2],v[3],v[4],v[5],v[6],v[7]).put("interpolation","linear"));
        }return keys;
    }
    private static double window(double t,double start,double end,double ramp){return ease(Math.min(1,Math.max(0,(t-start)/ramp)))*ease(Math.min(1,Math.max(0,(end-t)/ramp)));}
    private static double ease(double x){return x*x*(3-2*x);}
    static Path aperture(double cx,double cy,double width,double height,double[] v){
        return aperture(cx,cy,width,height,v,"front");
    }
    private static Path aperture(double cx,double cy,double width,double height,double[] v,String view){
        double mw=width*v[1]*(1-.36*v[7]),corner=cy-v[2]*height*.18,top=cy-height*v[0]*.18,bottom=cy+height*v[0]*.73,jaw=v[3]*width*.12*v[0];
        if("left".equals(view)||"right".equals(view)){
            double d="left".equals(view)?-1:1;mw*=.62;Path p=new Path();p.moveTo((float)(cx-d*mw*.5),(float)corner);
            p.cubicTo((float)(cx-d*mw*.10),(float)(top-height*.03*v[0]),(float)(cx+d*mw*.35),(float)(top-height*.08*v[0]),(float)(cx+d*mw*.5),(float)top);
            p.cubicTo((float)(cx+d*mw*.70+jaw),(float)(bottom-height*.08*v[0]),(float)(cx-d*mw*.10+jaw),(float)(bottom+height*.12*v[0]),(float)(cx-d*mw*.5),(float)corner);p.close();return p;
        }
        Path p=new Path();p.moveTo((float)(cx-mw/2),(float)corner);
        p.cubicTo((float)(cx-mw*.25),(float)(top+(top-corner)/3),(float)(cx+mw*.25),(float)(top+(top-corner)/3),(float)(cx+mw/2),(float)corner);
        p.cubicTo((float)(cx+mw*.36+jaw),(float)(bottom+(bottom-corner)/3),(float)(cx-mw*.36+jaw),(float)(bottom+(bottom-corner)/3),(float)(cx-mw/2),(float)corner);p.close();return p;
    }
    private static void draw(JSONArray objects,List<JSONObject> current,String id,JSONObject s,double[] v,double w,double h,int frame,Matrix matrix)throws Exception {
        String view=s.getString("view");if("back".equals(view))return;
        double direction="left".equals(view)?-1:"right".equals(view)?1:0;
        double cx=s.getDouble("centerX")*w,cy=s.getDouble("centerY")*h,width=s.getDouble("width")*w,height=s.getDouble("height")*h;
        if("high".equals(view))height*=.68;else if("low".equals(view))height*=.84;
        double mw=width*v[1]*(1-.36*v[7]),corner=cy-v[2]*height*.18,jaw=v[3]*width*.12*v[0],top=cy-height*v[0]*.18,bottom=cy+height*v[0]*.73;
        if(direction!=0)mw*=.62;
        if(v[0]<.003){
            Path seam=new Path();seam.moveTo((float)(cx-mw/2),(float)corner);seam.cubicTo((float)(cx-mw*.2),(float)cy,(float)(cx+mw*.2),(float)cy,(float)(cx+mw/2),(float)corner);
            if(!"cartoon".equals(s.getString("style"))){Path lip=new Path(seam);lip.offset(0,(float)(height*.035));add(objects,current,id+"_closed_lip",lip,false,s.getString("lipColor"),s,w,h,frame,matrix);}
            add(objects,current,id+"_seam",seam,false,s.getString("ink"),s,w,h,frame,matrix);return;
        }
        Path opening=aperture(cx,cy,width,height,v,view);
        // Lip volume hugs the aperture. It is not a stretched oval pasted on a face.
        float lip=(float)(height*("cartoon".equals(s.getString("style"))?.024:.052));Paint stroke=new Paint();stroke.setStyle(Paint.Style.STROKE);stroke.setStrokeWidth(lip*2);stroke.setStrokeJoin(Paint.Join.ROUND);Path lipBand=new Path();stroke.getFillPath(opening,lipBand);
        add(objects,current,id+"_lip",lipBand,true,s.getString("lipColor"),s,w,h,frame,matrix);
        add(objects,current,id+"_cavity",opening,true,s.getString("cavityColor"),s,w,h,frame,matrix);
        Path resting=oval(cx+jaw,bottom-height*v[0]*.07,mw*.30,height*v[0]*.25);
        add(objects,current,id+"_resting_tongue",intersection(resting,opening),true,s.getString("tongueColor"),s,w,h,frame,matrix);
        if(v[5]>.001){double visibility="high".equals(view)?.65:"low".equals(view)?1.25:1;Path teeth=teeth(cx,top-height*.12,mw*.87,height*(.12+.17*v[5]*visibility),"fang".equals(s.getString("style")),true);
            add(objects,current,id+"_upper_teeth",intersection(teeth,opening),true,s.getString("toothColor"),s,w,h,frame,matrix);}
        if(v[6]>.001){double visibility="high".equals(view)?1.3:"low".equals(view)?.6:1;Path teeth=teeth(cx+jaw,bottom-height*(.035+.16*v[6]*visibility),mw*.78,height*.20,false,false);
            add(objects,current,id+"_lower_teeth",intersection(teeth,opening),true,s.getString("toothColor"),s,w,h,frame,matrix);}
        add(objects,current,id+"_rim",opening,false,s.getString("ink"),s,w,h,frame,matrix);
        if(v[4]>.001&&v[0]>.12){
            double tx=cx+jaw+direction*mw*.15,tw=mw*.21,root=cy+height*v[0]*.16,tip=bottom+height*.48*v[4],tipX=tx+direction*mw*.32*v[4];
            Path tongue=new Path();tongue.moveTo((float)(tx-tw*.85),(float)root);tongue.cubicTo((float)(tx-tw),(float)(root+height*.12),(float)(tipX-tw*1.05),(float)tip,(float)tipX,(float)tip);
            tongue.cubicTo((float)(tipX+tw*1.05),(float)tip,(float)(tx+tw),(float)(root+height*.12),(float)(tx+tw*.85),(float)root);
            // A rounded, shaded root grows from inside the mouth rather than a
            // flat outlined stump pasted over the cavity.
            Path sides=new Path(tongue);
            tongue.cubicTo((float)(tx+tw*.4),(float)(root-height*.10),(float)(tx-tw*.4),(float)(root-height*.10),(float)(tx-tw*.85),(float)root);tongue.close();
            // Occlude the root at the upper lip, retaining the portion protruding
            // over the lower lip. Use sampled contour extrema, not Bezier controls.
            float[] edge=opening.approximate(.1f);float floor=Float.NEGATIVE_INFINITY;for(int n=0;n<edge.length;n+=3)floor=Math.max(floor,edge[n+2]);
            Path allowed=new Path(opening),outside=new Path();outside.addRect(0,floor-lip,(float)w,(float)h,Path.Direction.CW);if(!allowed.op(outside,Path.Op.UNION))throw SketchDocument.bad("Tongue occlusion failed");
            tongue=intersection(tongue,allowed);
            JSONObject tongueObject=add(objects,current,id+"_tongue",tongue,true,s.getString("tongueColor"),s,w,h,frame,matrix);
            if(tongueObject!=null){float[] ends={(float)tx,(float)root,(float)tipX,(float)tip};matrix.mapPoints(ends);tongueObject.put("gradient",new JSONObject().put("startColor",s.getString("lipColor")).put("endColor",s.getString("tongueColor"))
                    .put("x0",ends[0]/w).put("y0",ends[1]/h).put("x1",ends[2]/w).put("y1",ends[3]/h));}
            add(objects,current,id+"_tongue_rim",clippedStroke(sides,allowed,s.getDouble("outlinePx")),true,s.getString("lipColor"),s,w,h,frame,matrix);
            Path crease=new Path();crease.moveTo((float)tx,(float)(root+height*.04));crease.quadTo((float)((tx+tipX)/2+tw*.06),(float)(root+(tip-root)*.5),(float)tipX,(float)(tip-height*.08));
            add(objects,current,id+"_tongue_crease",clippedStroke(crease,allowed,s.getDouble("outlinePx")),true,s.getString("lipColor"),s,w,h,frame,matrix);
        }
    }
    private static Path teeth(double x,double y,double w,double h,boolean fang,boolean upper){
        Path p=new Path();p.addRoundRect((float)(x-w/2),(float)y,(float)(x+w/2),(float)(y+h),(float)(h*.18),(float)(h*.18),Path.Direction.CW);
        if(fang&&upper)for(int side:new int[]{-1,1}){double fx=x+side*w*.35;Path tooth=new Path();tooth.moveTo((float)(fx-w*.075),(float)(y+h*.6));tooth.lineTo((float)fx,(float)(y+h*1.85));tooth.lineTo((float)(fx+w*.075),(float)(y+h*.6));tooth.close();p.op(tooth,Path.Op.UNION);}return p;
    }
    static Path oval(double x,double y,double rx,double ry){Path p=new Path();p.addOval((float)(x-rx),(float)(y-ry),(float)(x+rx),(float)(y+ry),Path.Direction.CW);return p;}
    private static Path clippedStroke(Path line,Path allowed,double width){Paint paint=new Paint();paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth((float)width);paint.setStrokeCap(Paint.Cap.ROUND);Path fill=new Path();paint.getFillPath(line,fill);return intersection(fill,allowed);}
    static Path intersection(Path a,Path b){Path p=new Path();if(!p.op(a,b,Path.Op.INTERSECT))throw SketchDocument.bad("Native anatomy clipping failed");return p;}
    static JSONObject add(JSONArray objects,List<JSONObject> current,String id,Path input,boolean fill,String color,JSONObject settings,double width,double height,int frame,Matrix matrix)throws Exception {
        if(input.isEmpty())return null;Path p=new Path(input);p.transform(matrix);RectF bounds=new RectF();p.computeBounds(bounds,true);
        if(bounds.left<0||bounds.top<0||bounds.right>width||bounds.bottom>height)throw SketchDocument.bad("Facial geometry leaves canvas; adjust dimensions, position or tilt");
        float[] points=p.approximate(.35f);if(points.length<6)return null;JSONArray commands=new JSONArray();
        for(int i=0;i<points.length;i+=3)commands.put(new JSONArray().put(i==0||i>0&&points[i]==points[i-3]?"M":"L").put(Math.rint(points[i+1]/width*1e6)/1e6).put(Math.rint(points[i+2]/height*1e6)/1e6));
        if(fill)commands.put(new JSONArray().put("Z"));JSONObject o=SketchEditOps.completeObject(new JSONObject().put("id",id).put("kind","path").put("frame",frame).put("exposure",1).put("commands",commands).put("fill",fill).put("color",color).put("width",Math.max(.0005,settings.getDouble("outlinePx")/Math.min(width,height))));objects.put(o);current.add(o);return o;
    }
}
