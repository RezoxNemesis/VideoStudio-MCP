package com.rezoxnemesis.videostudio;

import java.util.List;

/**
 * Small, stable 2D contact solver on ORIGINAL tracked object bounding volumes.
 * Applies only nonpenetration and finite recoil near an actual illustrated
 * collision beat, not fabricated 3D physics or arbitrary ragdoll skeletons.
 */
public final class StoryboardInteractionSolver {
    public static final class Body {
        public final float authoredX,authoredY,radiusX,radiusY;
        public float x,y,contactEnergy;
        public Body(float x,float y,float radiusX,float radiusY) {
            if(!Float.isFinite(x)||!Float.isFinite(y)||
                    !Float.isFinite(radiusX)||!Float.isFinite(radiusY)||
                    radiusX<=0||radiusY<=0||radiusX>.6||radiusY>.6)
                throw new IllegalArgumentException("2D bounding box must be physically bounded");
            authoredX=x;authoredY=y;
            this.x=x;this.y=y;this.radiusX=radiusX;this.radiusY=radiusY;
        }
    }
    private StoryboardInteractionSolver(){}

    public static void resolve(List<Body> bodies,float t,
                               StoryboardMotionGrammar.Action action) {
        if(bodies==null||bodies.size()>8||t<0||t>1||!Float.isFinite(t))
            throw new IllegalArgumentException("Contact solver accepts at most eight source objects");
        if(action!=StoryboardMotionGrammar.Action.IMPACT
                &&action!=StoryboardMotionGrammar.Action.STRIKE
                &&action!=StoryboardMotionGrammar.Action.RECOIL
                &&action!=StoryboardMotionGrammar.Action.DODGE)
            return;
        float envelope=(float)Math.sin(Math.PI*t);
        for(int i=0;i<bodies.size();i++)for(int j=i+1;j<bodies.size();j++){
            Body a=bodies.get(i),b=bodies.get(j);
            float dx=b.x-a.x,dy=b.y-a.y;
            float xLimit=(a.radiusX+b.radiusX)*.85f;
            float yLimit=(a.radiusY+b.radiusY)*.75f;
            if(Math.abs(dx)>=xLimit||Math.abs(dy)>=yLimit)continue;
            float penetration=xLimit-Math.abs(dx);
            float sideways=Math.min(.022f,penetration*.38f)*envelope;
            float sign=dx>=0?1:-1;
            a.x-=sign*sideways*.5f;
            b.x+=sign*sideways*.5f;
            float separation=(float)Math.hypot(dx,dy);
            float contact=(1-Math.min(1,separation/Math.max(.001f,xLimit)))
                    *envelope;
            a.contactEnergy=Math.max(a.contactEnergy,contact);
            b.contactEnergy=Math.max(b.contactEnergy,contact);
        }
        // The start and end image positions are sacrosanct.
        if(t==0||t==1)for(Body b:bodies){
            b.x=b.authoredX;b.y=b.authoredY;b.contactEnergy=0;
        }
    }
}
