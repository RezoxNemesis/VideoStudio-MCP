package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;

/**
 * Source-textured soft-body 2D deformation that respects observed contour
 * changes between storyboard drawings. Unlike a rigid sticker, different
 * rows of a drawn figure/fabric/animal can shift independently.
 *
 * The algorithm is intentionally bounded to modest mesh deformation.
 * It cannot synthesize hidden limbs/face sides or solve extreme pose turns.
 * For precise bodies the authored 2D rig path supersedes this generic mesh.
 */
public final class StoryboardDeformableSprite {
    private static final int MESH_Y=40, MESH_X=28;
    private final float[] startLeft=new float[MESH_Y+1],
                          startRight=new float[MESH_Y+1],
                          targetLeft=new float[MESH_Y+1],
                          targetRight=new float[MESH_Y+1];
    private final float[] startTop=new float[MESH_X+1],
                          startBottom=new float[MESH_X+1],
                          targetTop=new float[MESH_X+1],
                          targetBottom=new float[MESH_X+1];
    private final StoryboardSourceArticulation sourceArticulation;
    private final StoryboardMotionGrammar.Action action;
    private final boolean deformable;
    public StoryboardDeformableSprite(StoryboardLayerIsolation.Region start,
                                    StoryboardLayerIsolation.Region end,
                                    int sourceWidth,int sourceHeight,
                                    boolean deformable) {
        this(start,end,sourceWidth,sourceHeight,deformable,
                StoryboardMotionGrammar.Action.TRACK,false);
    }
    public StoryboardDeformableSprite(StoryboardLayerIsolation.Region start,
                                    StoryboardLayerIsolation.Region end,
                                    int sourceWidth,int sourceHeight,
                                    boolean deformable,
                                    StoryboardMotionGrammar.Action motion,
                                    boolean sourceCharacter) {
        if(start==null||end==null||sourceWidth<32||sourceHeight<32
                ||start.alpha.length!=sourceWidth*sourceHeight
                ||end.alpha.length!=sourceWidth*sourceHeight||motion==null)
            throw new IllegalArgumentException("Two source masks and true frame size required");
        this.deformable=deformable;
        this.action=motion;
        profile(start,sourceWidth,sourceHeight,startLeft,startRight);
        profile(end,sourceWidth,sourceHeight,targetLeft,targetRight);
        verticalProfile(start,sourceWidth,startTop,startBottom);
        verticalProfile(end,sourceWidth,targetTop,targetBottom);
        sourceArticulation=new StoryboardSourceArticulation(
                start,end,sourceWidth,sourceHeight,deformable&&sourceCharacter);
    }
    private static void profile(StoryboardLayerIsolation.Region region,
                                int frameWidth,int frameHeight,
                                float[] left,float[] right) {
        int regionWidth=region.right-region.left+1;
        int regionHeight=region.bottom-region.top+1;
        for(int row=0;row<=MESH_Y;row++){
            int y=region.top+Math.min(regionHeight-1,
                    Math.round(row*(regionHeight-1f)/MESH_Y));
            int min=region.right+1,max=region.left-1;
            for(int search=0;search<=Math.max(2,regionHeight/11);search++){
                int[] candidates=search==0?new int[]{y}:new int[]{y-search,y+search};
                for(int yy:candidates){
                    if(yy<region.top||yy>region.bottom)continue;
                    for(int x=region.left;x<=region.right;x++){
                        if(region.alpha[yy*frameWidth+x]!=0){
                            min=Math.min(min,x);max=Math.max(max,x);
                        }
                    }
                }
                if(max>=min)break;
            }
            if(max<min){left[row]=0;right[row]=1;continue;}
            left[row]=Math.max(0,(min-region.left)/(float)regionWidth);
            right[row]=Math.min(1,(max-region.left+1)/(float)regionWidth);
        }
    }

    /** Record actual top and bottom ink extents in each original column. */
    private static void verticalProfile(StoryboardLayerIsolation.Region r,
                                        int stride,float[] top,float[] bottom) {
        int rw=r.right-r.left+1,rh=r.bottom-r.top+1;
        for(int column=0;column<=MESH_X;column++){
            int x=r.left+Math.min(rw-1,Math.round(column*(rw-1f)/MESH_X));
            int min=r.bottom+1,max=r.top-1;
            for(int search=0;search<=Math.max(2,rw/11);search++){
                int xl=Math.max(r.left,x-search),xr=Math.min(r.right,x+search);
                for(int xx=xl;xx<=xr;xx++)for(int yy=r.top;yy<=r.bottom;yy++)
                    if(r.alpha[yy*stride+xx]!=0){
                        min=Math.min(min,yy);max=Math.max(max,yy);
                    }
                if(max>=min)break;
            }
            if(max<min){top[column]=0;bottom[column]=1;continue;}
            top[column]=Math.max(0,(min-r.top)/(float)rh);
            bottom[column]=Math.min(1,(max-r.top+1)/(float)rh);
        }
    }

