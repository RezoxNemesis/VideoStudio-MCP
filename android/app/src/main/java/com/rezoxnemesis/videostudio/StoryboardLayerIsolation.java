package com.rezoxnemesis.videostudio;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * CPU-only, source-owned temporal foreground isolation for cartoon props,
 * logos, animals, shapes, cars, people and other visible moving objects.
 *
 * Finds actual changed pixels, reconstructs the shared world from portions
 * visible in at least one original frame, segments coherent moving regions
 * and matches their color/position across adjacent drawings.
 *
 * This heuristic is deliberately NOT labelled neural/SAM. On camera cuts,
 * low-confidence segmentation, stationary subjects or large occlusions it
 * reports UNSUPPORTED instead of rendering ghosting as finished animation.
 * A user-traced polygon or audited on-device model may supersede its masks.
 */
public final class StoryboardLayerIsolation {
    public enum Status {
        READY, NEEDS_SOURCE_MASKS, CAMERA_CUT, NO_VISIBLE_ACTION, TOO_COMPLEX
    }
    public static final class Region {
        public final byte[] alpha;
        public final int left,top,right,bottom,pixels;
        public final float cx,cy,meanR,meanG,meanB,confidence;
        Region(byte[] alpha,int l,int t,int r,int b,int pixels,
               float cX,float cY,float meanR,float meanG,float meanB,float confidence){
            this.alpha=alpha;left=l;top=t;right=r;bottom=b;this.pixels=pixels;
            cx=cX;cy=cY;this.meanR=meanR;this.meanG=meanG;
            this.meanB=meanB;this.confidence=confidence;
        }
        public float width(){return right-left+1;}
        public float height(){return bottom-top+1;}
    }
    public static final class Track {
        public final Region from,to;
        public final float confidence;
        Track(Region from,Region to,float confidence){
            this.from=from;this.to=to;this.confidence=confidence;
        }
    }
    public static final class Result {
        public final Status status;
        public final int[] cleanPlate;
        public final List<Track> tracks;
        public final float worldDifference,unseenFraction,changedFraction;
        public final String reason;
        Result(Status status,int[] plate,List<Track> tracks,float world,
               float unseen,float changed,String reason){
            this.status=status;cleanPlate=plate;this.tracks=tracks;
            worldDifference=world;unseenFraction=unseen;
            changedFraction=changed;this.reason=reason;
        }
        public boolean autoRenderable() {
            if(status!=Status.READY || tracks.isEmpty())return false;
            for(Track t:tracks)if(t.confidence<.42f)return false;
            return true;
        }
    }

    private static final int MAX_PIXELS=720*1280;
    private static final int MAX_TRACKS=8;
    private StoryboardLayerIsolation(){}

