package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.function.Consumer;

/** Detached native mask editor. Coordinates always address the complete source image. */
final class SketchReferenceMaskView extends View {
    private static final int[] COLOURS={0xffffbb55,0xff66ddff,0xffee88ff,0xff88ee99,
        0xffff8888,0xffbbbbff,0xffeeee77,0xff77dddd};
    private final Paint ink=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageBounds=new RectF();
    private final ArrayList<double[]> points=new ArrayList<>();
    private Bitmap bitmap; // Borrowed from the dialog, which owns recycling.
    private JSONArray accepted=new JSONArray();
    private Runnable onChanged=()->{};
    private Consumer<String> onMessage=s->{};
    private Consumer<double[]> pointPicker;
    private int activePart,dragVertex=-1;
    private float downX,downY;
    private boolean touching,moved;
    private boolean panMode,navigating;
    private float zoom=1,panX,panY,lastX,lastY;
    private final ScaleGestureDetector scaling;

    SketchReferenceMaskView(Context context){
        super(context);setContentDescription("Reference polygon editor");setFocusable(true);
        scaling=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector detector){scaleAt(detector.getScaleFactor(),detector.getFocusX(),detector.getFocusY());return true;}
        });
        setMinimumHeight(dp(220));
    }
    void setCallbacks(Runnable changed,Consumer<String> message){onChanged=changed;onMessage=message;}
    void setBitmap(Bitmap image){bitmap=image;touching=false;resetViewport();}
    void setPointPicker(Consumer<double[]> picker){pointPicker=picker;touching=false;}
    void setPanMode(boolean value){panMode=value;touching=false;}
    void zoomIn(){scaleAt(2,getWidth()/2f,getHeight()/2f);}
    void zoomOut(){scaleAt(.5f,getWidth()/2f,getHeight()/2f);}
    void resetViewport(){zoom=1;panX=panY=0;invalidate();}
    private void scaleAt(float factor,float x,float y){
        float next=Math.max(1,Math.min(16,zoom*factor)),ratio=next/zoom;
        panX=(panX+getWidth()/2f-x)*ratio+x-getWidth()/2f;
        panY=(panY+getHeight()/2f-y)*ratio+y-getHeight()/2f;zoom=next;invalidate();
    }
    void setAccepted(JSONArray masks,int selected)throws Exception {
        accepted=new JSONArray(masks.toString());activePart=selected;invalidate();
    }
    int pointCount(){return points.size();}
    JSONArray ring()throws Exception {
        JSONArray ring=new JSONArray();for(double[] p:points)ring.put(new JSONArray().put(p[0]).put(p[1]));return ring;
    }
    void setRing(JSONArray ring)throws Exception {
        points.clear();if(ring!=null)for(int i=0;i<ring.length();i++){
            JSONArray p=ring.getJSONArray(i);points.add(new double[]{p.getDouble(0),p.getDouble(1)});
        }
        touching=false;invalidate();onChanged.run();
    }
    void clearRing(){points.clear();touching=false;invalidate();onChanged.run();}
    void undoPoint(){if(!points.isEmpty()){points.remove(points.size()-1);invalidate();onChanged.run();}}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private boolean fit(){
        if(bitmap==null||bitmap.isRecycled()||getWidth()==0||getHeight()==0)return false;
        float scale=zoom*Math.min(getWidth()/(float)bitmap.getWidth(),getHeight()/(float)bitmap.getHeight());
        float w=bitmap.getWidth()*scale,h=bitmap.getHeight()*scale;
        imageBounds.set((getWidth()-w)/2+panX,(getHeight()-h)/2+panY,(getWidth()+w)/2+panX,(getHeight()+h)/2+panY);return true;
    }
    private boolean inside(float x,float y){return x>=imageBounds.left&&x<=imageBounds.right&&y>=imageBounds.top&&y<=imageBounds.bottom;}
    private Path path(JSONArray rings)throws Exception {
        Path path=new Path();path.setFillType(Path.FillType.EVEN_ODD);
        for(int i=0;i<rings.length();i++){
            JSONArray ring=rings.getJSONArray(i);
            for(int j=0;j<ring.length();j++){
                JSONArray p=ring.getJSONArray(j);float x=imageBounds.left+(float)p.getDouble(0)*imageBounds.width();
                float y=imageBounds.top+(float)p.getDouble(1)*imageBounds.height();
                if(j==0)path.moveTo(x,y);else path.lineTo(x,y);
            }
            path.close();
        }
        return path;
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);canvas.drawColor(0xff20232b);if(!fit())return;
        // Checkerboard shows existing source transparency; the bitmap itself is unchanged.
        ink.setStyle(Paint.Style.FILL);int tile=dp(16);
        float firstX=imageBounds.left+Math.max(0,(int)Math.floor(-imageBounds.left/tile))*tile;
        float firstY=imageBounds.top+Math.max(0,(int)Math.floor(-imageBounds.top/tile))*tile;
        for(float y=firstY;y<Math.min(getHeight(),imageBounds.bottom);y+=tile)for(float x=firstX;x<Math.min(getWidth(),imageBounds.right);x+=tile){
            int column=(int)((x-imageBounds.left)/tile),row=(int)((y-imageBounds.top)/tile);
            ink.setColor((column+row)%2==0?0xffdddddd:0xffaaaaaa);
            canvas.drawRect(x,y,Math.min(imageBounds.right,x+tile),Math.min(imageBounds.bottom,y+tile),ink);
        }
        ink.setColor(Color.WHITE);ink.setFilterBitmap(true);canvas.drawBitmap(bitmap,null,imageBounds,ink);
        try{
            for(int i=0;i<accepted.length();i++){
                JSONObject mask=accepted.getJSONObject(i);Path outline=path(mask.getJSONArray("rings"));int colour=COLOURS[i%COLOURS.length];
                ink.setStyle(Paint.Style.FILL);ink.setColor((colour&0x00ffffff)|0x33000000);canvas.drawPath(outline,ink);
                ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(dp(i==activePart?3:2));ink.setColor(colour);canvas.drawPath(outline,ink);
                if(mask.has("pivot")){JSONArray pivot=mask.getJSONArray("pivot");float x=imageBounds.left+(float)pivot.getDouble(0)*imageBounds.width(),y=imageBounds.top+(float)pivot.getDouble(1)*imageBounds.height();ink.setStyle(Paint.Style.STROKE);ink.setStrokeWidth(dp(2));canvas.drawCircle(x,y,dp(6),ink);canvas.drawLine(x-dp(10),y,x+dp(10),y,ink);canvas.drawLine(x,y-dp(10),x,y+dp(10),ink);}
            }
            if(!points.isEmpty()){
                Path draft=new Path();for(int i=0;i<points.size();i++){
                    double[] p=points.get(i);float x=imageBounds.left+(float)p[0]*imageBounds.width(),y=imageBounds.top+(float)p[1]*imageBounds.height();
                    if(i==0)draft.moveTo(x,y);else draft.lineTo(x,y);
                }
                if(points.size()>=3)draft.close();ink.setStyle(Paint.Style.STROKE);ink.setColor(Color.WHITE);ink.setStrokeWidth(dp(2));canvas.drawPath(draft,ink);
                ink.setStyle(Paint.Style.FILL);for(double[] p:points)canvas.drawCircle(imageBounds.left+(float)p[0]*imageBounds.width(),imageBounds.top+(float)p[1]*imageBounds.height(),dp(4),ink);
            }
        }catch(Exception invalid){onMessage.accept("Mask preview unavailable: "+invalid.getMessage());}
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        if(!isEnabled()||!fit())return false;
        scaling.onTouchEvent(event);
        if(event.getPointerCount()>1){
            float cx=(event.getX(0)+event.getX(1))/2,cy=(event.getY(0)+event.getY(1))/2;
            if(navigating){panX+=cx-lastX;panY+=cy-lastY;invalidate();}
            lastX=cx;lastY=cy;navigating=true;touching=false;
            if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;
        }
        if(navigating){if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){navigating=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);}return true;}
        float x=event.getX(),y=event.getY();
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                if(!panMode&&!inside(x,y))return false;
                downX=x;downY=y;touching=true;moved=false;dragVertex=-1;
                lastX=x;lastY=y;
                float best=dp(18);
                for(int i=0;i<points.size();i++){
                    double[] p=points.get(i);float distance=(float)Math.hypot(x-imageBounds.left-p[0]*imageBounds.width(),y-imageBounds.top-p[1]*imageBounds.height());
                    if(distance<best){best=distance;dragVertex=i;}
                }
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(true);return true;
            case MotionEvent.ACTION_MOVE:
                if(!touching)return false;
                if(panMode){panX+=x-lastX;panY+=y-lastY;lastX=x;lastY=y;invalidate();return true;}
                if(Math.hypot(x-downX,y-downY)>dp(5))moved=true;
                if(pointPicker==null&&moved&&dragVertex>=0&&inside(x,y)){
                    points.set(dragVertex,new double[]{(x-imageBounds.left)/imageBounds.width(),(y-imageBounds.top)/imageBounds.height()});invalidate();onChanged.run();
                }
                return true;
            case MotionEvent.ACTION_UP:
                if(!touching)return false;touching=false;
                if(!panMode&&!moved&&inside(x,y)){
                    if(pointPicker!=null)pointPicker.accept(new double[]{(x-imageBounds.left)/imageBounds.width(),(y-imageBounds.top)/imageBounds.height()});
                    else if(points.size()>=64)onMessage.accept("A ring allows at most 64 points. Undo a point or clear this ring.");
                    else{points.add(new double[]{(x-imageBounds.left)/imageBounds.width(),(y-imageBounds.top)/imageBounds.height()});invalidate();onChanged.run();}
                }
                if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);performClick();return true;
            case MotionEvent.ACTION_CANCEL:
                touching=false;if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(false);return true;
            default:return false;
        }
    }
    @Override public boolean performClick(){super.performClick();return true;}
}
