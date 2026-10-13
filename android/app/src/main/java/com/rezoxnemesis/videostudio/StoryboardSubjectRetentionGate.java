package com.rezoxnemesis.videostudio;

/**
 * Source-mask material continuity diagnostic for generated preview artwork.
 *
 * The earlier video test incorrectly passed when only a WHITE actor stayed
 * visible and its BLACK opponent disappeared from all interior frames.
 * Mean full-frame pixel change cannot catch that. This gate separately
 * samples each tracked source mask and checks that its actual
 * colors/foreground contrast remain present at its predicted location.
 *
 * Mask-relative coverage cannot establish complete actor anatomy, physical
 * contacts or choreography. An incomplete mask remains incomplete evidence.
 */
public final class StoryboardSubjectRetentionGate {
    public static final class Report {
        public final boolean accepted;
        public final int verifiedObjects,failedIndex;
        public final double minimumCoverage;
        public final String reason;
        public final String diagnosticScope="SOURCE_MASK_MATERIAL_ONLY";
        public final boolean anatomicalCoverageCertified=false;
        Report(boolean accepted,int verifiedObjects,int failedIndex,
               double coverage,String reason){
            this.accepted=accepted;this.verifiedObjects=verifiedObjects;
            this.failedIndex=failedIndex;minimumCoverage=coverage;
            this.reason=reason;
        }
    }
    private StoryboardSubjectRetentionGate(){}

    public static Report inspect(StoryboardLayerIsolation.Result tracked,
                                 int[] first,int[] last,int[] middle,
                                 int width,int height) {
        return inspect(tracked,first,last,middle,width,height,.5f);
    }

    /**
     * Predicted position must match the ACTUAL generated sample timestamp.
     * Checking all samples against the midpoint incorrectly rejects an
     * actor that is still taking its first legitimate step.
     */
    public static Report inspect(StoryboardLayerIsolation.Result tracked,
                                 int[] first,int[] last,int[] middle,
                                 int width,int height,float phase) {
        if(!Float.isFinite(phase)||phase<0||phase>1)
            throw new IllegalArgumentException("Sample phase must be in 0..1");
        if(tracked==null||!tracked.autoRenderable()||
                first==null||last==null||middle==null||
                width<32||height<32||
                (long)width*height>720L*1280L||
                first.length!=width*height||
                last.length!=first.length||
                middle.length!=first.length||
                tracked.cleanPlate.length!=first.length)
            throw new IllegalArgumentException(
                    "Need a complete source-object track and actual rendered midpoint");
        int confirmed=0,at=0;
        double minimum=1;
        for(StoryboardLayerIsolation.Track t:tracked.tracks){
            StoryboardLayerIsolation.Region a=t.from,b=t.to;
            float predictedX=a.cx+(b.cx-a.cx)*phase;
            float predictedY=a.cy+(b.cy-a.cy)*phase;
            int left=Math.max(0,(int)(predictedX-
                    (a.width()+b.width())*.33f));
            int right=Math.min(width-1,(int)(predictedX+
                    (a.width()+b.width())*.33f));
            int top=Math.max(0,(int)(predictedY-
                    (a.height()+b.height())*.32f));
            int bottom=Math.min(height-1,(int)(predictedY+
                    (a.height()+b.height())*.32f));
            int reference=Math.max(1,Math.min(a.pixels,b.pixels));
            float avgR=(a.meanR+b.meanR)*.5f,
                  avgG=(a.meanG+b.meanG)*.5f,
                  avgB=(a.meanB+b.meanB)*.5f;
            int contrasting=0,similar=0;
            for(int y=top;y<=bottom;y++)for(int x=left;x<=right;x++){
                int p=y*width+x;
                int foreground=middle[p],environment=tracked.cleanPlate[p];
                int bgDiff=distance(foreground,
                        environment>>>16&255,
                        environment>>>8&255,
                        environment&255);
                if(bgDiff<74)continue;
                contrasting++;
                int close=distance(foreground,avgR,avgG,avgB);
                if(close<Math.max(72,Math.min(132,
                        t.confidence*110f)))similar++;
            }
            // Original illustrated regions can contain many colors. If
            // their starting matte has a uniform paint palette, demand
            // retention of that palette as well as general scene contrast.
            boolean simpleColor=uniform(first,a,width)>.72f &&
                    uniform(last,b,width)>.72f;
            int evidence=simpleColor?similar:contrasting;
            float threshold=.65f;
            double coverage=Math.min(1,evidence/(double)reference);
            minimum=Math.min(minimum,coverage);
            if(coverage<threshold)
                return new Report(false,confirmed,at,coverage,
                        "Tracked source-mask material #"+(at+1)+
                        " vanished or lost substantial source material in the preview frame");
            confirmed++;
            at++;
        }
        return new Report(true,confirmed,-1,minimum,
                "Tracked source-mask material retained at the sampled phase; actor anatomy unverified");
    }

    private static float uniform(int[] pixels,StoryboardLayerIsolation.Region r,int width){
        int count=0,near=0;
        for(int y=r.top;y<=r.bottom;y++)
            for(int x=r.left;x<=r.right;x++){
                int at=y*width+x;
                if(r.alpha[at]==0)continue;
                count++;
                if(distance(pixels[at],r.meanR,r.meanG,r.meanB)<70)near++;
            }
        return near/(float)Math.max(1,count);
    }
    private static int distance(int color,float r,float g,float b){
        return Math.round(Math.abs((color>>>16&255)-r)+
                Math.abs((color>>>8&255)-g)+
                Math.abs((color&255)-b));
    }
}
