package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Recover the ENTIRE visible painted subject from a motion-difference seed.
 *
 * Two adjacent illustrations can show a fighter in overlapping positions:
 * unchanged portions of their bodies do not appear in the raw difference
 * map. Without this refinement, the background retains ghost fragments
 * while the actual moving sprite contains only thin changed strips.
 *
 * Uses real source color and scene-adjacent contrast, bounded flood fill,
 * and re-computes the clean plate from recovered masks. It is neither
 * segmentation-model inference nor synthesizing unseen body geometry.
 */
public final class StoryboardColorMatteRefiner {
    private static final int MAX_PIXELS=720*1280;
    private StoryboardColorMatteRefiner(){}

    public static StoryboardLayerIsolation.Result refine(
            StoryboardLayerIsolation.Result coarse,
            int[] first,int[] last,int width,int height){
        if(coarse==null||first==null||last==null||width<32||height<32
                ||(long)width*height>MAX_PIXELS||first.length!=width*height
                ||last.length!=first.length)
            throw new IllegalArgumentException("Source-owned artwork and tracked silhouettes required");
        if(!coarse.autoRenderable())return coarse;
        List<boolean[]> masksA=new ArrayList<>(),masksB=new ArrayList<>();
        float trust=1;
        for(StoryboardLayerIsolation.Track t:coarse.tracks){
            float contrastA=boundaryContrast(first,t.from,width,height);
            float contrastB=boundaryContrast(last,t.to,width,height);
            // Reject changed BACKGROUND PATCHES near high contrast
            // objects. They have low source-vs-surrounding separation
            // even though simple optical flow may match them.
            if(Math.min(contrastA,contrastB)<52f)continue;
            boolean[] a=completeSilhouette(first,t.from,width,height);
            boolean[] b=completeSilhouette(last,t.to,width,height);
            int expandedA=count(a),expandedB=count(b);
            float sizeRatio=Math.min(expandedA,expandedB)/
                    (float)Math.max(1,Math.max(expandedA,expandedB));
            if(sizeRatio<.25f)continue;
            trust=Math.min(trust,t.confidence);
            masksA.add(a);masksB.add(b);
        }
        if(masksA.isEmpty())
            return new StoryboardLayerIsolation.Result(
                    StoryboardLayerIsolation.Status.NEEDS_SOURCE_MASKS,
                    coarse.cleanPlate,new ArrayList<>(),
                    coarse.worldDifference,coarse.unseenFraction,
                    coarse.changedFraction,
                    "Automatic motion outlines did not contain reliably isolated painted objects");
        StoryboardLayerIsolation.Result refined=
                StoryboardLayerIsolation.fromMasks(
                        first,last,width,height,masksA,masksB,trust);
        if(!refined.autoRenderable())return new StoryboardLayerIsolation.Result(
                refined.status,refined.cleanPlate,refined.tracks,
                refined.worldDifference,refined.unseenFraction,
                coarse.changedFraction,refined.reason);
        return new StoryboardLayerIsolation.Result(
                StoryboardLayerIsolation.Status.READY,
                refined.cleanPlate,refined.tracks,
                refined.worldDifference,refined.unseenFraction,
                coarse.changedFraction,
                "Original silhouettes completed from tracked source colors; "+
                refined.tracks.size()+" independently retained object(s)");
    }

    static boolean[] completeSilhouette(int[] source,
            StoryboardLayerIsolation.Region region,int w,int h){
        if(source==null||region==null||source.length!=w*h||
                region.alpha.length!=source.length)
            throw new IllegalArgumentException("Valid tracked source region required");
        int n=w*h;
        boolean[] found=new boolean[n],queued=new boolean[n];
        int[] queue=new int[n];
        int tail=0,head=0;
        for(int i=0;i<n;i++)if(region.alpha[i]!=0){
            found[i]=true;queued[i]=true;queue[tail++]=i;
        }
        int starting=tail;
        if(starting<6)return found;
        int padX=Math.min(w/8,Math.max(7,Math.round(region.width()*.84f)));
        int padY=Math.min(h/8,Math.max(5,Math.round(region.height()*.19f)));
        int left=Math.max(0,region.left-padX),
            right=Math.min(w-1,region.right+padX);
        int top=Math.max(0,region.top-padY),
            bottom=Math.min(h-1,region.bottom+padY);
        float variability=0;
        int sampleCount=0;
        // Estimate the actual color variety in the original figure.
        for(int i=0;i<n;i++)if(region.alpha[i]!=0){
            variability+=rgbDistance(source[i],region.meanR,
                    region.meanG,region.meanB);
            sampleCount++;
        }
        variability/=Math.max(1,sampleCount);
        float maxDistance=Math.max(55,Math.min(145,38+variability*2.3f));
        int maxPixels=Math.min((right-left+1)*(bottom-top+1),
                Math.min(n/4,starting*3+Math.max(36,starting/3)));
        while(head<tail){
            int at=queue[head++],x=at%w,y=at/w;
            int[] neighbors={at-1,at+1,at-w,at+w};
            for(int k=0;k<4;k++){
                if((k==0&&x<=left)||(k==1&&x>=right)||
                        (k==2&&y<=top)||(k==3&&y>=bottom))
                    continue;
                int next=neighbors[k];
                if(next<0||next>=n||queued[next])continue;
                queued[next]=true;
                if(rgbDistance(source[next],region.meanR,
                        region.meanG,region.meanB)>maxDistance)continue;
                found[next]=true;
                queue[tail++]=next;
                if(tail>maxPixels){
                    // Ambiguous matte bled into the scene; retaining the
                    // coarse mask is safer than inventing extra anatomy.
                    boolean[] coarse=new boolean[n];
                    for(int i=0;i<n;i++)coarse[i]=region.alpha[i]!=0;
                    return coarse;
                }
            }
        }
        return found;
    }

    private static float boundaryContrast(int[] source,
            StoryboardLayerIsolation.Region region,int w,int h){
        int cx=(region.left+region.right)/2,
                cy=(region.top+region.bottom)/2;
        int padX=Math.max(3,Math.round(region.width()*.16f));
        int padY=Math.max(3,Math.round(region.height()*.10f));
        int xl=region.left-padX,xr=region.right+padX,
                yt=region.top-padY,yb=region.bottom+padY;
        float[] contrast=new float[4];int count=0;
        if(xl>=0)contrast[count++]=rgbDistance(
                source[cy*w+xl],region.meanR,region.meanG,region.meanB);
        if(xr<w)contrast[count++]=rgbDistance(
                source[cy*w+xr],region.meanR,region.meanG,region.meanB);
        if(yt>=0)contrast[count++]=rgbDistance(
                source[yt*w+cx],region.meanR,region.meanG,region.meanB);
        if(yb<h)contrast[count++]=rgbDistance(
                source[yb*w+cx],region.meanR,region.meanG,region.meanB);
        if(count<2)return 0;
        Arrays.sort(contrast,0,count);
        return contrast[(count-1)/2];
    }

    private static float rgbDistance(int argb,float r,float g,float b){
        return Math.abs((argb>>>16&255)-r)
                +Math.abs((argb>>>8&255)-g)
                +Math.abs((argb&255)-b);
    }
    private static int count(boolean[] mask) {
        int count=0;for(boolean value:mask)if(value)count++;return count;
    }
}
