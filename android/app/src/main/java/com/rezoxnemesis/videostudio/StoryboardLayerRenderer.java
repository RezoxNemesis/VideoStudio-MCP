package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import java.util.ArrayList;
import java.util.Random;

/**
 * Noncinematic preview of separately tracked pieces of ORIGINAL 2D artwork.
 * The source shapes are isolated, moved and deformed in a source scene.
 * Color masks and silhouette extrema do not establish anatomical action.
 *
 * For illustrated deforming bodies, the existing hand-correctable
 * NativeSkinnedSprite2D pose engine remains the higher-fidelity option.
 * Automatic masks use bounded generic source-material deformation;
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
                    new StoryboardDeformableSprite(t.from,t.to,width,height,softBodies,action,scene==StoryboardMotionGrammar.SceneKind.ACTION||scene==StoryboardMotionGrammar.SceneKind.COMIC)));
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
        boolean completed=false;
        try {
            Canvas canvas=new Canvas(bitmap);
            paint.reset();paint.setAntiAlias(true);paint.setFilterBitmap(true);
            int checkpoint=canvas.save();
            try{drawFrame(canvas,t,frameIndex);}
            finally{canvas.restoreToCount(checkpoint);}
            completed=true;
            return bitmap;
        }finally{if(!completed)bitmap.recycle();}
    }

    private void drawFrame(Canvas canvas,float t,int frameIndex){
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
    }

    private void drawTracked(Canvas canvas,SpritePair pair,float t,int slot,
                             StoryboardInteractionSolver.Body body) {
        StoryboardLayerIsolation.Region from=pair.start.region,to=pair.end.region;
        StoryboardMotionGrammar.SubjectKind subject=
                scene==StoryboardMotionGrammar.SceneKind.TYPOGRAPHY
                    ?StoryboardMotionGrammar.SubjectKind.TEXT
                    :scene==StoryboardMotionGrammar.SceneKind.LANDSCAPE
                        ?StoryboardMotionGrammar.SubjectKind.ENVIRONMENT
                        :(scene==StoryboardMotionGrammar.SceneKind.PORTRAIT
                        ||((scene==StoryboardMotionGrammar.SceneKind.ACTION
                             ||scene==StoryboardMotionGrammar.SceneKind.COMIC)
                            &&from.height()>.85f*from.width()
                            &&to.height()>.85f*to.width()))
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

        // Frame-by-frame body contours move from source pose to destination
        // pose across the complete shot. The old 46%-54% texture switch
        // held a nearly unchanged fighter until the cut.
        // Faster blade-contact beats use a narrower material handoff so
        // translucent swords are not smeared over the impact.
        float halfSpan=(action==StoryboardMotionGrammar.Action.IMPACT
                ||action==StoryboardMotionGrammar.Action.STRIKE)? .16f:.23f;
        float lo=.5f-halfSpan,hi=.5f+halfSpan;
        if(phase<=lo){
            drawSourceSprite(canvas,pair,pair.start,x,y,targetW,targetH,m,
                    phase,false);
        }else if(phase>=hi){
            drawSourceSprite(canvas,pair,pair.end,x,y,targetW,targetH,m,
                    phase,true);
        }else{
            float mix=(phase-lo)/(hi-lo);
            mix=mix*mix*(3-2*mix);
            // Actor-local blend only, never a whole-scene dissolve.
            blendSourceSprites(canvas,pair,x,y,targetW,targetH,m,phase,mix);
        }
    }

    /**
     * GPU and software Canvas disagree on nested bitmap clipping/translation
     * in some Android devices and Robolectric. Deform the REAL actor pixels
     * first, then draw one complete bitmap using explicit SOURCE-COORDINATE
     * absolute screen position. No procedural fighter is ever introduced.
     */
    private static final class SizedSource {
        final Bitmap bitmap;
        final float left,top;
        SizedSource(Bitmap bitmap,float left,float top){
            this.bitmap=bitmap;this.left=left;this.top=top;
        }
    }

    private SizedSource sizedSource(SpritePair pair,Sprite sprite,float t,
                                    boolean endTexture,int targetW,int targetH){
        StoryboardDeformableSprite.MaterialFrame material=pair.deformation.deformMaterial(
                sprite.bitmap,t,endTexture,1f);
        float sx=targetW/(float)material.originalWidth;
        float sy=targetH/(float)material.originalHeight;
        int rasterW=Math.max(1,Math.round(material.bitmap.getWidth()*sx));
        int rasterH=Math.max(1,Math.round(material.bitmap.getHeight()*sy));
        if((long)rasterW*rasterH>4L*720*1280){
            if(material.bitmap!=sprite.bitmap)material.bitmap.recycle();
            throw new IllegalStateException("Source preview raster exceeds mobile budget");
        }
        Bitmap resized=null;
        try{
            resized=Bitmap.createScaledBitmap(material.bitmap,rasterW,rasterH,true);
            return new SizedSource(resized,(material.originX-sprite.pivotX)*sx,
                    (material.originY-sprite.pivotY)*sy);
        }finally{
            if(material.bitmap!=resized&&material.bitmap!=sprite.bitmap)
                material.bitmap.recycle();
        }
    }

    private void drawSourceSprite(Canvas canvas,SpritePair pair,Sprite sprite,
                                  float x,float y,float targetW,float targetH,
                                  StoryboardMotionGrammar.Motion movement,
                                  float t,boolean endTexture){
        int w=Math.max(1,Math.round(targetW*movement.scale));
        int h=Math.max(1,Math.round(targetH*movement.scale));
        SizedSource source=sizedSource(pair,sprite,t,endTexture,w,h);
        canvas.save();
        try {
            canvas.rotate(movement.rotation,x*width,y*height);
            paint.setAlpha(Math.max(0,Math.min(255,
                    Math.round(255*movement.opacity))));
            canvas.drawBitmap(source.bitmap,x*width+source.left,y*height+source.top,paint);
        }finally{
            canvas.restore();
            paint.setAlpha(255);
            if(source.bitmap!=sprite.bitmap)source.bitmap.recycle();
        }
    }

    private void blendSourceSprites(Canvas canvas,SpritePair pair,float x,float y,
                                    float targetW,float targetH,
                                    StoryboardMotionGrammar.Motion movement,
                                    float t,float mix) {
        int w=Math.max(1,Math.round(targetW*movement.scale));
        int h=Math.max(1,Math.round(targetH*movement.scale));
        SizedSource a=sizedSource(pair,pair.start,t,false,w,h);
        SizedSource b=null;
        Bitmap blended=null;
        try{
            b=sizedSource(pair,pair.end,t,true,w,h);
            int aw=a.bitmap.getWidth(),ah=a.bitmap.getHeight();
            int bw=b.bitmap.getWidth(),bh=b.bitmap.getHeight();
            // Align each expanded source around its own original pivot.
            // Resizing padding to w/h or averaging pivots discards material
            // and shifts the figure when the endpoint crops differ.
            int left=(int)Math.floor(Math.min(a.left,b.left));
            int top=(int)Math.floor(Math.min(a.top,b.top));
            int right=(int)Math.ceil(Math.max(a.left+aw,b.left+bw));
            int bottom=(int)Math.ceil(Math.max(a.top+ah,b.top+bh));
            int rasterW=right-left,rasterH=bottom-top;
            if(rasterW<1||rasterH<1||(long)rasterW*rasterH>4L*720*1280)
                throw new IllegalStateException("Source appearance handoff exceeds mobile budget");
            int[] firstPixels=new int[aw*ah],lastPixels=new int[bw*bh],
                    output=new int[rasterW*rasterH];
            a.bitmap.getPixels(firstPixels,0,aw,0,0,aw,ah);
            b.bitmap.getPixels(lastPixels,0,bw,0,0,bw,bh);
            float one=1-mix;
            for(int yy=0;yy<rasterH;yy++)for(int xx=0;xx<rasterW;xx++){
                int ax=(int)Math.floor(xx+left+.5f-a.left);
                int ay=(int)Math.floor(yy+top+.5f-a.top);
                int bx=(int)Math.floor(xx+left+.5f-b.left);
                int by=(int)Math.floor(yy+top+.5f-b.top);
                int ca=ax>=0&&ax<aw&&ay>=0&&ay<ah?firstPixels[ay*aw+ax]:0;
                int cb=bx>=0&&bx<bw&&by>=0&&by<bh?lastPixels[by*bw+bx]:0;
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
                output[yy*rasterW+xx]=(Math.min(255,Math.round(coverage))<<24)
                        |(Math.min(255,r)<<16)|(Math.min(255,g)<<8)
                        |Math.min(255,blue);
            }
            blended=Bitmap.createBitmap(output,rasterW,rasterH,Bitmap.Config.ARGB_8888);
            paint.setAlpha(Math.max(0,Math.min(255,
                    Math.round(255*movement.opacity))));
            canvas.save();
            try {
                canvas.rotate(movement.rotation,x*width,y*height);
                canvas.drawBitmap(blended,x*width+left,y*height+top,paint);
            }finally{canvas.restore();}
        }finally{
            paint.setAlpha(255);
            if(blended!=null)blended.recycle();
            if(a.bitmap!=pair.start.bitmap)a.bitmap.recycle();
            if(b!=null&&b.bitmap!=pair.end.bitmap&&b.bitmap!=a.bitmap)b.bitmap.recycle();
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
