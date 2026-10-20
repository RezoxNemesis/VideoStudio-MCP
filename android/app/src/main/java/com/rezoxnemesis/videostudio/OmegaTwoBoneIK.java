package com.rezoxnemesis.videostudio;

/**
 * OMEGA Phase 4 pure kinematic core. Enforces fixed upper/lower bone lengths
 * and explicit elbow bend side; native deformable overlays are future adapters.
 */
final class OmegaTwoBoneIK {
    private OmegaTwoBoneIK() {}
    static final class Joint {
        final double x,y;
        Joint(double x,double y) {
            if(!Double.isFinite(x)||!Double.isFinite(y)||Math.abs(x)>100||Math.abs(y)>100)
                throw new IllegalArgumentException("Invalid rig point");
            this.x=x;this.y=y;
        }
        double distanceTo(Joint other) {return Math.hypot(x-other.x,y-other.y);}
    }
    static final class Solution {
        final Joint root,elbow,tip,requestedTip;
        final boolean reachable;
        Solution(Joint root,Joint elbow,Joint tip,Joint requested,boolean reachable){
            this.root=root;this.elbow=elbow;this.tip=tip;this.requestedTip=requested;
            this.reachable=reachable;
        }
    }
    static Solution solve(Joint root,Joint target,double upperLength,double lowerLength,int bendSide) {
        if(root==null||target==null||!Double.isFinite(upperLength)||!Double.isFinite(lowerLength)
            ||upperLength<=.0001||lowerLength<=.0001||upperLength>10||lowerLength>10
            ||(bendSide!=1&&bendSide!=-1))
            throw new IllegalArgumentException("Unsafe rig constraints");
        double dx=target.x-root.x,dy=target.y-root.y,original=Math.hypot(dx,dy);
        double minDist=Math.abs(upperLength-lowerLength),maxDist=upperLength+lowerLength;
        // For coincident equal-length endpoints, select deterministic +X direction.
        double dist=Math.min(maxDist,Math.max(Math.max(1e-9,minDist),original));
        double ux=original>1e-12?dx/original:1;
        double uy=original>1e-12?dy/original:0;
        double along=(upperLength*upperLength-lowerLength*lowerLength+dist*dist)/(2*dist);
        along=Math.max(-upperLength,Math.min(upperLength,along));
        double height=Math.sqrt(Math.max(0,upperLength*upperLength-along*along));
        Joint elbow=new Joint(root.x+ux*along-bendSide*uy*height,
                              root.y+uy*along+bendSide*ux*height);
        Joint tip=new Joint(root.x+ux*dist,root.y+uy*dist);
        boolean reached=original>=minDist-1e-9 && original<=maxDist+1e-9;
        return new Solution(root,elbow,tip,target,reached);
    }
}