package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;

/**
 * Deterministic, original 2D alpha track matte. An already-owned source-free
 * SHAPE draws matte pixels once; this class applies that shape's alpha to one
 * bounded, independently decoded IMAGE or original SHAPE visual.
 *
 * This is intentionally a STATIC track matte. Dynamic/video mattes require
 * dedicated GPU texture compositing, not allocating a bitmap per frame.
 *
 * Both coordinates are in the saved composition's normalized 0..1 frame.
 * The runtime computes the child/matte intersection once per Media3 shader
 * generation. The project never grants Gallery access or modifies source bytes.
 */
public final class StudioTrackMatteRasterizer {
    public static final long MAX_TARGET_PIXELS=1_048_576L;
    private StudioTrackMatteRasterizer(){}

    public static void validatePair(StudioLayer target, StudioLayer matte) {
        if(target==null || matte==null ||
                (!StudioLayer.IMAGE.equals(target.type)&&!StudioLayer.SHAPE.equals(target.type)))
            throw new IllegalArgumentException("Static track mattes require an image or shape target");
        if(!StudioLayer.SHAPE.equals(matte.type)||!matte.matteOnly)
            throw new IllegalArgumentException("Matte source must be a hidden native SHAPE layer");
        if(!matte.id.equals(target.matteLayerId)||matte.id.equals(target.id))
            throw new IllegalArgumentException("Matte reference must match a distinct same-project shape");
        if(!"alpha".equals(target.matteMode)&&!"alpha_inverted".equals(target.matteMode))
            throw new IllegalArgumentException("Matte mode must be alpha or alpha_inverted");
        if(!target.parentId.isEmpty()||!matte.parentId.isEmpty())
            throw new IllegalArgumentException("Static track mattes cannot be parented to moving groups");
        if(hasMotion(target)||hasMotion(matte))
            throw new IllegalArgumentException("Static alpha mattes cannot animate independently");
        if(target.startMs<matte.startMs
                ||target.durationMs>matte.durationMs-(target.startMs-matte.startMs))
            throw new IllegalArgumentException("Matte must cover the whole target time span");
        if(!matte.matteLayerId.isEmpty())
            throw new IllegalArgumentException("Track-matte source cannot itself be matted");
        target.validate();matte.validate();
    }

    private static boolean hasMotion(StudioLayer lane) {
        return lane.motionKeyframes!=null && lane.motionKeyframes.length()>0;
    }

    /**
     * Alpha-multiply the app's mutable decoded target. The matte is sampled
     * after transforming global canvas coordinates back into matte-local space.
     * This faithfully supports rectangular/ellipse/diamond/ring/grid geometry
     * and a static rotation (including transparent antialiased borders).
     */
    public static Bitmap apply(Bitmap targetPixels, StudioLayer target, StudioLayer matte) {
        validatePair(target,matte);
        if(targetPixels==null||targetPixels.isRecycled()
                ||!targetPixels.isMutable()
                ||targetPixels.getConfig()!=Bitmap.Config.ARGB_8888)
            throw new IllegalArgumentException("Track matte needs app-owned mutable ARGB_8888 target");
        int w=targetPixels.getWidth(),h=targetPixels.getHeight();
        long count=(long)w*h;
        if(w<1||h<1||count>MAX_TARGET_PIXELS)
            throw new IllegalArgumentException("Static track matte exceeds bounded 1 MP target");
        Bitmap mattePixels=StudioTimedShapeOverlay.drawOriginalShape(matte);
        try {
            if(matte.mask!=null)StudioMaskRasterizer.apply(mattePixels,matte.mask);
            int mw=mattePixels.getWidth(),mh=mattePixels.getHeight();
            int[] matteArgb=new int[mw*mh],source=new int[(int)count];
            mattePixels.getPixels(matteArgb,0,mw,0,0,mw,mh);
            targetPixels.getPixels(source,0,w,0,0,w,h);
            final double angle=Math.toRadians(-matte.rotation),cos=Math.cos(angle),sin=Math.sin(angle);
            final boolean inverse="alpha_inverted".equals(target.matteMode);
            final double mx=matte.x,my=matte.y,mwNorm=matte.width,mhNorm=matte.height;
            for(int y=0;y<h;y++){
                final double globalY=target.y+(((y+.5)/h)-.5)*target.height;
                for(int x=0;x<w;x++){
                    int idx=y*w+x,original=source[idx];
                    int originalAlpha=original>>>24;
                    if(originalAlpha==0)continue;
                    double globalX=target.x+(((x+.5)/w)-.5)*target.width;
                    double diffX=globalX-mx,diffY=globalY-my;
                    double localX=(diffX*cos-diffY*sin)/mwNorm+.5;
                    double localY=(diffX*sin+diffY*cos)/mhNorm+.5;
                    int matteAlpha=0;
                    if(localX>=0&&localX<1&&localY>=0&&localY<1){
                        int ix=Math.min(mw-1,(int)(localX*mw)),iy=Math.min(mh-1,(int)(localY*mh));
                        matteAlpha=matteArgb[iy*mw+ix]>>>24;
                    }
                    if(inverse)matteAlpha=255-matteAlpha;
                    int alpha=(originalAlpha*matteAlpha+127)/255;
                    source[idx]=(original&0x00ffffff)|(alpha<<24);
                }
            }
            targetPixels.setPixels(source,0,w,0,0,w,h);
            return targetPixels;
        } finally {
            mattePixels.recycle();
        }
    }
}
