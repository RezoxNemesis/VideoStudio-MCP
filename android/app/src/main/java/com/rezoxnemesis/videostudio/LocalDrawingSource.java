package com.rezoxnemesis.videostudio;
import android.graphics.*;
import org.json.JSONObject;
import java.io.*;

/** Freeze an explicitly selected owned reference in full EXIF-oriented source coordinates. */
final class LocalDrawingSource {
 static void validate(JSONObject p)throws Exception {
  SketchDocument.fields(p,"requestId,sketchId,expectedRevision,objectId,sourceSha256,sourceRect,frameSource,appearanceSource,conditioning,previewOnly,settings");
  for(String key:new String[]{"requestId","sketchId"})SketchDocument.id(p,key);
  SketchDocument.integer(p,"expectedRevision",1,Long.MAX_VALUE-1);
  if(p.has("previewOnly"))SketchDocument.bool(p,"previewOnly");
  if(p.has("frameSource")){
   if(p.has("objectId")||p.has("sourceSha256")||p.has("sourceRect"))throw SketchDocument.bad("Choose either a native frame source or an imported image source");
   JSONObject source=p.getJSONObject("frameSource");SketchDocument.fields(source,"frameIndex,documentSha256,layerIds,crop");SketchDocument.integer(source,"frameIndex",0,1199);hash(source,"documentSha256");
   org.json.JSONArray ids=source.getJSONArray("layerIds");if(ids.length()<1||ids.length()>32)throw SketchDocument.bad("Choose1..32 explicit source layers");java.util.HashSet<String> unique=new java.util.HashSet<>();
   for(int i=0;i<ids.length();i++){Object value=ids.get(i);if(!(value instanceof String))throw SketchDocument.bad("Frame source layers must be string IDs");String id=SketchDocument.id(new JSONObject().put("id",value),"id");if(!unique.add(id))throw SketchDocument.bad("Duplicate frame source layer");}
   if(source.has("crop"))rect(source.getJSONObject("crop"));
  }else{
   SketchDocument.id(p,"objectId");hash(p,"sourceSha256");if(p.has("sourceRect"))rect(p.getJSONObject("sourceRect"));
  }
  if(p.has("appearanceSource")||p.has("conditioning")){
   if(!p.has("appearanceSource")||!p.has("conditioning")||!p.has("frameSource"))throw SketchDocument.bad("Paired drawing requires frameSource, appearanceSource and conditioning together");
   JSONObject appearance=p.getJSONObject("appearanceSource");SketchDocument.fields(appearance,"objectId,sourceSha256,sourceRect");SketchDocument.id(appearance,"objectId");hash(appearance,"sourceSha256");if(appearance.has("sourceRect"))rect(appearance.getJSONObject("sourceRect"));
   JSONObject control=p.getJSONObject("conditioning");SketchDocument.fields(control,"appearanceStrength,structureStrength,adapter");SketchDocument.number(control,"appearanceStrength",0,1);SketchDocument.number(control,"structureStrength",0,1);if(control.has("adapter")){Object adapter=control.get("adapter");if(!(adapter instanceof String)||!(adapter.equals("plus_face")||adapter.equals("plus")))throw SketchDocument.bad("Choose plus_face or plus for explicit token conditioning");}
  }
  if(p.has("settings"))LocalDrawingEngine.validate(p.getJSONObject("settings"));else if(!p.optBoolean("previewOnly",false))throw SketchDocument.bad("Drawing settings are required for generation");
 }
 static void validateStored(JSONObject p)throws Exception {validate(p);if(p.optBoolean("previewOnly",false))throw SketchDocument.bad("A source preview is not a generated model study");}
 private static String hash(JSONObject p,String field)throws Exception {Object value=p.get(field);if(!(value instanceof String)||!((String)value).matches("[a-f0-9]{64}"))throw SketchDocument.bad("Exact "+field+" SHA256 required");return(String)value;}
 private static void check()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Local drawing source preparation cancelled");}
 static Bitmap prepare(android.content.Context context,SketchProjectStore store,JSONObject document,JSONObject p)throws Exception {
  return p.has("frameSource")?prepareFrame(context,document,p):prepare(store,document,p);
 }
 static JSONObject sourceMetadata(JSONObject document,JSONObject p)throws Exception {
  if(p.has("frameSource")){
   JSONObject source=p.getJSONObject("frameSource");int[] bounds=frameBounds(document,source);
   JSONObject out=new JSONObject().put("sourceKind","sketch_frame").put("sourceDocumentSha256",source.getString("documentSha256")).put("sourceRevision",document.getLong("revision")).put("sourceFrameIndex",source.getInt("frameIndex")).put("sourceLayerIds",new org.json.JSONArray(source.getJSONArray("layerIds").toString())).put("sourceWidth",document.getInt("width")).put("sourceHeight",document.getInt("height")).put("sourceProcessingBounds",new org.json.JSONArray(bounds)).put("sourcePreparation","Selected native frame/layers at authored resolution; integer canvas crop before aspect-preserving512px fit with opaque white letterbox");
   if(source.has("crop"))out.put("sourceCrop",SketchDocument.copy(source.getJSONObject("crop")));return out;
  }
  JSONObject reference=SketchDocument.object(document,p.getString("objectId")).getJSONObject("reference");
  return new JSONObject().put("sourceProcessingBounds",new org.json.JSONArray(processingBounds(reference,p))).put("sourcePreparation","Integer source crop, aspect-preserving512px fit with opaque white letterbox").put("originalUploadSha256",reference.optString("sourceSha256",""));
 }
 private static int[] frameBounds(JSONObject document,JSONObject source)throws Exception {
  JSONObject rect=source.has("crop")?source.getJSONObject("crop"):new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1);int width=document.getInt("width"),height=document.getInt("height");
  return new int[]{(int)Math.floor(rect.getDouble("x")*width),(int)Math.floor(rect.getDouble("y")*height),Math.min(width,(int)Math.ceil((rect.getDouble("x")+rect.getDouble("width"))*width)),Math.min(height,(int)Math.ceil((rect.getDouble("y")+rect.getDouble("height"))*height))};
 }
 private static Bitmap prepareFrame(android.content.Context context,JSONObject document,JSONObject p)throws Exception {
  validate(p);check();if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("Native frame source rendering requires a worker");
  SketchProjectStore.requireRevision(document,p.getLong("expectedRevision"));JSONObject frame=p.getJSONObject("frameSource");String actual=SketchDocument.sha256(SketchDocument.canonical(document).getBytes(java.nio.charset.StandardCharsets.UTF_8));if(!actual.equals(frame.getString("documentSha256")))throw new IllegalStateException("LOCAL_DRAWING_FRAME_SOURCE_CHANGED");
  int frameIndex=(int)SketchDocument.integer(frame,"frameIndex",0,document.getInt("frameCount")-1);java.util.HashSet<String> selectedIds=new java.util.HashSet<>();org.json.JSONArray ids=frame.getJSONArray("layerIds");for(int i=0;i<ids.length();i++){check();String id=ids.getString(i);JSONObject layer=SketchDocument.layer(document,id);if(!layer.getBoolean("visible"))throw SketchDocument.bad("Frame source layers must already be visible; hidden guides are not revealed");selectedIds.add(id);}
  int[] bounds=frameBounds(document,frame);int width=bounds[2]-bounds[0],height=bounds[3]-bounds[1];if(width<8||height<8)throw SketchDocument.bad("Choose a frame source region at least8 pixels on each axis");
  JSONObject drawing=SketchDocument.copy(document);drawing.remove("layeredDrawing");org.json.JSONArray layers=new org.json.JSONArray(),all=drawing.getJSONArray("layers");for(int i=0;i<all.length();i++){check();JSONObject layer=all.getJSONObject(i);if(selectedIds.contains(layer.getString("id")))layers.put(layer);}drawing.put("layers",layers);check();
  Bitmap full=null,crop=null,target=null;try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,drawing)){
   full=renderer.render(frameIndex,false);check();crop=Bitmap.createBitmap(full,bounds[0],bounds[1],width,height);boolean ink=false;int[] row=new int[width];
   for(int y=0;y<height&&!ink;y++){check();crop.getPixels(row,0,width,0,y,width,1);for(int color:row)if((color>>>24)!=0){ink=true;break;}}
   if(!ink)throw SketchDocument.bad("LOCAL_DRAWING_FRAME_EMPTY: selected frame/layers/crop contain no visible artwork");
   target=Bitmap.createBitmap(512,512,Bitmap.Config.ARGB_8888);Canvas canvas=new Canvas(target);canvas.drawColor(Color.WHITE);float scale=Math.min(512f/width,512f/height),dw=width*scale,dh=height*scale;canvas.drawBitmap(crop,null,new RectF((512-dw)/2,(512-dh)/2,(512+dw)/2,(512+dh)/2),new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG));check();return target;
  }catch(Exception|OutOfMemoryError error){if(target!=null)target.recycle();throw error;}finally{if(crop!=null&&crop!=full)crop.recycle();if(full!=null)full.recycle();}
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
  validate(p);Bitmap source=decodeOwnedImage(store,document,p);JSONObject ref=SketchDocument.object(document,p.getString("objectId")).getJSONObject("reference");
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
 static Bitmap decodeOwnedImage(SketchProjectStore store,JSONObject document,JSONObject p)throws Exception {
  SketchProjectStore.requireRevision(document,p.getLong("expectedRevision"));JSONObject object=SketchDocument.object(document,p.getString("objectId"));
  if(!"image".equals(object.getString("kind")))throw SketchDocument.bad("Choose an imported image reference for local AI drawing");
  if(object.has("imageMesh")||object.optBoolean("referenceFlipX",false)||object.optBoolean("referenceFlipY",false))throw SketchDocument.bad("Use an unmeshed, unflipped original reference; crop in full source coordinates");
  if(object.has("referenceCrop")){JSONObject crop=object.getJSONObject("referenceCrop");if(crop.getDouble("x")!=0||crop.getDouble("y")!=0||crop.getDouble("width")!=1||crop.getDouble("height")!=1)throw SketchDocument.bad("Use the full original guide and select the AI source rectangle explicitly");}
  JSONObject ref=object.getJSONObject("reference");if(!p.getString("sourceSha256").equals(ref.getString("sha256")))throw new IllegalStateException("LOCAL_DRAWING_SOURCE_CHANGED");
  File file=store.referenceFile(document.getString("id"),ref);if(file.length()>8L*1024*1024||!p.getString("sourceSha256").equals(SketchProjectStore.fileHash(file)))throw new IllegalStateException("LOCAL_DRAWING_SOURCE_CHANGED");
  BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
  if(bounds.outWidth!=ref.getInt("width")||bounds.outHeight!=ref.getInt("height")||bounds.outWidth<1||bounds.outHeight<1||bounds.outWidth>2048||bounds.outHeight>2048)throw SketchDocument.bad("Reference dimensions differ from the source identity");
  Bitmap source=BitmapFactory.decodeFile(file.getPath());if(source==null)throw SketchDocument.bad("Cannot decode selected reference");return source;
 }

}
