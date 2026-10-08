package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import android.net.Uri;
import org.json.*;
import java.io.InputStream;
import java.util.*;

/** Persistent native raster cache: repaint conservative changed regions, retaining stable pixels. */
public final class NativeSceneRenderer implements AutoCloseable {
    private final JSONObject graph;
    private final double duration;
    private final Map<String,Bitmap> images=new HashMap<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Map<String,android.media.MediaMetadataRetriever> videos=new HashMap<>();
    private final Map<String,Long> videoTicks=new HashMap<>();
    private ProceduralScene geometry;
    private Bitmap retained;
    private double previous=-1;
    private long frames, changedPixels, fullFrames;
    public NativeSceneRenderer(Context context,JSONObject scene) throws Exception {
        graph=new JSONObject(scene.getJSONObject("graph").toString()); duration=scene.getLong("durationMs")/1000d; graph.put("durationSeconds",duration);
        if("full_frame".equals(scene.getJSONObject("policy").optString("only"))) graph.put("forceFullFrame",true);
        long imageBytes=0; JSONArray objects=graph.getJSONArray("objects");
        try {
            for(int i=0;i<objects.length();i++) {
                JSONObject o=objects.getJSONObject(i); String type=o.getString("type"); if(!type.equals("image") && !type.equals("video")) continue;
                Bitmap bitmap;
                if(type.equals("video")) {
                    if(!videos.isEmpty()) throw new IllegalArgumentException("Use one video portal per scene to bound decoder memory");
                    android.media.MediaMetadataRetriever decoder=new android.media.MediaMetadataRetriever(); videos.put(o.getString("id"),decoder);
                    decoder.setDataSource(context,Uri.parse(o.getString("uri")));
                    bitmap=decoder.getScaledFrameAtTime(0,android.media.MediaMetadataRetriever.OPTION_CLOSEST,512,512);
                    if(bitmap==null) throw new IllegalArgumentException("Video portal first frame is unavailable");
                    videoTicks.put(o.getString("id"),0L);
                } else bitmap=loadImage(context,o.getString("uri"),1024);
                imageBytes+=bitmap.getAllocationByteCount();
                if(imageBytes>32L*1024*1024) {bitmap.recycle(); throw new IllegalArgumentException("Scene exceeds 32MB image working set; split scenes");}
                images.put(o.getString("id"),bitmap);
            }
            JSONArray geometryObjects=new JSONArray();
            for(int i=0;i<objects.length();i++) {JSONObject o=objects.getJSONObject(i);String type=o.getString("type");if(!type.equals("image")&&!type.equals("video")&&!type.equals("cloth")) geometryObjects.put(o);}
            if(geometryObjects.length()>0) {JSONObject geometryGraph=new JSONObject(graph.toString());geometryGraph.put("objects",geometryObjects);geometry=new ProceduralScene(geometryGraph);}
        } catch(Exception e) {close(); throw e;}
    }
    public void draw(Canvas target,double seconds) throws Exception {
        if(Thread.currentThread().isInterrupted()) throw new InterruptedException();
        int width=target.getWidth(), height=target.getHeight();
        if(width>1920||height>1920) throw new IllegalArgumentException("Scene raster exceeds native working-set limit");
        boolean first=retained==null||retained.getWidth()!=width||retained.getHeight()!=height||seconds<previous;
        if(first) {if(retained!=null) retained.recycle(); retained=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);}
        Rect dirty=first||graph.optBoolean("forceFullFrame",false)?new Rect(0,0,width,height):dirtyRegion(graph,previous,seconds,duration,width,height);
        if(!dirty.isEmpty()) {
            Canvas raster=new Canvas(retained); raster.save(); raster.clipRect(dirty);
            drawScene(raster,seconds); raster.restore(); changedPixels+=(long)dirty.width()*dirty.height();
            if(dirty.width()==width&&dirty.height()==height) fullFrames++;
        }
        target.drawBitmap(retained,0,0,paint); previous=seconds; frames++;
    }
    private void drawScene(Canvas canvas,double time) throws Exception {
        JSONArray all=graph.getJSONArray("objects");
        if(geometry!=null) geometry.draw(canvas,time,duration);
        else canvas.drawColor(Color.parseColor(graph.getString("background")));
        double p=SceneMath.ease(time/duration);
        for(int i=0;i<all.length();i++) {
            JSONObject o=all.getJSONObject(i); String type=o.getString("type");
            if(!type.equals("image")&&!type.equals("video")&&!type.equals("cloth")) continue;
            double x=track(o,"x",.5,p),y=track(o,"y",.5,p);
            float w=(float)o.optDouble("width",.4)*canvas.getWidth(), h=(float)o.optDouble("height",.4)*canvas.getHeight();
            canvas.save(); canvas.translate((float)x*canvas.getWidth(),(float)y*canvas.getHeight());
            canvas.rotate((float)(o.optDouble("rotation",0)+o.optDouble("spin",0)*time));
            if(type.equals("image")||type.equals("video")) {
                String identity=o.getString("id");
                if(type.equals("video")) {
                    long tick=(long)(time*15);
                    if(!Long.valueOf(tick).equals(videoTicks.get(identity))) {
                        Bitmap next=videos.get(identity).getScaledFrameAtTime(tick*1000000/15,android.media.MediaMetadataRetriever.OPTION_CLOSEST,512,512);
                        if(next==null) throw new IllegalStateException("Video portal frame could not be decoded");
                        Bitmap old=images.put(identity,next);if(old!=null) old.recycle();videoTicks.put(identity,tick);
                    }
                }
                Bitmap bitmap=images.get(identity);
                float imageRatio=bitmap.getWidth()/(float)bitmap.getHeight();
                if(w/h>imageRatio) w=h*imageRatio;else h=w/imageRatio;
                if("portal".equals(o.optString("kind"))) {
                    float perspective=(float)(.12*Math.sin(time*.6));
                    float[] src={0,0,bitmap.getWidth(),0,bitmap.getWidth(),bitmap.getHeight(),0,bitmap.getHeight()};
                    float[] dst={-w/2,-h/2,w/2,-h/2+perspective*h,w/2,h/2-perspective*h,-w/2,h/2};
                    Matrix matrix=new Matrix(); if(!matrix.setPolyToPoly(src,0,dst,0,4)) throw new IllegalStateException("Degenerate portal projection");
                    canvas.drawBitmap(bitmap,matrix,paint);
                } else canvas.drawBitmap(bitmap,null,new RectF(-w/2,-h/2,w/2,h/2),paint);
            } else {
                paint.setColor(Color.parseColor(o.optString("color","#eb44ff")));
                double wind=o.optDouble("wind",.04), turbulence=o.optDouble("turbulence",.22);
                for(int strip=0;strip<24;strip++) {
                    float a=-w/2+strip*w/24, b=-w/2+(strip+1)*w/24;
                    float da=(float)(Math.sin(strip*.34-time*3)*(strip/24d)*wind*canvas.getHeight());
                    float db=(float)(Math.sin((strip+1)*.34-time*3)*((strip+1)/24d)*wind*canvas.getHeight());
                    float flutter=(float)(Math.sin(strip*.71+time*5)*turbulence*wind*canvas.getHeight()*.2);
                    Path path=new Path(); path.moveTo(a,-h/2+da); path.lineTo(b,-h/2+db); path.lineTo(b,h/2+db+flutter); path.lineTo(a,h/2+da+flutter); path.close(); canvas.drawPath(path,paint);
                }
            }
            canvas.restore();
        }
    }
    public JSONObject statistics() throws Exception {
        long total=retained==null?0:frames*retained.getWidth()*retained.getHeight();
        return new JSONObject().put("frames",frames).put("fullFrames",fullFrames).put("repaintedPixelRatio",total==0?0:changedPixels/(double)total)
                .put("semanticValidation","unchecked").put("flowProvider","analytic-transforms").put("cacheBytes",retained==null?0:retained.getAllocationByteCount());
    }
    static Rect dirtyRegion(JSONObject graph,double before,double after,double duration,int w,int h) throws Exception {
        Rect result=new Rect(); JSONArray objects=graph.getJSONArray("objects");
        if(graph.optDouble("cameraOrbit",0)!=0 || graph.optDouble("cameraDolly",0)!=0) return new Rect(0,0,w,h);
        for(int i=0;i<objects.length();i++) {
            JSONObject o=objects.getJSONObject(i);
            boolean moving=o.has("toX")||o.has("toY")||o.has("toZ")||o.optDouble("spin",0)!=0||o.optDouble("amplitude",0)!=0||o.optString("type").equals("cloth")||o.optString("type").equals("video")||o.optString("kind").equals("portal");
            if(!moving) continue;
            String type=o.getString("type");
            if(type.equals("cube")||type.equals("pyramid")||type.equals("mesh")) return new Rect(0,0,w,h);
            result.union(bounds(o,before,duration,w,h)); result.union(bounds(o,after,duration,w,h));
        }
        if(!result.intersect(0,0,w,h)) result.setEmpty(); return result;
    }
    private static Rect bounds(JSONObject o,double time,double duration,int w,int h) {
        double p=SceneMath.ease(time/Math.max(.001,duration)),x=track(o,"x",.5,p)*w,y=track(o,"y",.5,p)*h;
        double ow=o.optDouble("width",.4)*w,oh=o.optDouble("height",.4)*h;
        double radius=Math.hypot(ow,oh)/2+Math.abs(o.optDouble("wind",.04))*h*2+Math.abs(o.optDouble("amplitude",0))*Math.max(w,h)+4;
        return new Rect((int)Math.floor(x-radius),(int)Math.floor(y-radius),(int)Math.ceil(x+radius),(int)Math.ceil(y+radius));
    }
    private static double track(JSONObject o,String key,double fallback,double p) {double v=o.optDouble(key,fallback); return SceneMath.lerp(v,o.optDouble("to"+key.toUpperCase(Locale.US),v),p);}
    public static Bitmap loadImage(Context context,String uri,int maxSide) throws Exception {
        BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
        try(InputStream input=context.getContentResolver().openInputStream(Uri.parse(uri))) {if(input==null) throw new IllegalArgumentException("Image unavailable"); BitmapFactory.decodeStream(input,null,bounds);}
        if(bounds.outWidth<=0||bounds.outHeight<=0) throw new IllegalArgumentException("Unreadable scene image");
        BitmapFactory.Options options=new BitmapFactory.Options(); options.inSampleSize=1;
        while(Math.max(bounds.outWidth,bounds.outHeight)/options.inSampleSize>maxSide) options.inSampleSize*=2;
        try(InputStream input=context.getContentResolver().openInputStream(Uri.parse(uri))) {Bitmap b=BitmapFactory.decodeStream(input,null,options); if(b==null) throw new IllegalArgumentException("Cannot decode scene image"); return b;}
    }
    @Override public void close() {if(retained!=null) {retained.recycle();retained=null;} for(Bitmap b:images.values()) if(!b.isRecycled()) b.recycle(); images.clear(); for(android.media.MediaMetadataRetriever decoder:videos.values()) {try {decoder.release();} catch(Exception ignored) {}} videos.clear();videoTicks.clear();}
}
