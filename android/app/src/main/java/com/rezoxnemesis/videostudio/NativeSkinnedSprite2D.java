package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;

/**
 * Reusable textured/mesh-skinned illustrated character. Unlike optical flow,
 * its vertices obey the actual CombatRigSolver bone motion at EVERY timestamp.
 *
 * A hand-authored actor outline or genuine transparent cutout is mandatory:
 * never accidentally overlay the unmoving original person/city on a new rig.
 * This is 2D mesh deformation; hidden surfaces/true 3D are not synthesized.
 */
public final class NativeSkinnedSprite2D implements AutoCloseable {
    private static final int MESH_W=18, MESH_H=32, VERTICES=(MESH_W+1)*(MESH_H+1);
    private static final int INFLUENCES=3;
    private final Bitmap texture;
    private final Rig2DProfile profile;
    private final NativeSourceProp2D prop;
    private final NativeRigidHead2D head;
    private final float sourceAspect;
    private long excludedOpaqueSourcePixels;
    private boolean rendered;
    private final float[] restU=new float[VERTICES],restV=new float[VERTICES];
    private final int[][] boneIds=new int[VERTICES][INFLUENCES];
    private final float[][] boneWeights=new float[VERTICES][INFLUENCES];
    private final float[] mesh=new float[VERTICES*2];
    private final float[][] boneTransforms=new float[Rig2DProfile.BONES.length][6];
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    public NativeSkinnedSprite2D(Context ctx, ProjectStore.Asset source,
                                 Rig2DProfile profile,int width,int height) throws Exception {
        this(ctx,source,profile,width,height,null,null);
    }
    public NativeSkinnedSprite2D(Context ctx, ProjectStore.Asset source,
                                 Rig2DProfile profile,int width,int height,org.json.JSONObject sourceEntry) throws Exception {
        this(ctx,source,profile,width,height,sourceEntry,null);
    }
    public NativeSkinnedSprite2D(Context ctx,ProjectStore.Asset source,
                                 Rig2DProfile profile,int width,int height,String expectedSha256) throws Exception {
        this(ctx,source,profile,width,height,null,expectedSha256);
    }
    private NativeSkinnedSprite2D(Context ctx,ProjectStore.Asset source,Rig2DProfile profile,
                                 int width,int height,org.json.JSONObject sourceEntry,String expectedSha256) throws Exception {
        if(ctx==null||source==null||profile==null||
                !profile.sourceAssetId.equals(source.id)||
                source.mime==null||!source.mime.startsWith("image/"))
            throw new IllegalArgumentException("Source image must match the approved rig profile");
        if(width<128||height<128||width>1280||height>1920)
            throw new IllegalArgumentException("Invalid skinned preview resolution");
        this.profile=Rig2DProfile.fromJson(profile.toJson());
        sourceAspect=width/(float)height;
        for(int y=0;y<=MESH_H;y++)for(int x=0;x<=MESH_W;x++){
            int i=y*(MESH_W+1)+x;
            float u=x/(float)MESH_W,v=y/(float)MESH_H;
            restU[i]=u;restV[i]=v;
            computeWeights(u,v,this.profile,sourceAspect,boneIds[i],boneWeights[i]);
        }
        Bitmap input=sourceEntry!=null?NativeStoryboardSourceImage.readVerified(ctx,source,width,height,sourceEntry)
                :expectedSha256!=null?NativeStoryboardSourceImage.readVerified(ctx,source,width,height,expectedSha256)
                :NativeStoryboardSourceImage.read(ctx,source,width,height);
        Bitmap actor=null;NativeSourceProp2D weapon=null;NativeRigidHead2D headRegion=null;
        try {
            if(profile.outline.length==0&&!hasRealTransparency(input))
                throw new IllegalArgumentException("A transparent cutout or manually drawn actor outline is required");
            boolean hasProp=profile.swordTip!=null&&profile.swordHilt!=null&&profile.swordOutline.length>=3;
            if(hasProp)weapon=new NativeSourceProp2D(input,this.profile);
            actor=applyOutline(input,this.profile.outline);
            if(actor==input)actor=input.copy(Bitmap.Config.ARGB_8888,true);
            if(actor==null)throw new IllegalStateException("Cannot allocate mutable source actor cutout");
            // The weapon has one source-material owner even when a body polygon overlaps it.
            if(hasProp)excludedOpaqueSourcePixels+=removeOutline(actor,this.profile.swordOutline);
            if(!hasAnyOpaquePixel(actor))throw new IllegalArgumentException("Actor mask has no visible source pixels");
            if(this.profile.headOutline.length>0){headRegion=new NativeRigidHead2D(actor,this.profile);removeOutline(actor,this.profile.headOutline);}
            texture=actor;prop=weapon;head=headRegion;actor=null;weapon=null;headRegion=null;
        }finally{
            input.recycle();if(actor!=null)actor.recycle();if(weapon!=null)weapon.close();if(headRegion!=null)headRegion.close();
        }
    }
    static Bitmap applyOutline(Bitmap source,float[][] polygon) {
        if(polygon.length<3)return source;
        int w=source.getWidth(),h=source.getHeight();
        Bitmap layer=null;
        try{
            boolean[] mask=StoryboardCleanPlate.mask(w,h,polygon);
            layer=source.copy(Bitmap.Config.ARGB_8888,true);
            if(layer==null)throw new IllegalStateException("Cannot allocate source cutout");
            int[] row=new int[w];
            // Pixel ownership matches clean-world exclusions exactly. Preserve original
            // RGB/alpha inside the mask; final transformed sampling supplies edge filtering.
            for(int y=0;y<h;y++){
                layer.getPixels(row,0,w,0,y,w,1);
                for(int x=0;x<w;x++)if(!mask[y*w+x])row[x]=Color.TRANSPARENT;
                layer.setPixels(row,0,w,0,y,w,1);
            }
            Bitmap result=layer;layer=null;return result;
        }finally{
            if(layer!=null)layer.recycle();
        }
    }

