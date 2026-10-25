package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import org.json.JSONArray;

/**
 * Four-corner PROJECTIVE perspective transform on a bounded owned static
 * bitmap, using native Android Skia Matrix.setPolyToPoly(4).
 *
 * This is a real 2D homography (corner pin), not a 3D mesh, tracker,
 * animated camera, or external After Effects binary. The warped bitmap is
 * cached within the existing Media3 native overlay generation.
 */
public final class StudioCornerPinRasterizer {
    public static final long MAX_PIXELS=1_048_576;
    private static final double MIN_CONVEX_CROSS=.004,MIN_AREA=.06;
    private StudioCornerPinRasterizer(){}

    public static JSONArray identity() {
        return new JSONArray().put((Object)0d).put((Object)0d)
                .put((Object)1d).put((Object)0d)
                .put((Object)1d).put((Object)1d)
                .put((Object)0d).put((Object)1d);
    }

    /** Coordinates TL,TR,BR,BL as 8 finite normalized numbers in [0,1]. */
    public static JSONArray validate(JSONArray corners) {
        if(corners==null||corners.length()!=8)
            throw new IllegalArgumentException("Corner pin needs four normalized x,y pairs");
        final double[] xy=new double[8];
        JSONArray safe=new JSONArray();
        for(int i=0;i<8;i++){
            Object raw=corners.opt(i);
            if(!(raw instanceof Number))
                throw new IllegalArgumentException("Corner pin coordinates must be numbers");
            double value=((Number)raw).doubleValue();
            if(!Double.isFinite(value)||value<0||value>1)
                throw new IllegalArgumentException("Corner pin corners must be finite values in [0,1]");
            xy[i]=value;
            safe.put((Object)Double.valueOf(value));
        }
        // Reject crossing, reversed, concave and nearly degenerate quads.
        for(int i=0;i<4;i++){
            int j=(i+1)%4,k=(i+2)%4;
            double ax=xy[2*j]-xy[2*i],ay=xy[2*j+1]-xy[2*i+1];
            double bx=xy[2*k]-xy[2*j],by=xy[2*k+1]-xy[2*j+1];
            double cross=ax*by-ay*bx;
            if(cross<=MIN_CONVEX_CROSS)
                throw new IllegalArgumentException("Corners must form a clockwise ordered convex quadrilateral");
        }
        double twiceArea=0;
        for(int i=0;i<4;i++){
            int j=(i+1)%4;
            twiceArea+=xy[2*i]*xy[2*j+1]-xy[2*j]*xy[2*i+1];
        }
        if(twiceArea/2<MIN_AREA)
            throw new IllegalArgumentException("Corner pin area is too small for stable rendering");
        return safe;
    }

    public static boolean isIdentity(JSONArray corners){
        JSONArray safe=validate(corners),identity=identity();
        for(int i=0;i<8;i++)
            if(Math.abs(safe.optDouble(i)-identity.optDouble(i))>1e-9)return false;
        return true;
    }

    /**
     * Return an independent raster at exactly the same pixel dimensions.
     * If the user changes the corner pin, the native shader generation is
     * recreated and its owned source decoded again — no source bytes change.
     */
    public static Bitmap apply(Bitmap source, JSONArray corners){
        JSONArray safe=validate(corners);
        if(source==null||source.isRecycled())
            throw new IllegalArgumentException("A private decoded bitmap is required");
        int w=source.getWidth(),h=source.getHeight();
        if(w<1||h<1||(long)w*h>MAX_PIXELS)
            throw new IllegalArgumentException("Perspective raster exceeds 1 MP GPU-preview budget");
        if(isIdentity(safe))return source;
        Bitmap out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        boolean ok=false;
        try{
            float[] original={0f,0f,(float)w,0f,(float)w,(float)h,0f,(float)h};
            float[] finalCorners=new float[8];
            for(int i=0;i<4;i++){
                finalCorners[i*2]=(float)(safe.optDouble(i*2)*w);
                finalCorners[i*2+1]=(float)(safe.optDouble(i*2+1)*h);
            }
            Matrix projection=new Matrix();
            if(!projection.setPolyToPoly(original,0,finalCorners,0,4))
                throw new IllegalArgumentException("Could not solve projective corner-pin transform");
            Paint filter=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
            new Canvas(out).drawBitmap(source,projection,filter);
            ok=true;
            return out;
        }finally{
            if(!ok&&!out.isRecycled())out.recycle();
        }
    }
}
