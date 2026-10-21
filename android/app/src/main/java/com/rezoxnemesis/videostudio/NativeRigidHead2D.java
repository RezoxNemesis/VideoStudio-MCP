package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;

/** A manually isolated source head; no inferred facial features or expression synthesis. */
final class NativeRigidHead2D implements AutoCloseable {
    private final Bitmap texture;
    private final Rig2DProfile profile;
    private final Matrix transform=new Matrix();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    NativeRigidHead2D(Bitmap source,Rig2DProfile profile){
        this.profile=profile;
        Bitmap selected=NativeSkinnedSprite2D.applyOutline(source,profile.headOutline);
        try{
            if(!NativeSkinnedSprite2D.hasAnyOpaquePixel(selected))throw new IllegalArgumentException("Head mask contains no visible source pixels");
            texture=selected;selected=null;
        }finally{if(selected!=null)selected.recycle();}
    }
    static boolean contains(float[][] polygon,float u,float v){
        boolean inside=false;
        for(int i=0,j=polygon.length-1;i<polygon.length;j=i++){
            float[] a=polygon[i],b=polygon[j];
            if((a[1]>v)!=(b[1]>v)&&u<(b[0]-a[0])*(v-a[1])/(b[1]-a[1])+a[0])inside=!inside;
        }
        return inside;
    }
    static float[] point(Rig2DProfile profile,float[][] pose,float u,float v,double aspect){
        float[] neck=profile.joints[Rig2DProfile.NECK],head=profile.joints[Rig2DProfile.HEAD];
        float[] nextNeck=pose[Rig2DProfile.NECK],nextHead=pose[Rig2DProfile.HEAD];
        double rx=(head[0]-neck[0])*aspect,ry=head[1]-neck[1],tx=(nextHead[0]-nextNeck[0])*aspect,ty=nextHead[1]-nextNeck[1];
        double denominator=rx*rx+ry*ry,a=(rx*tx+ry*ty)/denominator,b=(rx*ty-ry*tx)/denominator;
        double x=(u-neck[0])*aspect,y=v-neck[1];
        return new float[]{(float)(nextNeck[0]+(a*x-b*y)/aspect),(float)(nextNeck[1]+b*x+a*y)};
    }
    void render(Canvas canvas,float[][] pose){
        int w=canvas.getWidth(),h=canvas.getHeight();
        float[] a=profile.joints[Rig2DProfile.NECK],b=profile.joints[Rig2DProfile.HEAD];
        float[] c=pose[Rig2DProfile.NECK],d=pose[Rig2DProfile.HEAD];
        float[] source={a[0]*texture.getWidth(),a[1]*texture.getHeight(),b[0]*texture.getWidth(),b[1]*texture.getHeight()};
        float[] target={c[0]*w,c[1]*h,d[0]*w,d[1]*h};
        if(!transform.setPolyToPoly(source,0,target,0,2))throw new IllegalArgumentException("Head controls cannot define a rigid source transform");
        canvas.drawBitmap(texture,transform,paint);
    }
    long exclude(float[][] polygon){return NativeSkinnedSprite2D.removeOutline(texture,polygon);}
    @Override public void close(){if(!texture.isRecycled())texture.recycle();}
}
