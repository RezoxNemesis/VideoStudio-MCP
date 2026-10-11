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
        SpritePair(Sprite a,Sprite b,float c){start=a;end=b;confidence=c;}
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
        for(StoryboardLayerIsolation.Track t:analysis.tracks)
            sprites.add(new SpritePair(cutout(pixelsA,t.from),
                    cutout(pixelsB,t.to),t.confidence));
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
        // A scene transform is available for static/establishing frames.
        // Actor movement is independent, never synthesized by zooming the
        // entire frame. Keep camera modest to preserve 9:16 framing.
        float worldPan=scene==StoryboardMotionGrammar.SceneKind.LANDSCAPE
                ? (float)Math.sin(Math.PI*t)*width*.008f:0;
        canvas.translate(worldPan,0);
        canvas.drawBitmap(plate,0,0,paint);
        for(int i=0;i<sprites.size();i++){
            SpritePair pair=sprites.get(i);
            drawTracked(canvas,pair,t,i);
        }
        if(action==StoryboardMotionGrammar.Action.IMPACT||
           action==StoryboardMotionGrammar.Action.EXPLODE||
           action==StoryboardMotionGrammar.Action.SPLASH||
           action==StoryboardMotionGrammar.Action.STRIKE)
            drawActionParticles(canvas,t,frameIndex);
        canvas.restore();
        return bitmap;
    }

    private void drawTracked(Canvas canvas,SpritePair pair,float t,int slot) {
        StoryboardLayerIsolation.Region a=pair.start.region,b=pair.end.region;
        StoryboardMotionGrammar.SubjectKind subject=
                scene==StoryboardMotionGrammar.SceneKind.TYPOGRAPHY
                    ?StoryboardMotionGrammar.SubjectKind.TEXT
                    :scene==StoryboardMotionGrammar.SceneKind.LANDSCAPE
                        ?StoryboardMotionGrammar.SubjectKind.ENVIRONMENT
                        :scene==StoryboardMotionGrammar.SceneKind.PORTRAIT
                            ?StoryboardMotionGrammar.SubjectKind.CHARACTER
                            :StoryboardMotionGrammar.SubjectKind.OBJECT;
        float dx=(b.cx-a.cx)/width,dy=(b.cy-a.cy)/height;
        StoryboardMotionGrammar.Motion m=StoryboardMotionGrammar.at(
                action,t,dx,dy,0,0,1,1,subject);
        float x=a.cx/width+m.x,y=a.cy/height+m.y;
        float targetW=a.width()+(b.width()-a.width())*
                StoryboardMotionGrammar.phase(action,t);
        float targetH=a.height()+(b.height()-a.height())*
                StoryboardMotionGrammar.phase(action,t);
        x=Math.max(-.15f,Math.min(1.15f,x));
        y=Math.max(-.15f,Math.min(1.15f,y));
        if(t<.46f)drawSprite(canvas,pair.start,x,y,targetW,targetH,m,255);
        else if(t>.54f)drawSprite(canvas,pair.end,x,y,targetW,targetH,m,255);
        else{
            // Narrow, isolated texture handoff: only this ONE moving
            // object transitions between original painted appearances.
            float mix=(t-.46f)/.08f;mix=mix*mix*(3-2*mix);
            drawSprite(canvas,pair.start,x,y,targetW,targetH,m,
                    Math.round(255*(1-mix)));
            drawSprite(canvas,pair.end,x,y,targetW,targetH,m,
                    Math.round(255*mix));
        }
    }

    private void drawSprite(Canvas canvas,Sprite sprite,float x,float y,
                            float targetW,float targetH,
                            StoryboardMotionGrammar.Motion m,int alpha){
        if(alpha<=0)return;
        float widthScale=(targetW/sprite.region.width())*m.scale;
        float heightScale=(targetH/sprite.region.height())*m.scale;
        canvas.save();
        canvas.translate(x*width,y*height);
        canvas.rotate(m.rotation);
        canvas.scale(widthScale,heightScale);
        paint.setAlpha(Math.max(0,Math.min(255,Math.round(alpha*m.opacity))));
        canvas.drawBitmap(sprite.bitmap,-sprite.pivotX,-sprite.pivotY,paint);
        paint.setAlpha(255);
        canvas.restore();
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
