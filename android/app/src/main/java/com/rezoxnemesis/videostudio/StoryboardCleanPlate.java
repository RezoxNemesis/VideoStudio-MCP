package com.rezoxnemesis.videostudio;

import java.util.Arrays;

/**
 * Restores the still illustrated world, independently of the moving actors.
 *
 * Uses ONLY the two imported source images and the two traced character
 * outlines in each frame. No anonymous procedural city is substituted.
 * If a source pixel is covered by an actor we take the identical position
 * from the other drawing, when unoccluded. Remaining holes use the nearest
 * trusted surrounding source pixel. Large camera/scene cuts are REJECTED
 * rather than being blended into doubled buildings and sword ghosts.
 *
 * This is small-shot local restoration, not neural hallucination or a
 * general substitute for a user-supplied clean background plate.
 */
public final class StoryboardCleanPlate {
    public static final class Result {
        public final int[] pixels;
        public final double backgroundDifference,unobservedFraction;
        public final int stableBackgroundPixels,recoveredSourcePixels,filledPixels;
        Result(int[] pixels,double difference,double unobserved,
               int stable,int recovered,int filled){
            this.pixels=pixels;
            backgroundDifference=difference;
            unobservedFraction=unobserved;
            stableBackgroundPixels=stable;
            recoveredSourcePixels=recovered;
            filledPixels=filled;
        }
    }
    private StoryboardCleanPlate(){}

    public static Result restore(int[] first,int[] last,int width,int height,
                                 float[][] blackFirst,float[][] whiteFirst,
                                 float[][] blackLast,float[][] whiteLast){
        if(first==null||last==null||width<16||height<16||
                (long)width*height>1280L*1920L||
                first.length!=width*height||last.length!=first.length)
            throw new IllegalArgumentException("Need two bounded imported source images");
        boolean[] occludedFirst=mask(width,height,blackFirst);
        boolean[] occludedLast=mask(width,height,blackLast);
        or(occludedFirst,mask(width,height,whiteFirst));
        or(occludedLast,mask(width,height,whiteLast));
        int[] background=first.clone();
        int stable=0,recovered=0,uncovered=0;
        long disagreement=0;
        boolean[] missing=new boolean[first.length];
        for(int p=0;p<first.length;p++){
            if(!occludedFirst[p]){
                stable++;
                if(!occludedLast[p]){
                    disagreement+=pixelDelta(first[p],last[p]);
                    uncovered++;
                }
            }else if(!occludedLast[p]){
                background[p]=last[p];recovered++;
            }else missing[p]=true;
        }
        double difference=uncovered==0?1:
                disagreement/(uncovered*765.0);
        // Reject major perspective/lighting mismatches. A genuine jump
        // cut belongs in a new shot; deforming the backdrop is misleading.
        if(uncovered<first.length/12 || difference>.265)
            throw new IllegalArgumentException(
                    "Storyboards change camera or scenery too much. Split the shot or provide a clean background.");
        int holes=0;
        for(boolean m:missing)if(m)holes++;
        if(holes>first.length*.22)
            throw new IllegalArgumentException(
                    "Characters obscure too much scenery; supply a clean background.");
        if(holes>0)fillNearest(background,missing,width,height);
        return new Result(background,difference,holes/(double)first.length,
                stable,recovered,holes);
    }

