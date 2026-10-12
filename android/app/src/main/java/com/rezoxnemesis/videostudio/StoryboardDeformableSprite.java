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

    /** A raster in original crop coordinates; padding never changes the source scale. */
    public static final class MaterialFrame {
        public final Bitmap bitmap;
        public final int originX,originY,originalWidth,originalHeight;
        private MaterialFrame(Bitmap bitmap,int x,int y,int width,int height){
            this.bitmap=bitmap;originX=x;originY=y;
            originalWidth=width;originalHeight=height;
        }
    }

    /** Compatibility bitmap view; renderers must use deformMaterial for its origin. */
    public Bitmap deformBitmap(Bitmap source,float t,boolean endTexture,float amount){
        return deformMaterial(source,t,endTexture,amount).bitmap;
    }

    /**
     * Rasterize a continuous mesh of the actual source crop into its measured
     * destination bounds. Inverse sampling inside the old crop discarded edge
     * paint whenever the field moved outward. Every source triangle is now
     * represented, with no invented pixels beyond its original alpha mask.
     * This is a noncinematic source-deformation preview, not anatomical rigging.
     */
    public MaterialFrame deformMaterial(Bitmap source,float t,boolean endTexture,
                                        float amount){
        if(source==null||source.isRecycled()||!Float.isFinite(t)||t<0||t>1||
                !Float.isFinite(amount)||amount<0||amount>1)
            throw new IllegalArgumentException("Invalid source-texture deformation");
        int width=source.getWidth(),height=source.getHeight();
        if(width<1||height<1||(long)width*height>720L*1280L)
            throw new IllegalArgumentException("Deformable source object exceeds mobile budget");
        if(!deformable||amount==0||t<=0||t>=1)
            return new MaterialFrame(source,0,0,width,height);
        float[] vertices=new float[(MESH_X+1)*(MESH_Y+1)*2];
        float strength=amount;
        boolean safe=false;
        // Sparse silhouette rows can ask neighboring mesh cells to cross.
        // Reduce the bounded field while retaining nonzero deformation;
        // never clamp source sampling or replace the source with a still.
        for(int attempt=0;attempt<9;attempt++){
            positionMesh(vertices,width,height,t,endTexture,strength);
            if(positiveCells(vertices,width,height)){safe=true;break;}
            strength*=.5f;
        }
        if(!safe)throw new IllegalStateException(
                "Source deformation folds its material mesh; provide authored source masks/rigs");
        float minX=Float.POSITIVE_INFINITY,minY=Float.POSITIVE_INFINITY;
        float maxX=Float.NEGATIVE_INFINITY,maxY=Float.NEGATIVE_INFINITY;
        for(int i=0;i<vertices.length;i+=2){
            minX=Math.min(minX,vertices[i]);maxX=Math.max(maxX,vertices[i]);
            minY=Math.min(minY,vertices[i+1]);maxY=Math.max(maxY,vertices[i+1]);
        }
        int left=(int)Math.floor(minX)-1,top=(int)Math.floor(minY)-1;
        int right=(int)Math.ceil(maxX)+1,bottom=(int)Math.ceil(maxY)+1;
        int outWidth=right-left,outHeight=bottom-top;
        if(outWidth<1||outHeight<1||outWidth>width*3+4||outHeight>height*3+4||
                (long)outWidth*outHeight>4L*720*1280)
            throw new IllegalStateException("Source deformation exceeds bounded raster budget");
        int[] original=new int[width*height],warped=new int[outWidth*outHeight];
        source.getPixels(original,0,width,0,0,width,height);
        for(int i=0;i<vertices.length;i+=2){vertices[i]-=left;vertices[i+1]-=top;}
        for(int row=0;row<MESH_Y;row++)for(int col=0;col<MESH_X;col++){
            int a=(row*(MESH_X+1)+col)*2,b=a+2;
            int d=a+(MESH_X+1)*2,c=d+2;
            float x0=col*width/(float)MESH_X,x1=(col+1)*width/(float)MESH_X;
            float y0=row*height/(float)MESH_Y,y1=(row+1)*height/(float)MESH_Y;
            rasterTriangle(vertices,a,b,c,x0,y0,x1,y0,x1,y1,
                    original,width,height,warped,outWidth,outHeight);
            rasterTriangle(vertices,a,c,d,x0,y0,x1,y1,x0,y1,
                    original,width,height,warped,outWidth,outHeight);
        }
        return new MaterialFrame(Bitmap.createBitmap(warped,outWidth,outHeight,
                Bitmap.Config.ARGB_8888),left,top,width,height);
    }

    private void positionMesh(float[] vertices,int width,int height,float t,
                              boolean endTexture,float amount){
        float secondary=4f*t*(1f-t)*amount;
        float[] limb=new float[2];
        for(int row=0;row<=MESH_Y;row++){
            float v=row/(float)MESH_Y,y=v*height;
            float srcL=endTexture?targetLeft[row]:startLeft[row];
            float srcR=endTexture?targetRight[row]:startRight[row];
            float wantedL=startLeft[row]+(targetLeft[row]-startLeft[row])*t;
            float wantedR=startRight[row]+(targetRight[row]-startRight[row])*t;
            float scaleX=1f+(Math.max(.68f,Math.min(1.45f,
                    Math.max(.06f,wantedR-wantedL)/Math.max(.06f,srcR-srcL)))-1f)*amount;
            float offsetX=((wantedL+wantedR)*.5f-
                    (srcL+srcR)*.5f*scaleX)*width*amount;
            float sway=(float)Math.sin(v*4.2+t*3.1)*secondary*width*.0025f;
            for(int col=0;col<=MESH_X;col++){
                float u=col/(float)MESH_X,x=u*width;
                float srcT=endTexture?targetTop[col]:startTop[col];
                float srcB=endTexture?targetBottom[col]:startBottom[col];
                float wantedT=startTop[col]+(targetTop[col]-startTop[col])*t;
                float wantedB=startBottom[col]+(targetBottom[col]-startBottom[col])*t;
                float scaleY=1f+(Math.max(.73f,Math.min(1.32f,
                        Math.max(.07f,wantedB-wantedT)/Math.max(.07f,srcB-srcT)))-1f)*amount;
                float offsetY=((wantedT+wantedB)*.5f-
                        (srcT+srcB)*.5f*scaleY)*height*amount;
                sourceArticulation.shift(u,v,t,endTexture,action,limb);
                int at=(row*(MESH_X+1)+col)*2;
                vertices[at]=x*scaleX+offsetX+sway+limb[0]*width*amount;
                vertices[at+1]=y*scaleY+offsetY+limb[1]*height*amount;
            }
        }
    }

    private static boolean positiveCells(float[] vertices,int width,int height){
        for(float coordinate:vertices)if(!Float.isFinite(coordinate))return false;
        float minimum=width*(float)height/(MESH_X*MESH_Y)*.02f;
        for(int row=0;row<MESH_Y;row++)for(int col=0;col<MESH_X;col++){
            int a=(row*(MESH_X+1)+col)*2,b=a+2;
            int d=a+(MESH_X+1)*2,c=d+2;
            if(area(vertices,a,b,c)<minimum||area(vertices,a,c,d)<minimum)return false;
        }
        return true;
    }
    private static float area(float[] v,int a,int b,int c){
        return (v[b]-v[a])*(v[c+1]-v[a+1])-(v[b+1]-v[a+1])*(v[c]-v[a]);
    }

    private static void rasterTriangle(float[] v,int a,int b,int c,
            float ax,float ay,float bx,float by,float cx,float cy,
            int[] source,int width,int height,int[] output,int outWidth,int outHeight){
        float xa=v[a],ya=v[a+1],xb=v[b],yb=v[b+1],xc=v[c],yc=v[c+1];
        float denominator=area(v,a,b,c);
        int left=Math.max(0,(int)Math.floor(Math.min(xa,Math.min(xb,xc))));
        int right=Math.min(outWidth-1,(int)Math.ceil(Math.max(xa,Math.max(xb,xc))));
        int top=Math.max(0,(int)Math.floor(Math.min(ya,Math.min(yb,yc))));
        int bottom=Math.min(outHeight-1,(int)Math.ceil(Math.max(ya,Math.max(yb,yc))));
        for(int y=top;y<=bottom;y++)for(int x=left;x<=right;x++){
            float px=x+.5f,py=y+.5f;
            float wa=((xb-px)*(yc-py)-(yb-py)*(xc-px))/denominator;
            float wb=((xc-px)*(ya-py)-(yc-py)*(xa-px))/denominator;
            float wc=1-wa-wb;
            if(wa<-.00001f||wb<-.00001f||wc<-.00001f)continue;
            int sx=(int)Math.floor(wa*ax+wb*bx+wc*cx);
            int sy=(int)Math.floor(wa*ay+wb*by+wc*cy);
            if(sx<0||sx>=width||sy<0||sy>=height)continue;
            output[y*outWidth+x]=source[sy*width+sx];
        }
    }

    /** Render the complete bounded source material at its original crop pivot. */
    public void render(Canvas canvas,Bitmap pixels,float pivotX,float pivotY,
                       float t,float intensity,boolean usingEndTexture,Paint paint){
        if(canvas==null||paint==null||!Float.isFinite(pivotX)||!Float.isFinite(pivotY)||
                !Float.isFinite(intensity))
            throw new IllegalArgumentException("Untrusted deformable storyboard frame");
        MaterialFrame frame=deformMaterial(pixels,t,usingEndTexture,
                Math.max(0,Math.min(1,intensity)));
        try{canvas.drawBitmap(frame.bitmap,frame.originX-pivotX,frame.originY-pivotY,paint);}
        finally{if(frame.bitmap!=pixels)frame.bitmap.recycle();}
    }
}