    static int removeOutline(Bitmap actor,float[][] polygon){
        boolean[] mask=StoryboardCleanPlate.mask(actor.getWidth(),actor.getHeight(),polygon);
        int[] row=new int[actor.getWidth()];int removed=0;
        for(int y=0;y<actor.getHeight();y++){
            actor.getPixels(row,0,row.length,0,y,row.length,1);
            for(int x=0;x<row.length;x++)if(mask[y*row.length+x]){
                if((row[x]>>>24)>96)removed++;
                row[x]=Color.TRANSPARENT;
            }
            actor.setPixels(row,0,row.length,0,y,row.length,1);
        }
        return removed;
    }

    private static boolean hasRealTransparency(Bitmap b) {
        if(!b.hasAlpha())return false;
        int w=b.getWidth(),h=b.getHeight();
        for(int y=0;y<h;y+=Math.max(1,h/14))
            for(int x=0;x<w;x+=Math.max(1,w/14))
                if((b.getPixel(x,y)>>>24)<200)return true;
        return false;
    }
    static boolean hasAnyOpaquePixel(Bitmap b) {
        int w=b.getWidth(),h=b.getHeight();
        int[] row=new int[w];
        for(int y=0;y<h;y++){
            b.getPixels(row,0,w,0,y,w,1);
            for(int pixel:row)if((pixel>>>24)>96)return true;
        }
        return false;
    }

