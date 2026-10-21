package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.ByteArrayOutputStream;
import java.util.UUID;

/** Native masked-source pose evidence, deliberately separate from film acceptance. */
public final class NativeSourceRigPreview {
    private static final String LABEL="SOURCE RIG POSE DIAGNOSTIC";
    private NativeSourceRigPreview(){}

    public static JSONObject render(Context context,ProjectStore projects,JSONObject request)throws Exception {
        NativeSourceArtworkInspector.requireInputs(context,projects,request);
        String projectId=NativeSourceArtworkInspector.identity(request,"projectId"),rigId=NativeSourceArtworkInspector.identity(request,"rigId");
        int maximum=NativeSourceArtworkInspector.dimension(request,720,720);
        String expectedRig=NativeSourceArtworkInspector.optionalDigest(request,"expectedRigSha256");
        String expectedSource=NativeSourceArtworkInspector.optionalDigest(request,"expectedSourceSha256");
        ProjectStore.Project initial=NativeSourceArtworkInspector.project(projects,projectId);
        Rig2DProjectStore rigs=new Rig2DProjectStore(context,projects);
        Rig2DProfile profile=rigs.load(projectId,rigId);
        String rigSha=Rig2DProjectStore.sha256(profile);
        if(expectedRig!=null&&!expectedRig.equals(rigSha))throw new IllegalStateException("SOURCE_RIG_CHANGED: reopen the source rig before testing its pose");
        if(profile.sourceSha256==null)throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: diagnostic requires an original-byte-bound authored rig");
        if(expectedSource!=null&&!expectedSource.equals(profile.sourceSha256))throw new IllegalStateException("SOURCE_IMAGE_CHANGED: rig belongs to different original bytes");
        ProjectStore.Asset source=NativeSourceArtworkInspector.ownedImage(initial,profile.sourceAssetId);
        if(request.has("targetJoints16")&&request.has("targetJoints"))throw new IllegalArgumentException("Provide targetJoints16 or legacy targetJoints, never both");
        String targetField=request.has("targetJoints16")?"targetJoints16":"targetJoints";
        float[][] target=request.has(targetField)?points(request.optJSONArray(targetField),16,targetField):copy(profile.joints);
        float[] tip=request.has("swordTip")?point(request.optJSONArray("swordTip"),"swordTip"):profile.swordTip==null?null:profile.swordTip.clone();
        JSONObject computed=StoryboardSourceManifest.withVerifiedSource(context,source,profile.sourceSha256,(file,entry)->{
            int originalWidth=entry.getInt("orientedWidth"),originalHeight=entry.getInt("orientedHeight");
            int divisor=NativeSourceArtworkInspector.gcd(originalWidth,originalHeight);
            int basisWidth=originalWidth/divisor,basisHeight=originalHeight/divisor,multiple=maximum/Math.max(basisWidth,basisHeight);
            int width=basisWidth*multiple,height=basisHeight*multiple;
            boolean registered=width<128||height<128;
            double scaleX=1,scaleY=1,offsetX=0,offsetY=0;
            if(registered) {
                double fit=maximum/(double)Math.max(originalWidth,originalHeight);
                width=Math.max(128,(int)Math.round(originalWidth*fit));height=Math.max(128,(int)Math.round(originalHeight*fit));
                double physicalScale=Math.min(width/(double)originalWidth,height/(double)originalHeight);
                scaleX=originalWidth*physicalScale/width;scaleY=originalHeight*physicalScale/height;
                offsetX=(1-scaleX)/2;offsetY=(1-scaleY)/2;
            }
            Bitmap stage=null;
            File registrationFile=null;
            NativeSkinnedSprite2D sprite=null;
            try {
                Rig2DProfile renderProfile=profile;
                float[][] renderTarget=target;float[] renderTip=tip;
                if(registered) {
                    if(profile.swordTip==null||profile.swordHilt==null||profile.swordOutline.length<3)
                        throw new IllegalArgumentException("INSUFFICIENT_MOTION_PROVIDER: original authored sword polygon, hilt and tip are required");
                    Bitmap oriented=null,registration=null;
                    try {
                        oriented=NativeSourceArtworkInspector.decodeOrientedSnapshot(file,entry,width,height);
                        if(profile.outline.length<3&&!genuineTransparency(oriented))
                            throw new IllegalArgumentException("Original actor outline or genuine original transparency is required before diagnostic padding");
                        registration=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                        new Canvas(registration).drawBitmap(oriented,null,new RectF((float)(offsetX*width),(float)(offsetY*height),
                                (float)((offsetX+scaleX)*width),(float)((offsetY+scaleY)*height)),new Paint(Paint.FILTER_BITMAP_FLAG));
                        File directory=new File(context.getCacheDir(),"source_rig_stage_registration");
                        if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("Private diagnostic registration storage unavailable");
                        registrationFile=File.createTempFile("registered-",".png",directory);
                        ByteArrayOutputStream encoded=new ByteArrayOutputStream();
                        if(!registration.compress(Bitmap.CompressFormat.PNG,100,encoded))throw new IllegalStateException("Cannot encode native source registration");
                        byte[] bytes=encoded.toByteArray();
                        try(FileOutputStream output=new FileOutputStream(registrationFile)){output.write(bytes);}
                        if(!registrationFile.setReadOnly())throw new IllegalStateException("Private diagnostic registration could not be protected");
                        String internalSha=NativeSourceArtworkInspector.sha256(bytes),internalId=UUID.randomUUID().toString();
                        ProjectStore.Asset internal=new ProjectStore.Asset();internal.id=internalId;internal.uri=Uri.fromFile(registrationFile).toString();internal.mime="image/png";internal.name="diagnostic-registration";
                        renderProfile=new Rig2DProfile(profile.id,profile.projectId,internalId,profile.black,profile.transparentSource,
                                transform(profile.joints,scaleX,scaleY,offsetX,offsetY),transform(profile.outline,scaleX,scaleY,offsetX,offsetY),
                                transform(profile.swordTip,scaleX,scaleY,offsetX,offsetY),transform(profile.swordHilt,scaleX,scaleY,offsetX,offsetY),
                                transform(profile.swordOutline,scaleX,scaleY,offsetX,offsetY),internalSha,
                                transform(profile.headOutline,scaleX,scaleY,offsetX,offsetY));
                        renderTarget=transform(target,scaleX,scaleY,offsetX,offsetY);renderTip=transform(tip,scaleX,scaleY,offsetX,offsetY);
                        NativeSkinnedSprite2D.requireRenderablePose(renderProfile,renderTarget,width,height);
                        NativeSourceProp2D.requireSourceGeometry(renderProfile,width,height);
                        NativeSourceProp2D.requirePosedGeometry(renderTarget[Rig2DProfile.SWORD_HAND],renderTip,width,height);
                        sprite=new NativeSkinnedSprite2D(context,internal,renderProfile,width,height,internalSha);
                    }finally{if(oriented!=null)oriented.recycle();if(registration!=null)registration.recycle();}
                }else {
                    NativeSkinnedSprite2D.requireRenderablePose(profile,target,width,height);
                    NativeSourceProp2D.requireSourceGeometry(profile,width,height);
                    NativeSourceProp2D.requirePosedGeometry(target[Rig2DProfile.SWORD_HAND],tip,width,height);
                    sprite=new NativeSkinnedSprite2D(context,frozenAsset(source,file),profile,width,height,entry);
                }
                stage=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(stage);checkerboard(canvas);
                sprite.render(canvas,renderTarget,255);sprite.renderSourceProp(canvas,renderTarget[Rig2DProfile.SWORD_HAND],renderTip,255);label(canvas);
                JSONObject forward=NativeSourceArtworkInspector.mapping(scaleX,scaleY,offsetX,offsetY);
                JSONObject result=NativeSourceArtworkInspector.sourceMetadata(initial,entry).put("rigId",rigId).put("rigSha256",rigSha)
                        .put("scope","source-rig-pose-diagnostic").put("diagnosticOnly",true).put("cinematicAcceptance",false)
                        .put("completeAuthoredProfile",true).put("sourceRestModified",false).put("originalBytesModified",false)
                        .put("stage",new JSONObject(forward.toString()).put("background","checkerboard").put("label",LABEL)
                                .put("originalToStageUv",forward).put("registration",registered?"contain":"identity").put("independentStretch",false)
                                .put("physicalSourceAspect",originalWidth/(double)originalHeight))
                        .put("previewToOriginalUv",NativeSourceArtworkInspector.mapping(1/scaleX,1/scaleY,-offsetX/scaleX,-offsetY/scaleY))
                        .put("targetJoints16",pairs(target)).put("targetJoints",pairs(target)).put("swordTip",new JSONArray().put(tip[0]).put(tip[1]))
                        .put("rigPreviewImage",NativeSourceArtworkInspector.image(stage,"source-rig-pose-diagnostic",true));
                result.put("contactSheet",result.getJSONObject("rigPreviewImage"));
                NativeSourceArtworkInspector.interrupted();return result;
            }finally {
                if(sprite!=null)sprite.close();if(stage!=null)stage.recycle();
                if(registrationFile!=null&&registrationFile.exists()&&!registrationFile.delete())
                    throw new IllegalStateException("Private diagnostic registration could not be released");
            }
        });
        return projects.guardedProject(projectId,initial.updatedAt,current->{
            NativeSourceArtworkInspector.requireSameRegistration(current,source);rigs.loadVerified(current,rigId,rigSha);
            NativeSourceArtworkInspector.interrupted();return computed;
        });
    }
    private static float[] transform(float[] point,double scaleX,double scaleY,double offsetX,double offsetY){
        if(point==null)return null;return new float[]{(float)(offsetX+scaleX*point[0]),(float)(offsetY+scaleY*point[1])};
    }
    private static float[][] transform(float[][] points,double scaleX,double scaleY,double offsetX,double offsetY){
        float[][] result=new float[points.length][];for(int i=0;i<points.length;i++)result[i]=transform(points[i],scaleX,scaleY,offsetX,offsetY);return result;
    }
    private static boolean genuineTransparency(Bitmap original){
        if(!original.hasAlpha())return false;int width=original.getWidth();int[] row=new int[width];
        for(int y=0;y<original.getHeight();y++){original.getPixels(row,0,width,0,y,width,1);for(int pixel:row)if(Color.alpha(pixel)<200)return true;}return false;
    }
    private static ProjectStore.Asset frozenAsset(ProjectStore.Asset original,File file){
        ProjectStore.Asset frozen=ProjectStore.Asset.fromJson(original.toJson());frozen.uri=Uri.fromFile(file).toString();return frozen;
    }
    private static float[][] points(JSONArray values,int count,String name)throws Exception {
        if(values==null||values.length()!=count)throw new IllegalArgumentException("Exactly sixteen known labelled "+name+" landmarks are required");
        float[][] result=new float[count][];for(int i=0;i<count;i++)result[i]=point(values.optJSONArray(i),name+"["+i+"]");return result;
    }
    private static float[] point(JSONArray value,String name)throws Exception {
        if(value==null||value.length()!=2)throw new IllegalArgumentException(name+" must be a known original-oriented UV pair");
        float[] result=new float[2];
        for(int i=0;i<2;i++) {
            Object raw=value.opt(i);if(!(raw instanceof Number))throw new IllegalArgumentException(name+" coordinates must be finite numbers; unknown anatomy cannot be guessed");
            double number=((Number)raw).doubleValue();if(!Double.isFinite(number)||number<0||number>1)throw new IllegalArgumentException(name+" coordinates must lie in original UV [0,1]");
            result[i]=(float)number;
        }
        return result;
    }
    private static float[][] copy(float[][] points){float[][] result=new float[points.length][];for(int i=0;i<points.length;i++)result[i]=points[i].clone();return result;}
    private static JSONArray pairs(float[][] points)throws Exception {JSONArray result=new JSONArray();for(float[] point:points)result.put(new JSONArray().put(point[0]).put(point[1]));return result;}
    private static void checkerboard(Canvas canvas){
        Paint paint=new Paint();int cell=16;
        for(int y=0;y<canvas.getHeight();y+=cell)for(int x=0;x<canvas.getWidth();x+=cell){
            paint.setColor(((x/cell+y/cell)&1)==0?Color.rgb(210,210,210):Color.rgb(240,240,240));canvas.drawRect(x,y,Math.min(x+cell,canvas.getWidth()),Math.min(y+cell,canvas.getHeight()),paint);
        }
    }
    private static void label(Canvas canvas){
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(Color.argb(225,20,24,30));canvas.drawRect(0,0,canvas.getWidth(),26,paint);
        paint.setColor(Color.WHITE);paint.setTextSize(Math.min(13,canvas.getWidth()/25f));canvas.drawText(LABEL,5,17,paint);
    }
}
