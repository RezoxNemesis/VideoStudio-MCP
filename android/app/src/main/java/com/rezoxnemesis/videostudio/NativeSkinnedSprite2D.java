package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.net.Uri;
import java.io.InputStream;

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
    private final float[] restU=new float[VERTICES],restV=new float[VERTICES];
    private final int[][] boneIds=new int[VERTICES][INFLUENCES];
    private final float[][] boneWeights=new float[VERTICES][INFLUENCES];
    private final float[] mesh=new float[VERTICES*2];
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);

    public NativeSkinnedSprite2D(Context ctx, ProjectStore.Asset source,
                                 Rig2DProfile profile,int width,int height) throws Exception {
        if(ctx==null||source==null||profile==null||
                !profile.sourceAssetId.equals(source.id)||
                source.mime==null||!source.mime.startsWith("image/"))
            throw new IllegalArgumentException("Source image must match the approved rig profile");
        if(width<128||height<128||width>1280||height>1920)
            throw new IllegalArgumentException("Invalid skinned preview resolution");
        this.profile=profile;
        Bitmap input=read(ctx,Uri.parse(source.uri),width,height);
        if(profile.outline.length==0 && !hasRealTransparency(input)){
            input.recycle();
            throw new IllegalArgumentException("A transparent cutout or manually drawn actor outline is required");
        }
        texture=applyOutline(input,profile.outline);
        if(texture!=input)input.recycle();
        if(!hasAnyOpaquePixel(texture)){
            texture.recycle();
            throw new IllegalArgumentException("Actor mask has no visible source pixels");
        }
        for(int y=0;y<=MESH_H;y++)for(int x=0;x<=MESH_W;x++){
            int i=y*(MESH_W+1)+x;
            float u=x/(float)MESH_W,v=y/(float)MESH_H;
            restU[i]=u;restV[i]=v;
            computeWeights(i,u,v,profile);
        }
    }

    private static Bitmap read(Context ctx,Uri uri,int w,int h) throws Exception {
        BitmapFactory.Options bounds=new BitmapFactory.Options();
        bounds.inJustDecodeBounds=true;
        try(InputStream in=ctx.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IllegalArgumentException("Rig sprite file unavailable");
            BitmapFactory.decodeStream(in,null,bounds);
        }
        if(bounds.outWidth<=0||bounds.outHeight<=0||bounds.outWidth>8192||
                bounds.outHeight>8192)throw new IllegalArgumentException("Invalid rig source dimensions");
        BitmapFactory.Options opts=new BitmapFactory.Options();
        opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
        opts.inSampleSize=1;
        while(bounds.outWidth/opts.inSampleSize>w*2 &&
                bounds.outHeight/opts.inSampleSize>h*2)opts.inSampleSize*=2;
        Bitmap raw;
        try(InputStream in=ctx.getContentResolver().openInputStream(uri)){
            if(in==null)throw new IllegalArgumentException("Rig sprite no longer exists");
            raw=BitmapFactory.decodeStream(in,null,opts);
        }
        if(raw==null)throw new IllegalArgumentException("Could not decode actor image");
        Bitmap scaled=Bitmap.createScaledBitmap(raw,w,h,true);
        if(scaled!=raw)raw.recycle();
        return scaled;
    }

    private static Bitmap applyOutline(Bitmap source,float[][] polygon) {
        if(polygon.length<3)return source;
        int w=source.getWidth(),h=source.getHeight();
        Bitmap alpha=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas mask=new Canvas(alpha);
        Path path=new Path();path.moveTo(polygon[0][0]*w,polygon[0][1]*h);
        for(int i=1;i<polygon.length;i++)
            path.lineTo(polygon[i][0]*w,polygon[i][1]*h);
        path.close();
        Paint opaque=new Paint(Paint.ANTI_ALIAS_FLAG);
        opaque.setColor(Color.WHITE);
        mask.drawPath(path,opaque);

        Bitmap layer=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(layer);
        canvas.drawBitmap(source,0,0,new Paint(Paint.FILTER_BITMAP_FLAG));
        Paint clip=new Paint(Paint.ANTI_ALIAS_FLAG);
        clip.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        canvas.drawBitmap(alpha,0,0,clip);
        clip.setXfermode(null);
        alpha.recycle();
        return layer;
    }

    private static boolean hasRealTransparency(Bitmap b) {
        if(!b.hasAlpha())return false;
        int w=b.getWidth(),h=b.getHeight();
        for(int y=0;y<h;y+=Math.max(1,h/14))
            for(int x=0;x<w;x+=Math.max(1,w/14))
                if((b.getPixel(x,y)>>>24)<200)return true;
        return false;
    }
    private static boolean hasAnyOpaquePixel(Bitmap b) {
        int w=b.getWidth(),h=b.getHeight();
        for(int y=0;y<h;y+=Math.max(1,h/32))
            for(int x=0;x<w;x+=Math.max(1,w/24))
                if((b.getPixel(x,y)>>>24)>96)return true;
        return false;
    }

    private static float distanceToSegment(float x,float y,float[] a,float[] b){
        float dx=b[0]-a[0],dy=b[1]-a[1],den=dx*dx+dy*dy;
        float t=den<1e-9f?0:Math.max(0,Math.min(1,((x-a[0])*dx+(y-a[1])*dy)/den));
        return (float)Math.hypot(x-a[0]-t*dx,y-a[1]-t*dy);
    }
    private void computeWeights(int index,float x,float y,Rig2DProfile source){
        float[] best={Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY,Float.POSITIVE_INFINITY};
        int[] choices=boneIds[index];
        for(int i=0;i<Rig2DProfile.BONES.length;i++){
            int[] bone=Rig2DProfile.BONES[i];
            float d=distanceToSegment(x,y,source.joints[bone[0]],source.joints[bone[1]]);
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
            boneWeights[index][j]=1f/(best[j]+.00008f);
            sum+=boneWeights[index][j];
        }
        for(int j=0;j<INFLUENCES;j++)boneWeights[index][j]/=sum;
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
        float[][] target=targetPoints(pose);
        // Nearest-bone blending also works for isolated points outside the
        // regular bitmap mesh, e.g. to verify feature/head material stability.
        float[] out={0,0};float sum=0;
        for(int i=0;i<Rig2DProfile.BONES.length;i++){
            int[] bone=Rig2DProfile.BONES[i];
            float d=distanceToSegment(u,v,p.joints[bone[0]],p.joints[bone[1]]);
            float w=1f/(.0004f+d*d*d*.2f);
            float[] transformed=transform(u,v,
                    p.joints[bone[0]],p.joints[bone[1]],
                    target[bone[0]],target[bone[1]]);
            out[0]+=transformed[0]*w;out[1]+=transformed[1]*w;sum+=w;
        }
        out[0]/=sum;out[1]/=sum;
        return out;
    }

    private static float[] transform(float u,float v,float[] restA,float[] restB,
                                      float[] targetA,float[] targetB) {
        float rx=restB[0]-restA[0],ry=restB[1]-restA[1];
        float tx=targetB[0]-targetA[0],ty=targetB[1]-targetA[1];
        float restLen=(float)Math.max(.001,Math.hypot(rx,ry));
        float targetLen=(float)Math.max(.001,Math.hypot(tx,ty));
        float cos=(rx*tx+ry*ty)/(restLen*targetLen);
        float sin=(rx*ty-ry*tx)/(restLen*targetLen);
        float scale=Math.max(.12f,Math.min(3.5f,targetLen/restLen));
        float dx=u-restA[0],dy=v-restA[1];
        return new float[]{
            targetA[0]+(dx*cos-dy*sin)*scale,
            targetA[1]+(dx*sin+dy*cos)*scale
        };
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
        if(canvas==null||targetPose==null||targetPose.length!=Rig2DProfile.JOINTS.length)
            throw new IllegalArgumentException("Sixteen authentic 2D target landmarks required");
        for(float[] p:targetPose)
            if(p==null||p.length!=2||!Float.isFinite(p[0])||!Float.isFinite(p[1]))
                throw new IllegalArgumentException("Nonfinite source pose landmark");
        float[][] to=targetPose;
        float[][][] transforms=new float[Rig2DProfile.BONES.length][2][2];
        float[] cos=new float[transforms.length],sin=new float[transforms.length],
                scale=new float[transforms.length];
        for(int i=0;i<Rig2DProfile.BONES.length;i++){
            int[] bone=Rig2DProfile.BONES[i];
            float[] r0=profile.joints[bone[0]],r1=profile.joints[bone[1]];
            float[] t0=to[bone[0]],t1=to[bone[1]];
            float rx=r1[0]-r0[0],ry=r1[1]-r0[1],tx=t1[0]-t0[0],ty=t1[1]-t0[1];
            float rl=(float)Math.max(.001,Math.hypot(rx,ry));
            float tl=(float)Math.max(.001,Math.hypot(tx,ty));
            cos[i]=(rx*tx+ry*ty)/(rl*tl);
            sin[i]=(rx*ty-ry*tx)/(rl*tl);
            scale[i]=Math.max(.12f,Math.min(3.5f,tl/rl));
            transforms[i][0][0]=r0[0];transforms[i][0][1]=r0[1];
            transforms[i][1][0]=t0[0];transforms[i][1][1]=t0[1];
        }
        int w=canvas.getWidth(),h=canvas.getHeight();
        for(int i=0;i<VERTICES;i++){
            float x=0,y=0,srcX=restU[i],srcY=restV[i];
            for(int j=0;j<INFLUENCES;j++){
                int bone=boneIds[i][j];
                float rx=srcX-transforms[bone][0][0],ry=srcY-transforms[bone][0][1];
                float s=scale[bone],c=cos[bone],si=sin[bone];
                float weight=boneWeights[i][j];
                x+=(transforms[bone][1][0]+(rx*c-ry*si)*s)*weight;
                y+=(transforms[bone][1][1]+(rx*si+ry*c)*s)*weight;
            }
            mesh[2*i]=x*w;mesh[2*i+1]=y*h;
        }
        paint.setAlpha(Math.max(0,Math.min(255,opacity)));
        canvas.drawBitmapMesh(texture,MESH_W,MESH_H,mesh,0,null,0,paint);
        paint.setAlpha(255);
    }

    public Bitmap getCutoutForTesting() {return texture.copy(Bitmap.Config.ARGB_8888,false);}
    @Override public void close() {if(!texture.isRecycled())texture.recycle();}
}
