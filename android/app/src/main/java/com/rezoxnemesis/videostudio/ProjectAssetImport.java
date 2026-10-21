package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONObject;

/** Independent byte copies of explicitly selected, app-owned project media. No Gallery or URI import. */
public final class ProjectAssetImport {
    /** Media bytes are streamed, never loaded into a Java byte[] in full. */
    public static final long MAX_BYTES=10L*1024*1024*1024;
    /** Bitmap processing needs a smaller bound than video/audio file copies. */
    public static final long MAX_IMAGE_BYTES=256L*1024*1024;
    public static final long STORAGE_RESERVE_BYTES=512L*1024*1024;
    static boolean supportedSize(String mime,long bytes){
        return mime!=null&&bytes>0&&bytes<=(mime.startsWith("image/")?MAX_IMAGE_BYTES:MAX_BYTES)
                &&(mime.startsWith("image/")||mime.startsWith("video/")||mime.startsWith("audio/"));
    }
    private static final ReentrantLock COPY_LOCK=new ReentrantLock();
    private static final int BUFFER_BYTES=64*1024,MAX_JOURNAL_BYTES=1024*1024;
    private ProjectAssetImport(){}

    public interface Progress {void update(long copied,long total)throws Exception;}

    public static JSONObject capabilities()throws Exception {
        return new JSONObject().put("minimumNativeVersion","4.8.0")
                .put("infoAction","project_asset_info").put("importAction","project_import_asset")
                .put("maximumBytes",MAX_BYTES).put("maximumImageBytes",MAX_IMAGE_BYTES)
                .put("streamingCopy",true).put("partialResume",true)
                .put("storageReserveBytes",STORAGE_RESERVE_BYTES).put("appendDefault",false)
                .put("source","Explicit project ID, exact source revision and asset ID; only app-private regular video/image/audio files, no supplied paths, external providers, symlinks or Gallery enumeration")
                .put("integrity","project_asset_info returns bounded metadata and SHA-256. Import requires expectedSha256 plus exact source/destination revisions, streams a separate independent byte copy in bounded buffers, resumes a byte-verified partial after interruption and verifies source/output hashes before publication. Original generation metadata and source provenance are retained.")
                .put("retry","Retry the same operation and request to reuse the verified owned result without duplicate assets/clips; later human edits and the existing audio lane are preserved. Changed requests or modified output bytes reject.")
                .put("timeline","Optional appendToTimeline defaults false; video/image appends one clip when true, while audio remains a media-bin asset for explicit lane editing. No new audio lane or master offset is inserted.")
                .put("execution","Import runs as a cancellable recoverable transfer JobManager job. Admission returns ok/queued/jobId; wait for actual jobResult. Stop All interrupts copying and identical retry safely resumes.")
                .put("manualControls","Editor > Media Bin > Import from project; explicit source project/asset selection, hash inspection and optional timeline append use the same ProjectAssetImport core")
                .put("agentGuidance","Ordinary ChatGPT calls app_status and gates native 4.8.0, reads projectAssetImport capabilities, obtains fresh revisions and project_asset_info, then project_import_asset. Book-render output can be copied into the Story Audio output project and appended at time zero before existing project preview/export. No Files picker or FFmpeg is needed for already-owned files.")
                .put("galleryAccess",false).put("phoneExecutionVerified",false);
    }

    /** Pure request validation also used before creating a durable job. */
    public static JSONObject validate(JSONObject input)throws Exception {
        fields(input,"projectId","expectedUpdatedAt","sourceProjectId","sourceProjectUpdatedAt","assetId","expectedSha256","operationId","appendToTimeline");
        JSONObject request=copy(input);id(request,"projectId");id(request,"sourceProjectId");id(request,"assetId");id(request,"operationId");
        integer(request,"expectedUpdatedAt");integer(request,"sourceProjectUpdatedAt");
        if(request.getString("projectId").equals(request.getString("sourceProjectId")))throw bad("Choose a different destination project");
        if(!(request.get("expectedSha256") instanceof String)||!request.getString("expectedSha256").matches("[a-f0-9]{64}"))throw bad("An exact expected SHA-256 is required");
        if(request.has("appendToTimeline")&&!(request.get("appendToTimeline") instanceof Boolean))throw bad("appendToTimeline must be boolean");
        return request;
    }

