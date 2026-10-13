package com.rezoxnemesis.videostudio;

/**
 * Deterministic source-action quality gate. The app must not call a static
 * still, zoom, mirrored camera move, or repeated pose "real animation".
 * A rig shot needs observable change in ORIGINAL actor positions/joints.
 */
public final class StoryboardMotionQualityGate {
    public static final class Verdict {
        public final double actorMotionPixels;
        public final Double swordMotionPixels;
        public final double blackNonAffineMotionPixels,whiteNonAffineMotionPixels;
        public final boolean approved;
        public final String reason;
        Verdict(double actorMotion,Double swordMotion,double blackResidual,double whiteResidual,boolean allowed,String reason){
            actorMotionPixels=actorMotion;swordMotionPixels=swordMotion;
            blackNonAffineMotionPixels=blackResidual;whiteNonAffineMotionPixels=whiteResidual;
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
        if(!blackA.black||!blackB.black||whiteA.black||whiteB.black)
            throw new IllegalArgumentException("Source actor ownership must remain black/white");
        validate(blackA.joints);validate(whiteA.joints);validate(blackB.joints);validate(whiteB.joints);
        double blackResidual=nonAffineResidualPixels(blackA.joints,blackB.joints,width,height);
        double whiteResidual=nonAffineResidualPixels(whiteA.joints,whiteB.joints,width,height);
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
        Double sword=blackA.swordTip==null||blackB.swordTip==null||whiteA.swordTip==null||whiteB.swordTip==null?null:
                (pixels(blackA.swordTip,blackB.swordTip,width,height)+pixels(whiteA.swordTip,whiteB.swordTip,width,height))/2;
        if(typical<3.0 && (sword==null||sword<4.0))return new Verdict(typical,sword,blackResidual,whiteResidual,false,
                "Adjacent drawings contain no measurable fighter or sword motion. "
               +"Mark a real action pair; zooming the city is not character animation.");
        if(Math.abs(blackB.joints[0][0]-blackA.joints[0][0])>.34
                || Math.abs(whiteB.joints[0][0]-whiteA.joints[0][0])>.34
                || Math.abs(blackB.joints[0][1]-blackA.joints[0][1])>.38
                || Math.abs(whiteB.joints[0][1]-whiteA.joints[0][1])>.38
                ||typical>Math.max(width,height)*.52)return new Verdict(typical,sword,blackResidual,whiteResidual,false,
                "Displacement suggests a camera cut or unrelated shot. "
               +"Split the storyboard into separate keyed actions.");
        double minimum=Math.max(.75,Math.min(width,height)*.001);
        if(blackResidual<minimum||whiteResidual<minimum)return new Verdict(typical,sword,blackResidual,whiteResidual,false,
                "A source actor only inherits rigid/common-affine motion; genuine non-root joint articulation is required.");
        if(sword==null)return new Verdict(typical,null,blackResidual,whiteResidual,false,
                "Source sword motion is unavailable; both original sword endpoints are required.");
        return new Verdict(typical,sword,blackResidual,whiteResidual,true,
                "Both source poses contain non-affine joint changes. This endpoint eligibility is not decoded cinematic acceptance.");
    }
    private static double pixels(float[] a,float[] b,int w,int h) {
        if(a==null||b==null||a.length!=2||b.length!=2||!Float.isFinite(a[0])||!Float.isFinite(a[1])||!Float.isFinite(b[0])||!Float.isFinite(b[1]))
            throw new IllegalArgumentException("Incomplete 2D actor landmarks");
        return Math.hypot((a[0]-b[0])*w,(a[1]-b[1])*h);
    }
    private static void validate(float[][] pose){
        if(pose==null||pose.length!=16)throw new IllegalArgumentException("Exactly sixteen source joint landmarks required");
        for(float[] point:pose)if(point==null||point.length!=2||!Float.isFinite(point[0])||!Float.isFinite(point[1]))
            throw new IllegalArgumentException("Source joint landmarks must be finite 2D points");
    }
    /** RMS displacement remaining after the best single affine map, in real pixels. */
    public static double nonAffineResidualPixels(float[][] from,float[][] to,int width,int height){
        validate(from);validate(to);
        if(width<1||height<1)throw new IllegalArgumentException("Positive physical frame dimensions required");
        double[][] a=new double[16][2],b=new double[16][2];
        for(int index=0;index<16;index++){
            a[index][0]=from[index][0]*width;a[index][1]=from[index][1]*height;
            b[index][0]=to[index][0]*width;b[index][1]=to[index][1]*height;
        }
        return nonAffineResidualPixels(a,b);
    }
    /** Shared with the evidence ledger; inputs already use registered pixel coordinates. */
    public static double nonAffineResidualPixels(double[][] from,double[][] to){
        if(from==null||to==null||from.length!=16||to.length!=16)
            throw new IllegalArgumentException("Exactly sixteen joint landmarks required");
        double ax=0,ay=0,bx=0,by=0;
        for(int index=0;index<16;index++){
            double[] a=from[index],b=to[index];
            if(a==null||b==null||a.length!=2||b.length!=2||!Double.isFinite(a[0])||!Double.isFinite(a[1])||!Double.isFinite(b[0])||!Double.isFinite(b[1]))
                throw new IllegalArgumentException("Joint landmarks must be finite 2D points");
            ax+=a[0]/16;ay+=a[1]/16;bx+=b[0]/16;by+=b[1]/16;
        }
        double xx=0,xy=0,yy=0,ux=0,uy=0,vx=0,vy=0;
        for(int index=0;index<16;index++){
            double x=from[index][0]-ax,y=from[index][1]-ay,u=to[index][0]-bx,v=to[index][1]-by;
            xx+=x*x;xy+=x*y;yy+=y*y;ux+=x*u;uy+=y*u;vx+=x*v;vy+=y*v;
        }
        double determinant=xx*yy-xy*xy;
        if(!Double.isFinite(determinant)||determinant<=Math.max(1e-12,xx*yy*1e-10))
            throw new IllegalArgumentException("Joint layout cannot support a stable two-dimensional affine measurement");
        double m00=(ux*yy-uy*xy)/determinant,m01=(uy*xx-ux*xy)/determinant;
        double m10=(vx*yy-vy*xy)/determinant,m11=(vy*xx-vx*xy)/determinant,error=0;
        for(int index=0;index<16;index++){
            double x=from[index][0]-ax,y=from[index][1]-ay;
            double dx=to[index][0]-(bx+m00*x+m01*y),dy=to[index][1]-(by+m10*x+m11*y);
            error+=dx*dx+dy*dy;
        }
        return Math.sqrt(error/16);
    }
}
