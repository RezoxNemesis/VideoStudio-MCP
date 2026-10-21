package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import org.json.*;

/** Native bounded source preprocessing. No model, file writes or semantic pose inference. */
final class LocalDrawingPairedSource {
 static final String EDGE_ALGORITHM="native_canny_binomial5_l2_v1";
 static final float[] CLIP_MEAN={.48145466f,.4578275f,.40821073f},CLIP_STD={.26862954f,.26130258f,.27577711f};
 static final class Inputs implements AutoCloseable {final Bitmap primary,appearance,structure;Inputs(Bitmap p,Bitmap a,Bitmap s){primary=p;appearance=a;structure=s;}public void close(){primary.recycle();appearance.recycle();structure.recycle();}}
 static Inputs prepare(Context context,SketchProjectStore store,JSONObject document,JSONObject request)throws Exception {
  LocalDrawingSource.validate(request);check();Bitmap primary=null,appearance=null,structure=null;
  try{primary=LocalDrawingSource.prepare(context,store,document,request);appearance=appearance(store,document,request);structure=canny(primary);check();return new Inputs(primary,appearance,structure);}catch(Exception|OutOfMemoryError failure){if(primary!=null)primary.recycle();if(appearance!=null)appearance.recycle();if(structure!=null)structure.recycle();throw failure;}
 }
 static JSONObject identity(JSONObject document,JSONObject request)throws Exception {
  JSONObject chosen=request.getJSONObject("appearanceSource"),reference=SketchDocument.object(document,chosen.getString("objectId")).getJSONObject("reference");JSONObject simple=imageRequest(request);JSONObject result=new JSONObject().put("sourceObjectId",chosen.getString("objectId")).put("sourceReferenceSha256",chosen.getString("sourceSha256")).put("originalUploadSha256",reference.optString("sourceSha256","")).put("sourceWidth",reference.getInt("width")).put("sourceHeight",reference.getInt("height")).put("sourceProcessingBounds",new JSONArray(LocalDrawingSource.processingBounds(reference,simple))).put("sourceRevision",document.getLong("revision")).put("preprocessing","Native alpha-over-white Catmull-Rom bicubic shortest-side224 then center crop; actual224RGB preview is normalized with verified CLIP mean/std at tensor creation");if(chosen.has("sourceRect"))result.put("sourceRect",SketchDocument.copy(chosen.getJSONObject("sourceRect")));return result;
 }
 private static JSONObject imageRequest(JSONObject request)throws Exception {JSONObject chosen=request.getJSONObject("appearanceSource"),simple=new JSONObject().put("expectedRevision",request.getLong("expectedRevision")).put("objectId",chosen.getString("objectId")).put("sourceSha256",chosen.getString("sourceSha256"));if(chosen.has("sourceRect"))simple.put("sourceRect",SketchDocument.copy(chosen.getJSONObject("sourceRect")));return simple;}
 private static Bitmap appearance(SketchProjectStore store,JSONObject document,JSONObject request)throws Exception {
  if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IllegalStateException("Paired source preprocessing requires a worker");JSONObject simple=imageRequest(request),reference=SketchDocument.object(document,simple.getString("objectId")).getJSONObject("reference");Bitmap source=LocalDrawingSource.decodeOwnedImage(store,document,simple);try{
   int[] bounds=LocalDrawingSource.processingBounds(reference,simple);int width=bounds[2]-bounds[0],height=bounds[3]-bounds[1];if(width<8||height<8)throw SketchDocument.bad("Appearance crop must retain at least8 pixels on each axis");int[] pixels=new int[width*height];source.getPixels(pixels,0,width,bounds[0],bounds[1],width,height);boolean alpha=false;for(int y=0;y<height&&!alpha;y++){check();for(int x=0;x<width;x++)if((pixels[y*width+x]>>>24)!=0){alpha=true;break;}}if(!alpha)throw SketchDocument.bad("Appearance crop contains no visible source pixels");return clipBicubic(pixels,width,height);
  }finally{source.recycle();}
 }
 /** Explicit native bicubic implementation; no claim of byte identity to a different library. */
 static Bitmap clipBicubic(int[] source,int width,int height)throws Exception {
  if(width<8||height<8||width>2048||height>2048||source.length!=width*height)throw new IllegalArgumentException("Bounded appearance pixels required");int resizedW=width<=height?224:(int)(224L*width/height),resizedH=height<=width?224:(int)(224L*height/width);int offsetX=(resizedW-224)/2,offsetY=(resizedH-224)/2;double scaleX=(double)width/resizedW,scaleY=(double)height/resizedH,filterX=Math.max(1,scaleX),filterY=Math.max(1,scaleY);int[] output=new int[224*224];
  for(int y=0;y<224;y++){check();double centerY=(y+offsetY+.5)*scaleY-.5;int firstY=(int)Math.ceil(centerY-2*filterY),lastY=(int)Math.floor(centerY+2*filterY);
   for(int x=0;x<224;x++){double centerX=(x+offsetX+.5)*scaleX-.5;int firstX=(int)Math.ceil(centerX-2*filterX),lastX=(int)Math.floor(centerX+2*filterX);double red=0,green=0,blue=0,total=0;
    for(int sy=firstY;sy<=lastY;sy++){double wy=cubic((sy-centerY)/filterY);if(wy==0)continue;int row=Math.max(0,Math.min(height-1,sy))*width;for(int sx=firstX;sx<=lastX;sx++){double weight=wy*cubic((sx-centerX)/filterX);if(weight==0)continue;int color=source[row+Math.max(0,Math.min(width-1,sx))],a=color>>>24;red+=weight*((Color.red(color)*a+255*(255-a)+127)/255);green+=weight*((Color.green(color)*a+255*(255-a)+127)/255);blue+=weight*((Color.blue(color)*a+255*(255-a)+127)/255);total+=weight;}}
    if(!Double.isFinite(total)||Math.abs(total)<1e-12)throw new IllegalStateException("Invalid native bicubic weight");output[y*224+x]=Color.rgb(channel(red/total),channel(green/total),channel(blue/total));
   }
  }
  check();Bitmap image=Bitmap.createBitmap(224,224,Bitmap.Config.ARGB_8888);image.setPixels(output,0,224,0,0,224,224);return image;
 }
 private static int channel(double value){return(int)Math.round(Math.max(0,Math.min(255,value)));}
 private static double cubic(double x){x=Math.abs(x);return x<1?1.5*x*x*x-2.5*x*x+1:x<2?-.5*x*x*x+2.5*x*x-4*x+2:0;}
 static float[] clipTensor(Bitmap appearance)throws Exception {if(appearance.getWidth()!=224||appearance.getHeight()!=224)throw new IllegalArgumentException("CLIP appearance input must be224px");int[] pixels=new int[224*224];appearance.getPixels(pixels,0,224,0,0,224,224);float[] input=new float[3*pixels.length];for(int y=0;y<224;y++){check();for(int x=0;x<224;x++){int i=y*224+x,color=pixels[i];input[i]=(Color.red(color)/255f-CLIP_MEAN[0])/CLIP_STD[0];input[pixels.length+i]=(Color.green(color)/255f-CLIP_MEAN[1])/CLIP_STD[1];input[2*pixels.length+i]=(Color.blue(color)/255f-CLIP_MEAN[2])/CLIP_STD[2];}}return input;}
 static Bitmap canny(Bitmap source)throws Exception {
  if(source.getWidth()!=512||source.getHeight()!=512)throw new IllegalArgumentException("Canny source must be512px");int edge=512,count=edge*edge;int[] pixels=new int[count];source.getPixels(pixels,0,edge,0,0,edge,edge);float[] gray=new float[count],horizontal=new float[count],smooth=new float[count],magnitude=new float[count],gx=new float[count],gy=new float[count];int[] kernel={1,4,6,4,1};
  for(int y=0;y<edge;y++){check();for(int x=0;x<edge;x++){int i=y*edge+x,color=pixels[i];gray[i]=.299f*Color.red(color)+.587f*Color.green(color)+.114f*Color.blue(color);}}
  for(int y=0;y<edge;y++){check();for(int x=0;x<edge;x++){float value=0;for(int k=-2;k<=2;k++)value+=kernel[k+2]*gray[y*edge+Math.max(0,Math.min(edge-1,x+k))];horizontal[y*edge+x]=value/16;}}
  for(int y=0;y<edge;y++){check();for(int x=0;x<edge;x++){float value=0;for(int k=-2;k<=2;k++)value+=kernel[k+2]*horizontal[Math.max(0,Math.min(edge-1,y+k))*edge+x];smooth[y*edge+x]=value/16;}}
  for(int y=1;y<edge-1;y++){check();for(int x=1;x<edge-1;x++){int i=y*edge+x;float dx=-smooth[i-edge-1]+smooth[i-edge+1]-2*smooth[i-1]+2*smooth[i+1]-smooth[i+edge-1]+smooth[i+edge+1],dy=-smooth[i-edge-1]-2*smooth[i-edge]-smooth[i-edge+1]+smooth[i+edge-1]+2*smooth[i+edge]+smooth[i+edge+1];gx[i]=dx;gy[i]=dy;magnitude[i]=(float)Math.sqrt(dx*dx+dy*dy);}}
  byte[] state=new byte[count];int[] queue=new int[count];int tail=0;
  for(int y=1;y<edge-1;y++){check();for(int x=1;x<edge-1;x++){int i=y*edge+x;float value=magnitude[i],ax=Math.abs(gx[i]),ay=Math.abs(gy[i]);int d=ay<=.41421356f*ax?1:ay>=2.41421356f*ax?edge:gx[i]*gy[i]>=0?edge+1:edge-1;if(value<100||value<magnitude[i-d]||value<magnitude[i+d])continue;if(value>=200){state[i]=2;queue[tail++]=i;}else state[i]=1;}}
  int head=0;while(head<tail){if((head&1023)==0)check();int i=queue[head++],x=i%edge,y=i/edge;for(int dy=-1;dy<=1;dy++)for(int dx=-1;dx<=1;dx++){int xx=x+dx,yy=y+dy;if(xx<=0||xx>=edge-1||yy<=0||yy>=edge-1)continue;int next=yy*edge+xx;if(state[next]==1){state[next]=2;queue[tail++]=next;}}}
  for(int y=0;y<edge;y++){check();for(int x=0;x<edge;x++){int i=y*edge+x;pixels[i]=state[i]==2?Color.WHITE:Color.BLACK;}}Bitmap result=Bitmap.createBitmap(edge,edge,Bitmap.Config.ARGB_8888);result.setPixels(pixels,0,edge,0,0,edge,edge);return result;
 }
 private static void check()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Paired input preprocessing cancelled");}
}