    public static JSONObject inspect(Context context,JSONObject input)throws Exception {
        fields(input,"projectId","expectedUpdatedAt","assetId");String projectId=id(input,"projectId"),assetId=id(input,"assetId");long revision=integer(input,"expectedUpdatedAt");cancelled();
        ProjectStore store=new ProjectStore(context);ProjectStore.Project source=project(store,projectId,revision);ProjectStore.Asset asset=source.asset(assetId);File file=sourceFile(context,asset);long bytes=file.length();String digest=sha256(file);
        return store.guardedProject(projectId,revision,current->{
            if(!sameAsset(asset,current.asset(assetId)))throw bad("Source asset changed");regular(context,file);if(file.length()!=bytes)throw bad("Source bytes changed");cancelled();
            return new JSONObject().put("ok",true).put("readOnly",true).put("projectId",projectId).put("updatedAt",revision).put("assetId",assetId)
                    .put("name",asset.name).put("mime",asset.mime).put("durationMs",asset.durationMs).put("bytes",bytes).put("sha256",digest).put("appOwned",true);
        });
    }

    public static JSONObject execute(Context context,JSONObject input)throws Exception {return execute(context,input,(copied,total)->{});}

    public static JSONObject execute(Context context,JSONObject input,Progress progress)throws Exception {
        JSONObject request=validate(input);if(progress==null)throw bad("Progress callback is required");
        // The transfer lane serializes normal jobs. This lock also protects direct manual callers
        // from racing the same operation, without holding a SQLite transaction during the copy.
        COPY_LOCK.lockInterruptibly();try{
            cancelled();String destinationId=request.getString("projectId"),sourceId=request.getString("sourceProjectId"),operationId=request.getString("operationId");
            JSONObject normalized=copy(request).put("appendToTimeline",request.optBoolean("appendToTimeline",false));String fingerprint=hash(SketchDocument.canonical(normalized).getBytes(StandardCharsets.UTF_8));
            File directory=new File(new File(new File(context.getFilesDir(),"project_asset_imports"),destinationId),hash(operationId.getBytes(StandardCharsets.UTF_8)));
            owned(context,directory);File journalFile=new File(directory,"receipt.json");JSONObject journal=null;ProjectStore store=new ProjectStore(context);
            if(journalFile.isFile()||new File(journalFile.getPath()+".bak").isFile()){
                journal=readJournal(context,journalFile);if(!fingerprint.equals(journal.getString("fingerprint")))throw bad("PROJECT_ASSET_OPERATION_CONFLICT: keep the same operation request");
                ProjectStore.Project existing=store.get(destinationId);if(existing==null)throw bad("Destination project no longer exists");
                ProjectStore.Asset imported=existing.asset(journal.getString("assetId"));
                if(imported!=null)return result(context,existing,imported,journal,true);
                if(journal.optBoolean("completed",false))throw bad("Imported asset was removed; it will not be recreated over human edits");
            }
            ProjectStore.Project source=project(store,sourceId,request.getLong("sourceProjectUpdatedAt"));project(store,destinationId,request.getLong("expectedUpdatedAt"));
            ProjectStore.Asset selected=source.asset(request.getString("assetId"));File original=sourceFile(context,selected);long size=original.length();String expected=request.getString("expectedSha256");
            if(!expected.equals(sha256(original)))throw bad("PROJECT_ASSET_SOURCE_CHANGED: expected source hash differs");
            String assetId=UUID.nameUUIDFromBytes((destinationId+"/project-asset/"+operationId).getBytes(StandardCharsets.UTF_8)).toString();
            File output=new File(directory,"media"+extension(selected.mime));owned(context,output);
            if(journal==null){
                if(output.exists())throw bad("Import output already exists without its receipt");
                directory(context,directory);
                journal=new JSONObject().put("fingerprint",fingerprint).put("request",normalized).put("assetId",assetId).put("sha256",expected).put("bytes",size)
                        .put("outputName",output.getName()).put("sourceAsset",copy(selected.toJson())).put("completed",false);
                writeJournal(context,journalFile,journal);
            }else if(!assetId.equals(journal.getString("assetId"))||size!=journal.getLong("bytes")||!sameAsset(selected,ProjectStore.Asset.fromJson(journal.getJSONObject("sourceAsset")))||!output.getName().equals(journal.getString("outputName")))throw bad("Import source metadata changed");
            if(output.exists())verifyOutput(context,output,journal);
            else copyBytes(context,original,output,size,expected,progress);
            cancelled();if(!expected.equals(sha256(original)))throw bad("Source bytes changed during copying");verifyOutput(context,output,journal);
            final JSONObject receipt=journal;final File copied=output;
            JSONObject result=store.guardedProject(sourceId,request.getLong("sourceProjectUpdatedAt"),currentSource->{
                if(!sameAsset(selected,currentSource.asset(selected.id)))throw bad("Source asset changed during copying");
                return store.guardedProject(destinationId,request.getLong("expectedUpdatedAt"),destination->{
                    cancelled();if(destination.asset(assetId)!=null)throw bad("Destination asset identity already exists");
                    ProjectStore.Asset asset=ProjectStore.Asset.fromJson(copy(selected.toJson()));asset.id=assetId;asset.uri=Uri.fromFile(copied).toString();asset.sizeBytes=size;asset.seekable=true;asset.persistedReadAccess=true;asset.providerAuthority="";asset.createdAt=System.currentTimeMillis();
                    asset.generationMetadata=copy(selected.generationMetadata).put("projectAssetImport",new JSONObject().put("fingerprint",fingerprint).put("operationId",operationId).put("sourceProjectId",sourceId).put("sourceAssetId",selected.id).put("sourceSha256",expected));destination.assets.add(asset);
                    if(normalized.getBoolean("appendToTimeline")&&!asset.mime.startsWith("audio/")){
                        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id=UUID.nameUUIDFromBytes((assetId+"/clip").getBytes(StandardCharsets.UTF_8)).toString();clip.assetId=asset.id;clip.inMs=0;clip.outMs=asset.mime.startsWith("image/")?3000:asset.durationMs;
                        if(clip.outMs<=0)throw bad("A video duration is required to append this asset");destination.clips.add(clip);
                        StudioLayerContract.fromJson(destination,StudioLayerContract.toJson(destination.studioLayers));
                    }
                    store.save(destination);return result(context,destination,asset,receipt,false);
                });
            });
            // If interruption/restart happens after publication, the asset provenance above is the
            // commit witness. An identical retry verifies it instead of appending another clip.
            receipt.put("completed",true);writeJournal(context,journalFile,receipt);return result;
        }finally{COPY_LOCK.unlock();}
    }

