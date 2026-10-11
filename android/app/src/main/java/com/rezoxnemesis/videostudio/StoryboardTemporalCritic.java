package com.rezoxnemesis.videostudio;

/**
 * Concrete frame-level visual QA (not just a green Android build).
 *
 * Tests source-identity preservation, actual new in-between pixels, a
 * stable original background and lack of implausible giant temporal jumps.
 * This does NOT measure subjective human beauty, actual acting or hidden
 * anatomy. A failed objective gate stops final export rather than quietly
 * presenting a slideshow as motion synthesis.
 */
public final class StoryboardTemporalCritic {
    public static final class Report {
        public final boolean acceptable;
        public final String reason;
        public final double middleSourceDifference;
        public final double middleTargetDifference;
        public final double unchangedWorldError;
        public final double endpointSceneDifference;
        Report(boolean ok,String reason,double sa,double sb,double world,double endpoint){
            acceptable=ok;this.reason=reason;
            middleSourceDifference=sa;
            middleTargetDifference=sb;
            unchangedWorldError=world;
            endpointSceneDifference=endpoint;
        }
    }
    private StoryboardTemporalCritic(){}

    public static Report inspect(int[] from,int[] to,int[] middle,int w,int h,
                                 boolean cameraOnly) {
        if(from==null||to==null||middle==null||w<32||h<32||
                (long)w*h>720L*1280L||
                from.length!=w*h||to.length!=w*h||middle.length!=w*h)
            throw new IllegalArgumentException("Need three same-size actual storyboard frames");
        double startDiff=0,targetDiff=0,sourceChange=0;
        double staticError=0;int stable=0;
        for(int i=0;i<from.length;i+=2) {
            double pair=colorDifference(from[i],to[i]);
            sourceChange+=pair;
            startDiff+=colorDifference(from[i],middle[i]);
            targetDiff+=colorDifference(middle[i],to[i]);
            if(pair<.035) {
                stable++;
                staticError+=colorDifference(middle[i],from[i]);
            }
        }
        double count=(from.length+1)/2d;
        double overall=sourceChange/count;
        double a=startDiff/count,b=targetDiff/count;
        double staticDelta=staticError/Math.max(1,stable);
        if(overall<.004 && !cameraOnly)return new Report(false,
                "Two source frames contain no visible animation change",a,b,staticDelta,overall);
        if(a<.0009&&!cameraOnly)return new Report(false,
                "The middle of the shot is effectively the first still image",a,b,staticDelta,overall);
        if(b<.0009&&!cameraOnly)return new Report(false,
                "The middle of the shot is effectively the last still image",a,b,staticDelta,overall);
        if(!cameraOnly&&staticDelta>.085)return new Report(false,
                "The supposedly stationary source background is drifting or ghosting",
                a,b,staticDelta,overall);
        if(!cameraOnly&&Math.max(a,b)>Math.max(.16,overall*1.9))
            return new Report(false,"The middle pose changes more than its surrounding storyboard anchors",
                    a,b,staticDelta,overall);
        return new Report(true,"Actual source-conditioned intermediate image pixels differ from both anchors",
                a,b,staticDelta,overall);
    }
    private static double colorDifference(int a,int b){
        return (Math.abs((a>>>16&255)-(b>>>16&255))
                +Math.abs((a>>>8&255)-(b>>>8&255))
                +Math.abs((a&255)-(b&255)))/765d;
    }
}
