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

    /**
     * Explicit per-object polygons from the source storyboard or a verified
     * segmentation model may replace the imperfect pixel-difference detector.
     * Supports any number of moving illustrated objects from 1 to 8,
     * without imposing person/skeleton semantics on animals, vehicles or text.
     */
    public static Result annotated(int[] first,int[] last,int width,int height,
                                   List<float[][]> fromPolygons,
                                   List<float[][]> toPolygons) {
        if(fromPolygons==null||toPolygons==null||
                fromPolygons.size()!=toPolygons.size()||
                fromPolygons.size()<1||fromPolygons.size()>MAX_TRACKS)
            throw new IllegalArgumentException("Supply one to eight paired source masks");
        ArrayList<boolean[]> from=new ArrayList<>(),to=new ArrayList<>();
        for(int i=0;i<fromPolygons.size();i++) {
            from.add(StoryboardCleanPlate.mask(width,height,fromPolygons.get(i)));
            to.add(StoryboardCleanPlate.mask(width,height,toPolygons.get(i)));
        }
        return fromMasks(first,last,width,height,from,to,.97f);
    }

    /**
     * Bounded segmentation adapter accepting true per-pixel masks supplied
     * by manual tracing OR the already-bundled local ML Kit person model.
     * Never pretend selfie's model can segment arbitrary drawn creatures.
     */
    public static Result fromMasks(int[] first,int[] last,int width,int height,
                                   List<boolean[]> masksA,
                                   List<boolean[]> masksB,float trust) {
        if(first==null||last==null||width<32||height<32
                ||(long)width*height>MAX_PIXELS||
                first.length!=width*height||last.length!=first.length
                ||masksA==null||masksB==null
                ||masksA.size()!=masksB.size()
                ||masksA.isEmpty()||masksA.size()>MAX_TRACKS
                ||!Float.isFinite(trust)||trust<0||trust>1)
            throw new IllegalArgumentException("Supply 1..8 matching source object masks");
        int n=width*height;
        boolean[] occupiedA=new boolean[n],occupiedB=new boolean[n];
        ArrayList<Track> tracks=new ArrayList<>();
        for(int index=0;index<masksA.size();index++){
            boolean[] a=masksA.get(index),b=masksB.get(index);
            if(a==null||b==null||a.length!=n||b.length!=n)
                throw new IllegalArgumentException("Mask dimensions differ from the imported artwork");
            Region ra=manualRegion(a,first,width,height);
            Region rb=manualRegion(b,last,width,height);
            if(ra==null||rb==null)throw new IllegalArgumentException(
                    "An illustrated object's mask is empty: "+index);
            for(int p=0;p<n;p++){occupiedA[p]|=a[p];occupiedB[p]|=b[p];}
            tracks.add(new Track(ra,rb,trust));
        }
        boolean[] hidden=new boolean[n];
        int[] plate=first.clone();
        int count=0,stable=0;long delta=0;
        for(int p=0;p<n;p++){
            if(!occupiedA[p]&&!occupiedB[p]){
                stable++;delta+=difference(first[p],last[p]);
            }else if(occupiedA[p]&&!occupiedB[p])plate[p]=last[p];
            else if(occupiedA[p]&&occupiedB[p]){
                hidden[p]=true;count++;
            }
        }
        float drift=stable==0?1f:(float)(delta/(stable*765d));
        if(stable<n*.22||drift>.18f)return new Result(Status.CAMERA_CUT,
                plate,tracks,drift,count/(float)n,0,
                "Camera/lighting differs; provide separate shots or a clean plate");
        if(count>n*.24f)return new Result(Status.NEEDS_SOURCE_MASKS,
                plate,tracks,drift,count/(float)n,0,
                "Too much of the scene is obscured behind the objects");
        int[] nearest=nearestUnchanged(hidden,width,height);
        for(int i=0;i<n;i++)if(hidden[i]&&nearest[i]>=0)
            plate[i]=plate[nearest[i]];
        return new Result(trust>=.42f?Status.READY:Status.NEEDS_SOURCE_MASKS,
                plate,tracks,drift,count/(float)n,0,
                trust>=.42f?"Verified provider supplied source-object alpha masks"
                    :"Segmentation confidence too low for faithful animation");
    }

    private static Region manualRegion(boolean[] mask,int[] image,int w,int h){
        int count=0,left=w-1,right=0,top=h-1,bottom=0;
        long xSum=0,ySum=0,r=0,g=0,b=0;
        byte[] alpha=new byte[w*h];
        for(int i=0;i<mask.length;i++){
            if(!mask[i])continue;
            int x=i%w,y=i/w;
            alpha[i]=(byte)255;count++;left=Math.min(left,x);right=Math.max(right,x);
            top=Math.min(top,y);bottom=Math.max(bottom,y);
            xSum+=x;ySum+=y;
            r+=image[i]>>>16&255;g+=image[i]>>>8&255;b+=image[i]&255;
        }
        if(count<4)return null;
        return new Region(alpha,left,top,right,bottom,count,
                xSum/(float)count,ySum/(float)count,
                r/(float)count,g/(float)count,b/(float)count,.97f);
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
