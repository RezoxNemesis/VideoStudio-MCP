package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * Renders multiple separately-tracked pieces of ORIGINAL 2D artwork.
 * Not a still-image slideshow, Ken Burns trick, generic skeleton or
 * a full-frame crossfade. The source shapes are isolated, moved and
 * locally reposed in a clean source scene at each timestamp.
 *
 * For illustrated deforming bodies, the existing hand-correctable
 * NativeSkinnedSprite2D pose engine remains the higher-fidelity option.
 * Auto segmented objects use bounded rigid/affine 2D motion only;
 * hidden sides and genuinely new anatomy require a neural model.
 */
public final class StoryboardLayerRenderer implements AutoCloseable {
    private final int width,height;
    private final Bitmap first,last,plate;
    private final StoryboardLayerIsolation.Result tracking;
    private final StoryboardMotionGrammar.SceneKind scene;
    private final StoryboardMotionGrammar.Action action;
    private final ArrayList<SpritePair> sprites=new ArrayList<>();
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|
            Paint.FILTER_BITMAP_FLAG|Paint.DITHER_FLAG);

    private static final class Sprite {
        final Bitmap bitmap;
        final float pivotX,pivotY;
        final StoryboardLayerIsolation.Region region;
        Sprite(Bitmap bitmap,StoryboardLayerIsolation.Region region){
            this.bitmap=bitmap;this.region=region;
            pivotX=region.cx-region.left;
            pivotY=region.cy-region.top;
        }
    }
    private static final class SpritePair {
        final Sprite start,end;
        final float confidence;
        final StoryboardDeformableSprite deformation;
        SpritePair(Sprite a,Sprite b,float c,StoryboardDeformableSprite mesh){
            start=a;end=b;confidence=c;deformation=mesh;
        }
    }

    public StoryboardLayerRenderer(int[] pixelsA,int[] pixelsB,
                                   int width,int height,
                                   StoryboardLayerIsolation.Result analysis,
                                   StoryboardMotionGrammar.SceneKind scene,
                                   StoryboardMotionGrammar.Action action){
        if(width<32||height<32||(long)width*height>720L*1280L
                ||pixelsA==null||pixelsB==null
                ||pixelsA.length!=width*height||pixelsB.length!=pixelsA.length
                ||analysis==null||!analysis.autoRenderable()
                ||analysis.cleanPlate.length!=pixelsA.length)
            throw new IllegalArgumentException("Source-owned, isolated moving objects required");
        this.width=width;this.height=height;
        this.scene=scene==null?StoryboardMotionGrammar.SceneKind.MIXED:scene;
        this.action=action==null?StoryboardMotionGrammar.Action.TRACK:action;
        first=Bitmap.createBitmap(pixelsA,width,height,Bitmap.Config.ARGB_8888);
        last=Bitmap.createBitmap(pixelsB,width,height,Bitmap.Config.ARGB_8888);
        plate=Bitmap.createBitmap(analysis.cleanPlate,width,height,Bitmap.Config.ARGB_8888);
        tracking=analysis;
        boolean softBodies=this.scene==StoryboardMotionGrammar.SceneKind.ACTION
                ||this.scene==StoryboardMotionGrammar.SceneKind.COMIC
                ||this.scene==StoryboardMotionGrammar.SceneKind.PORTRAIT
                ||this.scene==StoryboardMotionGrammar.SceneKind.ABSTRACT
                ||action==StoryboardMotionGrammar.Action.SWAY
                ||action==StoryboardMotionGrammar.Action.FLOAT;
        for(StoryboardLayerIsolation.Track t:analysis.tracks)
            sprites.add(new SpritePair(cutout(pixelsA,t.from),
                    cutout(pixelsB,t.to),t.confidence,
                    new StoryboardDeformableSprite(t.from,t.to,width,height,softBodies)));
    }

    private Sprite cutout(int[] original,StoryboardLayerIsolation.Region region){
        int w=region.right-region.left+1;
        int h=region.bottom-region.top+1;
        if(w<1||h<1)throw new IllegalArgumentException("Empty tracked source object");
        int[] clipped=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int px=region.left+x,py=region.top+y,index=py*width+px;
            if(region.alpha[index]==0)continue;
            int color=original[index];
            // Feather a one-pixel silhouette edge, but retain all original
            // RGB texture, line work and matte colors inside.
            int support=0;
            for(int sy=-1;sy<=1;sy++)for(int sx=-1;sx<=1;sx++){
                int xx=px+sx,yy=py+sy;
                if(xx>=0&&xx<width&&yy>=0&&yy<height
                        &&region.alpha[yy*width+xx]!=0)support++;
            }
            int alpha=support<6?Math.max(80,Math.min(255,support*35)):255;
            clipped[y*w+x]=(color&0x00ffffff)|(alpha<<24);
        }
        Bitmap bitmap=Bitmap.createBitmap(clipped,w,h,Bitmap.Config.ARGB_8888);
        return new Sprite(bitmap,region);
    }

    public Bitmap render(float t,int frameIndex){
        if(!Float.isFinite(t)||t<0||t>1)
            throw new IllegalArgumentException("Storyboard frame time out of range");
        if(t==0)return first.copy(Bitmap.Config.ARGB_8888,false);
        if(t==1)return last.copy(Bitmap.Config.ARGB_8888,false);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(bitmap);
        paint.reset();paint.setAntiAlias(true);paint.setFilterBitmap(true);
        canvas.save();
        // Cinematic perspective is secondary to actual image-object motion.
        // A tracked source world and every subject share one restrained
        // camera matrix; source-keyframe endpoints remain exact.
        float centerX=.5f,centerY=.5f,observedDx=0,observedDy=0;
        if(!tracking.tracks.isEmpty()){
            StoryboardLayerIsolation.Track focus=tracking.tracks.get(0);
            centerX=focus.from.cx/width;
            centerY=focus.from.cy/height;
            observedDx=(focus.to.cx-focus.from.cx)/width;
            observedDy=(focus.to.cy-focus.from.cy)/height;
        }
        StoryboardCameraDirector.State camera=StoryboardCameraDirector.at(
                scene,action,t,centerX,centerY,observedDx,observedDy,frameIndex);
        camera.apply(canvas,width,height);
        canvas.drawBitmap(plate,0,0,paint);
        // Independently measured source-object trajectories first; then
        // apply small contact constraints only to appropriate action beats.
        ArrayList<StoryboardInteractionSolver.Body> positions=new ArrayList<>();
        for(SpritePair pair:sprites){
            StoryboardLayerIsolation.Region from=pair.start.region,
                    to=pair.end.region;
            StoryboardMotionGrammar.Motion movement=StoryboardMotionGrammar.at(
                    action,t,(to.cx-from.cx)/width,(to.cy-from.cy)/height,
                    0,0,1,1,StoryboardMotionGrammar.SubjectKind.OBJECT);
            positions.add(new StoryboardInteractionSolver.Body(
                    from.cx/width+movement.x,from.cy/height+movement.y,
                    Math.max(.003f,Math.min(.50f,(from.width()+to.width())*.25f/width)),
                    Math.max(.003f,Math.min(.50f,(from.height()+to.height())*.25f/height))));
        }
        StoryboardInteractionSolver.resolve(positions,t,action);
        for(int i=0;i<sprites.size();i++){
            SpritePair pair=sprites.get(i);
            drawTracked(canvas,pair,t,i,positions.get(i));
        }
        if(action==StoryboardMotionGrammar.Action.IMPACT||
           action==StoryboardMotionGrammar.Action.EXPLODE||
           action==StoryboardMotionGrammar.Action.SPLASH||
           action==StoryboardMotionGrammar.Action.STRIKE)
            drawActionParticles(canvas,t,frameIndex);
        canvas.restore();
        return bitmap;
    }

    private void drawTracked(Canvas canvas,SpritePair pair,float t,int slot,
                             StoryboardInteractionSolver.Body body) {
        StoryboardLayerIsolation.Region from=pair.start.region,to=pair.end.region;
        StoryboardMotionGrammar.SubjectKind subject=
                scene==StoryboardMotionGrammar.SceneKind.TYPOGRAPHY
                    ?StoryboardMotionGrammar.SubjectKind.TEXT
                    :scene==StoryboardMotionGrammar.SceneKind.LANDSCAPE
                        ?StoryboardMotionGrammar.SubjectKind.ENVIRONMENT
                        :scene==StoryboardMotionGrammar.SceneKind.PORTRAIT
                            ?StoryboardMotionGrammar.SubjectKind.CHARACTER
                            :StoryboardMotionGrammar.SubjectKind.OBJECT;
        float dx=(to.cx-from.cx)/width,dy=(to.cy-from.cy)/height;
        StoryboardMotionGrammar.Motion m=StoryboardMotionGrammar.at(
                action,t,dx,dy,0,0,1,1,subject);
        float x=Math.max(-.15f,Math.min(1.15f,body.x));
        float y=Math.max(-.15f,Math.min(1.15f,body.y));
        float phase=StoryboardMotionGrammar.phase(action,t);
        float targetW=from.width()+(to.width()-from.width())*phase;
        float targetH=from.height()+(to.height()-from.height())*phase;

        if(t<.46f) {
            drawSourceSprite(canvas,pair,pair.start,x,y,targetW,targetH,m,t,false);
        }else if(t>.54f){
            drawSourceSprite(canvas,pair,pair.end,x,y,targetW,targetH,m,t,true);
        }else {
            // Blend RGB inside a SINGLE isolated original object, not two
            // half-transparent figures over the city. The opaque source
            // ink remains opaque at the collision midpoint.
            float mix=(t-.46f)/.08f;
            mix=mix*mix*(3-2*mix);
            blendSourceSprites(canvas,pair,x,y,targetW,targetH,m,t,mix);
        }
    }

    /**
     * GPU and software Canvas disagree on nested bitmap clipping/translation
     * in some Android devices and Robolectric. Deform the REAL actor pixels
     * first, then draw one complete bitmap using explicit SOURCE-COORDINATE
     * absolute screen position. No procedural fighter is ever introduced.
     */
    private Bitmap sizedSource(SpritePair pair,Sprite sprite,float t,
                               boolean endTexture,int targetW,int targetH){
        Bitmap deformed=pair.deformation.deformBitmap(
                sprite.bitmap,t,endTexture,1f);
        Bitmap resized=Bitmap.createScaledBitmap(deformed,targetW,targetH,true);
        if(deformed!=resized&&deformed!=sprite.bitmap)
            deformed.recycle();
        return resized;
    }

    private void drawSourceSprite(Canvas canvas,SpritePair pair,Sprite sprite,
                                  float x,float y,float targetW,float targetH,
                                  StoryboardMotionGrammar.Motion movement,
                                  float t,boolean endTexture){
        int w=Math.max(1,Math.round(targetW*movement.scale));
        int h=Math.max(1,Math.round(targetH*movement.scale));
        Bitmap bitmap=sizedSource(pair,sprite,t,endTexture,w,h);
        try {
            float left=x*width-(sprite.pivotX/sprite.bitmap.getWidth())*w;
            float top=y*height-(sprite.pivotY/sprite.bitmap.getHeight())*h;
            paint.setAlpha(Math.max(0,Math.min(255,
                    Math.round(255*movement.opacity))));
            canvas.drawBitmap(bitmap,left,top,paint);
        }finally{
            paint.setAlpha(255);
            if(bitmap!=sprite.bitmap)bitmap.recycle();
        }
    }

    private void blendSourceSprites(Canvas canvas,SpritePair pair,float x,float y,
                                    float targetW,float targetH,
                                    StoryboardMotionGrammar.Motion movement,
                                    float t,float mix) {
        int w=Math.max(1,Math.round(targetW*movement.scale));
        int h=Math.max(1,Math.round(targetH*movement.scale));
        Bitmap a=sizedSource(pair,pair.start,t,false,w,h);
        Bitmap b=sizedSource(pair,pair.end,t,true,w,h);
        Bitmap blended=null;
        try{
            int[] firstPixels=new int[w*h],lastPixels=new int[w*h],
                    output=new int[w*h];
            a.getPixels(firstPixels,0,w,0,0,w,h);
            b.getPixels(lastPixels,0,w,0,0,w,h);
            float one=1-mix;
            for(int i=0;i<output.length;i++){
                int ca=firstPixels[i],cb=lastPixels[i];
                float aa=(ca>>>24&255)*one,bb=(cb>>>24&255)*mix;
                float coverage=aa+bb;
                if(coverage<=.01f)continue;
                float weightA=aa/coverage,weightB=bb/coverage;
                int r=Math.round((ca>>>16&255)*weightA+
                        (cb>>>16&255)*weightB);
                int g=Math.round((ca>>>8&255)*weightA+
                        (cb>>>8&255)*weightB);
                int blue=Math.round((ca&255)*weightA+
                        (cb&255)*weightB);
                output[i]=(Math.min(255,Math.round(coverage))<<24)
                        |(Math.min(255,r)<<16)|(Math.min(255,g)<<8)
                        |Math.min(255,blue);
            }
            blended=Bitmap.createBitmap(output,w,h,Bitmap.Config.ARGB_8888);
            float pivotX=(pair.start.pivotX/
                    pair.start.bitmap.getWidth())*one+
                    (pair.end.pivotX/pair.end.bitmap.getWidth())*mix;
            float pivotY=(pair.start.pivotY/
                    pair.start.bitmap.getHeight())*one+
                    (pair.end.pivotY/pair.end.bitmap.getHeight())*mix;
            paint.setAlpha(Math.max(0,Math.min(255,
                    Math.round(255*movement.opacity))));
            canvas.drawBitmap(blended,x*width-pivotX*w,
                    y*height-pivotY*h,paint);
        }finally{
            paint.setAlpha(255);
            if(blended!=null)blended.recycle();
            if(a!=pair.start.bitmap)a.recycle();
            if(b!=pair.end.bitmap&&b!=a)b.recycle();
        }
    }

    private void drawActionParticles(Canvas canvas,float t,int frameIndex){
        float intensity=(float)Math.exp(-Math.pow((t-.64f)/.11f,2));
        if(intensity<.02f||sprites.isEmpty())return;
        StoryboardLayerIsolation.Track subject=tracking.tracks.get(0);
        float x=subject.from.cx+(subject.to.cx-subject.from.cx)*t;
        float y=subject.from.cy+(subject.to.cy-subject.from.cy)*t;
        Random r=new Random(56128); // stable deterministic effect
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        for(int n=0;n<18;n++){
            double angle=6.28318530718*n/18d;
            float travel=width*intensity*(.007f+r.nextFloat()*.035f);
            float xx=x+(float)Math.cos(angle)*travel;
            float yy=y+(float)Math.sin(angle)*travel;
            paint.setColor(n%3==0?0xfffceac7:0xffdf995e);
            paint.setStrokeWidth(1.0f+r.nextFloat()*1.5f);
            canvas.drawLine(x,y,xx,yy,paint);
        }
        paint.setStyle(Paint.Style.FILL);
    }
    @Override public void close(){
        first.recycle();last.recycle();plate.recycle();
        for(SpritePair pair:sprites){
            pair.start.bitmap.recycle();pair.end.bitmap.recycle();
        }
    }
}
