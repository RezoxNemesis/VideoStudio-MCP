package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

/** Inspect only project-owned original bytes, in their EXIF-oriented coordinate space. */
public final class NativeSourceArtworkInspector {
    static final int MAX_BASE64_IMAGE_CHARACTERS=768*1024;
    private static final long MAX_DECODE_PIXELS=4L*1024*1024;
    private NativeSourceArtworkInspector(){}

    public static JSONObject inspect(Context context,ProjectStore projects,JSONObject request)throws Exception {
        requireInputs(context,projects,request);
        String projectId=identity(request,"projectId"),assetId=identity(request,"assetId");
        int maximum=dimension(request,1024,1024);
        String expected=optionalDigest(request,"expectedSourceSha256");
        double[] crop=crop(request);
        JSONArray rigIds=request.optJSONArray("rigIds");
        if(request.has("rigIds")&&rigIds==null)throw new IllegalArgumentException("rigIds must be an array");
        if(rigIds!=null&&rigIds.length()>8)throw new IllegalArgumentException("At most eight owned overlay profiles may be inspected");
        Set<String> seen=new HashSet<>();
        if(rigIds!=null)for(int i=0;i<rigIds.length();i++) {
            String id=identityValue(rigIds.opt(i));
            if(!seen.add(id))throw new IllegalArgumentException("Overlay profile identities must be unique");
        }
        ProjectStore.Project initial=project(projects,projectId);
        ProjectStore.Asset source=ownedImage(initial,assetId);
        StoryboardSourceManifest.VerifiedSource<JSONObject> reader=(file,entry)->{
            int originalWidth=entry.getInt("orientedWidth"),originalHeight=entry.getInt("orientedHeight");
            double cropWidth=originalWidth*(crop[2]-crop[0]),cropHeight=originalHeight*(crop[3]-crop[1]);
            double scale=maximum/Math.max(cropWidth,cropHeight);
            int width=Math.max(1,(int)Math.round(cropWidth*scale)),height=Math.max(1,(int)Math.round(cropHeight*scale));
            Bitmap oriented=null,preview=null;
            try {
                oriented=decodeOrientedSnapshot(file,entry,(int)Math.min(8192,Math.ceil(width/(crop[2]-crop[0]))),
                        (int)Math.min(8192,Math.ceil(height/(crop[3]-crop[1]))));
                preview=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
                Canvas canvas=new Canvas(preview);
                RectF destination=new RectF((float)(-crop[0]*width/(crop[2]-crop[0])),
                        (float)(-crop[1]*height/(crop[3]-crop[1])),(float)((1-crop[0])*width/(crop[2]-crop[0])),
                        (float)((1-crop[1])*height/(crop[3]-crop[1])));
                canvas.drawBitmap(oriented,null,destination,new Paint(Paint.FILTER_BITMAP_FLAG));
                JSONArray overlays=new JSONArray();
                if(rigIds!=null) {
                    Rig2DProjectStore rigs=new Rig2DProjectStore(context,projects);
                    for(int i=0;i<rigIds.length();i++) {
                        Rig2DProfile rig=rigs.load(projectId,rigIds.getString(i));
                        if(!source.id.equals(rig.sourceAssetId))throw new IllegalArgumentException("Overlay rig belongs to another source image");
                        if(rig.sourceSha256==null||!rig.sourceSha256.equals(entry.getString("sourceSha256")))
                            throw new IllegalStateException("SOURCE_IMAGE_CHANGED: overlay annotations are not bound to displayed original bytes");
                        JSONObject record=rig.toJson();record.put("rigId",rig.id).put("rigSha256",Rig2DProjectStore.sha256(rig));
                        overlays.put(record);drawOverlay(canvas,rig,crop);
                    }
                }
                JSONObject result=sourceMetadata(initial,entry).put("scope","source-artwork-inspection")
                        .put("coordinateSpace","oriented-source-uv").put("cinematicAcceptance",false)
                        .put("previewToOriginalUv",mapping(crop[2]-crop[0],crop[3]-crop[1],crop[0],crop[1]))
                        .put("crop",new JSONObject().put("left",crop[0]).put("top",crop[1]).put("right",crop[2]).put("bottom",crop[3]))
                        .put("rigOverlays",overlays).put("originalBytesModified",false)
                        .put("sourceArtworkImage",image(preview,overlays.length()==0?"source-artwork-inspection":"source-artwork-with-authored-overlay",false));
                result.put("contactSheet",result.getJSONObject("sourceArtworkImage"));
                interrupted();return result;
            }finally {
                if(preview!=null)preview.recycle();if(oriented!=null)oriented.recycle();
            }
        };
        JSONObject result=expected==null?StoryboardSourceManifest.withFrozenSource(context,source,reader)
                :StoryboardSourceManifest.withVerifiedSource(context,source,expected,reader);
        return projects.guardedProject(projectId,initial.updatedAt,current->{
            requireSameRegistration(current,source);
            JSONArray overlays=result.getJSONArray("rigOverlays");Rig2DProjectStore rigs=new Rig2DProjectStore(context,projects);
            for(int i=0;i<overlays.length();i++)rigs.loadVerified(current,overlays.getJSONObject(i).getString("rigId"),overlays.getJSONObject(i).getString("rigSha256"));
            interrupted();return result;
        });
    }