    private static float distanceToSegment(float x,float y,float[] a,float[] b,float aspect){
        float dx=(b[0]-a[0])*aspect,dy=b[1]-a[1],den=dx*dx+dy*dy;
        float px=(x-a[0])*aspect,py=y-a[1];
        float t=den<1e-12f?0:Math.max(0,Math.min(1,(px*dx+py*dy)/den));
        return (float)Math.hypot(px-t*dx,py-t*dy);
    }
    private static void computeWeights(float x,float y,Rig2DProfile source,float aspect,int[] choices,float[] weights){
        float[] best={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY};
        for(int i=0;i<Rig2DProfile.BONES.length;i++){
            int[] bone=Rig2DProfile.BONES[i];
            float d=distanceToSegment(x,y,source.joints[bone[0]],source.joints[bone[1]],aspect);
            // Large torso influence around pelvis; smaller local joints keep
            // hands/legs from borrowing distant and unrelated bone motion.
            float score=d*d+(i==0?.0006f:.0009f);
            for(int j=0;j<INFLUENCES;j++)if(score<best[j]){
                for(int z=INFLUENCES-1;z>j;z--){best[z]=best[z-1];choices[z]=choices[z-1];}
                best[j]=score;choices[j]=i;break;
            }
        }
        float sum=0;
        for(int j=0;j<INFLUENCES;j++){
            weights[j]=1f/(best[j]+.00008f);
            sum+=weights[j];
        }
        for(int j=0;j<INFLUENCES;j++)weights[j]/=sum;
    }

    private static float[][] targetPoints(CombatRigSolver.Warrior w) {
        return new float[][]{
            {(float)w.pelvis.x,(float)w.pelvis.y},
            {(float)w.chest.x,(float)w.chest.y},
            {(float)w.neck.x,(float)w.neck.y},
            {(float)w.head.x,(float)w.head.y},
            {(float)w.swordShoulder.x,(float)w.swordShoulder.y},
            {(float)w.swordElbow.x,(float)w.swordElbow.y},
            {(float)w.swordHand.x,(float)w.swordHand.y},
            {(float)w.freeShoulder.x,(float)w.freeShoulder.y},
            {(float)w.freeElbow.x,(float)w.freeElbow.y},
            {(float)w.freeHand.x,(float)w.freeHand.y},
            {(float)w.leadingHip.x,(float)w.leadingHip.y},
            {(float)w.leadingKnee.x,(float)w.leadingKnee.y},
            {(float)w.leadingFoot.x,(float)w.leadingFoot.y},
            {(float)w.trailingHip.x,(float)w.trailingHip.y},
            {(float)w.trailingKnee.x,(float)w.trailingKnee.y},
            {(float)w.trailingFoot.x,(float)w.trailingFoot.y}
        };
    }

    /** Returns a NEW position, normalised to target canvas. Pure and testable. */
    public static float[] skinPoint(Rig2DProfile p,CombatRigSolver.Warrior pose,
                                    float u,float v) {
        return skinPoint(p,targetPoints(pose),u,v,1);
    }

    /** Shares the rendered nearest-three bone weights and physical similarity kernel. */
    public static float[] skinPoint(Rig2DProfile profile,float[][] target,float u,float v,double aspect){
        validatePose(target);
        if(profile==null||!Double.isFinite(aspect)||aspect<=0||aspect>8||!Float.isFinite(u)||!Float.isFinite(v))
            throw new IllegalArgumentException("Finite source geometry required");
        float[][] transforms=new float[Rig2DProfile.BONES.length][6];
        computeTransforms(profile,target,(float)aspect,transforms);
        if(profile.headOutline.length>0&&NativeRigidHead2D.contains(profile.headOutline,u,v))return NativeRigidHead2D.point(profile,target,u,v,aspect);
        int[] choices=new int[INFLUENCES];float[] weights=new float[INFLUENCES];
        computeWeights(u,v,profile,(float)aspect,choices,weights);
        return weightedPoint(u,v,(float)aspect,choices,weights,transforms);
    }

    private static void computeTransforms(Rig2DProfile profile,float[][] target,float aspect,float[][] transforms){
        for(int i=0;i<Rig2DProfile.BONES.length;i++){
            int[] bone=Rig2DProfile.BONES[i];
            float[] r0=profile.joints[bone[0]],r1=profile.joints[bone[1]];
            float[] t0=target[bone[0]],t1=target[bone[1]];
            double rx=(r1[0]-r0[0])*aspect,ry=r1[1]-r0[1];
            double tx=(t1[0]-t0[0])*aspect,ty=t1[1]-t0[1];
            double denominator=rx*rx+ry*ry;
            if(denominator<1e-14||tx*tx+ty*ty<1e-14)
                throw new IllegalArgumentException("Source or target bone is degenerate");
            float a=(float)((rx*tx+ry*ty)/denominator),b=(float)((rx*ty-ry*tx)/denominator);
            double scale=Math.hypot(a,b);
            if(!Double.isFinite(scale)||scale<.12||scale>3.5)throw new IllegalArgumentException("Pose exceeds supported source bone scale [0.12,3.5]");
            float[] matrix=transforms[i];matrix[0]=a;matrix[1]=b;
            matrix[2]=r0[0]*aspect;matrix[3]=r0[1];matrix[4]=t0[0]*aspect;matrix[5]=t0[1];
        }
    }

