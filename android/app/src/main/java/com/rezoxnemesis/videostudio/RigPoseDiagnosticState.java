package com.rezoxnemesis.videostudio;

/** Phone-only test-pose copies. No rig-store save or source-rest mutation is exposed. */
final class RigPoseDiagnosticState {
    static final class Snapshot {
        private final float[][] joints;
        private final float[] swordTip;
        private final float phase;
        private Snapshot(float[][] joints,float[] tip,float phase){this.joints=copy(joints);this.swordTip=tip.clone();this.phase=phase;}
    }
    private final float[][] sourceJoints;
    private final float[] sourceSwordTip;
    private float[][] testJoints;
    private float[] testSwordTip;
    private float phase=1;
    RigPoseDiagnosticState(float[][] joints,float[] tip){
        if(joints==null||joints.length!=16||tip==null)throw new IllegalArgumentException("A saved source pose and original sword tip are required");
        for(float[] point:joints)check(point);check(tip);
        sourceJoints=copy(joints);sourceSwordTip=tip.clone();reset();
    }
    void reset(){testJoints=copy(sourceJoints);testSwordTip=sourceSwordTip.clone();phase=1;}
    void moveJoint(int index,float u,float v){if(index<0||index>=16)throw new IllegalArgumentException("Select a named joint");float[] point={u,v};check(point);testJoints[index]=point;}
    void moveSwordTip(float u,float v){float[] point={u,v};check(point);testSwordTip=point;}
    void setPhase(float value){if(!Float.isFinite(value)||value<0||value>1)throw new IllegalArgumentException("Preview phase must be in 0..1");phase=value;}
    float phase(){return phase;}
    Snapshot snapshot(){return new Snapshot(testJoints,testSwordTip,phase);}
    void restore(Snapshot saved){if(saved==null)return;testJoints=copy(saved.joints);testSwordTip=saved.swordTip.clone();phase=saved.phase;}
    float[][] targetJoints(){float[][] result=new float[16][2];for(int i=0;i<16;i++)result[i]=interpolate(sourceJoints[i],testJoints[i]);return result;}
    float[] targetSwordTip(){return interpolate(sourceSwordTip,testSwordTip);}
    private float[] interpolate(float[] a,float[] b){return new float[]{a[0]+(b[0]-a[0])*phase,a[1]+(b[1]-a[1])*phase};}
    private static void check(float[] point){if(point==null||point.length!=2||!Float.isFinite(point[0])||!Float.isFinite(point[1])||point[0]<0||point[0]>1||point[1]<0||point[1]>1)throw new IllegalArgumentException("Test-pose coordinates must be finite oriented-source U/V in 0..1");}
    private static float[][] copy(float[][] values){float[][] result=new float[values.length][];for(int i=0;i<values.length;i++)result[i]=values[i].clone();return result;}
}