    /** Caller owns the returned bitmap; only frozen verified source files may enter here. */
    static Bitmap decodeOrientedSnapshot(File encoded,JSONObject entry,int targetWidth,int targetHeight)throws Exception {
        Bitmap owned=null;
        try {
            int codedWidth=entry.getInt("codedWidth"),codedHeight=entry.getInt("codedHeight");
            int orientedWidth=entry.getInt("orientedWidth"),orientedHeight=entry.getInt("orientedHeight");
            BitmapFactory.Options options=new BitmapFactory.Options();options.inPreferredConfig=Bitmap.Config.ARGB_8888;options.inSampleSize=1;
            while((long)Math.ceil(codedWidth/(double)options.inSampleSize)*Math.ceil(codedHeight/(double)options.inSampleSize)>MAX_DECODE_PIXELS
                    ||orientedWidth/options.inSampleSize>Math.max(1,targetWidth)*2
                    ||orientedHeight/options.inSampleSize>Math.max(1,targetHeight)*2)options.inSampleSize*=2;
            interrupted();owned=BitmapFactory.decodeFile(encoded.getAbsolutePath(),options);
            // The immutable entry already checked the encoded geometry. Options.outWidth
            // can describe the sampled result, so it cannot be compared to codedWidth.
            if(owned==null||owned.getWidth()<1||owned.getHeight()<1
                    ||(long)owned.getWidth()*owned.getHeight()>MAX_DECODE_PIXELS)
                throw new IllegalArgumentException("Decoded original exceeds its bounded source geometry");
            Matrix orientation=NativeStoryboardSourceImage.orientationMatrix(entry.getInt("exifOrientation"));
            if(!orientation.isIdentity()) {
                Bitmap rotated=Bitmap.createBitmap(owned,0,0,owned.getWidth(),owned.getHeight(),orientation,true);
                if(rotated!=owned){owned.recycle();owned=rotated;}
            }
            interrupted();Bitmap result=owned;owned=null;return result;
        }finally{if(owned!=null&&!owned.isRecycled())owned.recycle();}
    }