    /**
     * Portable CPU deformation that returns actual source-textured ARGB
     * pixels. Unlike Canvas.drawBitmap with nested clipping/transforms,
     * this works identically in Android hardware, Android software and
     * Robolectric's screenshot renderer.
     *
     * Each horizontal row bends in response to the two REAL outlined
     * source bodies. No procedural replacement geometry is introduced.
     */
    public Bitmap deformBitmap(Bitmap source,float t,boolean endTexture,
                               float amount) {
        if(source==null||!Float.isFinite(t)||t<0||t>1||
                !Float.isFinite(amount)||amount<0||amount>1)
            throw new IllegalArgumentException("Invalid source-texture deformation");
        if(!deformable||amount==0||t<=0||t>=1)return source;
        int width=source.getWidth(),height=source.getHeight();
        if(width<1||height<1||(long)width*height>720L*1280L)
            throw new IllegalArgumentException("Deformable source object exceeds mobile budget");
        int[] original=new int[width*height],warped=new int[original.length];
        source.getPixels(original,0,width,0,0,width,height);
        // A/B silhouettes converge onto the same intermediate contour.
        // A sine envelope returned every texture to its original pose at
        // the handoff, making the fighter visually freeze then snap.
        // Instead, the start texture progresses toward B and the target
        // texture progresses back toward A, at the exact shot time.
        float secondary=4f*t*(1f-t)*amount;
        float[] limb=new float[2];
        for(int y=0;y<height;y++){
            float v=(y+.5f)/height;
            int row=Math.min(MESH_Y,Math.round(v*MESH_Y));
            float srcL=endTexture?targetLeft[row]:startLeft[row];
            float srcR=endTexture?targetRight[row]:startRight[row];
            float wantedL=startLeft[row]+(targetLeft[row]-startLeft[row])*t;
            float wantedR=startRight[row]+(targetRight[row]-startRight[row])*t;
            float scaleX=1f+(Math.max(.68f,Math.min(1.45f,
                    Math.max(.06f,wantedR-wantedL)/
                    Math.max(.06f,srcR-srcL)))-1f)*amount;
            float offsetX=((wantedL+wantedR)*.5f-
                    (srcL+srcR)*.5f*scaleX)*width*amount;
            float sway=(float)Math.sin(v*4.2+t*3.1)*secondary*width*.0025f;
            for(int x=0;x<width;x++){
                float u=(x+.5f)/width;
                int col=Math.min(MESH_X,Math.round(u*MESH_X));
                float srcT=endTexture?targetTop[col]:startTop[col];
                float srcB=endTexture?targetBottom[col]:startBottom[col];
                float wantedT=startTop[col]+(targetTop[col]-startTop[col])*t;
                float wantedB=startBottom[col]+(targetBottom[col]-startBottom[col])*t;
                float scaleY=1f+(Math.max(.73f,Math.min(1.32f,
                        Math.max(.07f,wantedB-wantedT)/
                        Math.max(.07f,srcB-srcT)))-1f)*amount;
                float offsetY=((wantedT+wantedB)*.5f-
                        (srcT+srcB)*.5f*scaleY)*height*amount;
                sourceArticulation.shift(u,v,t,endTexture,action,limb);
                int sx=Math.round((x-offsetX-sway)/scaleX-limb[0]*width*amount);
                int sy=Math.round((y-offsetY)/scaleY-limb[1]*height*amount);
                if(sx<0||sx>=width||sy<0||sy>=height)continue;
                warped[y*width+x]=original[sy*width+sx];
            }
        }
        return Bitmap.createBitmap(warped,width,height,Bitmap.Config.ARGB_8888);
    }

    /**
     * Render fully-masked source artwork with optional per-row spring/wind
     * deformation, never a new procedural character.
     */
    public void render(Canvas canvas,Bitmap pixels,float pivotX,float pivotY,
                       float t,float intensity,boolean usingEndTexture,
                       Paint paint){
        if(canvas==null||pixels==null||paint==null||
                t<0||t>1||!Float.isFinite(t))
            throw new IllegalArgumentException("Untrusted deformable storyboard frame");
        if(!deformable||intensity<=.001f){
            canvas.drawBitmap(pixels,-pivotX,-pivotY,paint);
            return;
        }
        int w=pixels.getWidth(),h=pixels.getHeight();
        float envelope=(float)Math.sin(Math.PI*t);
        for(int row=0;row<MESH_Y;row++){
            int y0=(int)(row*h/(float)MESH_Y);
            int y1=(int)((row+1)*h/(float)MESH_Y);
            if(row==MESH_Y-1)y1=h;
            if(y1<=y0)continue;
            float v=(row+.5f)/MESH_Y;
            float lA=usingEndTexture?targetLeft[row]:startLeft[row],
                  rA=usingEndTexture?targetRight[row]:startRight[row];
            float lWanted=startLeft[row]+
                    (targetLeft[row]-startLeft[row])*t;
            float rWanted=startRight[row]+
                    (targetRight[row]-startRight[row])*t;
            float observedSpan=Math.max(.03f,rA-lA);
            float desiredSpan=Math.max(.03f,rWanted-lWanted);
            // Preserve the source texture while allowing modest
            // illustrated-body contour changes. Extreme remeshing must
            // use traced multi-joint art or a real neural motion model.
            float scaleX=Math.max(.75f,Math.min(1.30f,
                    1+(desiredSpan/observedSpan-1)*intensity));
            float targetLeftPixel=lWanted*w-lA*w*scaleX;
            float bend=(float)Math.sin(v*4.5+t*5)*envelope
                    *.009f*w*intensity;
            float bob=(float)Math.sin(v*5+t*6)*envelope
                    *.011f*h*intensity;
            // Canvas.drawBitmap(Bitmap, Rect, RectF, Paint) can be a no-op
            // in some Android software rendering backends used for native
            // unit tests. Render each real source-texture strip with the
            // consistently supported bitmap(x,y) operation instead.
            // The local clip is applied AFTER the transform, so only this
            // strip is visible and no duplicate full figure is drawn.
            float stripHeight=y1-y0;
            canvas.save();
            canvas.translate(targetLeftPixel-pivotX+bend,
                    y0-pivotY+bob);
            canvas.scale(scaleX,1.0f+.55f/stripHeight);
            canvas.clipRect(0,0,w,stripHeight);
            canvas.drawBitmap(pixels,0,-y0,paint);
            canvas.restore();
        }
    }
}
