package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;

/**
 * Imports a source-bound JSONL frame manifest entirely through the existing
 * native Sketch Studio edit facade. No Gallery browsing, no network upload of
 * artwork, no replacement of the user's original projects.
 */
public final class SketchFrameManifestImporter {
    private static final int MAX_MANIFEST_BYTES=2*1024*1024;
    private static final int MAX_VIDEO_BYTES=64*1024*1024;
    private static final String SCHEMA="videostudio.sketch.frame-ops.v1";
    private static final String[] EXTRA_LAYERS={"bg_ink","combat_black","combat_white","action_ink"};

    public interface Progress {
        void completed(int frames,int total) throws Exception;
    }
    private SketchFrameManifestImporter(){}

    public static JSONObject importFromWorkspace(Context context,JSONObject params,
                                                  Progress progress) throws Exception {
        SketchDocument.fields(params,"sketchId,manifestName,videoName,expectedSourceSha256,expectedFrameCount");
        String id=SketchDocument.id(params,"sketchId");
        String manifestName=checkedName(params.getString("manifestName"),".jsonl");
        String videoName=checkedName(params.getString("videoName"),".mp4");
        String expectedHash=params.getString("expectedSourceSha256").toLowerCase(java.util.Locale.ROOT);
        if(!expectedHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Source SHA-256 must be explicit");
        int expectedCount=(int)SketchDocument.integer(params,"expectedFrameCount",1,1200);
        DriveWorkspaceProvider drive=new DriveWorkspaceProvider(context);
        byte[] manifest;
        String actualHash;
        try(InputStream input=drive.openLinkedSourceFile(manifestName)){
            manifest=bounded(input,MAX_MANIFEST_BYTES);
        }
        try(InputStream input=drive.openLinkedSourceFile(videoName)){
            MessageDigest sha=MessageDigest.getInstance("SHA-256");
            byte[] buffer=new byte[32768];int n;long total=0;
            while((n=input.read(buffer))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException();
                total+=n;
                if(total>MAX_VIDEO_BYTES)throw new IllegalArgumentException("Owned MP4 exceeds 64 MiB import budget");
                sha.update(buffer,0,n);
            }
            if(total<1024)throw new IllegalArgumentException("Video source is incomplete");
            actualHash=hex(sha.digest());
        }
        if(!expectedHash.equals(actualHash))
            throw new IllegalStateException("VIDEO_SOURCE_SHA256_MISMATCH: no sketch changes made");
        return applyBytes(context,manifest,actualHash,id,expectedCount,progress);
    }

    /** Bounded pure-source entrypoint also exercised by native Robolectric tests. */
    static JSONObject applyBytes(Context context,byte[] data,String sourceSha,String sketchId,
                                 int expectedCount,Progress progress) throws Exception {
        if(data==null||data.length<30||data.length>MAX_MANIFEST_BYTES)
            throw new IllegalArgumentException("Source sketch manifest exceeds bounded size");
        if(sourceSha==null||!sourceSha.matches("[a-f0-9]{64}"))
            throw new IllegalArgumentException("Verified media hash is required");
        String manifestSha=hex(MessageDigest.getInstance("SHA-256").digest(data));
        String raw=new String(data,StandardCharsets.UTF_8);
        java.io.BufferedReader scan=new java.io.BufferedReader(new java.io.StringReader(raw));
        String firstLine=scan.readLine();
        if(firstLine==null)throw new IllegalArgumentException("Sketch manifest is empty");
        JSONObject first=new JSONObject(firstLine),header=first.getJSONObject("header");
        if(!SCHEMA.equals(header.getString("schema"))
                ||!sketchId.equals(header.getString("targetSketchId"))
                ||!sourceSha.equals(header.getString("sourceSha256")))
            throw new IllegalArgumentException("Manifest/source/sketch identities do not match");
        int count=header.getInt("frameCount"),width=header.getInt("targetWidth"),
                height=header.getInt("targetHeight"),fps=header.getInt("fps");
        if(count!=expectedCount||count<1||count>1200
                ||width<256||width>1280||height<256||height>1280
                ||(width&1)!=0||(height&1)!=0
                ||(long)width*height>720L*1280L
                ||fps!=24&&fps!=30&&fps!=60)
            throw new IllegalArgumentException("Sketch native frame/raster budget mismatch");
        JSONArray background=first.getJSONArray("backgroundOperations");
        if(background.length()<1||background.length()>64)
            throw new IllegalArgumentException("Sketch background count is invalid");
        HashSet<String> allIds=new HashSet<>();
        checkOps(background,0,count,true,allIds);
        for(int f=0;f<count;f++){
            String line=scan.readLine();
            if(line==null||line.length()>80000)throw new IllegalArgumentException("Video frame row missing or too large: "+f);
            JSONObject row=new JSONObject(line);
            if(row.getInt("frame")!=f)throw new IllegalArgumentException("Video frame order mismatch");
            JSONArray commands=row.getJSONArray("operations");
            if(commands.length()<1||commands.length()>16)
                throw new IllegalArgumentException("Frame must contain 1..16 drawing objects");
            checkOps(commands,f,count,false,allIds);
        }
        if(scan.readLine()!=null)throw new IllegalArgumentException("Unexpected frames after declared video length");

        // No user file or sketch is changed until full manifest and MP4 proof passes.
        String binding=sourceSha+":"+manifestSha;
        SharedPreferences prefs=context.getSharedPreferences("sketch_manifest_import_v1",Context.MODE_PRIVATE);
        String previous=prefs.getString(sketchId,"");
        JSONObject present=readExisting(context,sketchId);
        if(!previous.isEmpty()&&!previous.equals(binding))
            throw new IllegalStateException("SKETCH_SOURCE_CHANGED: preserving existing source-bound drawing");
        if(previous.isEmpty()&&present!=null)
            throw new IllegalStateException("SKETCH_ALREADY_EXISTS: refusing to replace an existing user drawing");
        if(previous.isEmpty()&&!prefs.edit().putString(sketchId,binding).commit())
            throw new IllegalStateException("Unable to retain source manifest identity");
        if(present==null){
            JSONObject create=new JSONObject().put("sketchId",sketchId)
                    .put("operationId","manifest_create_"+manifestSha.substring(0,12))
                    .put("name","Sword Duel • 192 Source Frames (Ink Sketch)")
                    .put("width",width).put("height",height).put("fps",fps)
                    .put("frameCount",count).put("background","#FFF7EFE7");
            SketchEditOps.execute(context,"sketch_create",create);
        }
        JSONArray layers=new JSONArray();
        String[] titles={"Ruined city atmosphere","Black swordsman","White swordsman","Original weapon and impact linework"};
        for(int i=0;i<EXTRA_LAYERS.length;i++)
            layers.put(new JSONObject().put("type","add_layer").put("layer",
                    new JSONObject().put("id",EXTRA_LAYERS[i]).put("name",titles[i])
                            .put("visible",true).put("locked",false).put("opacity",1)
                            .put("blendMode","normal").put("objects",new JSONArray())));
        applyMissing(context,sketchId,layers,"manifest_layers_"+manifestSha.substring(0,10));
        applyMissing(context,sketchId,background,"manifest_background_"+manifestSha.substring(0,10));

        java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.StringReader(raw));
        reader.readLine();
        JSONArray batch=new JSONArray();int batchStart=0,imported=0;
        for(int f=0;f<count;f++){
            JSONObject row=new JSONObject(reader.readLine());
            JSONArray ops=row.getJSONArray("operations");
            for(int i=0;i<ops.length();i++)batch.put(ops.getJSONObject(i));
            if(batch.length()>=28||f==count-1){
                applyMissing(context,sketchId,batch,
                        "manifest_"+manifestSha.substring(0,10)+"_frame_"+batchStart);
                batch=new JSONArray();batchStart=f+1;
                if(progress!=null)progress.completed(f+1,count);
            }
            imported++;
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        }
        JSONObject finalDoc=readExisting(context,sketchId);
        if(finalDoc==null)throw new IllegalStateException("Imported sketch disappeared");
        HashSet<String> finalIds=objectIds(finalDoc);
        if(!finalIds.containsAll(allIds))
            throw new IllegalStateException("Native sketch import missing authored frames: imported source retained");
        return new JSONObject().put("ok",true).put("sketchId",sketchId)
                .put("revision",finalDoc.getLong("revision"))
                .put("frameCount",count).put("fps",fps).put("width",width).put("height",height)
                .put("validatedObjects",allIds.size())
                .put("verifiedSourceSha256",sourceSha).put("manifestSha256",manifestSha)
                .put("nativeSketchEditable",true).put("sourceVideoUnchanged",true)
                .put("galleryAccess",false);
    }

