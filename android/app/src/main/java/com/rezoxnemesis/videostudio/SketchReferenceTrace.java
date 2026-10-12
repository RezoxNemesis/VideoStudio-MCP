package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.*;
import org.json.*;
import java.io.File;
/** Bounded Sobel tracing of owned artwork into editable vector strokes, not learned reconstruction. */
public final class SketchReferenceTrace {
    private SketchReferenceTrace() {}
    public static void apply(SketchProjectStore store,JSONObject doc,JSONObject op) throws Exception {
        SketchDocument.fields(op,"type,objectId,layerId,frame,threshold,idPrefix,exposure,color,width");
        JSONObject source=SketchDocument.object(doc,SketchDocument.id(op,"objectId"));if(!"image".equals(source.getString("kind")))throw SketchDocument.bad("Select an owned image reference");
        JSONObject target=SketchDocument.layer(doc,SketchDocument.id(op,"layerId"));SketchEditOps.requireUnlocked(target);String prefix=SketchDocument.id(op,"idPrefix");if(prefix.length()>72)throw SketchDocument.bad("Trace ID prefix too long");
        int frame=(int)SketchDocument.integer(op,"frame",0,doc.getInt("frameCount")-1);double threshold=op.has("threshold")?SketchDocument.number(op,"threshold",.01,1):.15;
        Bitmap decoded=null,bitmap=null;
        try {
            File file=store.referenceFile(doc.getString("id"),source.getJSONObject("reference"));decoded=BitmapFactory.decodeFile(file.getPath());if(decoded==null)throw SketchDocument.bad("Reference decode failed");
            JSONObject crop=source.optJSONObject("referenceCrop");int x=0,y=0,cw=decoded.getWidth(),ch=decoded.getHeight();if(crop!=null){x=(int)Math.round(crop.getDouble("x")*cw);y=(int)Math.round(crop.getDouble("y")*ch);cw=Math.max(1,(int)Math.round(crop.getDouble("width")*cw));ch=Math.max(1,(int)Math.round(crop.getDouble("height")*ch));cw=Math.min(cw,decoded.getWidth()-x);ch=Math.min(ch,decoded.getHeight()-y);}
            Bitmap cropped=Bitmap.createBitmap(decoded,x,y,cw,ch);double ratio=Math.min(1,384d/Math.max(cw,ch));bitmap=Bitmap.createScaledBitmap(cropped,Math.max(3,(int)Math.round(cw*ratio)),Math.max(3,(int)Math.round(ch*ratio)),true);if(cropped!=decoded&&cropped!=bitmap)cropped.recycle();
            int w=bitmap.getWidth(),h=bitmap.getHeight(),size=w*h;int[] pixels=new int[size];bitmap.getPixels(pixels,0,w,0,0,w,h);float[] gray=new float[size];boolean[] edges=new boolean[size];
            for(int i=0;i<size;i++){int p=pixels[i];gray[i]=(.2126f*Color.red(p)+.7152f*Color.green(p)+.0722f*Color.blue(p))*Color.alpha(p)/255f;}
            for(int yy=1;yy<h-1;yy++)for(int xx=1;xx<w-1;xx++){int i=yy*w+xx;float gx=-gray[i-w-1]+gray[i-w+1]-2*gray[i-1]+2*gray[i+1]-gray[i+w-1]+gray[i+w+1],gy=-gray[i-w-1]-2*gray[i-w]-gray[i-w+1]+gray[i+w-1]+2*gray[i+w]+gray[i+w+1];edges[i]=Math.hypot(gx,gy)>threshold*1020;}
            JSONObject bounds=source.getJSONObject("bounds");Matrix world=new Matrix();world.setConcat(SketchFrameRenderer.matrix(SketchDocument.transformAt(SketchDocument.owner(doc,source.getString("id")),frame),doc.getInt("width"),doc.getInt("height")),SketchFrameRenderer.matrix(SketchDocument.transformAt(source,frame),doc.getInt("width"),doc.getInt("height")));Matrix inverse=new Matrix();if(!SketchFrameRenderer.matrix(SketchDocument.transformAt(target,frame),doc.getInt("width"),doc.getInt("height")).invert(inverse))throw SketchDocument.bad("Target layer transform cannot be inverted");world.postConcat(inverse);
            int added=0;for(int i=0;i<size;i++){if(!edges[i])continue;JSONArray points=new JSONArray();int cursor=i;
                while(cursor>=0&&points.length()<2048){edges[cursor]=false;double nx=bounds.getDouble("x")+cursor%w/(double)(w-1)*bounds.getDouble("width"),ny=bounds.getDouble("y")+cursor/w/(double)(h-1)*bounds.getDouble("height");float[] mapped={(float)nx*doc.getInt("width"),(float)ny*doc.getInt("height")};world.mapPoints(mapped);points.put(new JSONArray().put(Math.max(0,Math.min(1,mapped[0]/doc.getDouble("width")))).put(Math.max(0,Math.min(1,mapped[1]/doc.getDouble("height")))).put(1));
                    int next=-1,xx=cursor%w,yy=cursor/w;for(int dy=-1;dy<=1&&next<0;dy++)for(int dx=-1;dx<=1;dx++){int ax=xx+dx,ay=yy+dy;if(ax>=0&&ax<w&&ay>=0&&ay<h&&edges[ay*w+ax]){next=ay*w+ax;break;}}cursor=next;
                }
                if(points.length()<3)continue;if(added>=512)throw SketchDocument.bad("Reference is too detailed for one trace; crop a smaller area or raise threshold");
                target.getJSONArray("objects").put(SketchEditOps.completeObject(new JSONObject().put("id",prefix+"_"+added++).put("kind","stroke").put("points",points).put("color",op.optString("color","#FF161C2B")).put("width",op.optDouble("width",.0015)).put("frame",frame).put("exposure",op.optInt("exposure",1))));
            }
            if(added==0)throw SketchDocument.bad("No trace edges found; lower threshold");SketchDocument.validate(doc);
        }finally{if(bitmap!=null&&bitmap!=decoded)bitmap.recycle();if(decoded!=null)decoded.recycle();}
    }
}
