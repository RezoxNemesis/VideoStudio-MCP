package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.util.AtomicFile;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.UUID;

/** Full-resolution native still export. No preview resize, video encoder or MediaStore publication. */
public final class SketchStillExport {
    public static final String CAPABILITY_JSON = "{\"minimumNativeVersion\":\"4.10.22\",\"action\":\"sketch_render\",\"format\":\"png\",\"frameField\":\"frame\",\"source\":\"Exact revision and explicit frame of an existing native sketch; full frozen canvas and owned dependencies\",\"fullResolution\":true,\"maximumAxis\":2048,\"maximumPixels\":4194304,\"maximumBytes\":8388608,\"preservesAlpha\":true,\"sourceUnchanged\":true,\"durableOperationId\":true,\"outputProject\":\"New deterministic owned project\",\"outputAssetCount\":1,\"outputClipCount\":0,\"destinationImportAction\":\"project_import_asset\",\"manualControls\":\"Sketch Studio > File > Save current frame as PNG; Media Bin > Import from project\",\"legacyMp4DefaultUnchanged\":true,\"newCanonicalAction\":false,\"limitations\":\"Saves existing visible native artwork, including source-assisted pixels; no redraw, hidden-view inference, automatic background removal, codec execution or artistic acceptance\"}";
    private static final long MAX_PNG=8L*1024*1024, RESERVE=64L*1024*1024;
    private static final int MAX_SNAPSHOT=SketchDocument.MAX_BYTES+16*1024*1024+4096, MAX_RECEIPT=64*1024;
    private static final String SCHEMA="videostudio.sketch.still.png.v1", ASSET_ID="sketch_still_output";
    private SketchStillExport(){}

    public static JSONObject capabilities()throws Exception{return new JSONObject(CAPABILITY_JSON);}

    /** Strict, detached request. This new format does not normalize legacy MP4 requests. */
    public static JSONObject validate(JSONObject input)throws Exception {
        if(input==null)throw SketchDocument.bad("PNG request required");
        SketchDocument.fields(input,"sketchId,expectedRevision,operationId,format,frame");
        String id=SketchDocument.id(input,"sketchId"),operation=SketchDocument.id(input,"operationId");
        long revision=SketchDocument.integer(input,"expectedRevision",1,Long.MAX_VALUE-1);
        if(!(input.get("format") instanceof String)||!"png".equals(input.getString("format")))
            throw SketchDocument.bad("Use explicit format png with a frame; omit format for legacy video");
        long frame=SketchDocument.integer(input,"frame",0,SketchDocument.MAX_FRAMES-1);
        return new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId",operation).put("format","png").put("frame",frame);
    }