    public static Result analyse(int[] before,int[] after,int w,int h){
        if(before==null||after==null||w<32||h<32||(long)w*h>MAX_PIXELS
                ||before.length!=w*h||after.length!=before.length)
            throw new IllegalArgumentException("Expected two bounded source-owned ARGB images");
        int n=w*h;
        boolean[] changed=new boolean[n];
        int changeCount=0;
        long diffSum=0;
        for(int i=0;i<n;i++){
            int d=difference(before[i],after[i]);
            if(d>68){changed[i]=true;changeCount++;}
            if(i%3==0)diffSum+=d;
        }
        float fraction=changeCount/(float)n;
        if(fraction<.002f)
            return fail(Status.NO_VISIBLE_ACTION,n,fraction,
                    "No significant subject movement between these two storyboards");
        if(fraction>.62f)
            return fail(Status.CAMERA_CUT,n,fraction,
                    "Most of the image changes; split into separate camera shots");

        // Find real stable-pixel references. Both source frames have the
        // exact same reference view at locations not marked as changed.
        int[] nearest=nearestUnchanged(changed,w,h);
        int[] plate=before.clone();
        boolean[] from=new boolean[n],to=new boolean[n];
        long stableDelta=0;
        int stable=0,unseen=0;
        for(int i=0;i<n;i++){
            if(!changed[i]){
                stableDelta+=difference(before[i],after[i]);
                stable++;
                continue;
            }
            int ref=nearest[i];
            if(ref<0){unseen++;continue;}
            int background=before[ref];
            int a=difference(before[i],background);
            int b=difference(after[i],background);
            // The more unusual side of a changed pixel is its most likely
            // moving foreground. Both may be foreground when objects overlap.
            int max=Math.max(a,b),min=Math.min(a,b);
            if(max<60)continue;
            if(a>b+24)from[i]=true;
            else if(b>a+24)to[i]=true;
            else if(max>118){from[i]=true;to[i]=true;}
            if(from[i]&&!to[i])plate[i]=after[i];
            else if(to[i]&&!from[i])plate[i]=before[i];
            else{
                // The source world here is not visible in either frame.
                // Borrow only real nearby stable scenery pixels.
                plate[i]=background;unseen++;
            }
        }
        float drift=stable==0?1f:(float)(stableDelta/(stable*765d));
        float hidden=unseen/(float)n;
        if(stable<n*.22 || drift>.135f)
            return fail(Status.CAMERA_CUT,n,fraction,
                    "Background/camera alignment differs across the source art");
        if(hidden>.20f)
            return fail(Status.NEEDS_SOURCE_MASKS,n,fraction,
                    "Large foreground overlap hides the real world; request a clean plate");
        from=close(from,w,h);
        to=close(to,w,h);
        List<Region> a=components(from,before,w,h);
        List<Region> b=components(to,after,w,h);
        if(a.isEmpty()||b.isEmpty())
            return new Result(Status.NEEDS_SOURCE_MASKS,plate,new ArrayList<>(),
                    drift,hidden,fraction,
                    "Subjects could not be isolated reliably from these two frames");
        List<Track> tracks=match(a,b,w,h);
        if(tracks.isEmpty())
            return new Result(Status.NEEDS_SOURCE_MASKS,plate,tracks,
                    drift,hidden,fraction,
                    "No visual subject could be followed across both source frames");
        if(tracks.size()>MAX_TRACKS)
            return new Result(Status.TOO_COMPLEX,plate,tracks,
                    drift,hidden,fraction,
                    "Too many overlapping moving parts for automatic isolation");
        float totalConfidence=0;
        for(Track t:tracks)totalConfidence+=t.confidence;
        if(totalConfidence/tracks.size()<.42f)
            return new Result(Status.NEEDS_SOURCE_MASKS,plate,tracks,
                    drift,hidden,fraction,
                    "Subject tracking is uncertain. Trace objects once or install an appropriate model pack.");
        return new Result(Status.READY,plate,tracks,drift,hidden,fraction,
                "Independent original-artwork objects isolated and tracked");
    }

