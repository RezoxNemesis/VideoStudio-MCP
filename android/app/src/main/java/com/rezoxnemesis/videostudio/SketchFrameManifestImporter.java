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
    private static final int MAX_MANIFEST_BYTES=4*1024*1024;
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
        String manifestName=checkedPacketName(params.getString("manifestName"));
        String videoName=checkedName(params.getString("videoName"),null);
        String expectedHash=params.getString("expectedSourceSha256").toLowerCase(java.util.Locale.ROOT);
        if(!expectedHash.matches("[a-f0-9]{64}"))throw new IllegalArgumentException("Source SHA-256 must be explicit");
        int expectedCount=(int)SketchDocument.integer(params,"expectedFrameCount",1,1200);
        DriveWorkspaceProvider drive=new DriveWorkspaceProvider(context);
        byte[] manifest;
        String actualHash;
        try(InputStream input=drive.openLinkedSourceFile(manifestName)){
            manifest=readPacket(input,manifestName.endsWith(".gz"));
        }
        actualHash=sourceHash(context,videoName);
        if(!expectedHash.equals(actualHash))
            throw new IllegalStateException("VIDEO_SOURCE_SHA256_MISMATCH: no sketch changes made");
        JSONObject result=applyBytes(context,manifest,actualHash,id,expectedCount,progress).put("sourceName",videoName);
        if(!videoName.toLowerCase(java.util.Locale.ROOT).endsWith(".mp4"))result.remove("sourceVideoUnchanged");return result;
    }

    public static String sourceHash(Context context,String sourceName)throws Exception {
        checkedName(sourceName,null);try(InputStream input=new DriveWorkspaceProvider(context).openLinkedSourceFile(sourceName)){
            return sourceHash(input);
        }
    }

    /** Hashing an owned source streams 32KiB blocks; source bytes never enter the drawing/relay. */
    static String sourceHash(InputStream input)throws Exception {
        MessageDigest sha=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[32768];int n;long total=0;
        while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new InterruptedException();total=Math.addExact(total,n);sha.update(buffer,0,n);}
        if(total==0)throw SketchDocument.bad("Drawing source is empty");return hex(sha.digest());
    }
    static byte[] readPacket(InputStream input,boolean gzip)throws Exception {
        if(!gzip)return bounded(input,MAX_MANIFEST_BYTES);
        return SketchCompressedPacket.read(input,MAX_MANIFEST_BYTES);
    }

    private static String checkedPacketName(String value){
        if(value==null||!value.matches("[A-Za-z0-9_.-]{1,120}")||value.contains("..")||!(value.endsWith(".jsonl")||value.endsWith(".jsonl.gz")))throw new IllegalArgumentException("Use an exact linked .jsonl or .jsonl.gz packet name");return value;
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
        if(background.length()>64)
            throw new IllegalArgumentException("Sketch background count is invalid");
        JSONObject create=new JSONObject().put("sketchId",sketchId)
                .put("operationId","manifest_create_"+manifestSha.substring(0,12))
                .put("name",header.optString("name","Source drawing • "+count+" frames"))
                .put("width",width).put("height",height).put("fps",fps)
                .put("frameCount",count).put("background",header.optString("background","#FFF7EFE7"));
        JSONArray layers=new JSONArray();HashSet<String> allowedLayers=new HashSet<>();allowedLayers.add("layer_1");
        JSONArray declared=header.optJSONArray("layers");
        String[] titles={"Ruined city atmosphere","Black swordsman","White swordsman","Original weapon and impact linework"};
        if(declared==null){declared=new JSONArray();for(int n=0;n<EXTRA_LAYERS.length;n++)declared.put(new JSONObject().put("id",EXTRA_LAYERS[n]).put("name",titles[n]));}
        if(declared.length()>31)throw SketchDocument.bad("Use at most 31 imported layers");
        JSONObject candidate=SketchDocument.create(create);
        for(int n=0;n<declared.length();n++){JSONObject layer=declared.getJSONObject(n);SketchDocument.fields(layer,"id,name,visible,locked,opacity,transform,keys,objects,blendMode");String layerId=SketchDocument.id(layer,"id");if(!allowedLayers.add(layerId)||layer.optJSONArray("objects")!=null&&layer.getJSONArray("objects").length()!=0)throw SketchDocument.bad("Imported layers require unique IDs and empty objects");JSONObject add=new JSONObject().put("type","add_layer").put("layer",layer);layers.put(add);SketchEditOps.apply(candidate,add);}
        HashSet<String> allIds=new HashSet<>();
        checkOps(background,0,count,true,allIds,allowedLayers);
        for(int n=0;n<background.length();n++)appendCandidate(candidate,background.getJSONObject(n));
        for(int f=0;f<count;f++){
            String line=scan.readLine();
            if(line==null||line.length()>MAX_MANIFEST_BYTES)throw new IllegalArgumentException("Video frame row missing or too large: "+f);
            JSONObject row=new JSONObject(line);
            if(row.getInt("frame")!=f)throw new IllegalArgumentException("Video frame order mismatch");
            JSONArray commands=row.getJSONArray("operations");
            if(commands.length()<1||commands.length()>SketchDocument.MAX_OBJECTS)
                throw new IllegalArgumentException("Frame exceeds the document drawing-object budget");
            checkOps(commands,f,count,false,allIds,allowedLayers);
            for(int n=0;n<commands.length();n++)appendCandidate(candidate,commands.getJSONObject(n));
        }
        if(scan.readLine()!=null)throw new IllegalArgumentException("Unexpected frames after declared video length");

        SketchDocument.validate(candidate); // Combined geometry, points, IDs and byte budgets before writes.

        // No user file or sketch is changed until full manifest and source proof passes.
        String binding=sourceSha+":"+manifestSha;
        SharedPreferences prefs=context.getSharedPreferences("sketch_manifest_import_v1",Context.MODE_PRIVATE);
        String previous=prefs.getString(sketchId,"");
        JSONObject present=readExisting(context,sketchId);
        boolean legacyPartition=!previous.isEmpty()&&!prefs.getBoolean(sketchId+":partition395",false);
        if(!previous.isEmpty()&&!previous.equals(binding))
            throw new IllegalStateException("SKETCH_SOURCE_CHANGED: preserving existing source-bound drawing");
        if(previous.isEmpty()&&present!=null)
            throw new IllegalStateException("SKETCH_ALREADY_EXISTS: refusing to replace an existing user drawing");
        if(present!=null){
            requireMatchingObjects(present,candidate);
            if(legacyPartition)requireLegacyReceiptsIntact(context,sketchId,manifestSha,raw,count,objectIds(present));
        }
        if(previous.isEmpty()&&!prefs.edit().putString(sketchId,binding).putBoolean(sketchId+":partition395",true).commit())
            throw new IllegalStateException("Unable to retain source manifest identity");
        if(present==null)SketchEditOps.execute(context,"sketch_create",create);
        String liveSession="manifest_live_"+manifestSha.substring(0,16);boolean startLive=false;
        try(SketchProjectStore store=new SketchProjectStore(context)){try{store.liveSession(sketchId,liveSession);}catch(IllegalArgumentException unknown){if(!unknown.getMessage().contains("Unknown live drawing session"))throw unknown;startLive=true;}}
        if(startLive){JSONObject current=readExisting(context,sketchId);SketchEditOps.execute(context,"sketch_live_begin",new JSONObject().put("sketchId",sketchId).put("operationId",liveSession).put("expectedRevision",current.getLong("revision")));}
        applyMissing(context,sketchId,layers,"manifest_layers_"+manifestSha.substring(0,10));
        applyMissing(context,sketchId,background,"manifest_background_"+manifestSha.substring(0,10));

        java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.StringReader(raw));
        reader.readLine();
        String chunkPrefix=(legacyPartition?"manifest395_":"manifest_")+manifestSha.substring(0,10)+"_frame_";
        JSONArray batch=new JSONArray();int batchBytes=0;String batchId=chunkPrefix+"0";
        for(int f=0;f<count;f++){
            JSONObject row=new JSONObject(reader.readLine());JSONArray ops=row.getJSONArray("operations");
            for(int i=0;i<ops.length();i++){
                JSONObject item=ops.getJSONObject(i);int itemBytes=item.toString().getBytes(StandardCharsets.UTF_8).length;
                if(batch.length()>0&&(batch.length()>=64||batchBytes+itemBytes>480*1024)){
                    applyMissing(context,sketchId,batch,batchId);batch=new JSONArray();batchBytes=0;
                    batchId=chunkPrefix+f+(i==0?"":"_part_"+i);
                }
                batch.put(item);batchBytes+=itemBytes;
            }
            if(batch.length()>=28||f==count-1){
                applyMissing(context,sketchId,batch,batchId);batch=new JSONArray();batchBytes=0;
                batchId=chunkPrefix+(f+1);
            }
            if(progress!=null)progress.completed(f+1,count);
            if(Thread.currentThread().isInterrupted())throw new InterruptedException();
        }
        JSONObject finalDoc=readExisting(context,sketchId);
        if(finalDoc==null)throw new IllegalStateException("Imported sketch disappeared");
        requireMatchingObjects(finalDoc,candidate);
        HashSet<String> finalIds=objectIds(finalDoc);
        if(!finalIds.containsAll(allIds))
            throw new IllegalStateException("Native sketch import missing authored frames: imported source retained");
        SketchEditOps.execute(context,"sketch_live_end",new JSONObject().put("sketchId",sketchId).put("sessionId",liveSession).put("expectedRevision",finalDoc.getLong("revision")));
        return new JSONObject().put("ok",true).put("sketchId",sketchId).put("liveSessionId",liveSession).put("everyAcceptedOperationRecorded",true)
                .put("revision",finalDoc.getLong("revision"))
                .put("frameCount",count).put("fps",fps).put("width",width).put("height",height)
                .put("validatedObjects",allIds.size())
                .put("verifiedSourceSha256",sourceSha).put("manifestSha256",manifestSha)
                .put("nativeSketchEditable",true).put("sourceVideoUnchanged",true).put("sourceUnchanged",true)
                .put("visualFidelity","UNVERIFIED").put("qualityEvidence","Geometry and timing validity do not establish source likeness")
                .put("galleryAccess",false);
    }

    private static void checkOps(JSONArray ops,int frame,int count,boolean background,
                                 HashSet<String> ids,HashSet<String> allowedLayers)throws Exception {
        for(int i=0;i<ops.length();i++){
            JSONObject operation=ops.getJSONObject(i);SketchDocument.fields(operation,"type,layerId,object");
            if(!"add_object".equals(operation.getString("type")))
                throw new IllegalArgumentException("Only editable source-derived drawing objects may be imported");
            String layer=operation.getString("layerId");
            if(!allowedLayers.contains(layer))
                throw new IllegalArgumentException("Sketch layer outside approved import set");
            JSONObject raw=operation.getJSONObject("object");
            if("paint".equals(raw.optString("kind")))throw SketchDocument.bad("Vector drawing packets cannot import paint snapshot pointers; use a checked owned archive");
            JSONObject o=SketchEditOps.completeObject(raw);
            if(operation.toString().getBytes(StandardCharsets.UTF_8).length>500*1024)throw SketchDocument.bad("One drawing exceeds the atomic edit budget; split its paths or strokes before import");
            if("image".equals(o.getString("kind")))throw SketchDocument.bad("Vector drawing packets cannot embed raster references; import a guide through sketch_import_reference");
            if(o.getInt("frame")!=frame||o.getInt("exposure")!=(background?count:1))
                throw new IllegalArgumentException("Sketch frame exposure/order mismatch");
            String id=SketchDocument.id(o,"id");
            if(!ids.add(id))throw new IllegalArgumentException("Duplicate source drawing ID");
        }
    }

    private static void appendCandidate(JSONObject doc,JSONObject operation)throws Exception {
        if(Thread.currentThread().isInterrupted())throw new InterruptedException();JSONObject layer=SketchDocument.layer(doc,operation.getString("layerId"));SketchEditOps.requireUnlocked(layer);layer.getJSONArray("objects").put(SketchEditOps.completeObject(operation.getJSONObject("object")));
    }
    private static void requireMatchingObjects(JSONObject existing,JSONObject expected)throws Exception {
        for(String field:new String[]{"width","height","frameCount","fps","background"})if(!existing.get(field).equals(expected.get(field)))throw new IllegalStateException("SKETCH_IMPORT_CONFLICT: preserving edited drawing "+field);
        JSONArray expectedLayers=expected.getJSONArray("layers");for(int n=0;n<expectedLayers.length();n++){JSONObject wanted=expectedLayers.getJSONObject(n),actual=null;JSONArray existingLayers=existing.getJSONArray("layers");for(int k=0;k<existingLayers.length();k++)if(wanted.getString("id").equals(existingLayers.getJSONObject(k).getString("id")))actual=existingLayers.getJSONObject(k);if(actual!=null)for(String field:new String[]{"transform","keys","opacity","visible","blendMode"}){Object a="blendMode".equals(field)?actual.optString(field,"normal"):actual.opt(field),b="blendMode".equals(field)?wanted.optString(field,"normal"):wanted.opt(field);if(a==null&&b==null)continue;if(a==null||b==null||!SketchDocument.canonical(new JSONObject().put("value",a)).equals(SketchDocument.canonical(new JSONObject().put("value",b))))throw new IllegalStateException("SKETCH_IMPORT_CONFLICT: preserving edited source layer "+wanted.getString("id"));}}
        java.util.Map<String,String> current=new java.util.HashMap<>();
        JSONArray layers=existing.getJSONArray("layers");
        for(int n=0;n<layers.length();n++){JSONObject layer=layers.getJSONObject(n);JSONArray objects=layer.getJSONArray("objects");for(int k=0;k<objects.length();k++){JSONObject o=objects.getJSONObject(k);current.put(o.getString("id"),layer.getString("id")+":"+SketchDocument.canonical(o));}}
        layers=expected.getJSONArray("layers");for(int n=0;n<layers.length();n++){JSONObject layer=layers.getJSONObject(n);JSONArray objects=layer.getJSONArray("objects");for(int k=0;k<objects.length();k++){JSONObject o=objects.getJSONObject(k);String old=current.get(o.getString("id"));if(old!=null&&!old.equals(layer.getString("id")+":"+SketchDocument.canonical(o)))throw new IllegalStateException("SKETCH_IMPORT_CONFLICT: preserving edited source object "+o.getString("id"));}}
    }

    /** Preserve receipts from the original394 row-based partition before making smaller395 batches. */
    private static void requireLegacyReceiptsIntact(Context context,String id,String manifestSha,String raw,int count,HashSet<String> present)throws Exception {
        java.io.BufferedReader reader=new java.io.BufferedReader(new java.io.StringReader(raw));reader.readLine();
        java.util.ArrayList<String> ids=new java.util.ArrayList<>();int start=0;
        try(SketchProjectStore store=new SketchProjectStore(context)){
            for(int frame=0;frame<count;frame++){
                JSONArray operations=new JSONObject(reader.readLine()).getJSONArray("operations");
                if(operations.length()>16)return; //394 could not accept detailed frame rows.
                for(int n=0;n<operations.length();n++)ids.add(operations.getJSONObject(n).getJSONObject("object").getString("id"));
                if(ids.size()>=28||frame==count-1){
                    if(store.hasOperation(id,"manifest_"+manifestSha.substring(0,10)+"_frame_"+start))for(String objectId:ids)if(!present.contains(objectId))throw new IllegalStateException("SKETCH_IMPORT_CONFLICT: preserving removed objects from an already committed legacy drawing batch");
                    ids.clear();start=frame+1;
                }
            }
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
        try(SketchProjectStore store=new SketchProjectStore(ctx)){if(store.hasOperation(id,operationId))throw new IllegalStateException("SKETCH_IMPORT_CONFLICT: preserving removed objects from an already committed drawing batch");}
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
        if(value==null||!value.matches("[A-Za-z0-9_.-]{1,120}")||value.contains("..")||(suffix==null?!value.toLowerCase(java.util.Locale.ROOT).matches(".*\\.(mp4|png|jpg|jpeg|webp|txt)"):!value.endsWith(suffix)))
            throw new IllegalArgumentException("Only exact linked workspace source names are allowed");
        return value;
    }
    private static String hex(byte[] b){
        StringBuilder s=new StringBuilder(b.length*2);
        for(byte x:b)s.append(String.format(java.util.Locale.ROOT,"%02x",x&255));
        return s.toString();
    }
}