    /** Encode a bounded derivative; this method never recycles its caller's bitmap. */
    static JSONObject image(Bitmap source,String role,boolean exactAspect)throws Exception {
        Bitmap current=source;
        try {
            for(;;) {
                interrupted();ByteArrayOutputStream encoded=new ByteArrayOutputStream();
                if(!current.compress(Bitmap.CompressFormat.PNG,100,encoded))throw new IllegalStateException("Cannot encode native diagnostic image");
                byte[] bytes=encoded.toByteArray();
                if(((bytes.length+2L)/3)*4<=MAX_BASE64_IMAGE_CHARACTERS)
                    return new JSONObject().put("base64",Base64.encodeToString(bytes,Base64.NO_WRAP)).put("mimeType","image/png")
                            .put("width",current.getWidth()).put("height",current.getHeight()).put("role",role)
                            .put("sha256",sha256(bytes));
                int width=Math.max(1,current.getWidth()/2),height=Math.max(1,current.getHeight()/2);
                if(exactAspect) {
                    int divisor=gcd(current.getWidth(),current.getHeight()),multiple=divisor/2;
                    if(multiple<1)throw new IllegalArgumentException("Diagnostic image exceeds its encoded budget at the exact source aspect");
                    width=current.getWidth()/divisor*multiple;height=current.getHeight()/divisor*multiple;
                }
                if(width==current.getWidth()&&height==current.getHeight())throw new IllegalArgumentException("Native image exceeds its encoded budget");
                Bitmap smaller=Bitmap.createScaledBitmap(current,width,height,true);
                if(current!=source)current.recycle();current=smaller;
            }
        }finally{if(current!=source&&!current.isRecycled())current.recycle();}
    }
    static JSONObject sourceMetadata(ProjectStore.Project project,JSONObject entry)throws Exception {
        JSONObject result=new JSONObject().put("ok",true).put("projectId",project.id).put("assetId",entry.getString("assetId"))
                .put("sourceProjectUpdatedAt",project.updatedAt);
        for(String field:new String[]{"sourceSha256","bytes","codedWidth","codedHeight","exifOrientation","orientedWidth","orientedHeight"})result.put(field,entry.get(field));
        return result;
    }
    static JSONObject mapping(double scaleX,double scaleY,double offsetX,double offsetY)throws Exception {
        return new JSONObject().put("coordinateSpace","oriented-source-uv").put("scaleX",scaleX).put("scaleY",scaleY)
                .put("offsetX",offsetX).put("offsetY",offsetY);
    }
    static void requireInputs(Context context,ProjectStore projects,JSONObject request){
        if(context==null||projects==null||request==null)throw new IllegalArgumentException("Native context, project store and request are required");
    }
    static ProjectStore.Project project(ProjectStore projects,String id){
        ProjectStore.Project project=projects.get(id);if(project==null)throw new IllegalArgumentException("Unknown owned source project");return project;
    }
    static ProjectStore.Asset ownedImage(ProjectStore.Project project,String id){
        ProjectStore.Asset asset=project.asset(id);
        if(asset==null||!StoryboardBatchPlanner.isImage(asset))throw new IllegalArgumentException("Source image is not registered in the selected project");
        return asset;
    }
    static void requireSameRegistration(ProjectStore.Project current,ProjectStore.Asset frozen){
        ProjectStore.Asset actual=ownedImage(current,frozen.id);
        if(!java.util.Objects.equals(actual.uri,frozen.uri)||!java.util.Objects.equals(actual.mime,frozen.mime)
                ||!java.util.Objects.equals(actual.name,frozen.name)||!java.util.Objects.equals(actual.role,frozen.role)
                ||actual.generated!=frozen.generated||actual.sizeBytes!=frozen.sizeBytes)
            throw new IllegalStateException("SOURCE_IMAGE_CHANGED: native original registration changed during inspection");
    }
    static String identity(JSONObject request,String key){return identityValue(request.opt(key));}
    static String identityValue(Object value){
        if(!(value instanceof String)||!((String)value).matches("[A-Za-z0-9_.:-]{1,180}"))
            throw new IllegalArgumentException("A bounded owned native identity is required");
        return(String)value;
    }
    static String optionalDigest(JSONObject request,String key){
        if(!request.has(key))return null;
        Object value=request.opt(key);
        if(!(value instanceof String)||!((String)value).matches("[a-f0-9]{64}"))throw new IllegalArgumentException(key+" must be a lowercase SHA-256");
        return(String)value;
    }
    static int dimension(JSONObject request,int fallback,int maximum){
        if(!request.has("maxDimension"))return fallback;
        Object raw=request.opt("maxDimension");
        if(!(raw instanceof Number))throw new IllegalArgumentException("maxDimension must be an integer");
        double number=((Number)raw).doubleValue();
        if(!Double.isFinite(number)||number!=Math.rint(number)||number<128||number>maximum)
            throw new IllegalArgumentException("maxDimension must be between 128 and "+maximum);
        return(int)number;
    }
    static String sha256(byte[] bytes)throws Exception {
        StringBuilder out=new StringBuilder(64);for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))out.append(String.format(java.util.Locale.US,"%02x",b&255));return out.toString();
    }
    static int gcd(int first,int second){while(second!=0){int next=first%second;first=second;second=next;}return first;}
    static void interrupted()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Native original inspection cancelled");}
    private static double[] crop(JSONObject request)throws Exception {
        if(!request.has("crop"))return new double[]{0,0,1,1};
        JSONObject crop=request.optJSONObject("crop");if(crop==null)throw new IllegalArgumentException("crop must be a normalized original-oriented rectangle");
        double[] values=new double[4];String[] names={"left","top","right","bottom"};
        for(int i=0;i<4;i++) {
            Object raw=crop.opt(names[i]);if(!(raw instanceof Number))throw new IllegalArgumentException("Crop edges must be finite numbers");
            values[i]=((Number)raw).doubleValue();if(!Double.isFinite(values[i])||values[i]<0||values[i]>1)throw new IllegalArgumentException("Crop edges must lie in original UV [0,1]");
        }
        if(values[2]<=values[0]||values[3]<=values[1])throw new IllegalArgumentException("Crop must have positive original-oriented area");
        return values;
    }
    private static void drawOverlay(Canvas canvas,Rig2DProfile rig,double[] crop){
        Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);paint.setColor(rig.black?Color.YELLOW:Color.CYAN);paint.setStrokeWidth(2);paint.setStyle(Paint.Style.STROKE);
        outline(canvas,rig.outline,crop,paint);outline(canvas,rig.swordOutline,crop,paint);
        for(int[] bone:Rig2DProfile.BONES)canvas.drawLine(x(rig.joints[bone[0]][0],crop,canvas),y(rig.joints[bone[0]][1],crop,canvas),
                x(rig.joints[bone[1]][0],crop,canvas),y(rig.joints[bone[1]][1],crop,canvas),paint);
        paint.setStyle(Paint.Style.FILL);for(float[] joint:rig.joints)canvas.drawCircle(x(joint[0],crop,canvas),y(joint[1],crop,canvas),3,paint);
    }
    private static void outline(Canvas canvas,float[][] points,double[] crop,Paint paint){
        if(points.length<3)return;Path path=new Path();path.moveTo(x(points[0][0],crop,canvas),y(points[0][1],crop,canvas));
        for(int i=1;i<points.length;i++)path.lineTo(x(points[i][0],crop,canvas),y(points[i][1],crop,canvas));path.close();canvas.drawPath(path,paint);
    }
    private static float x(float u,double[] crop,Canvas canvas){return(float)((u-crop[0])/(crop[2]-crop[0])*canvas.getWidth());}
    private static float y(float v,double[] crop,Canvas canvas){return(float)((v-crop[1])/(crop[3]-crop[1])*canvas.getHeight());}
}
