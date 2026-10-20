package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import org.json.*;
import java.util.function.Consumer;

/** Borrowed raster, real alpha backdrop and local mesh editing. Never decodes files. */
final class SketchPaintMeshViewport extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final RectF bounds=new RectF();
    private final ScaleGestureDetector scale;
    private Bitmap bitmap;
    private JSONObject mesh;
    private float zoom=1,panX,panY,lastX,lastY,localWidth,localHeight;
    private int backdrop,selected=-1,drag=-1;
    private boolean pan,navigating,gridVisible=true;
    private Runnable changed=()->{};
    private Consumer<Integer> selection=i->{};
    SketchPaintMeshViewport(Context context){super(context);setMinimumHeight(dp(220));scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){public boolean onScale(ScaleGestureDetector d){scaleAt(d.getScaleFactor(),d.getFocusX(),d.getFocusY());return true;}});}
    void setBitmap(Bitmap value){bitmap=value;invalidate();}
    void setLocalSize(float width,float height){localWidth=width;localHeight=height;invalidate();}
    void setGridVisible(boolean value){gridVisible=value;invalidate();}
    void setBackdrop(int value){backdrop=value;invalidate();}
    void setMesh(JSONObject value){mesh=value;invalidate();}
    void setCallbacks(Runnable edit,Consumer<Integer> select){changed=edit;selection=select;}
    void selectVertex(int value){selected=value;invalidate();}
    void setPanMode(boolean value){pan=value;drag=-1;}
    void fit(){zoom=1;panX=panY=0;invalidate();}
    void zoomIn(){scaleAt(2,getWidth()/2f,getHeight()/2f);}
    void zoomOut(){scaleAt(.5f,getWidth()/2f,getHeight()/2f);}
    private int dp(int value){return Math.round(value*getResources().getDisplayMetrics().density);}
    private void scaleAt(float factor,float x,float y){float next=Math.max(.25f,Math.min(16,zoom*factor)),ratio=next/zoom;panX=(panX+getWidth()/2f-x)*ratio+x-getWidth()/2f;panY=(panY+getHeight()/2f-y)*ratio+y-getHeight()/2f;zoom=next;invalidate();}
    private boolean layoutImage(){if(bitmap==null||bitmap.isRecycled()||getWidth()<1||getHeight()<1)return false;float sourceWidth=localWidth>0?localWidth:bitmap.getWidth(),sourceHeight=localHeight>0?localHeight:bitmap.getHeight();float factor=zoom*Math.min(getWidth()/sourceWidth,getHeight()/sourceHeight);float width=sourceWidth*factor,height=sourceHeight*factor;bounds.set((getWidth()-width)/2+panX,(getHeight()-height)/2+panY,(getWidth()+width)/2+panX,(getHeight()+height)/2+panY);return true;}
    private float x(JSONArray p)throws JSONException{return bounds.left+(float)p.getDouble(0)*bounds.width();}
    private float y(JSONArray p)throws JSONException{return bounds.top+(float)p.getDouble(1)*bounds.height();}
    @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);paint.setStyle(Paint.Style.FILL);canvas.drawColor(backdrop==2?0xfff5f5f5:0xff20232b);if(backdrop==0){int tile=dp(16);for(int y=0;y<getHeight();y+=tile)for(int x=0;x<getWidth();x+=tile){paint.setColor(((x/tile+y/tile)&1)==0?0xffdddddd:0xff999999);canvas.drawRect(x,y,x+tile,y+tile,paint);}}if(!layoutImage())return;paint.setColor(Color.WHITE);
        if(mesh==null){canvas.drawBitmap(bitmap,null,bounds,paint);return;}
        try{JSONArray points=mesh.getJSONArray("points");float[] vertices=new float[points.length()*2];for(int i=0;i<points.length();i++){vertices[i*2]=x(points.getJSONArray(i));vertices[i*2+1]=y(points.getJSONArray(i));}int columns=mesh.getInt("columns"),rows=mesh.getInt("rows");canvas.drawBitmapMesh(bitmap,columns,rows,vertices,0,null,0,paint);if(!gridVisible)return;paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(0xff66ddff);for(int row=0;row<=rows;row++)for(int col=0;col<=columns;col++){int index=row*(columns+1)+col;if(col<columns)canvas.drawLine(vertices[index*2],vertices[index*2+1],vertices[index*2+2],vertices[index*2+3],paint);if(row<rows){int next=index+columns+1;canvas.drawLine(vertices[index*2],vertices[index*2+1],vertices[next*2],vertices[next*2+1],paint);}}paint.setStyle(Paint.Style.FILL);for(int i=0;i<points.length();i++){paint.setColor(i==selected?0xffffbb55:0xff66ddff);canvas.drawCircle(vertices[i*2],vertices[i*2+1],dp(i==selected?5:3),paint);}}
        catch(JSONException invalid){/* Only detached, validated mesh data reaches this viewport. */}
    }
    @Override public boolean onTouchEvent(MotionEvent event){if(!isEnabled()||!layoutImage())return false;scale.onTouchEvent(event);if(event.getPointerCount()>1){float x=(event.getX(0)+event.getX(1))/2,y=(event.getY(0)+event.getY(1))/2;if(navigating){panX+=x-lastX;panY+=y-lastY;invalidate();}navigating=true;drag=-1;lastX=x;lastY=y;intercept(true);return true;}if(navigating){if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){navigating=false;intercept(false);}return true;}
        try{switch(event.getActionMasked()){case MotionEvent.ACTION_DOWN:lastX=event.getX();lastY=event.getY();drag=-1;if(!pan&&mesh!=null){JSONArray points=mesh.getJSONArray("points");double best=dp(22);for(int i=0;i<points.length();i++){double distance=Math.hypot(lastX-x(points.getJSONArray(i)),lastY-y(points.getJSONArray(i)));if(distance<best){best=distance;drag=i;}}if(drag>=0){selected=drag;selection.accept(drag);invalidate();}}intercept(true);return true;
            case MotionEvent.ACTION_MOVE:if(pan){panX+=event.getX()-lastX;panY+=event.getY()-lastY;lastX=event.getX();lastY=event.getY();invalidate();}else if(drag>=0){double xx=Math.max(-4,Math.min(4,(event.getX()-bounds.left)/bounds.width())),yy=Math.max(-4,Math.min(4,(event.getY()-bounds.top)/bounds.height()));mesh.getJSONArray("points").put(drag,new JSONArray().put(xx).put(yy));changed.run();invalidate();}return true;
            case MotionEvent.ACTION_UP:drag=-1;intercept(false);performClick();return true;case MotionEvent.ACTION_CANCEL:drag=-1;intercept(false);return true;default:return false;}}
        catch(JSONException invalid){return false;}
    }
    private void intercept(boolean value){if(getParent()!=null)getParent().requestDisallowInterceptTouchEvent(value);}
    @Override public boolean performClick(){super.performClick();return true;}
}
