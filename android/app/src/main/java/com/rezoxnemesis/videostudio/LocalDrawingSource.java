package com.rezoxnemesis.videostudio;
import android.graphics.*;
import org.json.JSONObject;
import java.io.*;

/** Freeze an explicitly selected owned reference in full EXIF-oriented source coordinates. */
final class LocalDrawingSource {
 static void validate(JSONObject p)throws Exception {
  SketchDocument.fields(p,"requestId,sketchId,expectedRevision,objectId,sourceSha256,sourceRect,settings");
  for(String key:new String[]{"requestId","sketchId","objectId"})SketchDocument.id(p,key);
  SketchDocument.integer(p,"expectedRevision",1,Long.MAX_VALUE-1);
  Object hash=p.get("sourceSha256");if(!(hash instanceof String)||!((String)hash).matches("[a-f0-9]{64}"))throw SketchDocument.bad("Exact working reference SHA256 required");
  if(p.has("sourceRect"))rect(p.getJSONObject("sourceRect"));LocalDrawingEngine.validate(p.getJSONObject("settings"));
 }
 static void rect(JSONObject rect)throws Exception {
  SketchDocument.fields(rect,"x,y,width,height");double x=SketchDocument.number(rect,"x",0,1),y=SketchDocument.number(rect,"y",0,1),w=SketchDocument.number(rect,"width",Double.MIN_VALUE,1),h=SketchDocument.number(rect,"height",Double.MIN_VALUE,1);
  if(x+w>1||y+h>1)throw SketchDocument.bad("Selected source rectangle must fit the full oriented reference");
 }
 static int[] processingBounds(JSONObject reference,JSONObject p)throws Exception{
  JSONObject crop=p.has("sourceRect")?p.getJSONObject("sourceRect"):new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1);int width=reference.getInt("width"),height=reference.getInt("height");
  return new int[]{(int)Math.floor(crop.getDouble("x")*width),(int)Math.floor(crop.getDouble("y")*height),Math.min(width,(int)Math.ceil((crop.getDouble("x")+crop.getDouble("width"))*width)),Math.min(height,(int)Math.ceil((crop.getDouble("y")+crop.getDouble("height"))*height))};
 }
 static Bitmap prepare(SketchProjectStore store,JSONObject document,JSONObject p)throws Exception {
  validate(p);SketchProjectStore.requireRevision(document,p.getLong("expectedRevision"));JSONObject object=SketchDocument.object(document,p.getString("objectId"));
  if(!"image".equals(object.getString("kind")))throw SketchDocument.bad("Choose an imported image reference for local AI drawing");
  if(object.has("imageMesh")||object.optBoolean("referenceFlipX",false)||object.optBoolean("referenceFlipY",false))throw SketchDocument.bad("Use an unmeshed, unflipped original reference; crop in full source coordinates");
  if(object.has("referenceCrop")){JSONObject crop=object.getJSONObject("referenceCrop");if(crop.getDouble("x")!=0||crop.getDouble("y")!=0||crop.getDouble("width")!=1||crop.getDouble("height")!=1)throw SketchDocument.bad("Use the full original guide and select the AI source rectangle explicitly");}
  JSONObject ref=object.getJSONObject("reference");if(!p.getString("sourceSha256").equals(ref.getString("sha256")))throw new IllegalStateException("LOCAL_DRAWING_SOURCE_CHANGED");
  File file=store.referenceFile(document.getString("id"),ref);if(file.length()>8L*1024*1024||!p.getString("sourceSha256").equals(SketchProjectStore.fileHash(file)))throw new IllegalStateException("LOCAL_DRAWING_SOURCE_CHANGED");
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
  if(bounds.outWidth!=ref.getInt("width")||bounds.outHeight!=ref.getInt("height")||bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>2048||bounds.outHeight>2048)throw SketchDocument.bad("Reference dimensions differ from the source identity");
  Bitmap source=BitmapFactory.decodeFile(file.getPath());if(source==null)throw SketchDocument.bad("Cannot decode selected reference");
  Bitmap target=null,selected=null;try{
   JSONObject crop=p.has("sourceRect")?p.getJSONObject("sourceRect"):new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1);
   float l=(float)(crop.getDouble("x")*source.getWidth()),t=(float)(crop.getDouble("y")*source.getHeight()),w=(float)(crop.getDouble("width")*source.getWidth()),h=(float)(crop.getDouble("height")*source.getHeight());
   if(w<8||h<8)throw SketchDocument.bad("Choose a source region at least8 pixels on each axis");
   // Crop before filtering: clipping the full bitmap lets excluded neighbouring pixels bleed in.
   int[] selectedBounds=processingBounds(ref,p);int left=selectedBounds[0],top=selectedBounds[1],right=selectedBounds[2],bottom=selectedBounds[3];
   selected=Bitmap.createBitmap(source,left,top,right-left,bottom-top);w=right-left;h=bottom-top;float scale=Math.min(512/w,512/h),dw=w*scale,dh=h*scale;
   target=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(target);canvas.drawColor(Color.WHITE);
   canvas.drawBitmap(selected,null,new RectF((512-dw)/2,(512-dh)/2,(512+dw)/2,(512+dh)/2),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));
   return target;
  }catch(Exception e){if(target!=null)target.recycle();throw e;}finally{if(selected!=null&&selected!=source)selected.recycle();source.recycle();}
 }
}
