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
  worker();cancelled();SketchDocument.fields(args,"sketchId,expectedRevision,objectId,columns,rows");String id=SketchDocument.id(args,"sketchId"),objectId=SketchDocument.id(args,"objectId");long revision=SketchDocument.integer(args,"expectedRevision",1,Long.MAX_VALUE-1);int columns=(int)SketchDocument.integer(args,"columns",1,16),rows=(int)SketchDocument.integer(args,"rows",1,16);
  try(SketchProjectStore store=new SketchProjectStore(context)){JSONObject document=store.get(id);SketchProjectStore.requireRevision(document,revision);JSONObject item=source(document,objectId,true);String unchanged=SketchDocument.canonical(document);Bitmap image=texture(context,document,item);boolean ink=false;
   try{int[] row=new int[image.getWidth()];for(int y=0;y<image.getHeight();y++){cancelled();image.getPixels(row,0,row.length,0,y,row.length,1);for(int pixel:row)if(pixel>>>24!=0){ink=true;break;}if(ink)break;}}finally{image.recycle();}
   if(!ink)throw SketchDocument.bad("Paint cel has no retained artwork to deform");cancelled();SketchProjectStore.requireRevision(store.get(id),revision);if(!unchanged.equals(SketchDocument.canonical(document)))throw new IllegalStateException("Read-only paint preparation changed source");JSONObject patch=new JSONObject().put("paintMesh",SketchPaintMesh.regular(columns,rows));return new JSONObject().put("sketchId",id).put("objectId",objectId).put("sourceRevision",revision).put("sourcePaintSha256",item.getJSONObject("paint").getString("sha256")).put("minimumNativeVersion","4.9.5").put("sourceModified",false).put("operation",new JSONObject().put("type","update_object").put("objectId",objectId).put("patch",patch));
  }
 }
}
