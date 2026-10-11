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
                                    int sourceWidth,int sourceHeight,
                                    boolean deformable) {
        if(start==null||end==null||sourceWidth<32||sourceHeight<32
                ||start.alpha.length!=sourceWidth*sourceHeight
                ||end.alpha.length!=sourceWidth*sourceHeight)
            throw new IllegalArgumentException("Two source masks and true frame size required");
        this.deformable=deformable;
        profile(start,sourceWidth,sourceHeight,startLeft,startRight);
        profile(end,sourceWidth,sourceHeight,targetLeft,targetRight);
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
        int index=0;
        float envelope=(float)Math.sin(Math.PI*t);
        for(int row=0;row<=MESH_Y;row++){
            float yy=row*h/(float)MESH_Y;
            float v=row/(float)MESH_Y;
            float bob=(float)Math.sin(t*Math.PI*2+v*4.5)*envelope*.018f
                    *Math.min(w,h)*intensity;
            float srcL=usingEndTexture?targetLeft[row]:startLeft[row],
                  srcR=usingEndTexture?targetRight[row]:startRight[row];
            float desiredL=startLeft[row]+(targetLeft[row]-startLeft[row])*t;
            float desiredR=startRight[row]+(targetRight[row]-startRight[row])*t;
            float lowShift=Math.max(-.13f,Math.min(.13f,desiredL-srcL));
            float highShift=Math.max(-.13f,Math.min(.13f,desiredR-srcR));
            float rowSpread=(float)Math.sin(Math.PI*v)*envelope*.035f
                    *Math.min(w,h)*intensity;
            for(int col=0;col<=MESH_X;col++){
                float u=col/(float)MESH_X;
                float anchoredX=u*w-pivotX;
                float anchoredY=yy-pivotY;
                float edgeBias=(u-.5f)*2;
                vertices[index++]=anchoredX+
                        ((1-u)*lowShift+u*highShift)*w
                        +(float)Math.sin(v*5+t*5)*envelope*.004f*w*intensity
                        +edgeBias*rowSpread;
                vertices[index++]=anchoredY+
                        bob*Math.max(.15f,1-v);
            }
        }
        canvas.drawBitmapMesh(pixels,MESH_X,MESH_Y,vertices,0,null,0,paint);
    }
}