    private static JSONObject result(Context context,ProjectStore.Project destination,ProjectStore.Asset asset,JSONObject journal,boolean replayed)throws Exception {
        File output=new File(new File(new File(new File(context.getFilesDir(),"project_asset_imports"),destination.id),hash(journal.getJSONObject("request").getString("operationId").getBytes(StandardCharsets.UTF_8))),journal.getString("outputName"));
        if(!Uri.fromFile(output).toString().equals(asset.uri)||asset.sizeBytes!=journal.getLong("bytes")||!sameImportedMetadata(asset,journal))throw bad("Imported asset metadata changed");verifyOutput(context,output,journal);
        JSONObject request=journal.getJSONObject("request");return new JSONObject().put("ok",true).put("projectId",destination.id).put("updatedAt",destination.updatedAt).put("assetId",asset.id).put("operationId",request.getString("operationId"))
                .put("sourceProjectId",request.getString("sourceProjectId")).put("sourceAssetId",request.getString("assetId")).put("sha256",journal.getString("sha256")).put("bytes",journal.getLong("bytes"))
                .put("durationMs",asset.durationMs).put("mime",asset.mime).put("independentCopy",true).put("appendedToTimeline",request.getBoolean("appendToTimeline")&&!asset.mime.startsWith("audio/"))
                .put("audioLanesUnchanged",true).put("replayed",replayed);
    }
    private static boolean sameImportedMetadata(ProjectStore.Asset asset,JSONObject journal)throws Exception {
        ProjectStore.Asset source=ProjectStore.Asset.fromJson(journal.getJSONObject("sourceAsset"));JSONObject provenance=asset.generationMetadata.optJSONObject("projectAssetImport");
        return Objects.equals(source.mime,asset.mime)&&source.durationMs==asset.durationMs&&provenance!=null&&journal.getString("fingerprint").equals(provenance.optString("fingerprint"));
    }
    private static void copyBytes(Context context,File source,File output,long size,String expected,Progress progress)throws Exception {
        // Stable per-operation staging is required for 5–10 GiB resume. An interruption
        // leaves no published asset and retains a hash-verifiable partial for retry.
        File stage=new File(output.getParentFile(),output.getName()+".partial");owned(context,stage);
        if(stage.exists())regular(context,stage);
        long prior=stage.isFile()?stage.length():0L;
        if(prior>size)throw bad("Partial copy exceeds its pinned source length");
        StorageBudget.Check budget=StorageBudget.checkTransfer(
                output.getParentFile().getUsableSpace(),size,prior,STORAGE_RESERVE_BYTES);
        if(!budget.allowed)throw new IllegalStateException(
                "PROJECT_ASSET_WAITING_STORAGE: need "+budget.requiredWithReserveBytes+
                " bytes including reserve; free "+budget.freeBytes+
                "; preserved "+prior+" verified transfer bytes for a retry");
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        long copied=0,lastProgress=0;
        try(DataInputStream in=new DataInputStream(new FileInputStream(source));
            RandomAccessFile out=new RandomAccessFile(stage,"rw")){
            byte[] buffer=new byte[BUFFER_BYTES],existing=new byte[BUFFER_BYTES];
            // A .partial is never blindly trusted. Re-read and compare its ENTIRE
            // prefix with the pinned original, then continue the same SHA-256 digest.
            while(copied<prior){
                cancelled();int take=(int)Math.min(buffer.length,prior-copied);
                in.readFully(buffer,0,take);out.readFully(existing,0,take);
                for(int i=0;i<take;i++)if(buffer[i]!=existing[i])
                    throw bad("PROJECT_ASSET_PARTIAL_CHANGED: existing partial does not match source");
                digest.update(buffer,0,take);copied+=take;
            }
            out.seek(prior);
            progress.update(prior,size);cancelled();lastProgress=prior;
            int n;while((n=in.read(buffer))!=-1){
                if(n==0)continue;cancelled();
                if((long)n>size-copied)throw bad("Source size changed during copying");
                out.write(buffer,0,n);digest.update(buffer,0,n);copied+=n;
                if(copied-lastProgress>=2L*1024*1024||copied==size){
                    progress.update(copied,size);lastProgress=copied;
                }
            }
            out.getFD().sync();
        }
        cancelled();
        if(copied!=size)throw bad("Source size changed during copying; partial kept for retry");
        if(!expected.equals(hex(digest.digest())))throw bad("PROJECT_ASSET_COPY_HASH_MISMATCH: staged bytes are not the selected source");
        if(output.exists())throw bad("Import output already exists");
        Files.move(stage.toPath(),output.toPath(),StandardCopyOption.ATOMIC_MOVE);
    }
    static File sourceFile(Context context,ProjectStore.Asset asset)throws Exception {
        if(asset==null||asset.mime==null||!(asset.mime.startsWith("video/")||asset.mime.startsWith("image/")||asset.mime.startsWith("audio/")))throw bad("Choose an existing video, image or audio asset");
        if(asset.name==null||asset.name.isEmpty()||asset.name.length()>512||asset.durationMs<0||asset.uri==null)throw bad("Invalid source metadata");
        Uri uri=Uri.parse(asset.uri);if(!"file".equals(uri.getScheme())||uri.getPath()==null||uri.getQuery()!=null||uri.getFragment()!=null||!(uri.getAuthority()==null||uri.getAuthority().isEmpty()))throw bad("Source must already be an app-owned file; external links are unavailable");
        File file=new File(uri.getPath());regular(context,file);if(!supportedSize(asset.mime,file.length()))throw bad("Owned video/audio must be 1 byte..10 GiB; images must be 1 byte..256 MiB");
        if(asset.sizeBytes>=0&&asset.sizeBytes!=file.length())throw bad("Source size changed");return file;
    }
    private static ProjectStore.Project project(ProjectStore store,String id,long revision){ProjectStore.Project p=store.get(id);if(p==null)throw bad("Unknown project");if(p.updatedAt!=revision)throw bad("SOURCE_PROJECT_CHANGED: refresh the exact project revision");return p;}
    private static boolean sameAsset(ProjectStore.Asset a,ProjectStore.Asset b)throws Exception {return a!=null&&b!=null&&SketchDocument.canonical(copy(a.toJson())).equals(SketchDocument.canonical(copy(b.toJson())));}
    private static String extension(String mime){if(mime.equals("video/mp4"))return ".mp4";if(mime.equals("audio/wav")||mime.equals("audio/x-wav"))return ".wav";if(mime.equals("image/png"))return ".png";if(mime.equals("image/jpeg"))return ".jpg";return ".bin";}
    private static void verifyOutput(Context c,File file,JSONObject journal)throws Exception {regular(c,file);if(file.length()!=journal.getLong("bytes")||!journal.getString("sha256").equals(sha256(file)))throw bad("PROJECT_ASSET_RESULT_CHANGED: existing bytes will not be overwritten");}
    private static void owned(Context c,File file)throws Exception {
        Path path=file.toPath().toAbsolutePath();if(!path.normalize().equals(path))throw bad("Unsafe media path");for(Path p=path;p!=null;p=p.getParent())if(Files.isSymbolicLink(p))throw bad("Media symlinks are unavailable");
        if(!file.getCanonicalPath().startsWith(c.getFilesDir().getCanonicalPath()+File.separator))throw bad("Media must be owned by this application");
    }
    private static void regular(Context c,File file)throws Exception {owned(c,file);if(!Files.isRegularFile(file.toPath(),LinkOption.NOFOLLOW_LINKS))throw bad("Owned file is unavailable");}
    private static void directory(Context c,File file)throws Exception {owned(c,file);Files.createDirectories(file.toPath());if(!file.isDirectory())throw bad("Media copy storage is unavailable");}
    private static JSONObject readJournal(Context c,File file)throws Exception {
        owned(c,file);owned(c,new File(file.getPath()+".bak"));owned(c,new File(file.getPath()+".new"));try(InputStream in=new AtomicFile(file).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buffer=new byte[8192];for(int n;(n=in.read(buffer))!=-1;){cancelled();if(out.size()+n>MAX_JOURNAL_BYTES)throw bad("Import receipt exceeds budget");out.write(buffer,0,n);}return new JSONObject(out.toString("UTF-8"));
        }
    }
    private static void writeJournal(Context c,File file,JSONObject value)throws Exception {
        owned(c,file);owned(c,new File(file.getPath()+".bak"));owned(c,new File(file.getPath()+".new"));byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_JOURNAL_BYTES)throw bad("Import receipt exceeds budget");cancelled();
        AtomicFile atomic=new AtomicFile(file);FileOutputStream out=null;try{out=atomic.startWrite();out.write(bytes);atomic.finishWrite(out);}catch(Exception error){if(out!=null)atomic.failWrite(out);throw error;}
    }
    private static JSONObject copy(JSONObject value)throws Exception {return new JSONObject(value.toString());}
    private static String sha256(File file)throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] buffer=new byte[BUFFER_BYTES];for(int n;(n=in.read(buffer))!=-1;){cancelled();digest.update(buffer,0,n);}}return hex(digest.digest());}
    private static String hash(byte[] value)throws Exception {return hex(MessageDigest.getInstance("SHA-256").digest(value));}
    private static String hex(byte[] value){StringBuilder text=new StringBuilder();for(byte b:value)text.append(String.format(Locale.ROOT,"%02x",b&255));return text.toString();}
    private static void fields(JSONObject object,String... allowed){if(object==null)throw bad("Request is required");Set<String> set=new HashSet<>(Arrays.asList(allowed));for(Iterator<String> i=object.keys();i.hasNext();)if(!set.contains(i.next()))throw bad("Unknown project import field");}
    private static String id(JSONObject object,String key)throws Exception {Object v=object.get(key);if(!(v instanceof String)||!((String)v).matches("[A-Za-z0-9_-]{1,96}"))throw bad("Invalid identity: "+key);return (String)v;}
    private static long integer(JSONObject object,String key)throws Exception {Object v=object.get(key);if(!(v instanceof Number)||!Double.isFinite(((Number)v).doubleValue())||((Number)v).doubleValue()!=((Number)v).longValue()||((Number)v).longValue()<0||((Number)v).longValue()>9_007_199_254_740_991L)throw bad("Invalid revision: "+key);return ((Number)v).longValue();}
    private static void cancelled()throws InterruptedException {if(Thread.currentThread().isInterrupted())throw new InterruptedException("Project media copy cancelled; retry the same operation");}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException("PROJECT_ASSET_IMPORT: "+message);}
}
