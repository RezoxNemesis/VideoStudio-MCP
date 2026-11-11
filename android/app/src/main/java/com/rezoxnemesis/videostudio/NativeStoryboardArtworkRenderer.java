package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import java.util.Random;

/**
 * Paints the ACTUAL illustrated fighters into a locked, source-derived city.
 * Every output frame uses continuous 2D masked mesh deformations of their
 * own image textures, never procedural replacement stickmen or image zooms.
 *
 * Supports short paired, authored-keyframe action shots. A large camera jump
 * must be split into another shot; 2D meshes cannot reveal unseen body sides.
 */
public final class NativeStoryboardArtworkRenderer implements AutoCloseable {
    private final int width,height;
    private final Bitmap background;
    private final Rig2DProfile blackStart,blackEnd,whiteStart,whiteEnd;
    private final NativeSkinnedSprite2D blackTextureStart,blackTextureEnd;
    private final NativeSkinnedSprite2D whiteTextureStart,whiteTextureEnd;
    private final float[] blackTipStart,blackTipEnd,whiteTipStart,whiteTipEnd;
    private final StoryboardPoseMotion.Action action;
    private final Paint brush=new Paint(Paint.ANTI_ALIAS_FLAG
            |Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);
    private final Path shape=new Path();

    public NativeStoryboardArtworkRenderer(int width,int height,int[] city,
            Rig2DProfile blackStart,Rig2DProfile whiteStart,
            Rig2DProfile blackEnd,Rig2DProfile whiteEnd,
            NativeSkinnedSprite2D blackTextureStart,
            NativeSkinnedSprite2D whiteTextureStart,
            NativeSkinnedSprite2D blackTextureEnd,
            NativeSkinnedSprite2D whiteTextureEnd,
            float[] blackTipStart,float[] whiteTipStart,
            float[] blackTipEnd,float[] whiteTipEnd,
            StoryboardPoseMotion.Action action) {
        if(width<256||height<432||width>720||height>1280||
                city==null||city.length!=width*height)
            throw new IllegalArgumentException("Invalid source artwork and portrait frame dimensions");
        if(blackStart==null||blackEnd==null||whiteStart==null||whiteEnd==null
                ||blackTextureStart==null||blackTextureEnd==null
                ||whiteTextureStart==null||whiteTextureEnd==null||action==null)
            throw new IllegalArgumentException("Two authentic traced frames per character are mandatory");
        this.width=width;this.height=height;
        this.blackStart=blackStart;this.blackEnd=blackEnd;
        this.whiteStart=whiteStart;this.whiteEnd=whiteEnd;
        this.blackTextureStart=blackTextureStart;
        this.blackTextureEnd=blackTextureEnd;
        this.whiteTextureStart=whiteTextureStart;
        this.whiteTextureEnd=whiteTextureEnd;
        this.blackTipStart=blackTipStart.clone();
        this.blackTipEnd=blackTipEnd.clone();
        this.whiteTipStart=whiteTipStart.clone();
        this.whiteTipEnd=whiteTipEnd.clone();
        this.action=action;
        background=Bitmap.createBitmap(city,width,height,Bitmap.Config.ARGB_8888);
    }

    /** Authored endpoints have exactly the reference motion landmarks. */
    public Bitmap render(double progress,int frameNo,int frameCount) {
        StoryboardPoseMotion.Frame motion=StoryboardPoseMotion.between(
                blackStart,whiteStart,blackEnd,whiteEnd,
                blackTipStart,whiteTipStart,blackTipEnd,whiteTipEnd,
                progress,action);
        Bitmap out=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(out);
        brush.reset();brush.setAntiAlias(true);brush.setFilterBitmap(true);
        canvas.save();
        canvas.drawBitmap(background,0,0,brush);
        // Controlled contact impulse, never a global Ken Burns camera drift.
        if(motion.contact>.1) {
            float impulse=(float)(.006*motion.contact*Math.sin(frameNo*1.8));
            canvas.translate(impulse*width,impulse*height*.5f);
        }
        drawFootShadow(canvas,motion.white);
        drawFootShadow(canvas,motion.black);
        // White is the distant actor in this particular screenplay shot.
        // Only the precise traced sprite is composited (the source city is
        // NEVER embedded in a foreground bitmap).
        actor(canvas,whiteTextureStart,whiteTextureEnd,motion.white,progress);
        actor(canvas,blackTextureStart,blackTextureEnd,motion.black,progress);
        blade(canvas,motion.white[Rig2DProfile.SWORD_HAND],
                motion.whiteBladeTip,false);
        blade(canvas,motion.black[Rig2DProfile.SWORD_HAND],
                motion.blackBladeTip,true);
        if(motion.contact>.05)impact(canvas,motion,frameNo,frameCount);
        canvas.restore();
        return out;
    }

