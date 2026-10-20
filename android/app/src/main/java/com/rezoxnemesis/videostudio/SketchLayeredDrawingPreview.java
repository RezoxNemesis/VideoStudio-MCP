package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Looper;
import android.util.Base64;
import org.json.JSONObject;
import org.json.JSONArray;
import java.io.ByteArrayOutputStream;

/** Read-only native comparison. Reference pixels never enter the authored document. */
final class SketchLayeredDrawingPreview {
    private SketchLayeredDrawingPreview() {}

    static JSONObject inspect(Context context,JSONObject source,JSONObject args)throws Exception {
        if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Layered drawing previews require a background worker");
        SketchDocument.fields(args,"sketchId,expectedRevision,view,frameIndex,maxDimension,crop,stageOnly");
        SketchLayeredDrawing.validate(source);
        if(!source.has("layeredDrawing"))throw SketchDocument.bad("Begin a layer-by-layer drawing before comparing it");
        if(args.has("sketchId")&&!source.getString("id").equals(SketchDocument.id(args,"sketchId")))throw SketchDocument.bad("Preview sketch identity differs");
        if(args.has("expectedRevision"))SketchProjectStore.requireRevision(source,SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1));
        if(args.has("view")&&!(args.get("view") instanceof String))throw SketchDocument.bad("Preview view must be a string");
        String view=args.optString("view","comparison");
        if(!java.util.Arrays.asList("reference","artwork","comparison").contains(view))throw SketchDocument.bad("Choose reference, artwork or comparison preview");
        if(args.has("stageOnly")&&!(args.get("stageOnly") instanceof Boolean))throw SketchDocument.bad("stageOnly must be a boolean");
        boolean stageOnly=args.optBoolean("stageOnly",false);
        if(stageOnly&&view.equals("reference"))throw SketchDocument.bad("Stage isolation applies to artwork or comparison");
        int frame=args.has("frameIndex")?(int)SketchDocument.integer(args,"frameIndex",0,source.getInt("frameCount")-1):0;
        int edge=args.has("maxDimension")?(int)SketchDocument.integer(args,"maxDimension",128,1024):720;
        JSONObject crop=args.has("crop")?args.getJSONObject("crop"):null;
        if(crop!=null)SketchDocument.validateBounds(crop);
        String unchanged=SketchDocument.canonical(source);
        JSONObject guide=source.getJSONObject("layeredDrawing");
        Bitmap reference=null,artwork=null,output=null;
        try {
            int panelEdge=view.equals("comparison")?Math.max(128,edge-28):edge;
            if(!view.equals("artwork"))reference=render(context,SketchLayeredDrawing.referenceDocument(source),frame,panelEdge,crop);
            if(!view.equals("reference"))artwork=render(context,stageOnly?stageDocument(source):source,frame,panelEdge,crop);
            if(view.equals("comparison")){
                int gap=12,label=24;
                int width=reference.getWidth()+artwork.getWidth()+gap,height=Math.max(reference.getHeight(),artwork.getHeight())+label;
                double scale=Math.min(1,edge/(double)Math.max(width,height));
                output=Bitmap.createBitmap(Math.max(1,(int)Math.round(width*scale)),Math.max(1,(int)Math.round(height*scale)),Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(output);canvas.drawColor(Color.rgb(242,242,245));canvas.scale((float)scale,(float)scale);
                Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);ink.setTextSize(14);ink.setColor(Color.rgb(25,30,40));
                canvas.drawText("REFERENCE",4,17,ink);canvas.drawText("AUTHORED DRAWING",reference.getWidth()+gap+4,17,ink);
                canvas.drawBitmap(reference,null,new RectF(0,label,reference.getWidth(),label+reference.getHeight()),ink);
                canvas.drawBitmap(artwork,null,new RectF(reference.getWidth()+gap,label,width,label+artwork.getHeight()),ink);
            }else output=(view.equals("reference")?reference:artwork);
            if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Layered preview cancelled");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();
            if(!output.compress(Bitmap.CompressFormat.PNG,100,bytes))throw new IllegalStateException("Native drawing preview encoding failed");
            byte[] png=bytes.toByteArray();if(png.length>8*1024*1024)throw SketchDocument.bad("Layered preview exceeds its PNG budget");
            if(!unchanged.equals(SketchDocument.canonical(source)))throw new IllegalStateException("Read-only preview modified its source");
            JSONObject image=new JSONObject().put("mimeType","image/png").put("width",output.getWidth()).put("height",output.getHeight())
                    .put("sizeBytes",png.length).put("sha256",SketchDocument.sha256(png)).put("base64",Base64.encodeToString(png,Base64.NO_WRAP));
            return new JSONObject().put("ok",true).put("sketchId",source.getString("id")).put("revision",source.getLong("revision"))
                    .put("stageOnly",stageOnly).put("sourceRevision",source.getLong("revision")).put("view",view).put("frameIndex",frame)
                    .put("referenceSha256",guide.getString("referenceSha256")).put("stage",guide.getString("stage"))
                    .put("sourceModified",false).put("reviewOnly",true).put("sourceAssistedReferenceOnly",view.equals("reference"))
                    .put("referencePixelsInArtwork",false).put("artworkDoesNotContainReferencePixels",true)
                    .put("creativeQualityCertified",false).put("renderer","VideoStudio Android Canvas")
                    .put("workflow",SketchLayeredDrawing.summary(source)).put("sketchPreviewImage",image);
        } finally {
            if(output!=null&&output!=reference&&output!=artwork&&!output.isRecycled())output.recycle();
            if(reference!=null&&!reference.isRecycled())reference.recycle();
            if(artwork!=null&&!artwork.isRecycled())artwork.recycle();
        }
    }
    private static JSONObject stageDocument(JSONObject source)throws Exception {
        JSONObject copy=SketchDocument.copy(source);JSONArray active=SketchLayeredDrawing.activeLayerIds(source),layers=new JSONArray(),ordered=source.getJSONArray("layers");
        for(int i=0;i<ordered.length();i++){JSONObject layer=ordered.getJSONObject(i);for(int j=0;j<active.length();j++)if(layer.getString("id").equals(active.getString(j))){layers.put(SketchDocument.copy(layer));break;}}
        copy.remove("layeredDrawing");copy.put("layers",layers);SketchDocument.validate(copy);return copy;
    }
    private static Bitmap render(Context context,JSONObject doc,int frame,int edge,JSONObject crop)throws Exception {
        if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException("Layered preview cancelled");
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,doc)){return renderer.render(frame,true,edge,crop);}
    }
}
