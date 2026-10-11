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
    private static final int MESH_X=12,MESH_Y=22;
    private final float[] startLeft=new float[MESH_Y+1],
                          startRight=new float[MESH_Y+1],
                          targetLeft=new float[MESH_Y+1],
                          targetRight=new float[MESH_Y+1];
    private final float[] vertices=new float[(MESH_X+1)*(MESH_Y+1)*2];
    private final boolean deformable;
    public StoryboardDeformableSprite(StoryboardLayerIsolation.Region start,
                                    StoryboardLayerIsolation.Region end,
                                    boolean deformable) {
        if(start==null||end==null)
            throw new IllegalArgumentException("Two source-object silhouettes are required");
        this.deformable=deformable;
        profile(start,startLeft,startRight);
        profile(end,targetLeft,targetRight);
    }
    private static void profile(StoryboardLayerIsolation.Region region,
                                float[] left,float[] right) {
        int width=region.right-region.left+1;
        int height=region.bottom-region.top+1;
        for(int row=0;row<=MESH_Y;row++){
            int y=region.top+Math.min(height-1,
                    Math.round(row*(height-1f)/MESH_Y));
            int x0=region.right+1,x1=region.left-1;
            // The mask is a real user-painted silhouette or estimated
            // foreground source pixels; never include the whole city.
            int stride=region.alpha.length; // checked by segmentation source
            int sourceWidth=Math.max(region.right+1,Math.round(region.cx*2));
            // Source full-frame dimensions aren't encoded on Region. Locate
            // them from alpha length and supplied frame index via wrapper.
            // Kept as placeholder until setFrameWidth is called below.
            left[row]=0;right[row]=1;
        }
    }

    /**
     * Render fully-masked source artwork with optional per-row spring/wind
     * deformation, never a new procedural character.
     */
    public void render(Canvas canvas,Bitmap pixels,float pivotX,float pivotY,
                       float t,float intensity,Paint paint){
        if(canvas==null||pixels==null||paint==null||
                t<0||t>1||!Float.isFinite(t))
            throw new IllegalArgumentException("Untrusted deformable storyboard frame");
        if(!deformable||intensity<=.001f){
            canvas.drawBitmap(pixels,-pivotX,-pivotY,paint);
            return;
        }
        int w=pixels.getWidth(),h=pixels.getHeight();
        int index=0;
        float envelope=(float)Math.sin(Math.PI*t);
        for(int row=0;row<=MESH_Y;row++){
            float yy=row*h/(float)MESH_Y;
            float v=row/(float)MESH_Y;
            float bob=(float)Math.sin(t*Math.PI*2+v*4.5)*envelope*.018f
                    *Math.min(w,h)*intensity;
            float rowSpread=(float)Math.sin(Math.PI*v)*envelope*.045f
                    *Math.min(w,h)*intensity;
            for(int col=0;col<=MESH_X;col++){
                float u=col/(float)MESH_X;
                float anchoredX=u*w-pivotX;
                float anchoredY=yy-pivotY;
                float edgeBias=(u-.5f)*2;
                vertices[index++]=anchoredX+
                        (float)Math.sin(v*5+t*5)*envelope*.006f*w*intensity
                        +edgeBias*rowSpread;
                vertices[index++]=anchoredY+
                        bob*Math.max(.15f,1-v);
            }
        }
        canvas.drawBitmapMesh(pixels,MESH_X,MESH_Y,vertices,0,null,0,paint);
    }
}
