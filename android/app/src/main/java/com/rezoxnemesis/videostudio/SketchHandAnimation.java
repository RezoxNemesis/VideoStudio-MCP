package com.rezoxnemesis.videostudio;

import android.graphics.*;
import org.json.*;
import java.util.*;

/** Shared manual/MCP authoring: articulated anatomy becomes ordinary editable native cels. */
final class SketchHandAnimation {
    static final String[] CHANNELS={"indexCurl","middleCurl","ringCurl","littleCurl","thumbCurl","thumbOpposition","spread","wristFlex","wristTurn"};
    private static final double[] NEUTRAL={.28,.34,.40,.46,.25,.25,.18,0,0};
    private SketchHandAnimation(){}
    static JSONObject defaults()throws Exception {return new JSONObject().put("view","front").put("handedness","right").put("detail","anatomical").put("skinColor","#FFD6A078").put("nailColor","#FFE5C1AC").put("centerX",.5).put("centerY",.55).put("size",1).put("motion",1).put("wristMotion",.6);}
    static JSONObject settings(JSONObject in)throws Exception {
        SketchDocument.fields(in,"view,handedness,detail,skinColor,nailColor,centerX,centerY,size,motion,wristMotion");JSONObject s=defaults();Iterator<String> keys=in.keys();while(keys.hasNext()){String k=keys.next();s.put(k,in.get(k));}
        if(!Set.of("front","left","right","back","high","low").contains(s.getString("view")))throw SketchDocument.bad("Unknown hand view");
        if(!Set.of("right","left").contains(s.getString("handedness")))throw SketchDocument.bad("Unknown handedness");
        if(!Set.of("anatomical","contour").contains(s.getString("detail")))throw SketchDocument.bad("Unknown hand detail");
        SketchDocument.color(s.getString("skinColor"));SketchDocument.color(s.getString("nailColor"));
        SketchDocument.number(s,"centerX",.2,.8);SketchDocument.number(s,"centerY",.2,.8);SketchDocument.number(s,"size",.4,1.2);SketchDocument.number(s,"motion",0,1);SketchDocument.number(s,"wristMotion",0,1);return s;
    }
    static void validatePoses(JSONArray a,int frames)throws Exception {
        if(a.length()<1||a.length()>32)throw SketchDocument.bad("Use 1..32 hand poses");int previous=-1;
        for(int i=0;i<a.length();i++){JSONObject p=a.getJSONObject(i);SketchDocument.fields(p,"frame,indexCurl,middleCurl,ringCurl,littleCurl,thumbCurl,thumbOpposition,spread,wristFlex,wristTurn,interpolation,curve");int f=(int)SketchDocument.integer(p,"frame",0,frames-1);if(f<=previous||i==0&&f!=0)throw SketchDocument.bad("Hand poses must start at zero and strictly increase");previous=f;
            for(int c=0;c<CHANNELS.length;c++)if(p.has(CHANNELS[c]))SketchDocument.number(p,CHANNELS[c],c<7?0:-25,c<7?1:25);SketchEasing.validate(p);
        }
    }
    static double[] poseAt(JSONArray a,int frame)throws Exception {
        JSONObject left=a.getJSONObject(0),right=left;for(int i=1;i<a.length();i++){right=a.getJSONObject(i);if(frame<right.getInt("frame"))break;left=right;}
        double t=left==right?0:SketchEasing.sample(left.optString("interpolation","linear"),left.optJSONArray("curve"),(frame-left.getInt("frame"))/(double)(right.getInt("frame")-left.getInt("frame")));
        double[] p=new double[CHANNELS.length];for(int c=0;c<p.length;c++)p[c]=left.optDouble(CHANNELS[c],NEUTRAL[c])+(right.optDouble(CHANNELS[c],NEUTRAL[c])-left.optDouble(CHANNELS[c],NEUTRAL[c]))*t;return p;
    }
    static JSONArray preset(int frames,int fps,JSONObject settings)throws Exception {
        double motion=settings.getDouble("motion"),wrist=settings.getDouble("wristMotion");JSONArray a=new JSONArray();
        double[][] stages={{0,.28,.34,.40,.46,.25,.25,.18,0,0},{.10,.48,.53,.58,.63,.40,.48,.10,-8,-7},{.20,.86,.90,.92,.94,.65,.72,.03,-3,-2},
            {.31,.18,.35,.59,.76,.45,.40,.25,5,5},{.43,.03,.04,.07,.10,.08,.10,.55,9,8},{.62,.03,.04,.07,.10,.08,.10,.55,9,8},
            {.76,.26,.42,.62,.77,.30,.40,.22,3,2},{.86,.70,.78,.86,.90,.60,.65,.05,-3,-3},{1,.28,.34,.40,.46,.25,.25,.18,0,0}};
        int previous=-1;for(double[] stage:stages){int f=(int)Math.round(stage[0]*(frames-1));if(f==previous)continue;previous=f;JSONObject p=new JSONObject().put("frame",f).put("interpolation","smooth");for(int c=0;c<CHANNELS.length;c++)p.put(CHANNELS[c],c<7?NEUTRAL[c]+(stage[c+1]-NEUTRAL[c])*motion:stage[c+1]*wrist*motion);a.put(p);}return a;
    }
    static void apply(JSONObject doc,JSONObject op)throws Exception {
        cancelled();SketchDocument.fields(op,"type,layerId,handId,settings,poses");String id=SketchDocument.id(op,"handId"),layerId=SketchDocument.id(op,"layerId");if(id.length()>20)throw SketchDocument.bad("Hand identity must fit20characters");JSONObject layer=SketchDocument.layer(doc,layerId);if(layer.getBoolean("locked"))throw SketchDocument.bad("Layer is locked");
        JSONArray layers=doc.getJSONArray("layers");int existing=0;for(int l=0;l<layers.length();l++){JSONArray o=layers.getJSONObject(l).getJSONArray("objects");existing+=o.length();for(int i=0;i<o.length();i++)if(o.getJSONObject(i).getString("id").startsWith(id+"_"))throw SketchDocument.bad("Hand identity already exists");}
        int frames=doc.getInt("frameCount"),fps=doc.getInt("fps");double seconds=frames/(double)fps;if(frames<24||frames>240||seconds<4||seconds>8||!Set.of(24,30,60).contains(fps))throw SketchDocument.bad("Hand animation needs4..8seconds,24..240frames,24/30/60FPS");
        JSONObject s=settings(op.getJSONObject("settings"));JSONArray poses=op.has("poses")?op.getJSONArray("poses"):preset(frames,fps,s);validatePoses(poses,frames);
        JSONObject object=SketchEditOps.completeObject(new JSONObject().put("id",id+"_rig").put("kind","hand").put("width",.0012).put("frame",0).put("exposure",frames).put("hand",new JSONObject().put("settings",s).put("poses",new JSONArray(poses.toString())).put("durationFrames",frames).put("fps",fps)));
        refreshBounds(doc,object);JSONObject candidate=SketchDocument.copy(doc);SketchDocument.layer(candidate,layerId).getJSONArray("objects").put(object);SketchDocument.validate(candidate);
        layer.getJSONArray("objects").put(object);
    }
    static void validate(JSONObject doc,JSONObject object)throws Exception {
        JSONObject hand=object.getJSONObject("hand");SketchDocument.fields(hand,"settings,poses,durationFrames,fps,renderFrame,sourceFrameOffset");settings(hand.getJSONObject("settings"));
        int frames=(int)SketchDocument.integer(hand,"durationFrames",24,240),fps=(int)SketchDocument.integer(hand,"fps",24,60);if(!Set.of(24,30,60).contains(fps)||frames/(double)fps<4||frames/(double)fps>8)throw SketchDocument.bad("Invalid retained hand clock");validatePoses(hand.getJSONArray("poses"),frames);
        if(hand.has("renderFrame"))SketchDocument.integer(hand,"renderFrame",0,frames-1);if(hand.has("sourceFrameOffset"))SketchDocument.integer(hand,"sourceFrameOffset",-1200,1200);
        if(object.has("gradient")||object.has("styleId")||object.has("shapeKeys")||object.has("commands"))throw SketchDocument.bad("Hand anatomy uses its own editable settings and poses");
        int w=doc.getInt("width"),h=doc.getInt("height");JSONObject owner=null;for(int i=0;i<doc.getJSONArray("layers").length();i++){JSONObject l=doc.getJSONArray("layers").getJSONObject(i);JSONArray o=l.getJSONArray("objects");for(int j=0;j<o.length();j++)if(o.getJSONObject(j)==object)owner=l;}
        RectF envelope=new RectF();int first=object.getInt("frame"),last=first+object.getInt("exposure");
        for(int f=first;f<last;f++){
            RectF bounds=frameBounds(object,w,h,f);if(f==first)envelope.set(bounds);else envelope.union(bounds);
            RectF inkBounds=new RectF(bounds);float stroke=(float)(object.getDouble("width")*Math.min(w,h)/2);inkBounds.inset(-stroke,-stroke);
            Matrix world=owner==null?new Matrix():SketchFrameRenderer.matrix(SketchDocument.transformAt(owner,f),w,h);world.preConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(object,f),w,h));world.mapRect(inkBounds);
            if(inkBounds.left<0||inkBounds.top<0||inkBounds.right>w||inkBounds.bottom>h)throw SketchDocument.bad("Hand leaves canvas through destination transform");
        }
        RectF stored=SketchFrameRenderer.bounds(object.getJSONObject("bounds"),w,h);if(stored.left>envelope.left+.1||stored.top>envelope.top+.1||stored.right<envelope.right-.1||stored.bottom<envelope.bottom-.1)throw SketchDocument.bad("Hand bounds must contain its complete pose programme");
    }
    private static void cancelled()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Hand construction cancelled");}
    static int localFrame(JSONObject object,int frame)throws Exception {JSONObject hand=object.getJSONObject("hand");return hand.has("renderFrame")?hand.getInt("renderFrame"):Math.min(hand.getInt("durationFrames")-1,Math.max(0,frame-object.getInt("frame")+hand.optInt("sourceFrameOffset",0)));}
    static RectF frameBounds(JSONObject object,int width,int height,int frame)throws Exception {cancelled();JSONObject h=object.getJSONObject("hand");return SketchHandGeometry.bounds(h.getJSONObject("settings"),poseAt(h.getJSONArray("poses"),localFrame(object,frame)),width,height);}
    static void refreshBounds(JSONObject doc,JSONObject object)throws Exception {
        RectF bounds=new RectF();for(int f=object.getInt("frame");f<object.getInt("frame")+object.getInt("exposure");f++){RectF b=frameBounds(object,doc.getInt("width"),doc.getInt("height"),f);if(f==object.getInt("frame"))bounds.set(b);else bounds.union(b);}
        double w=doc.getInt("width"),h=doc.getInt("height");if(bounds.left<0||bounds.top<0||bounds.right>w||bounds.bottom>h)throw SketchDocument.bad("Hand leaves canvas; adjust position or size");object.put("bounds",new JSONObject().put("x",bounds.left/w).put("y",bounds.top/h).put("width",bounds.width()/w).put("height",bounds.height()/h));
    }
    static JSONObject sampled(JSONObject object,int frame)throws Exception {JSONObject copy=SketchDocument.copy(object),hand=copy.getJSONObject("hand"),frozen=new JSONObject().put("frame",0).put("interpolation","hold");double[] pose=poseAt(hand.getJSONArray("poses"),localFrame(object,frame));for(int i=0;i<CHANNELS.length;i++)frozen.put(CHANNELS[i],pose[i]);hand.put("poses",new JSONArray().put(frozen)).put("renderFrame",localFrame(object,frame));return copy;}
    static List<SketchHandGeometry.Ink> ink(JSONObject object,int width,int height,int frame)throws Exception {JSONObject h=object.getJSONObject("hand");return SketchHandGeometry.draw(h.getJSONObject("settings"),poseAt(h.getJSONArray("poses"),localFrame(object,frame)),width,height);}
    static void draw(Canvas canvas,JSONObject object,int width,int height,int frame,double alpha)throws Exception {
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setStrokeWidth((float)object.getDouble("width")*Math.min(width,height));paint.setStrokeJoin(Paint.Join.ROUND);paint.setStrokeCap(Paint.Cap.ROUND);
        for(SketchHandGeometry.Ink mark:ink(object,width,height,frame)){int color=Color.parseColor(mark.color);paint.setColor(color);paint.setAlpha((int)Math.round(Color.alpha(color)*alpha));paint.setStyle(mark.fill?Paint.Style.FILL:Paint.Style.STROKE);paint.setShader(mark.gradient==null?null:SketchGradients.shader(mark.gradient,width,height));canvas.drawPath(mark.path,paint);}
    }
    static JSONArray vectorFrame(JSONObject object,int width,int height,int frame)throws Exception {
        JSONArray objects=new JSONArray();int ordinal=0;for(SketchHandGeometry.Ink mark:ink(object,width,height,frame)){JSONArray commands=commands(mark.path,width,height);if(commands.length()<2)continue;JSONObject path=SketchEditOps.completeObject(new JSONObject().put("id",object.getString("id")+"_ink"+ordinal++).put("kind","path").put("frame",0).put("exposure",1).put("commands",commands).put("fill",mark.fill).put("color",mark.color).put("width",object.getDouble("width")).put("transform",SketchDocument.transformAt(object,frame)));if(mark.gradient!=null)path.put("gradient",new JSONObject(mark.gradient.toString()));objects.put(path);}return objects;
    }
    private static JSONArray commands(Path path,int w,int h)throws Exception {
        RectF b=new RectF();path.computeBounds(b,true);if(b.left<0||b.top<0||b.right>w||b.bottom>h)throw SketchDocument.bad("Hand leaves canvas; adjust position/size");
        float[] p=path.approximate(.35f);JSONArray a=new JSONArray();List<float[]> contour=new ArrayList<>();
        for(int i=0;i<=p.length;i+=3){boolean boundary=i==p.length||i>0&&p[i]==p[i-3];if(boundary&&!contour.isEmpty()){appendContour(a,contour,w,h);contour.clear();}if(i<p.length)contour.add(new float[]{p[i+1],p[i+2]});}return a;
    }
    /** Remove redundant edge vertices within a fixed subpixel error; never resample time or canvas. */
    private static void appendContour(JSONArray out,List<float[]> p,int w,int h)throws Exception {
        if(p.size()<2)return;boolean[] keep=new boolean[p.size()];keep[0]=keep[p.size()-1]=true;simplify(p,keep,0,p.size()-1);
        for(int i=0,n=0;i<p.size();i++)if(keep[i]){float[] v=p.get(i);out.put(new JSONArray().put(n++==0?"M":"L").put(Math.rint(v[0]/w*1e5)/1e5).put(Math.rint(v[1]/h*1e5)/1e5));}
    }
    private static void simplify(List<float[]> p,boolean[] keep,int start,int end){
        if(end<=start+1)return;float[] a=p.get(start),b=p.get(end);double dx=b[0]-a[0],dy=b[1]-a[1],length=dx*dx+dy*dy,max=.20*.20;int at=-1;
        for(int i=start+1;i<end;i++){float[] v=p.get(i);double t=length==0?0:Math.max(0,Math.min(1,((v[0]-a[0])*dx+(v[1]-a[1])*dy)/length));double ex=v[0]-a[0]-t*dx,ey=v[1]-a[1]-t*dy,d=ex*ex+ey*ey;if(d>max){max=d;at=i;}}
        if(at>=0){keep[at]=true;simplify(p,keep,start,at);simplify(p,keep,at,end);}
    }
}