    private void actor(Canvas canvas,NativeSkinnedSprite2D first,
                       NativeSkinnedSprite2D last,float[][] pose,double t) {
        // Masked artwork is aligned to the SAME interpolated landmarks.
        // A short local appearance handoff avoids a hard texture pop without
        // ghosting two whole frames/cities over one another.
        if(t<=.465)first.render(canvas,pose,255);
        else if(t>=.535)last.render(canvas,pose,255);
        else{
            float u=(float)((t-.465)/.07);
            float smooth=u*u*(3-2*u);
            first.render(canvas,pose,Math.round(255*(1-smooth)));
            last.render(canvas,pose,Math.round(255*smooth));
        }
    }

    private void drawFootShadow(Canvas canvas,float[][] pose) {
        float a=(pose[12][0]+pose[15][0])*.5f;
        float b=Math.max(pose[12][1],pose[15][1]);
        brush.reset();brush.setAntiAlias(true);brush.setColor(0x65030912);
        float r=width*.056f;
        canvas.drawOval(a*width-r,b*height-r*.10f,
                a*width+r,b*height+r*.10f,brush);
    }

    private void blade(Canvas canvas,float[] hand,float[] tip,boolean black) {
        float aX=hand[0]*width,aY=hand[1]*height;
        float bX=tip[0]*width,bY=tip[1]*height;
        float dx=bX-aX,dy=bY-aY;
        float dist=(float)Math.hypot(dx,dy);
        if(dist<3)return;
        float nx=-dy/dist,ny=dx/dist;
        // Metal blade physically rooted at the original drawn hand.
        shape.reset();
        shape.moveTo(aX+nx*2,aY+ny*2);
        shape.lineTo(bX,bY);
        shape.lineTo(aX-nx*2,aY-ny*2);
        shape.close();
        brush.reset();brush.setAntiAlias(true);
        brush.setShader(new LinearGradient(aX+nx*2,aY+ny*2,
                aX-nx*2,aY-ny*2,
                new int[]{0xfffbeacc,0xffc1d3db,0xff74848f},
                null,Shader.TileMode.CLAMP));
        canvas.drawPath(shape,brush);
        brush.setShader(null);
        brush.setColor(black?0xffb38b66:0xffb9c0c9);
        brush.setStrokeWidth(Math.max(2f,width*.006f));
        brush.setStrokeCap(Paint.Cap.ROUND);
        canvas.drawLine(aX-nx*width*.017f,aY-ny*width*.017f,
                aX+nx*width*.017f,aY+ny*width*.017f,brush);
    }

    private void impact(Canvas canvas,StoryboardPoseMotion.Frame frame,
                        int frameNo,int frames) {
        float centerX=(frame.blackBladeTip[0]+frame.whiteBladeTip[0])*.5f*width;
        float centerY=(frame.blackBladeTip[1]+frame.whiteBladeTip[1])*.5f*height;
        double energy=frame.contact;
        Random random=new Random(194038); // stable particles, never flashing random confetti
        brush.reset();brush.setAntiAlias(true);
        brush.setStrokeCap(Paint.Cap.ROUND);
        float elapsed=(float)frame.progress;
        for(int i=0;i<20;i++){
            float angle=(float)(Math.PI*2*i/20d);
            float spread=(float)(width*energy*
                    (.008+random.nextDouble()*.040));
            float x=centerX+(float)Math.cos(angle)*spread;
            float y=centerY+(float)Math.sin(angle)*spread;
            brush.setColor(i%4==0?0xfff7e6bf:0xfff8a861);
            brush.setStrokeWidth(i%3==0?2.4f:1.35f);
            canvas.drawLine(centerX,centerY,x,y,brush);
        }
        brush.setColor(0xfffaeac8);
        canvas.drawCircle(centerX,centerY,(float)(width*.009*energy),brush);
    }

    public Bitmap backgroundForQa(){
        return background.copy(Bitmap.Config.ARGB_8888,false);
    }

    @Override public void close(){
        if(!background.isRecycled())background.recycle();
        blackTextureStart.close();whiteTextureStart.close();
        blackTextureEnd.close();whiteTextureEnd.close();
    }
}