    private static Result fail(Status type,int n,float changed,String why) {
        return new Result(type,new int[0],new ArrayList<>(),1f,1f,changed,why);
    }
    private static int difference(int a,int b) {
        return Math.abs((a>>>16&255)-(b>>>16&255))
             + Math.abs((a>>>8&255)-(b>>>8&255))
             + Math.abs((a&255)-(b&255));
    }
    private static int[] nearestUnchanged(boolean[] changed,int w,int h) {
        int n=w*h;int[] nearest=new int[n];
        Arrays.fill(nearest,-1);
        ArrayDeque<Integer> frontier=new ArrayDeque<>();
        for(int i=0;i<n;i++)if(!changed[i]){
            nearest[i]=i;frontier.add(i);
        }
        // Multi-source BFS: O(w*h), capped memory, no model downloads.
        while(!frontier.isEmpty()){
            int at=frontier.removeFirst(),x=at%w,y=at/w;
            int id=nearest[at];
            if(x>0&&nearest[at-1]<0){nearest[at-1]=id;frontier.add(at-1);}
            if(x<w-1&&nearest[at+1]<0){nearest[at+1]=id;frontier.add(at+1);}
            if(y>0&&nearest[at-w]<0){nearest[at-w]=id;frontier.add(at-w);}
            if(y<h-1&&nearest[at+w]<0){nearest[at+w]=id;frontier.add(at+w);}
        }
        return nearest;
    }
    private static boolean[] close(boolean[] src,int w,int h) {
        int n=src.length;boolean[] dilated=new boolean[n],result=new boolean[n];
        for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){
            int i=y*w+x;
            for(int oy=-1;oy<=1&&!dilated[i];oy++)
                for(int ox=-1;ox<=1;ox++)if(src[i+oy*w+ox]){dilated[i]=true;break;}
        }
        for(int y=2;y<h-2;y++)for(int x=2;x<w-2;x++){
            int i=y*w+x;
            boolean all=true;
            for(int oy=-1;oy<=1&&all;oy++)
                for(int ox=-1;ox<=1;ox++)if(!dilated[i+oy*w+ox]){all=false;break;}
            result[i]=all;
        }
        return result;
    }
    private static List<Region> components(boolean[] mask,int[] image,int w,int h){
        int n=w*h,min=Math.max(18,n/1800);
        boolean[] visited=new boolean[n];
        int[] queue=new int[n];
        ArrayList<Region> found=new ArrayList<>();
        for(int index=0;index<n;index++){
            if(!mask[index]||visited[index])continue;
            visited[index]=true;queue[0]=index;
            int head=0,tail=1;
            int l=w-1,r=0,t=h-1,b=0;
            long sumX=0,sumY=0,red=0,green=0,blue=0;
            while(head<tail){
                int p=queue[head++],x=p%w,y=p/w;
                l=Math.min(l,x);r=Math.max(r,x);t=Math.min(t,y);b=Math.max(b,y);
                sumX+=x;sumY+=y;
                red+=image[p]>>>16&255;
                green+=image[p]>>>8&255;
                blue+=image[p]&255;
                if(x>0&&mask[p-1]&&!visited[p-1]){visited[p-1]=true;queue[tail++]=p-1;}
                if(x<w-1&&mask[p+1]&&!visited[p+1]){visited[p+1]=true;queue[tail++]=p+1;}
                if(y>0&&mask[p-w]&&!visited[p-w]){visited[p-w]=true;queue[tail++]=p-w;}
                if(y<h-1&&mask[p+w]&&!visited[p+w]){visited[p+w]=true;queue[tail++]=p+w;}
            }
            if(tail<min||tail>n*.26f||r-l+1<3||b-t+1<3)continue;
            byte[] alpha=new byte[n];
            for(int j=0;j<tail;j++)alpha[queue[j]]=(byte)255;
            float coverage=tail/(float)Math.max(1,(r-l+1)*(b-t+1));
            float confidence=Math.min(1f,.25f+coverage*.60f+
                    Math.min(.15f,(float)tail/n*4));
            found.add(new Region(alpha,l,t,r,b,tail,
                    sumX/(float)tail,sumY/(float)tail,
                    red/(float)tail,green/(float)tail,
                    blue/(float)tail,confidence));
        }
        found.sort(Comparator.comparingInt((Region c)->c.pixels).reversed());
        if(found.size()>MAX_TRACKS*3)return found.subList(0,MAX_TRACKS*3);
        return found;
    }
    private static List<Track> match(List<Region> before,List<Region> after,int w,int h) {
        ArrayList<Track> tracks=new ArrayList<>();
        boolean[] consumed=new boolean[after.size()];
        for(Region a:before){
            float best=-1;int bestIndex=-1;
            for(int j=0;j<after.size();j++){
                if(consumed[j])continue;
                Region b=after.get(j);
                float colorDistance=(float)Math.sqrt(
                        Math.pow((a.meanR-b.meanR)/255f,2)
                       +Math.pow((a.meanG-b.meanG)/255f,2)
                       +Math.pow((a.meanB-b.meanB)/255f,2))/1.732f;
                float spatial=(float)Math.hypot((a.cx-b.cx)/w,(a.cy-b.cy)/h);
                float areaRatio=Math.min(a.pixels,b.pixels)/
                        (float)Math.max(1,Math.max(a.pixels,b.pixels));
                if(spatial>.55f||areaRatio<.16f)continue;
                float similarity=Math.max(0,1-colorDistance)*.52f
                        +Math.max(0,1-spatial/.55f)*.29f
                        +areaRatio*.19f;
                if(similarity>best){best=similarity;bestIndex=j;}
            }
            if(bestIndex<0)continue;
            Region b=after.get(bestIndex);
            consumed[bestIndex]=true;
            float confidence=(float)(best*.72+
                    .28*Math.min(a.confidence,b.confidence));
            tracks.add(new Track(a,b,confidence));
            if(tracks.size()>=MAX_TRACKS)break;
        }
        return tracks;
    }
}
