package com.rezoxnemesis.videostudio;

import android.graphics.Canvas;

/**
 * Safe, 2.5D camera choreography layered ON TOP of actual source-object
 * animation. This never substitutes a zoom for missing figure articulation.
 *
 * Because flat source drawings do not contain unseen sides of buildings,
 * camera moves are small, keep the 9:16 world covered, and return precisely
 * to the authored source view at every keyframe boundary.
 */
public final class StoryboardCameraDirector {
    public static final class State {
        public final float dx,dy,scale,rotation,shake,focusX,focusY;
        State(float dx,float dy,float scale,float rotation,float shake,
              float focusX,float focusY){
            this.dx=dx;this.dy=dy;this.scale=scale;
            this.rotation=rotation;this.shake=shake;
            this.focusX=focusX;this.focusY=focusY;
        }
        public void apply(Canvas canvas,int width,int height){
            if(canvas==null||width<=0||height<=0)
                throw new IllegalArgumentException("Valid source screen required");
            float cx=focusX*width,cy=focusY*height;
            canvas.translate(dx*width,dy*height);
            canvas.translate(cx,cy);
            canvas.scale(scale,scale);
            canvas.rotate(rotation);
            canvas.translate(-cx,-cy);
        }
    }
    private StoryboardCameraDirector(){}
    public static State at(StoryboardMotionGrammar.SceneKind scene,
                           StoryboardMotionGrammar.Action action,float time,
                           float focusX,float focusY,float travelX,
                           float travelY,int frame) {
        if(scene==null||action==null||!Float.isFinite(time)||
                time<0||time>1||!Float.isFinite(focusX)||
                !Float.isFinite(focusY))
            throw new IllegalArgumentException("Camera needs verified storyboard motion");
        float window=(float)Math.pow(Math.sin(Math.PI*time),2);
        float strength=scene==StoryboardMotionGrammar.SceneKind.ACTION?.60f:.40f;
        // The camera tracks moving actors ONLY by a small portion of their
        // observed motion; the actor still does the real moving.
        float dx=-travelX*.13f*strength*window;
        float dy=-travelY*.10f*strength*window;
        float scale=1f,rotation=0f,shake=0;
        switch(action){
            case RUN:case WALK:case TRACK:
                scale+=.012f*window;
                break;
            case IMPACT:case STRIKE:case EXPLODE:
                float hit=(float)Math.exp(-Math.pow((time-.62f)/.10f,2));
                shake=hit*.0045f;
                dx+=shake*(float)Math.sin(frame*2.41f);
                dy-=shake*(float)Math.cos(frame*2.97f)*.5f;
                scale+=.017f*hit;
                break;
            case PUSH:
                scale+=.025f*window;
                break;
            case PULL:
                // Never uncover black bars outside the illustrator's frame;
                // visual depth comes from independent layer motion instead.
                scale+=.006f*window;
                break;
            case PAN:
                dx+=.012f*window;
                break;
            case ORBIT:
                rotation+=.45f*window;
                break;
            case LEAP:
                dy+=.008f*window;scale+=.008f*window;
                break;
            case REVEAL:case FLOAT:case DRIFT:
                dx+=.004f*window;
                break;
            default:break;
        }
        // Cover the full portrait despite the bounded lateral movement.
        // No black/transparent borders, no new unseen camera surface.
        dx=Math.max(-.012f,Math.min(.012f,dx));
        dy=Math.max(-.012f,Math.min(.012f,dy));
        float coverage=1+2.3f*Math.max(Math.abs(dx),Math.abs(dy));
        scale=Math.max(coverage,Math.max(1f,Math.min(1.035f,scale)));
        return new State(dx,dy,scale,rotation,shake,
                Math.max(.2f,Math.min(.8f,focusX)),
                Math.max(.2f,Math.min(.8f,focusY)));
    }
}
