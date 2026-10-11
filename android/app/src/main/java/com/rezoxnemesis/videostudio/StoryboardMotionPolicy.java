package com.rezoxnemesis.videostudio;

/**
 * Image-only motion suitability check, before a storyboard is rendered.
 *
 * Can detect large image changes, but does NOT recognize skeletons or solve
 * limb motion. The meaning of the decision is intentionally conservative:
 * FLOW_CANDIDATE is not a claim that cinematic character motion was generated.
 */
public final class StoryboardMotionPolicy {
    public enum Decision {
        FLOW_CANDIDATE, NEEDS_ARTICULATED_RIG, NEEDS_SCENE_RECONSTRUCTION
    }
    public static final class Diagnosis {
        public final Decision decision;
        public final double changedActionFraction, actionDifference, staticRegionDifference;
        public final double correspondenceConfidence, forwardBackwardConsistency;
        Diagnosis(Decision d,double fraction,double difference,double scene,
                  double confidence,double consistency) {
            decision=d;changedActionFraction=fraction;actionDifference=difference;
            staticRegionDifference=scene;correspondenceConfidence=confidence;
            forwardBackwardConsistency=consistency;
        }
        public boolean requiresPoseGeneration() {
            return decision!=Decision.FLOW_CANDIDATE;
        }
    }

    private StoryboardMotionPolicy() {}

    public static Diagnosis evaluate(int[] before,int[] after,int w,int h,
                                     PoseSequenceFlow.Pair flow) {
        if(flow==null||before==null||after==null||w<32||h<32||
                before.length!=w*h||after.length!=w*h)
            throw new IllegalArgumentException("Valid source frames and flow are required");
        double action=0,background=0;
        int actionPixels=0,changed=0,backgroundPixels=0;
        int split=(int)(h*.34),bottom=(int)(h*.96);
        int left=(int)(w*.06),right=(int)(w*.94);
        for(int y=0;y<h;y+=3) {
            for(int x=0;x<w;x+=3) {
                int i=y*w+x;
                int a=before[i],b=after[i];
                int r=Math.abs((a>>>16&255)-(b>>>16&255));
                int g=Math.abs((a>>>8&255)-(b>>>8&255));
                int blue=Math.abs((a&255)-(b&255));
                double delta=(r+g+blue)/(3.0*255);
                if(y>=split&&y<bottom&&x>=left&&x<right) {
                    action+=delta;actionPixels++;
                    if(delta>.17)changed++;
                }else if(y<split){
                    background+=delta;backgroundPixels++;
                }
            }
        }
        double actionDiff=action/Math.max(1,actionPixels);
        double bgDiff=background/Math.max(1,backgroundPixels);
        double changedFraction=changed/(double)Math.max(1,actionPixels);
        Decision decision;
        if(flow.sceneCutRisk ||
           (flow.sceneDifference>.23 && flow.changedFraction>.48) ||
           (bgDiff>.23 && changedFraction>.18)) {
            decision=Decision.NEEDS_SCENE_RECONSTRUCTION;
        }else if(changedFraction>.16 ||
                (changedFraction>.065 && actionDiff>bgDiff*1.7) ||
                (changedFraction>.04 && flow.confidence<.15) ||
                (changedFraction>.04 && flow.averageConsistency<.50)) {
            decision=Decision.NEEDS_ARTICULATED_RIG;
        }else{
            decision=Decision.FLOW_CANDIDATE;
        }
        return new Diagnosis(decision,changedFraction,actionDiff,bgDiff,
                flow.confidence,flow.averageConsistency);
    }
}