    /** Pure preflight through the identical transform kernel used for every rendered mesh vertex. */
    public static void requireRenderablePose(Rig2DProfile profile,float[][] target,int width,int height){
        if(profile==null||width<128||height<128||width>1280||height>1920)
            throw new IllegalArgumentException("A bounded source rig and output geometry are required");
        validatePose(profile.joints);validatePose(target);
        computeTransforms(profile,target,width/(float)height,new float[Rig2DProfile.BONES.length][6]);
    }
    public static void requireRenderablePose(Rig2DProfile profile,CombatRigSolver.Warrior target,int width,int height){
        if(target==null)throw new IllegalArgumentException("Sampled native actor pose is required");
        requireRenderablePose(profile,targetPoints(target),width,height);
    }

    private static float[] weightedPoint(float u,float v,float aspect,int[] choices,float[] weights,float[][] transforms){
        float x=0,y=0;
        for(int j=0;j<INFLUENCES;j++){
            float[] matrix=transforms[choices[j]];
            float dx=u*aspect-matrix[2],dy=v-matrix[3],weight=weights[j];
            x+=(matrix[4]+matrix[0]*dx-matrix[1]*dy)*weight;
            y+=(matrix[5]+matrix[1]*dx+matrix[0]*dy)*weight;
        }
        return new float[]{x/aspect,y};
    }

    private static void validatePose(float[][] pose){
        if(pose==null||pose.length!=Rig2DProfile.JOINTS.length)
            throw new IllegalArgumentException("Sixteen authentic 2D target landmarks required");
        for(float[] point:pose)if(point==null||point.length!=2||!Float.isFinite(point[0])||!Float.isFinite(point[1]))
            throw new IllegalArgumentException("Nonfinite source pose landmark");
    }

    /**
     * Source appearance stays one coherent rigged bitmap. There are NO whole
     * image crossfades, no repeated source fighters and no procedural silhouette
     * drawn over a still character.
     */
    public void render(Canvas canvas,CombatRigSolver.Warrior target) {
        render(canvas,targetPoints(target),255);
    }

    /**
     * Source-keyframe-driven 2D mesh skinning. The end pose comes from
     * a traced ORIGINAL storyboard image, never the generic combat solver.
     * The source actor texture remains unchanged and masked. No bones or
     * stick figures are drawn on top of the illustration.
     */
    public void render(Canvas canvas,float[][] targetPose,int opacity) {
        if(canvas==null)throw new IllegalArgumentException("Output canvas required");
        rendered=true;
        updateMesh(targetPose,canvas.getWidth(),canvas.getHeight());
        int alpha=Math.max(0,Math.min(255,opacity));
        if(alpha==0)return;
        // Canvas.drawBitmapMesh ignores Paint alpha on the native raster backend.
        // Apply opacity to the completed mesh layer, including its actual masked alpha.
        int saved=alpha==255?-1:canvas.saveLayerAlpha(0,0,canvas.getWidth(),canvas.getHeight(),alpha);
        try{canvas.drawBitmapMesh(texture,MESH_W,MESH_H,mesh,0,null,0,paint);if(head!=null)head.render(canvas,targetPose);}
        finally{if(saved>=0)canvas.restoreToCount(saved);}
    }