    private static void checkOps(JSONArray ops,int frame,int count,boolean background,
                                 HashSet<String> ids)throws Exception {
        for(int i=0;i<ops.length();i++){
            JSONObject operation=ops.getJSONObject(i);
            if(!"add_object".equals(operation.getString("type")))
                throw new IllegalArgumentException("Only editable source-derived drawing objects may be imported");
            String layer=operation.getString("layerId");
            if(!("layer_1".equals(layer)||java.util.Arrays.asList(EXTRA_LAYERS).contains(layer)))
                throw new IllegalArgumentException("Sketch layer outside approved import set");
            JSONObject o=SketchEditOps.completeObject(operation.getJSONObject("object"));
            if(o.getInt("frame")!=frame||o.getInt("exposure")!=(background?count:1))
                throw new IllegalArgumentException("Sketch frame exposure/order mismatch");
            String id=SketchDocument.id(o,"id");
            if(!ids.add(id))throw new IllegalArgumentException("Duplicate source drawing ID");
        }
    }

    private static JSONObject readExisting(Context ctx,String id)throws Exception {
        try(SketchProjectStore store=new SketchProjectStore(ctx)){
            try{return store.get(id);}
            catch(IllegalArgumentException absent){
                if(absent.getMessage()!=null&&absent.getMessage().startsWith("UNKNOWN_SKETCH"))return null;
                throw absent;
            }
        }
    }
    private static HashSet<String> objectIds(JSONObject doc)throws Exception {
        HashSet<String> ids=new HashSet<>();
        JSONArray layers=doc.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){
            JSONArray o=layers.getJSONObject(i).getJSONArray("objects");
            for(int j=0;j<o.length();j++)ids.add(o.getJSONObject(j).getString("id"));
        }
        return ids;
    }
    private static void applyMissing(Context ctx,String id,JSONArray proposed,String operationId)
            throws Exception {
        JSONObject doc=readExisting(ctx,id);
        if(doc==null)throw new IllegalStateException("Source sketch disappeared");
        HashSet<String> existing=objectIds(doc);
        HashSet<String> layerIds=new HashSet<>();
        JSONArray layers=doc.getJSONArray("layers");
        for(int i=0;i<layers.length();i++)layerIds.add(layers.getJSONObject(i).getString("id"));
        JSONArray missing=new JSONArray();
        for(int i=0;i<proposed.length();i++){
            JSONObject op=proposed.getJSONObject(i);
            String type=op.getString("type");
            String identifier="add_layer".equals(type)?op.getJSONObject("layer").getString("id")
                    :op.getJSONObject("object").getString("id");
            if("add_layer".equals(type)?!layerIds.contains(identifier):!existing.contains(identifier))
                missing.put(op);
        }
        if(missing.length()==0)return;
        JSONObject request=new JSONObject().put("sketchId",id)
                .put("expectedRevision",doc.getLong("revision"))
                .put("operationId",operationId).put("operations",missing);
        SketchEditOps.execute(ctx,"sketch_edit",request);
    }
    private static byte[] bounded(InputStream in,int limit)throws Exception{
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] buffer=new byte[32768];int n;
        while((n=in.read(buffer))!=-1){
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
            if(out.size()+n>limit)throw new IllegalArgumentException("Linked source file exceeds bounded import budget");
            out.write(buffer,0,n);
        }
        return out.toByteArray();
    }
    private static String checkedName(String value,String suffix){
        if(value==null||!value.matches("[A-Za-z0-9_.-]{1,120}")||value.contains("..")||!value.endsWith(suffix))
            throw new IllegalArgumentException("Only exact linked workspace source names are allowed");
        return value;
    }
    private static String hex(byte[] b){
        StringBuilder s=new StringBuilder(b.length*2);
        for(byte x:b)s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));
        return s.toString();
    }
}
