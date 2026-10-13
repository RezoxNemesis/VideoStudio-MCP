package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

/** Original painted sword, rigidly transformed in the same physical source metric as its actor. */
public final class NativeSourceProp2D implements AutoCloseable {
    public static final double MIN_BLADE_PIXELS=2;
    private final Bitmap texture;
    private final float[] hand,hilt,tip;
    private final int width,height;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Matrix transform=new Matrix();
    private final float[] values=new float[9];

    NativeSourceProp2D(Bitmap source,Rig2DProfile profile){
        width=source.getWidth();height=source.getHeight();
        requireSourceGeometry(profile,width,height);
        hand=profile.joints[Rig2DProfile.SWORD_HAND].clone();hilt=profile.swordHilt.clone();tip=profile.swordTip.clone();
        Bitmap cutout=NativeSkinnedSprite2D.applyOutline(source,profile.swordOutline);
        try{
            if(!NativeSkinnedSprite2D.hasAnyOpaquePixel(cutout))
                throw new IllegalArgumentException("Original sword mask contains no visible material");
            texture=cutout;cutout=null;
        }finally{if(cutout!=null)cutout.recycle();}
    }
    public void render(Canvas canvas,float[] targetHand,float[] targetTip,int opacity){
        update(targetHand,targetTip,canvas.getWidth(),canvas.getHeight());
        paint.setAlpha(Math.max(0,Math.min(255,opacity)));
        canvas.drawBitmap(texture,transform,paint);paint.setAlpha(255);
    }
    /** Predicted transformed source hilt, not an observed raster/contact measurement. */
    public float[] predictedHilt(float[] targetHand,float[] targetTip){
        update(targetHand,targetTip,width,height);
        float[] point={hilt[0]*width,hilt[1]*height};transform.mapPoints(point);
        point[0]/=width;point[1]/=height;return point;
    }
    private void update(float[] targetHand,float[] targetTip,int outputWidth,int outputHeight){
        requirePosedGeometry(targetHand,targetTip,outputWidth,outputHeight);
        if(Math.abs(width/(double)height-outputWidth/(double)outputHeight)>0.000001)
            throw new IllegalArgumentException("Prop and output aspect must share one source coordinate system");
        double rx=(tip[0]-hand[0])*width,ry=(tip[1]-hand[1])*height;
        double tx=(targetTip[0]-targetHand[0])*outputWidth,ty=(targetTip[1]-targetHand[1])*outputHeight;
        double denominator=rx*rx+ry*ry;
        double a=(rx*tx+ry*ty)/denominator,b=(rx*ty-ry*tx)/denominator;
        if(!Double.isFinite(a)||!Double.isFinite(b))
            throw new IllegalArgumentException("Invalid posed source sword anchors");
        values[0]=(float)a;values[1]=(float)-b;
        values[2]=(float)(targetHand[0]*outputWidth-a*hand[0]*width+b*hand[1]*height);
        values[3]=(float)b;values[4]=(float)a;
        values[5]=(float)(targetHand[1]*outputHeight-b*hand[0]*width-a*hand[1]*height);
        values[6]=0;values[7]=0;values[8]=1;transform.setValues(values);
    }
    /** Pure source eligibility, in the exact output pixel metric used by the rigid prop renderer. */
    public static void requireSourceGeometry(Rig2DProfile profile,int width,int height){
        if(profile==null||profile.swordTip==null||profile.swordHilt==null||profile.swordOutline.length<3)
            throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: original sword polygon, hilt and tip are required");
        requirePosedGeometry(profile.joints[Rig2DProfile.SWORD_HAND],profile.swordTip,width,height);
    }
    public static void requirePosedGeometry(float[] hand,float[] tip,int width,int height){
        valid(hand);valid(tip);
        if(width<128||height<128||width>1280||height>1920)
            throw new IllegalArgumentException("Bounded output dimensions required for source sword geometry");
        if(Math.hypot(((double)tip[0]-hand[0])*width,((double)tip[1]-hand[1])*height)<MIN_BLADE_PIXELS)
            throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: rendered source sword hand-to-tip span must be at least 2 output pixels");
    }
    private static void valid(float[] point){
        if(point==null||point.length!=2||!Float.isFinite(point[0])||!Float.isFinite(point[1]))
            throw new IllegalArgumentException("Finite sword hand and tip required");
    }
    @Override public void close(){if(!texture.isRecycled())texture.recycle();}
}