    public static String outputProjectId(String sketchId,String operationId)throws Exception {
        SketchDocument.id(new JSONObject().put("id",sketchId),"id");SketchDocument.id(new JSONObject().put("id",operationId),"id");
        return UUID.nameUUIDFromBytes(("sketch-output/"+sketchId+"/"+operationId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Read-only admission: exact frozen retries are checked before current drawing revisions. */
    public static JSONObject admit(Context context,JSONObject input)throws Exception {
        JSONObject request=validate(input);cancelled();
        synchronized(SketchNativeExport.class){
            File generation=generation(context,request);try(ProjectStore projects=new ProjectStore(context)){
            JSONObject snapshot=existingSnapshot(context,generation,request),commit=projects.sketchStillCommit(operationKey(request));
            if(commit!=null){
                requireSnapshot(snapshot);verifyCommitted(context,projects,generation,request,snapshot,commit);return request;
            }
            if(completionExists(generation))throw changed("Completion has no atomic ownership witness");
            if(snapshot==null){
                if(projects.get(outputProjectId(request.getString("sketchId"),request.getString("operationId")))!=null)throw changed("Output project identity already exists");
                source(context,request);
            }else{
                JSONObject image=existingImage(context,generation,request,snapshot);
                File png=png(generation,request);if(png.exists()){if(image==null)throw changed("Published PNG has no pinned image receipt");verifyPng(context,png,image);}
            }
            cancelled();return request;
            }
        }
    }

    public static JSONObject execute(Context context,JSONObject input,SketchNativeExport.Progress progress)throws Exception {
        return prepare(context,input,progress).metadata;
    }
    public static JSONObject execute(Context context,JSONObject input)throws Exception{return execute(context,input,(done,total)->{});}

    public static SketchNativeExport.Prepared prepare(Context context,JSONObject input,SketchNativeExport.Progress progress)throws Exception {
        JSONObject request=validate(input);if(progress==null)throw SketchDocument.bad("PNG progress callback required");cancelled();
        synchronized(SketchNativeExport.class){
            cancelled();File generation=generation(context,request);try(ProjectStore projects=new ProjectStore(context)){
            JSONObject snapshot=existingSnapshot(context,generation,request),commit=projects.sketchStillCommit(operationKey(request));
            if(commit!=null){
                requireSnapshot(snapshot);JSONObject result=verifyCommitted(context,projects,generation,request,snapshot,commit);
                if(!completionExists(generation))writeCompletion(context,generation,commit);
                return prepared(projects.get(result.getString("projectId")),generation,request,result);
            }
            if(completionExists(generation))throw changed("Completion has no atomic ownership witness");
            if(projects.get(outputProjectId(request.getString("sketchId"),request.getString("operationId")))!=null)throw changed("Output project identity already exists");
            if(snapshot==null){
                JSONObject document=source(context,request);storage(context,document);directory(context,generation);
                JSONObject dependencies=SketchPaintArchive.freezeDependencies(context,document,new File(generation,"assets"));cancelled();
                snapshot=new JSONObject().put("requestSha256",requestHash(request)).put("request",request).put("document",document)
                        .put("dependencyInventory",dependencies).put("documentSha256",jsonHash(document));
                immutableJson(context,new File(generation,"snapshot.json"),snapshot,MAX_SNAPSHOT);
                // Validate the actual persisted representation, never a transient numeric hash.
                snapshot=existingSnapshot(context,generation,request);
            }
            JSONObject document=snapshot.getJSONObject("document");storage(context,document);progress.onProgress(0,1);cancelled();
            JSONObject image=existingImage(context,generation,request,snapshot);File output=png(generation,request);
            if(output.exists()){
                if(image==null)throw changed("Published PNG has no pinned image receipt");verifyPng(context,output,image);
            }else image=capture(context,generation,request,snapshot,image);
            // A published image remains immutable even when this callback cancels a writer.
            progress.onProgress(1,1);cancelled();verifySnapshot(context,generation,request,snapshot);verifyPng(context,output,image);
            ProjectStore.Project project=new ProjectStore.Project();project.id=outputProjectId(request.getString("sketchId"),request.getString("operationId"));
            project.name=document.getString("name")+" · PNG frame "+request.getInt("frame")+" r"+request.getLong("expectedRevision");
            JSONObject receipt=baseReceipt(request,snapshot,image,project,generation);
            ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=ASSET_ID;asset.name=output.getName();asset.uri=Uri.fromFile(output).toString();asset.mime="image/png";
            asset.generated=true;asset.role="generated_sketch_still";asset.sizeBytes=output.length();asset.durationMs=0;asset.seekable=true;asset.persistedReadAccess=true;
            asset.generationMetadata=assetProof(receipt);project.assets.add(asset);
            JSONObject committed=projects.createSketchStillOutput(project,operationKey(request),receipt);
            writeCompletion(context,generation,committed);
            JSONObject result=verifyCommitted(context,projects,generation,request,snapshot,committed).put("replayed",false);
            return prepared(projects.get(project.id),generation,request,result);
            }
        }
    }

    /** Validate a job's EXISTING committed PNG. Never render, import, repair or publish anything. */
    public static JSONObject verify(Context context,JSONObject supplied)throws Exception {
        if(supplied==null)throw changed("PNG receipt is required");
        if(supplied.has("replayed")&&!(supplied.get("replayed") instanceof Boolean))throw changed("PNG replay flag must be boolean");
        JSONObject request=validate(new JSONObject().put("sketchId",supplied.get("sketchId")).put("expectedRevision",supplied.get("sourceRevision"))
                .put("operationId",supplied.get("operationId")).put("format",supplied.get("format")).put("frame",supplied.get("frame")));
        cancelled();synchronized(SketchNativeExport.class){
            File generation=generation(context,request);JSONObject snapshot=existingSnapshot(context,generation,request);requireSnapshot(snapshot);
            try(ProjectStore projects=new ProjectStore(context)){
            JSONObject commit=projects.sketchStillCommit(operationKey(request));if(commit==null)throw changed("PNG has not committed");
            JSONObject result=verifyCommitted(context,projects,generation,request,snapshot,commit);
            for(String field:new String[]{"projectId","outputProjectId","assetId","sketchId","sourceRevision","sketchRevision","operationId","format","mime","sha256","outputSha256","bytes","width","height","frame","documentSha256","requestSha256"})
                if(!SketchDocument.canonical(result.get(field)).equals(SketchDocument.canonical(supplied.get(field))))throw changed("Delivered PNG receipt differs: "+field);
            return result.put("replayed",supplied.optBoolean("replayed",false));
            }
        }
    }

    private static SketchNativeExport.Prepared prepared(ProjectStore.Project project,File generation,JSONObject request,JSONObject receipt)throws Exception {
        if(project==null)throw changed("Output project was removed");
        return new SketchNativeExport.Prepared(project,png(generation,request),"source:"+receipt.getInt("width")+"x"+receipt.getInt("height"),"png",receipt,true,generation);
    }

    private static JSONObject source(Context context,JSONObject request)throws Exception {
        cancelled();try(SketchProjectStore sketches=new SketchProjectStore(context)){
            JSONObject document=sketches.get(request.getString("sketchId"));SketchProjectStore.requireRevision(document,request.getLong("expectedRevision"));
            SketchDocument.validate(document);frame(request,document);sketches.verifyReferences(document);cancelled();return SketchDocument.copy(document);
        }
    }
    private static JSONObject existingSnapshot(Context context,File generation,JSONObject request)throws Exception {
        File file=new File(generation,"snapshot.json");owned(context,file);if(!file.exists())return null;
        JSONObject snapshot=readJson(context,file,MAX_SNAPSHOT);
        if(!requestHash(request).equals(snapshot.getString("requestSha256")))throw new IllegalStateException("SKETCH_OPERATION_ID_CONFLICT");
        verifySnapshot(context,generation,request,snapshot);return snapshot;
    }
    private static void verifySnapshot(Context context,File generation,JSONObject request,JSONObject snapshot)throws Exception {
        JSONObject document=snapshot.getJSONObject("document");SketchDocument.validate(document);frame(request,document);
        if(!request.getString("sketchId").equals(document.getString("id"))||request.getLong("expectedRevision")!=document.getLong("revision")||
                !requestHash(request).equals(snapshot.getString("requestSha256"))||!jsonHash(document).equals(snapshot.getString("documentSha256")))throw changed("Frozen source identity differs");
        SketchPaintArchive.verifyFrozen(context,document,new File(generation,"assets"),snapshot.getJSONObject("dependencyInventory"));cancelled();
    }
    private static void frame(JSONObject request,JSONObject document)throws Exception {
        if(request.getLong("frame")>=document.getInt("frameCount"))throw SketchDocument.bad("PNG frame is outside the frozen sketch clock");
    }
    private static void requireSnapshot(JSONObject snapshot){if(snapshot==null)throw changed("Frozen source snapshot was removed");}
    private static JSONObject existingImage(Context context,File generation,JSONObject request,JSONObject snapshot)throws Exception {
        File file=new File(generation,"image.json");if(!file.exists())return null;
        JSONObject image=readJson(context,file,MAX_RECEIPT);
        if(!requestHash(request).equals(image.getString("requestSha256"))||!snapshot.getString("documentSha256").equals(image.getString("documentSha256"))||
                !png(generation,request).getName().equals(image.getString("file"))||image.getInt("frame")!=request.getInt("frame")||
                image.getInt("width")!=snapshot.getJSONObject("document").getInt("width")||image.getInt("height")!=snapshot.getJSONObject("document").getInt("height"))throw changed("Pinned image identity differs");
        imageSize(image);return image;
    }

    private static JSONObject capture(Context context,File generation,JSONObject request,JSONObject snapshot,JSONObject previous)throws Exception {
        JSONObject document=snapshot.getJSONObject("document");File temporary=File.createTempFile(".still_",".part",generation);
        Bitmap bitmap=null;
        try {
            cancelled();try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,document,new File(generation,"assets"))){bitmap=renderer.render(request.getInt("frame"));}
            cancelled();if(bitmap.getWidth()!=document.getInt("width")||bitmap.getHeight()!=document.getInt("height"))throw changed("Native renderer dimensions differ");
            try(BoundedOutput out=new BoundedOutput(temporary)){
                boolean encoded=bitmap.compress(Bitmap.CompressFormat.PNG,100,out);cancelled();if(!encoded)throw new IOException("Native still PNG encoding failed");out.getFD().sync();
            }
            bitmap.recycle();bitmap=null;
            JSONObject image=new JSONObject().put("requestSha256",requestHash(request)).put("documentSha256",snapshot.getString("documentSha256"))
                    .put("file",png(generation,request).getName()).put("frame",request.getInt("frame")).put("width",document.getInt("width")).put("height",document.getInt("height"))
                    .put("bytes",temporary.length()).put("sha256",hash(temporary));verifyPng(context,temporary,image);
            if(previous!=null&&!SketchDocument.canonical(previous).equals(SketchDocument.canonical(image)))throw changed("Recovered native capture differs from its pinned image");
            if(previous==null)immutableJson(context,new File(generation,"image.json"),image,MAX_RECEIPT);
            cancelled();publishImmutable(context,temporary,png(generation,request),image.getLong("bytes"),image.getString("sha256"));return image;
        }catch(InterruptedIOException interrupted){InterruptedException cancelled=new InterruptedException("Sketch still export cancelled; source retained");cancelled.initCause(interrupted);throw cancelled;}
        catch(Exception failure){
            if(Thread.currentThread().isInterrupted()&&!(failure instanceof InterruptedException)){
                InterruptedException interrupted=new InterruptedException("Sketch still export cancelled; source retained");interrupted.initCause(failure);throw interrupted;
            }
            throw failure;
        }
        finally{if(bitmap!=null)bitmap.recycle();Files.deleteIfExists(temporary.toPath());}
    }

    private static JSONObject baseReceipt(JSONObject request,JSONObject snapshot,JSONObject image,ProjectStore.Project project,File generation)throws Exception {
        return new JSONObject().put("ok",true).put("schema",SCHEMA).put("format","png").put("mime","image/png").put("encoded",false)
                .put("sketchId",request.getString("sketchId")).put("sourceRevision",request.getLong("expectedRevision")).put("sketchRevision",request.getLong("expectedRevision"))
                .put("operationId",request.getString("operationId")).put("frame",request.getInt("frame")).put("requestSha256",requestHash(request))
                .put("documentSha256",snapshot.getString("documentSha256")).put("dependencyInventorySha256",jsonHash(snapshot.getJSONObject("dependencyInventory")))
                .put("projectId",project.id).put("outputProjectId",project.id).put("projectName",project.name).put("outputProjectName",project.name)
                .put("assetId",ASSET_ID).put("assetName",image.getString("file")).put("width",image.getInt("width")).put("height",image.getInt("height"))
                .put("bytes",image.getLong("bytes")).put("sha256",image.getString("sha256")).put("outputSha256",image.getString("sha256"))
                .put("outputUri",Uri.fromFile(png(generation,request)).toString())
                .put("renderer","android-bitmap-canvas").put("fullResolution",true).put("preservesAlpha",true).put("sourceUnchanged",true)
                .put("outputAssetCount",1).put("outputClipCount",0).put("replayed",false).put("galleryAccess",false)
                .put("artworkOrigin","Existing native sketch; copied reference pixels remain source-assisted");
    }

    private static JSONObject assetProof(JSONObject receipt)throws Exception {
        JSONObject proof=new JSONObject();for(String key:new String[]{"schema","sketchId","sourceRevision","sketchRevision","operationId","frame","requestSha256","documentSha256","dependencyInventorySha256","sha256","outputSha256","width","height","artworkOrigin"})proof.put(key,receipt.get(key));return proof;
    }
    private static JSONObject verifyCommitted(Context context,ProjectStore projects,File generation,JSONObject request,JSONObject snapshot,JSONObject commit)throws Exception {
        String expectedProject=outputProjectId(request.getString("sketchId"),request.getString("operationId"));JSONObject image=existingImage(context,generation,request,snapshot);
        if(image==null||!SCHEMA.equals(commit.getString("schema"))||!"png".equals(commit.getString("format"))||!"image/png".equals(commit.getString("mime"))||
                !requestHash(request).equals(commit.getString("requestSha256"))||!expectedProject.equals(commit.getString("projectId"))||!expectedProject.equals(commit.getString("outputProjectId"))||
                !request.getString("sketchId").equals(commit.getString("sketchId"))||!request.getString("operationId").equals(commit.getString("operationId"))||
                request.getLong("expectedRevision")!=commit.getLong("sourceRevision")||request.getLong("expectedRevision")!=commit.getLong("sketchRevision")||
                !ASSET_ID.equals(commit.getString("assetId"))||!snapshot.getString("documentSha256").equals(commit.getString("documentSha256"))||
                !jsonHash(snapshot.getJSONObject("dependencyInventory")).equals(commit.getString("dependencyInventorySha256")))throw changed("Committed ownership identity differs");
        for(String key:new String[]{"sha256","bytes","width","height","frame"})if(!SketchDocument.canonical(commit.get(key)).equals(SketchDocument.canonical(image.get(key))))throw changed("Committed PNG receipt differs");
        File output=png(generation,request);verifyPng(context,output,image);
        if(!Uri.fromFile(output).toString().equals(commit.getString("outputUri"))||!commit.getString("sha256").equals(commit.getString("outputSha256")))throw changed("Committed output path differs");
        if(completionExists(generation)&&!SketchDocument.canonical(readCompletion(context,generation)).equals(SketchDocument.canonical(commit)))throw changed("Completed receipt was changed");
        ProjectStore.Project project=projects.get(expectedProject);if(project==null)throw changed("Output project was removed");ProjectStore.Asset asset=project.asset(ASSET_ID);
        if(asset==null||!asset.generated||!"generated_sketch_still".equals(asset.role)||!"image/png".equals(asset.mime)||asset.sizeBytes!=image.getLong("bytes")||
                !Uri.fromFile(output).toString().equals(asset.uri)||!SketchDocument.canonical(assetProof(commit)).equals(SketchDocument.canonical(asset.generationMetadata)))throw changed("Owned PNG asset was changed or removed");
        cancelled();return SketchDocument.copy(commit).put("updatedAt",project.updatedAt).put("projectName",project.name).put("outputProjectName",project.name).put("assetName",asset.name).put("replayed",true);
    }

    private static File generation(Context context,JSONObject request)throws Exception {
        File directory=new File(context.getFilesDir(),"sketch_studio/"+request.getString("sketchId")+"/exports/"+request.getString("operationId"));owned(context,directory);return directory;
    }
    private static File png(File generation,JSONObject request)throws Exception{return new File(generation,String.format(Locale.ROOT,"frame_%04d.png",request.getInt("frame")));}
    private static String operationKey(JSONObject request)throws Exception{return SketchDocument.sha256((request.getString("sketchId")+"/"+request.getString("operationId")).getBytes(StandardCharsets.UTF_8));}
    private static String requestHash(JSONObject request)throws Exception{return jsonHash(request);}
    private static String jsonHash(JSONObject object)throws Exception{return SketchDocument.sha256(SketchDocument.canonical(object).getBytes(StandardCharsets.UTF_8));}
    private static void imageSize(JSONObject image)throws Exception {
        SketchDocument.integer(image,"bytes",1,MAX_PNG);SketchDocument.integer(image,"width",1,2048);SketchDocument.integer(image,"height",1,2048);
        if(!(image.get("sha256") instanceof String)||!image.getString("sha256").matches("[a-f0-9]{64}"))throw changed("PNG hash is invalid");
    }
    private static void verifyPng(Context context,File file,JSONObject image)throws Exception {
        imageSize(image);regular(context,file);if(file.length()!=image.getLong("bytes")||!image.getString("sha256").equals(hash(file)))throw changed("PNG bytes were changed");
        byte[] signature=new byte[8];try(InputStream in=new FileInputStream(file)){if(in.read(signature)!=8||!Arrays.equals(signature,new byte[]{(byte)137,80,78,71,13,10,26,10}))throw changed("Output is not PNG");}
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
        if(bounds.outWidth!=image.getInt("width")||bounds.outHeight!=image.getInt("height")||!"image/png".equals(bounds.outMimeType))throw changed("PNG dimensions or MIME differ");
        cancelled();Bitmap decoded=BitmapFactory.decodeFile(file.getPath());if(decoded==null)throw changed("PNG cannot decode");
        try{if(decoded.getWidth()!=image.getInt("width")||decoded.getHeight()!=image.getInt("height"))throw changed("PNG decoded dimensions differ");}finally{decoded.recycle();}cancelled();
    }
    private static void storage(Context context,JSONObject document)throws Exception {
        long frame=(long)document.getInt("width")*document.getInt("height")*4;
        if(context.getFilesDir().getUsableSpace()<RESERVE+frame+MAX_PNG)throw new IllegalStateException("SKETCH_STILL_STORAGE: insufficient free space");cancelled();
    }
    private static void directory(Context context,File directory)throws Exception {owned(context,directory);Files.createDirectories(directory.toPath());if(!directory.isDirectory())throw changed("Output directory is unavailable");}
    private static void owned(Context context,File file)throws Exception {
        java.nio.file.Path lexical=file.toPath().toAbsolutePath();
        if(!lexical.normalize().equals(lexical))throw changed("Unnormalized private path rejected");
        java.nio.file.Path trusted=context.getFilesDir().toPath().toAbsolutePath().normalize(),canonicalRoot=context.getFilesDir().getCanonicalFile().toPath();
        java.nio.file.Path canonical=file.getCanonicalFile().toPath();
        if(canonical.equals(canonicalRoot)||!canonical.startsWith(canonicalRoot))throw changed("Private path rejected");
        // Android may alias an ancestor of filesDir. Trust that context root while
        // refusing every symlink created INSIDE the owned operation subtree.
        java.nio.file.Path boundary=lexical.startsWith(trusted)?trusted:canonicalRoot;
        if(!lexical.startsWith(boundary))throw changed("Private lexical path rejected");
        for(java.nio.file.Path cursor=lexical;cursor!=null&&!cursor.equals(boundary);cursor=cursor.getParent())
            if(Files.isSymbolicLink(cursor))throw changed("Symlink path rejected");
    }
    private static void regular(Context context,File file)throws Exception {owned(context,file);if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw changed("Owned file unavailable");}
    private static JSONObject readJson(Context context,File file,int limit)throws Exception {
        regular(context,file);if(file.length()>limit)throw changed("Metadata exceeds budget");try(InputStream in=new FileInputStream(file)){return parseJson(in,limit);}
    }
    private static JSONObject parseJson(InputStream in,int limit)throws Exception {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;){cancelled();if(bytes.size()+n>limit)throw changed("Metadata exceeds budget");bytes.write(buffer,0,n);}return new JSONObject(bytes.toString("UTF-8"));
    }
    private static void immutableJson(Context context,File target,JSONObject value,int limit)throws Exception {
        owned(context,target);byte[] bytes=SketchDocument.canonical(value).getBytes(StandardCharsets.UTF_8);if(bytes.length>limit)throw changed("Metadata exceeds budget");
        String expected=SketchDocument.sha256(bytes);if(target.exists()){regular(context,target);if(target.length()!=bytes.length||!expected.equals(hash(target)))throw changed("Immutable metadata was changed");return;}
        File temporary=File.createTempFile(".still_metadata_",".part",target.getParentFile());try{try(FileOutputStream out=new FileOutputStream(temporary)){cancelled();out.write(bytes);out.getFD().sync();}cancelled();publishImmutable(context,temporary,target,bytes.length,expected);}finally{Files.deleteIfExists(temporary.toPath());}
    }
    private static void publishImmutable(Context context,File temporary,File target,long bytes,String expected)throws Exception {
        owned(context,target);cancelled();try{Files.createLink(target.toPath(),temporary.toPath());}
        catch(FileAlreadyExistsException collision){regular(context,target);if(target.length()!=bytes||!expected.equals(hash(target)))throw changed("Immutable publication already differs");}
        // No replacement/copy fallback: a partial public image must never be observable.
    }
    private static boolean completionExists(File generation){File file=new File(generation,"completed.json");return file.exists()||new File(file.getPath()+".bak").exists();}
    private static JSONObject readCompletion(Context context,File generation)throws Exception {
        File file=new File(generation,"completed.json");owned(context,file);owned(context,new File(file.getPath()+".bak"));owned(context,new File(file.getPath()+".new"));
        try(InputStream in=new AtomicFile(file).openRead()){return parseJson(in,MAX_RECEIPT);}
    }
    private static void writeCompletion(Context context,File generation,JSONObject commit)throws Exception {
        File file=new File(generation,"completed.json");owned(context,file);owned(context,new File(file.getPath()+".bak"));owned(context,new File(file.getPath()+".new"));
        if(completionExists(generation)){if(!SketchDocument.canonical(readCompletion(context,generation)).equals(SketchDocument.canonical(commit)))throw changed("Completed receipt was changed");return;}
        byte[] bytes=SketchDocument.canonical(commit).getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_RECEIPT)throw changed("Receipt exceeds budget");cancelled();
        AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;try{out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out);}catch(Exception error){if(out!=null)atomic.failWrite(out);throw error;}
    }
    private static String hash(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] bytes=new byte[32768];for(int n;(n=in.read(bytes))!=-1;){cancelled();digest.update(bytes,0,n);}}
        StringBuilder text=new StringBuilder();for(byte b:digest.digest())text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();
    }
    private static void cancelled()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Sketch still export cancelled; source retained");}
    private static IllegalStateException changed(String detail){return new IllegalStateException("SKETCH_STILL_OUTPUT_CHANGED: "+detail);}
    private static final class BoundedOutput extends FileOutputStream {
        long count;BoundedOutput(File file)throws IOException{super(file);}
        private void reserve(int size)throws IOException{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Sketch still export cancelled");if(size<0||count>MAX_PNG-size)throw new IOException("SKETCH_STILL_BUDGET: PNG exceeds 8 MiB");count+=size;}
        @Override public void write(int value)throws IOException{reserve(1);super.write(value);}
        @Override public void write(byte[] bytes)throws IOException{write(bytes,0,bytes.length);}
        @Override public void write(byte[] bytes,int offset,int length)throws IOException{reserve(length);super.write(bytes,offset,length);}
    }
}
