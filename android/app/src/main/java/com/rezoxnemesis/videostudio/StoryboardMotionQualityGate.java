package com.rezoxnemesis.videostudio;

/**
 * Deterministic source-action quality gate. The app must not call a static
 * still, zoom, mirrored camera move, or repeated pose "real animation".
 * A rig shot needs observable change in ORIGINAL actor positions/joints.
 */
public final class StoryboardMotionQualityGate {
    public static final class Verdict {
        public final double actorMotionPixels,swordMotionPixels;
        public final boolean approved;
        public final String reason;
        Verdict(double actorMotion,double swordMotion,boolean allowed,String reason){
            actorMotionPixels=actorMotion;swordMotionPixels=swordMotion;
            approved=allowed;this.reason=reason;
        }
    }
    private StoryboardMotionQualityGate(){}

    public static Verdict inspect(Rig2DProfile blackA,Rig2DProfile whiteA,
                                  Rig2DProfile blackB,Rig2DProfile whiteB,
                                  int width,int height){
        if(blackA==null||blackB==null||whiteA==null||whiteB==null||
                width<1||height<1)
            throw new IllegalArgumentException("Two authored black/white keyframes required");
        double displacement=0;
        int samples=0;
        for(int k:new int[]{Rig2DProfile.PELVIS,Rig2DProfile.HEAD,
                Rig2DProfile.SWORD_HAND,Rig2DProfile.LEADING_FOOT,
                Rig2DProfile.TRAILING_FOOT}) {
            displacement+=pixels(blackA.joints[k],blackB.joints[k],width,height);
            displacement+=pixels(whiteA.joints[k],whiteB.joints[k],width,height);
            samples+=2;
        }
        double typical=displacement/Math.max(1,samples);
        double sword=0;
        if(blackA.swordTip!=null&&blackB.swordTip!=null)
            sword+=pixels(blackA.swordTip,blackB.swordTip,width,height);
        if(whiteA.swordTip!=null&&whiteB.swordTip!=null)
            sword+=pixels(whiteA.swordTip,whiteB.swordTip,width,height);
        sword/=2;
        if(typical<3.0 && sword<4.0)return new Verdict(typical,sword,false,
                "Adjacent drawings contain no measurable fighter or sword motion. "
               +"Mark a real action pair; zooming the city is not character animation.");
        if(Math.abs(blackB.joints[0][0]-blackA.joints[0][0])>.34
                || Math.abs(whiteB.joints[0][0]-whiteA.joints[0][0])>.34
                || Math.abs(blackB.joints[0][1]-blackA.joints[0][1])>.38
                || Math.abs(whiteB.joints[0][1]-whiteA.joints[0][1])>.38
                ||typical>Math.max(width,height)*.52)return new Verdict(typical,sword,false,
                "Displacement suggests a camera cut or unrelated shot. "
               +"Split the storyboard into separate keyed actions.");
        return new Verdict(typical,sword,true,
                "Visible actor and blade motion follows real storyboard landmarks.");
    }
    private static double pixels(float[] a,float[] b,int w,int h) {
        if(a==null||b==null||a.length!=2||b.length!=2)
            throw new IllegalArgumentException("Incomplete 2D actor landmarks");
        return Math.hypot((a[0]-b[0])*w,(a[1]-b[1])*h);
    }
}