    private void updateMesh(float[][] targetPose,int width,int height){
        validatePose(targetPose);
        if(Math.abs(width/(double)height-sourceAspect)>.000001)
            throw new IllegalArgumentException("Actor and output aspect must share one source coordinate system");
        computeTransforms(profile,targetPose,sourceAspect,boneTransforms);
        for(int i=0;i<VERTICES;i++){
            float x=0,y=0,srcX=restU[i],srcY=restV[i];
            for(int j=0;j<INFLUENCES;j++){
                int bone=boneIds[i][j];
                float[] matrix=boneTransforms[bone];
                float rx=srcX*sourceAspect-matrix[2],ry=srcY-matrix[3];
                float weight=boneWeights[i][j];
                x+=(matrix[4]+matrix[0]*rx-matrix[1]*ry)*weight;
                y+=(matrix[5]+matrix[1]*rx+matrix[0]*ry)*weight;
            }
            mesh[2*i]=x/sourceAspect*width;mesh[2*i+1]=y*height;
        }
    }

    public boolean hasSourceProp(){return prop!=null;}
    /** Configure both original weapon masks before the first draw; no hidden body pixels are invented. */
    public void excludeSourceProps(java.util.List<float[][]> masks){
        if(rendered)throw new IllegalStateException("Source weapon exclusions must be configured before rendering");
        if(masks==null||masks.size()>8)throw new IllegalArgumentException("Bounded source weapon exclusions required");
        for(float[][] polygon:masks)if(polygon!=null&&polygon.length>=3){
            excludedOpaqueSourcePixels+=removeOutline(texture,polygon);
            if(head!=null)excludedOpaqueSourcePixels+=head.exclude(polygon);
        }
    }
    public long excludedOpaqueSourcePixels(){return excludedOpaqueSourcePixels;}
    public double sourceAspect(){return sourceAspect;}
    /** Predicted position on the actual cached mesh triangles, never measured raster evidence. */
    public float[] predictedMeshPoint(float[][] targetPose,float u,float v){
        if(!Float.isFinite(u)||!Float.isFinite(v)||u<0||u>1||v<0||v>1)
            throw new IllegalArgumentException("Mesh point must lie in source UV coordinates");
        if(head!=null&&NativeRigidHead2D.contains(profile.headOutline,u,v)){
            requireRenderablePose(profile,targetPose,texture.getWidth(),texture.getHeight());
            return NativeRigidHead2D.point(profile,targetPose,u,v,sourceAspect);
        }
        int width=texture.getWidth(),height=texture.getHeight();updateMesh(targetPose,width,height);
        float gx=u*MESH_W,gy=v*MESH_H;
        int x=Math.min(MESH_W-1,(int)gx),y=Math.min(MESH_H-1,(int)gy);
        float dx=gx-x,dy=gy-y;
        int topLeft=y*(MESH_W+1)+x,topRight=topLeft+1,bottomLeft=topLeft+MESH_W+1,bottomRight=bottomLeft+1;
        float a,b,c;int ia,ib,ic;
        if(dx+dy<=1){ia=topLeft;ib=topRight;ic=bottomLeft;a=1-dx-dy;b=dx;c=dy;}
        else{ia=bottomRight;ib=bottomLeft;ic=topRight;a=dx+dy-1;b=1-dx;c=1-dy;}
        return new float[]{(mesh[2*ia]*a+mesh[2*ib]*b+mesh[2*ic]*c)/width,
                (mesh[2*ia+1]*a+mesh[2*ib+1]*b+mesh[2*ic+1]*c)/height};
    }
    public void renderSourceProp(Canvas canvas,float[] hand,float[] tip,int opacity){
        if(prop==null)throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: original source sword is not bound");
        prop.render(canvas,hand,tip,opacity);
    }
    public float[] predictedPropHilt(float[] hand,float[] tip){
        if(prop==null)throw new IllegalArgumentException("Original source sword is not bound");
        return prop.predictedHilt(hand,tip);
    }

    public Bitmap getCutoutForTesting() {return texture.copy(Bitmap.Config.ARGB_8888,false);}
    @Override public void close() {if(!texture.isRecycled())texture.recycle();if(prop!=null)prop.close();if(head!=null)head.close();}
}
