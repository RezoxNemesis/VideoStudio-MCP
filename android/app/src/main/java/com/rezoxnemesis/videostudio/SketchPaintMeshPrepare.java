package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Looper;
import org.json.*;
import java.io.InterruptedIOException;

/** Read-only worker preparation from owned authored paint; no trim or source replacement. */
final class SketchPaintMeshPrepare {
 private SketchPaintMeshPrepare(){}
 static void worker(){if(Looper.myLooper()==Looper.getMainLooper())throw new IllegalStateException("Paint grid preparation requires a worker");}
 static void cancelled()throws InterruptedIOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Paint grid preparation cancelled");}
 /** All nonzero alpha, including alpha1, plus bilinear support at the smallest allowed cache edge. */
 static JSONArray supportRectangle(Bitmap image)throws Exception {
  worker();int width=image.getWidth(),height=image.getHeight(),left=width,top=height,right=0,bottom=0;int[] row=new int[width];
  for(int y=0;y<height;y++){cancelled();image.getPixels(row,0,width,0,y,width,1);for(int x=0;x<width;x++)if(row[x]>>>24!=0){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+1);bottom=y+1;}}
  if(right==0)throw SketchDocument.bad("Paint cel has no retained artwork to deform");int gutter=(int)Math.ceil(Math.max(width,height)/128d)+2;
  return new JSONArray().put(Math.max(0,left-gutter)).put(Math.max(0,top-gutter)).put(Math.min(width,right+gutter)).put(Math.min(height,bottom+gutter));
 }
 static void requireSupport(JSONObject item,JSONArray required)throws Exception {
  JSONObject mesh=item.optJSONObject("paintMesh");if(mesh==null||!mesh.has("sourceRect"))return;JSONArray rectangle=mesh.getJSONArray("sourceRect");
  if(rectangle.getInt(0)>required.getInt(0)||rectangle.getInt(1)>required.getInt(1)||rectangle.getInt(2)<required.getInt(2)||rectangle.getInt(3)<required.getInt(3))throw SketchDocument.bad("Paint source rectangle excludes retained alpha or its sampling support");
 }
 static JSONObject source(JSONObject document,String id,boolean newGrid)throws Exception {
  JSONObject item=SketchDocument.object(document,id);SketchEditOps.requireUnlocked(SketchDocument.owner(document,id));
  if(!"paint".equals(item.getString("kind")))throw SketchDocument.bad("Choose an owned authored paint cel");
  if(newGrid&&item.has("paintMesh"))throw SketchDocument.bad("Paint cel already has an editable grid");
  JSONObject b=item.getJSONObject("bounds");if(b.getDouble("x")!=0||b.getDouble("y")!=0||b.getDouble("width")!=1||b.getDouble("height")!=1)throw SketchDocument.bad("Prepare full-canvas paint bounds without changing source coordinates");
  int width=document.getInt("width"),height=document.getInt("height");if((long)width*height>1048576)throw SketchDocument.bad("Paint grid preparation is bounded to1048576 native source pixels; no downsampling");return item;
 }
 static Bitmap texture(Context context,JSONObject document,JSONObject item)throws Exception {
  worker();cancelled();source(document,item.getString("id"),false);
  try(SketchPaintStore paint=new SketchPaintStore(context,document.getString("id"))){paint.verify(item.getJSONObject("paint"));Bitmap image=paint.renderSource(item.getJSONObject("paint"));try{cancelled();return image;}catch(Exception failure){image.recycle();throw failure;}}
 }
 static JSONObject prepare(Context context,JSONObject args)throws Exception {
  worker();cancelled();SketchDocument.fields(args,"sketchId,expectedRevision,objectId,columns,rows,focusToInk");if(args.has("focusToInk"))SketchDocument.bool(args,"focusToInk");boolean focus=args.optBoolean("focusToInk",false);String id=SketchDocument.id(args,"sketchId"),objectId=SketchDocument.id(args,"objectId");long revision=SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1);int columns=(int)SketchDocument.integer(args,"columns",1,16),rows=(int)SketchDocument.integer(args,"rows",1,16);
  try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject document=store.get(id);SketchProjectStore.requireRevision(document,revision);JSONObject item=source(document,objectId,true);String unchanged=SketchDocument.canonical(document);Bitmap image=texture(context,document,item);JSONArray support;
   try{support=supportRectangle(image);}finally{image.recycle();}
   cancelled();SketchProjectStore.requireRevision(store.get(id),revision);if(!unchanged.equals(SketchDocument.canonical(document)))throw new IllegalStateException("Read-only paint preparation changed source");JSONObject patch=new JSONObject().put("paintMesh",focus?SketchPaintMesh.focused(columns,rows,support,document.getInt("width"),document.getInt("height")):SketchPaintMesh.regular(columns,rows));return new JSONObject().put("sketchId",id).put("objectId",objectId).put("sourceRevision",revision).put("sourcePaintSha256",item.getJSONObject("paint").getString("sha256")).put("minimumNativeVersion",args.has("focusToInk")?"4.9.8":"4.9.5").put("sourceModified",false).put("operation",new JSONObject().put("type","update_object").put("objectId",objectId).put("patch",patch));
  }
 }
}
