package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.*;
import android.view.*;
/** Full-spectrum HSV colour field. Hex entry retains exact alpha/RGB control. */
public final class SketchColourPicker extends View {
    public interface Listener{void colour(String value);}
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);private final float[] hsv={0,1,1};private Listener listener;private int alpha=255;
    public SketchColourPicker(Context c,String colour,Listener listener){super(c);Color.colorToHSV(SketchDocument.color(colour),hsv);alpha=Color.alpha(SketchDocument.color(colour));this.listener=listener;}
    @Override protected void onDraw(Canvas c){super.onDraw(c);float hueHeight=getHeight()*.18f,gap=8,top=hueHeight+gap;
        int[] spectrum=new int[]{Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED};paint.setShader(new LinearGradient(0,0,getWidth(),0,spectrum,null,Shader.TileMode.CLAMP));c.drawRect(0,0,getWidth(),hueHeight,paint);
        Shader saturation=new LinearGradient(0,top,getWidth(),top,Color.WHITE,Color.HSVToColor(new float[]{hsv[0],1,1}),Shader.TileMode.CLAMP),brightness=new LinearGradient(0,top,0,getHeight(),Color.WHITE,Color.BLACK,Shader.TileMode.CLAMP);paint.setShader(new ComposeShader(saturation,brightness,PorterDuff.Mode.MULTIPLY));c.drawRect(0,top,getWidth(),getHeight(),paint);paint.setShader(null);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(3);paint.setColor(Color.WHITE);c.drawCircle(hsv[1]*getWidth(),top+(1-hsv[2])*(getHeight()-top),7,paint);c.drawLine(hsv[0]/360*getWidth(),0,hsv[0]/360*getWidth(),hueHeight,paint);paint.setStyle(Paint.Style.FILL);
    }
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN||e.getActionMasked()==MotionEvent.ACTION_MOVE){float hueHeight=getHeight()*.18f,top=hueHeight+8;if(e.getY()<=hueHeight)hsv[0]=Math.max(0,Math.min(359.99f,e.getX()/getWidth()*360));else{hsv[1]=Math.max(0,Math.min(1,e.getX()/getWidth()));hsv[2]=1-Math.max(0,Math.min(1,(e.getY()-top)/(getHeight()-top)));}listener.colour(String.format(java.util.Locale.ROOT,"#%08X",Color.HSVToColor(alpha,hsv)));invalidate();getParent().requestDisallowInterceptTouchEvent(true);return true;}if(e.getActionMasked()==MotionEvent.ACTION_UP){performClick();getParent().requestDisallowInterceptTouchEvent(false);return true;}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
}
