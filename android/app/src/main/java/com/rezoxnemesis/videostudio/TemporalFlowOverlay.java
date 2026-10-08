package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import androidx.media3.effect.CanvasOverlay;
import androidx.media3.common.util.UnstableApi;
import org.json.JSONObject;
import java.io.*;

/** Bidirectional flow mesh interpolation between two observed frames; no future-frame synthesis claim. */
@UnstableApi
public final class TemporalFlowOverlay extends CanvasOverlay {
    private final Bitmap first,second;
    private final float[] forward,backward;
    private final int width,height;
    private final double duration;
    private final Paint paint=new Paint(Paint.FILTER_BITMAP_FLAG);
    public TemporalFlowOverlay(Context context,JSONObject spec) throws Exception {
        super(true); width=spec.getInt("width"); height=spec.getInt("height"); duration=spec.getDouble("durationSeconds");
        forward=read(spec.getString("forwardPath"),width*height*2); backward=read(spec.getString("backwardPath"),width*height*2);
        Bitmap a=NativeSceneRenderer.loadImage(context,spec.getString("firstUri"),1024), b=null;
        try {b=NativeSceneRenderer.loadImage(context,spec.getString("secondUri"),1024);} catch(Exception e) {a.recycle();throw e;}
        first=a;second=b;
    }
    private static float[] read(String path,int count) throws Exception {
        if(count<=0||count>480*360*2) throw new IllegalArgumentException("Flow size out of range");
        File f=new File(path); if(f.length()!=count*4L) throw new IOException("Flow artifact size mismatch");
        float[] values=new float[count]; try(DataInputStream in=new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) {for(int i=0;i<count;i++) {values[i]=in.readFloat();if(!Float.isFinite(values[i])) throw new IOException("Invalid flow");}} return values;
    }
    @Override public void onDraw(Canvas canvas,long timeUs) {
        double p=Math.max(0,Math.min(1,timeUs/1000000d/duration)); canvas.drawColor(Color.BLACK);
        draw(canvas,first,forward,p,1f); draw(canvas,second,backward,1-p,(float)p);
    }
    private void draw(Canvas c,Bitmap bitmap,float[] flow,double amount,float alpha) {
        int columns=40,rows=30; float[] mesh=new float[(columns+1)*(rows+1)*2]; int k=0;
        for(int row=0;row<=rows;row++) for(int col=0;col<=columns;col++) {
            double nx=col/(double)columns, ny=row/(double)rows;
            int px=Math.min(width-1,(int)(nx*width)),py=Math.min(height-1,(int)(ny*height)); int at=(py*width+px)*2;
            mesh[k++]=(float)((nx+flow[at]*amount/width)*c.getWidth()); mesh[k++]=(float)((ny+flow[at+1]*amount/height)*c.getHeight());
        }
        paint.setAlpha(Math.round(alpha*255)); c.drawBitmapMesh(bitmap,columns,rows,mesh,0,null,0,paint);
    }
    @Override public void release() throws androidx.media3.common.VideoFrameProcessingException {try {super.release();} finally {first.recycle();second.recycle();}}
}
