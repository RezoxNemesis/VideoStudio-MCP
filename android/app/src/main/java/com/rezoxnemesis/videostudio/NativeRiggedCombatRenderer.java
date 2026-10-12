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
 * Genuine per-time-step textured, two-actor combat. Same world camera across
 * frames, independently rigged source character images, physical foot locations,
 * attached metallic swords, timed sparks and contact dust.
 *
 * There is no duplicate static subject painted into a background. A user-supplied
 * background must be a clean plate; generated procedural plate is the safe fallback.
 */
public final class NativeRiggedCombatRenderer implements AutoCloseable {
    private final int width,height;
    private final Bitmap world;
    private final NativeSkinnedSprite2D black,white;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Path blade=new Path();

    public NativeRiggedCombatRenderer(int width,int height,Bitmap cleanPlate,
                                      NativeSkinnedSprite2D black,
                                      NativeSkinnedSprite2D white) {
        if(black==null||white==null)
            throw new IllegalArgumentException("Two approved rigged character cutouts are required");
        if(width<320||height<540||width>720||height>1280)
            throw new IllegalArgumentException("Invalid bounded combat frame");
        this.width=width;this.height=height;
        this.black=black;this.white=white;
        if(cleanPlate!=null) {
            Bitmap scaled=Bitmap.createScaledBitmap(cleanPlate,width,height,true);
            // Android returns the caller's bitmap when no resize is needed.
            // The world is renderer-owned and close() must never recycle that input.
            world=scaled==cleanPlate?cleanPlate.copy(Bitmap.Config.ARGB_8888,false):scaled;
            if(world==null)throw new IllegalStateException("Cannot allocate an owned clean scene bitmap");
        }else{
            NativeCombatRigRenderer env=new NativeCombatRigRenderer(width,height);
            world=env.staticBackgroundCopy();
            env.recycle();
        }
    }

    private float px(double x){return (float)(x*width);}
    private float py(double y){return (float)(y*height);}

    public Bitmap render(CombatRigSolver.Frame f,int frameIndex) {
        return render(f,frameIndex,null);
    }

    public Bitmap render(CombatRigSolver.Frame f,int frameIndex,
                         CombatActionPlan.Frame director) {
        Bitmap out=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(out);
        c.save();
        // A tiny impact-only world-camera impulse. All scene layers move
        // together; never create another copy of a fighter or shift its mask.
        if(director!=null) {
            float shake=(float)(director.cameraShake*width*.33);
            c.translate(shake,-Math.abs(shake)*.45f);
        }
        paint.reset();
        paint.setAntiAlias(true);paint.setFilterBitmap(true);
        c.drawBitmap(world,0,0,paint);

        drawGroundShadow(c,f.black);
        drawGroundShadow(c,f.white);

        // Explicit consistent z-order. If the story later changes depth, use
        // a scene graph and split this into per-shot compositing layers.
        black.render(c,f.black);
        white.render(c,f.white);

        // Keep swords physically attached to the character's IK hand.
        drawBlade(c,f.black,true);
        drawBlade(c,f.white,false);
        drawDust(c,f,frameIndex);
        drawImpact(c,f);
        c.restore();
        return out;
    }

    private void drawGroundShadow(Canvas c,CombatRigSolver.Warrior w){
        paint.reset();
        paint.setAntiAlias(true);
        paint.setColor(0x6505060c);
        float x=px(w.pelvis.x),y=py(.846);
        float radius=width*.085f;
        c.drawOval(x-radius,y-height*.009f,x+radius,y+height*.009f,paint);
    }

    private void drawBlade(Canvas c,CombatRigSolver.Warrior w,boolean dark){
        double dx=w.swordTip.x-w.swordHand.x;
        double dy=w.swordTip.y-w.swordHand.y;
        double distance=Math.max(.0001,Math.hypot(dx,dy));
        double ux=dx/distance,uy=dy/distance,nx=-uy,ny=ux;
        double rootX=w.swordHand.x+ux*.015,rootY=w.swordHand.y+uy*.015;
        blade.reset();
        blade.moveTo(px(rootX+nx*.007),py(rootY+ny*.007));
        blade.lineTo(px(w.swordTip.x),py(w.swordTip.y));
        blade.lineTo(px(rootX-nx*.007),py(rootY-ny*.007));
        blade.close();
        paint.reset();
        paint.setAntiAlias(true);
        paint.setStyle(Paint.Style.FILL);
        paint.setShader(new LinearGradient(
                px(rootX+nx*.006),py(rootY+ny*.006),
                px(rootX-nx*.006),py(rootY-ny*.006),
                new int[]{0xffd8e6ed,dark?0xffb4b8c5:0xfff1f5f7,0xff647580},
                null,Shader.TileMode.CLAMP));
        c.drawPath(blade,paint);
        paint.setShader(null);
        paint.setColor(dark?0xff493631:0xff756658);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeWidth(width*.011f);
        c.drawLine(px(w.swordHand.x-ux*.025),py(w.swordHand.y-uy*.025),
                px(rootX),py(rootY),paint);
        paint.setColor(dark?0xffae8259:0xffd3ab79);
        paint.setStrokeWidth(width*.014f);
        c.drawLine(px(w.swordHand.x-nx*.021),py(w.swordHand.y-ny*.021),
                px(w.swordHand.x+nx*.021),py(w.swordHand.y+ny*.021),paint);
    }

    private void drawImpact(Canvas c,CombatRigSolver.Frame f) {
        // Sparks appear ONLY around a real scheduled blade contact event.
        if(f.impactIntensity<.07||f.swordTipSeparation>.065)return;
        Random rng=new Random(77338);
        float x=px((f.black.swordTip.x+f.white.swordTip.x)*.5);
        float y=py((f.black.swordTip.y+f.white.swordTip.y)*.5);
        paint.reset();paint.setAntiAlias(true);paint.setStrokeCap(Paint.Cap.ROUND);
        for(int n=0;n<28;n++){
            double angle=2*Math.PI*(n+rng.nextDouble()*.5)/28;
            double reach=(.008+rng.nextDouble()*.065)*f.impactIntensity;
            paint.setColor(n%3==0?0xffffeac0:0xffed9850);
            paint.setStrokeWidth(Math.max(1f,width*.0025f));
            c.drawLine(x,y,px(x/(float)width+Math.cos(angle)*reach),
                    py(y/(float)height+Math.sin(angle)*reach),paint);
        }
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xfffff7da);
        c.drawCircle(x,y,width*(float)(.01*f.impactIntensity),paint);
    }

    private void drawDust(Canvas c,CombatRigSolver.Frame f,int frameIndex) {
        if(f.dustIntensity<.18)return;
        Random random=new Random(285193);
        paint.reset();paint.setAntiAlias(true);
        for(int j=0;j<50;j++){
            CombatRigSolver.Warrior w=(j%2==0)?f.black:f.white;
            double particleStart=(j%9)/11.0;
            double phase=frameIndex/25.0* (.28+.06*(j%7));
            double x=w.leadingFoot.x+(random.nextDouble()-.5)*.18
                     +(j%2==0?-1:1)*phase*.02;
            double y=.832-Math.abs(Math.sin(particleStart+phase))*f.dustIntensity*.067;
            float r=width*(.0006f+random.nextFloat()*.0025f);
            paint.setColor(j%3==0?0x9df9b87d:0x805e5248);
            c.drawCircle(px(x),py(y),r,paint);
        }
    }

    public Bitmap staticWorldCopy(){
        return world.copy(Bitmap.Config.ARGB_8888,false);
    }
    @Override public void close(){
        if(!world.isRecycled())world.recycle();
        black.close();white.close();
    }
}
