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
    public static final class TrajectoryReport {
        public final boolean acceptable;
        public final String reason;
        public final double minAdjacentMotion,maxAdjacentMotion;
        TrajectoryReport(boolean ok,String reason,double low,double high){
            acceptable=ok;this.reason=reason;
            minAdjacentMotion=low;maxAdjacentMotion=high;
        }
    }

    /**
     * A midpoint alone cannot detect animation which holds the opening
     * keyframe, jumps once, and holds the ending keyframe. Four temporal
     * intervals must carry measurable source-owned movement. This is a
     * pixel-continuity QA gate, not a claim of anatomical plausibility.
     */
    public static TrajectoryReport inspectTrajectory(int[] start,int[] end,
            int[] quarter,int[] middle,int[] threeQuarter,
            int width,int height,boolean cameraOnly) {
        if(start==null||end==null||quarter==null||middle==null
                ||threeQuarter==null||width<32||height<32
                ||(long)width*height>720L*1280L
                ||start.length!=width*height||end.length!=start.length
                ||quarter.length!=start.length||middle.length!=start.length
                ||threeQuarter.length!=start.length)
            throw new IllegalArgumentException("Five equal-sized observed motion samples required");
        Report midpoint=inspect(start,end,middle,width,height,cameraOnly);
        if(!midpoint.acceptable)return new TrajectoryReport(false,
                midpoint.reason,0,0);
        if(cameraOnly)return new TrajectoryReport(true,
                "Camera/graphic scene: character motion not required",0,0);
        double source=meanDifference(start,end);
        double[] stages={
            meanDifference(start,quarter),
            meanDifference(quarter,middle),
            meanDifference(middle,threeQuarter),
            meanDifference(threeQuarter,end)
        };
        double smallest=Double.POSITIVE_INFINITY,biggest=0;
        for(double step:stages){
            smallest=Math.min(smallest,step);
            biggest=Math.max(biggest,step);
        }
        double minimum=Math.max(.0015,Math.min(.008,source*.075));
        if(smallest<minimum)
            return new TrajectoryReport(false,
                    "Character/object motion stalls between storyboard keyframes",
                    smallest,biggest);
        if(biggest>Math.max(.17,source*2.4))
            return new TrajectoryReport(false,
                    "Abrupt frame-to-frame jump instead of continuous action",
                    smallest,biggest);
        return new TrajectoryReport(true,
                "Four intervals contain measurable source-artwork movement",
                smallest,biggest);
    }

    private static double meanDifference(int[] a,int[] b) {
        double sum=0;int count=0;
        for(int i=0;i<a.length;i+=2){
            sum+=colorDifference(a[i],b[i]);count++;
        }
        return sum/Math.max(1,count);
    }

    private static double colorDifference(int a,int b){
        return (Math.abs((a>>>16&255)-(b>>>16&255))
                +Math.abs((a>>>8&255)-(b>>>8&255))
                +Math.abs((a&255)-(b&255)))/765d;
    }
}
