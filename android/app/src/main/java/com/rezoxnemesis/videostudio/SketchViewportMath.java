package com.rezoxnemesis.videostudio;

/**
 * Sketch Studio presentation-only camera. Converts an authored normalized
 * canvas location to viewport pan at a finite zoom, without editing pixels,
 * transform keys, project media or the drawing's stored coordinates.
 */
public final class SketchViewportMath {
    public static final float MIN_ZOOM=1f,MAX_ZOOM=12f;
    private SketchViewportMath(){}

    public static final class Camera {
        public final float zoom,panX,panY;
        Camera(float zoom,float panX,float panY) {
            this.zoom=zoom;this.panX=panX;this.panY=panY;
        }
    }

    public static Camera focus(int screenWidth,int screenHeight,int documentWidth,
                               int documentHeight,double x,double y,double requestedZoom) {
        if(screenWidth<1||screenHeight<1||documentWidth<1||documentHeight<1
                ||!Double.isFinite(x)||!Double.isFinite(y)||!Double.isFinite(requestedZoom)
                ||x<0||x>1||y<0||y>1||requestedZoom<MIN_ZOOM||requestedZoom>MAX_ZOOM)
            throw new IllegalArgumentException("Finite sketch focus 0..1, zoom 1..12, and measured viewport required");
        double fit=Math.min(screenWidth/(double)documentWidth,
                            screenHeight/(double)documentHeight);
        double scale=fit*requestedZoom;
        return new Camera((float)requestedZoom,
                (float)((.5-x)*documentWidth*scale),
                (float)((.5-y)*documentHeight*scale));
    }

    public static float[] follow(int screenWidth,int screenHeight,int documentWidth,
                                 int documentHeight,float zoom,float panX,float panY,
                                 double normalizedX,double normalizedY) {
        Camera exact=focus(screenWidth,screenHeight,documentWidth,documentHeight,
                normalizedX,normalizedY,zoom);
        double fit=Math.min(screenWidth/(double)documentWidth,
                            screenHeight/(double)documentHeight)*zoom;
        double px=screenWidth/2.0+panX+(normalizedX-.5)*documentWidth*fit;
        double py=screenHeight/2.0+panY+(normalizedY-.5)*documentHeight*fit;
        // No camera work is done if the *actual* accepted pen remains visible
        // within the safe center. Prevents needless preview/raster churn.
        if(px>=screenWidth*.22&&px<=screenWidth*.78
                &&py>=screenHeight*.22&&py<=screenHeight*.78)
            return new float[]{panX,panY};
        // Move partway toward the exact focus, not an instantaneous camera jump.
        return new float[]{panX+(exact.panX-panX)*.27f,
                           panY+(exact.panY-panY)*.27f};
    }
}
