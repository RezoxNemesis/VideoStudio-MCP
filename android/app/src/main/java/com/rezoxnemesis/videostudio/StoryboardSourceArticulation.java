package com.rezoxnemesis.videostudio;

/**
 * Source-only seven-point motion field. The points are extrema and centroids
 * observed in a user's actual illustration mattes, NOT detected anatomy
 * or an invented 3D/skeletal model.
 */
public final class StoryboardSourceArticulation {
    private static final int COUNT=7;
    private final float[][] from,to;
    private final boolean subject;
    public StoryboardSourceArticulation(
            StoryboardLayerIsolation.Region a,
            StoryboardLayerIsolation.Region b,int stride,int height,
            boolean moveSubject) {
        if(a==null||b==null||stride<32||height<32||
                a.alpha.length!=stride*height||b.alpha.length!=stride*height)
            throw new IllegalArgumentException("Actual paired source mattes required");
        from=points(a,stride);to=points(b,stride);
        subject=moveSubject&&a.height()>18&&b.height()>18
                &&a.height()>.68f*a.width()&&b.height()>.68f*b.width();
    }
    /** Writes reusable x/y offsets in local source-sprite coordinates. */
    public void shift(float x,float y,float t,boolean endSource,
                      StoryboardMotionGrammar.Action action,float[] output) {
        if(output==null||output.length<2||action==null||
                !Float.isFinite(x)||!Float.isFinite(y)||
                !Float.isFinite(t)||t<0||t>1)
            throw new IllegalArgumentException("Invalid animated silhouette sample");
        output[0]=output[1]=0;
        if(!subject||(t==0&&!endSource)||(t==1&&endSource))return;
        float[][] src=endSource?to:from;
        float total=0,dx=0,dy=0;
        for(int i=0;i<COUNT;i++){
            float x0=src[i][0],y0=src[i][1];
            float rx=x-x0,ry=y-y0;
            float weight=1f/((i==1||i==4?.085f:.028f)+rx*rx+ry*ry);
            float xt=from[i][0]*(1-t)+to[i][0]*t;
            float yt=from[i][1]*(1-t)+to[i][1]*t;
            dx+=(xt-x0)*weight;dy+=(yt-y0)*weight;total+=weight;
        }
        output[0]=clamp(dx/total*.72f,-.20f,.20f);
        output[1]=clamp(dy/total*.64f,-.18f,.18f);
        float envelope=(float)Math.sin(Math.PI*t);
        if(action==StoryboardMotionGrammar.Action.WALK||
                action==StoryboardMotionGrammar.Action.RUN) {
            float foot=fade(.58f,.88f,y);
            float strideWave=(float)Math.sin(2*Math.PI*t)*envelope;
            float side=x<.5f?1f:-1f;
            output[0]+=side*strideWave*foot*
                    (action==StoryboardMotionGrammar.Action.RUN?.055f:.030f);
            output[1]-=Math.max(0,side*strideWave)*foot*.020f;
        }else if(action==StoryboardMotionGrammar.Action.STRIKE||
                action==StoryboardMotionGrammar.Action.IMPACT){
            float arm=fade(.19f,.34f,y)*(1-fade(.56f,.76f,y));
            output[0]+=(x<.5f?-1f:1f)*arm*envelope*
                    (action==StoryboardMotionGrammar.Action.STRIKE?.043f:.024f);
            output[1]-=.018f*arm*envelope;
        }else if(action==StoryboardMotionGrammar.Action.RECOIL||
                action==StoryboardMotionGrammar.Action.FALL){
            float chest=fade(.18f,.36f,y)*(1-fade(.65f,.82f,y));
            output[0]-=.018f*chest*envelope;
            output[1]+=.016f*chest*envelope;
        }
        output[0]=clamp(output[0],-.23f,.23f);
        output[1]=clamp(output[1],-.19f,.19f);
    }
    private static float fade(float a,float b,float v) {
        float t=clamp((v-a)/(b-a),0,1);return t*t*(3-2*t);
    }
    private static float clamp(float x,float a,float b) {
        return Math.max(a,Math.min(b,x));
    }
    private static float[][] points(StoryboardLayerIsolation.Region r,int w) {
        return new float[][]{
            centroid(r,w,.04f,.22f),centroid(r,w,.27f,.49f),
            tip(r,w,.22f,.69f,true),tip(r,w,.22f,.69f,false),
            centroid(r,w,.50f,.70f),tip(r,w,.73f,1f,true),
            tip(r,w,.73f,1f,false)
        };
    }
    private static float[] centroid(StoryboardLayerIsolation.Region r,int w,
                                     float low,float high) {
        int h=r.bottom-r.top+1;
        int ymin=r.top+Math.min(h-1,(int)(low*h));
        int ymax=r.top+Math.min(h-1,(int)(high*h));
        long sx=0,sy=0,n=0;
        for(int y=ymin;y<=ymax;y++)for(int x=r.left;x<=r.right;x++)
            if(r.alpha[y*w+x]!=0){sx+=x;sy+=y;n++;}
        return n<1?new float[]{.5f,(low+high)*.5f}:
                point(r,sx/(float)n,sy/(float)n);
    }
    private static float[] tip(StoryboardLayerIsolation.Region r,int w,
                                float low,float high,boolean left) {
        int h=r.bottom-r.top+1;
        int ymin=r.top+Math.min(h-1,(int)(low*h));
        int ymax=r.top+Math.min(h-1,(int)(high*h));
        int extreme=left?r.right+1:r.left-1;
        for(int y=ymin;y<=ymax;y++)for(int x=r.left;x<=r.right;x++)
            if(r.alpha[y*w+x]!=0)
                extreme=left?Math.min(extreme,x):Math.max(extreme,x);
        if(extreme<r.left||extreme>r.right)
            return centroid(r,w,low,high);
        long sx=0,sy=0,n=0;
        for(int y=ymin;y<=ymax;y++)
            for(int x=Math.max(r.left,extreme-2);
                    x<=Math.min(r.right,extreme+2);x++)
                if(r.alpha[y*w+x]!=0){sx+=x;sy+=y;n++;}
        return n==0?point(r,extreme,(ymin+ymax)*.5f):
                point(r,sx/(float)n,sy/(float)n);
    }
    private static float[] point(StoryboardLayerIsolation.Region r,float x,float y) {
        return new float[]{
            clamp((x-r.left)/Math.max(1,r.right-r.left),0,1),
            clamp((y-r.top)/Math.max(1,r.bottom-r.top),0,1)
        };
    }
}