    /**
     * Exact-traced mask in full-frame normalized coordinates. An opaque
     * source without polygon boundaries is not safe to animate.
     */
    public static boolean[] mask(int width,int height,float[][] outline){
        if(width<1||height<1||(long)width*height>1280L*1920L||
                outline==null||outline.length<3||outline.length>64)
            throw new IllegalArgumentException("Traced character polygon is required");
        float minX=1,maxX=0,minY=1,maxY=0;
        for(float[] p:outline){
            if(p==null||p.length!=2||!Float.isFinite(p[0])||
                    !Float.isFinite(p[1])||p[0]<0||p[0]>1||p[1]<0||p[1]>1)
                throw new IllegalArgumentException("Polygon coordinates must lie in source image");
            minX=Math.min(minX,p[0]);maxX=Math.max(maxX,p[0]);
            minY=Math.min(minY,p[1]);maxY=Math.max(maxY,p[1]);
        }
        boolean[] result=new boolean[width*height];
        int left=Math.max(0,(int)Math.floor(minX*width)-1),
            right=Math.min(width-1,(int)Math.ceil(maxX*width)+1);
        int top=Math.max(0,(int)Math.floor(minY*height)-1),
            bottom=Math.min(height-1,(int)Math.ceil(maxY*height)+1);
        for(int y=top;y<=bottom;y++){
            float py=(y+.5f)/height;
            for(int x=left;x<=right;x++){
                float px=(x+.5f)/width;
                boolean inside=false;
                for(int a=0,b=outline.length-1;a<outline.length;b=a++){
                    float[] u=outline[a],v=outline[b];
                    if((u[1]>py)!=(v[1]>py) &&
                            px<(v[0]-u[0])*(py-u[1])/
                                (v[1]-u[1])+u[0])inside=!inside;
                }
                if(inside)result[y*width+x]=true;
            }
        }
        return result;
    }

    private static void or(boolean[] dest,boolean[] other){
        for(int i=0;i<dest.length;i++)dest[i]|=other[i];
    }
    private static int pixelDelta(int a,int b){
        return Math.abs((a>>>16&255)-(b>>>16&255))
            +Math.abs((a>>>8&255)-(b>>>8&255))
            +Math.abs((a&255)-(b&255));
    }

    /**
     * Fast deterministic inpaint seed propagation, nearest authentic
     * visible world pixel from either illustration. It intentionally
     * does not blend whole poses or invent buildings/hidden objects.
     */
    private static void fillNearest(int[] colors,boolean[] missing,int w,int h){
        int n=w*h;
        int[] nearest=new int[n];
        Arrays.fill(nearest,-1);
        for(int i=0;i<n;i++)if(!missing[i])nearest[i]=i;
        // Multiple passes approximate the 2D nearest-neighbour distance
        // transform, with no full-resolution model or large intermediate.
        for(int pass=0;pass<2;pass++){
            for(int y=0;y<h;y++)for(int x=0;x<w;x++){
                int i=y*w+x;
                if(nearest[i]>=0&&!missing[i])continue;
                if(x>0)consider(nearest,i,i-1,w);
                if(y>0)consider(nearest,i,i-w,w);
                if(x>0&&y>0)consider(nearest,i,i-w-1,w);
                if(x+1<w&&y>0)consider(nearest,i,i-w+1,w);
            }
            for(int y=h-1;y>=0;y--)for(int x=w-1;x>=0;x--){
                int i=y*w+x;
                if(nearest[i]>=0&&!missing[i])continue;
                if(x+1<w)consider(nearest,i,i+1,w);
                if(y+1<h)consider(nearest,i,i+w,w);
                if(x+1<w&&y+1<h)consider(nearest,i,i+w+1,w);
                if(x>0&&y+1<h)consider(nearest,i,i+w-1,w);
            }
        }
        for(int i=0;i<n;i++){
            if(!missing[i])continue;
            if(nearest[i]<0)throw new IllegalArgumentException(
                    "No visible background remains beneath actors");
            colors[i]=colors[nearest[i]];
        }
    }
    private static void consider(int[] nearest,int pixel,int neighbor,int w){
        int candidate=nearest[neighbor];
        if(candidate<0)return;
        int best=nearest[pixel];
        int x=pixel%w,y=pixel/w;
        long cx=x-candidate%w,cy=y-candidate/w;
        long dist=cx*cx+cy*cy;
        if(best<0){nearest[pixel]=candidate;return;}
        long bx=x-best%w,by=y-best/w;
        if(dist<bx*bx+by*by)nearest[pixel]=candidate;
    }
}
