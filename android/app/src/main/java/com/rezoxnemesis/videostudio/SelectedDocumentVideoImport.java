package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.UriPermission;
import android.database.Cursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.json.JSONObject;

/** Explicit selected VIDEO documents only. All reads use one registered/picked URI, never a Gallery query. */
final class SelectedDocumentVideoImport {
    private static final int BUFFER=64*1024,MAX_JSON=1024*1024;
    private static final ReentrantLock COPY_LOCK=new ReentrantLock();
    private SelectedDocumentVideoImport(){}

    static JSONObject validate(JSONObject input)throws Exception{
        fields(input,"selectedDocument","projectId","expectedUpdatedAt","sourceProjectId","sourceProjectUpdatedAt","assetId","expectedSha256","operationId","appendToTimeline");
        selected(input);id(input,"projectId");id(input,"sourceProjectId");id(input,"assetId");id(input,"operationId");long destination=revision(input,"expectedUpdatedAt"),source=revision(input,"sourceProjectUpdatedAt");
        digest(input,"expectedSha256");if(input.has("appendToTimeline")&&!Boolean.FALSE.equals(input.get("appendToTimeline")))throw bad("Selected VIDEO copying cannot append to the timeline");
        if(input.getString("projectId").equals(input.getString("sourceProjectId"))&&destination!=source)throw bad("Same-project selected VIDEO copying requires identical source and destination revisions");
        return copy(input);
    }

    static JSONObject inspectRegistered(Context context,JSONObject input)throws Exception{
        worker();fields(input,"selectedDocument","projectId","expectedUpdatedAt","assetId");selected(input);String projectId=id(input,"projectId"),assetId=id(input,"assetId");long revision=revision(input,"expectedUpdatedAt");
        try(ProjectStore store=new ProjectStore(context)){
            ProjectStore.Project project=project(store,projectId,revision);ProjectStore.Asset asset=video(project.asset(assetId));Source source=Source.registered(context,projectId,revision,asset);
            String sha=sourceHash(context,source);return store.guardedProject(projectId,revision,current->{source.checkRegistered(current);grant(context,source.uri);cancelled();
                return new JSONObject().put("ok",true).put("readOnly",true).put("selectedDocument",true).put("appOwned",false).put("projectId",projectId).put("updatedAt",revision).put("assetId",assetId).put("name",asset.name).put("mime",asset.mime).put("durationMs",asset.durationMs).put("bytes",source.bytes).put("sha256",sha);
            });
        }
    }

    static JSONObject executeRegistered(Context context,JSONObject request,ProjectAssetImport.Progress progress)throws Exception{
        worker();JSONObject validated=validate(request);return execute(context,validated,progress,null,()->true);
    }

    /** Private native Files descriptor; never accepted as a public URI/command parameter. */
    static final class Picked {
        final JSONObject exact;
        private Picked(JSONObject exact)throws Exception{this.exact=copy(exact);}
        JSONObject toJson()throws Exception{return copy(exact);}
    }

    static Picked preparePicked(Context context,String projectId,long revision,Uri selectedUri,String operationId)throws Exception{
        worker();id(new JSONObject().put("projectId",projectId),"projectId");id(new JSONObject().put("operationId",operationId),"operationId");
        try(ProjectStore store=new ProjectStore(context)){
            project(store,projectId,revision);grant(context,selectedUri);Metadata metadata=metadata(context,selectedUri);ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="picked_document";asset.name=metadata.name;asset.mime=metadata.mime;asset.uri=selectedUri.toString();asset.sizeBytes=metadata.bytes;asset.role="source";
            Source source=Source.picked(context,asset);String sha=sourceHash(context,source);project(store,projectId,revision);
            return new Picked(new JSONObject().put("projectId",projectId).put("expectedUpdatedAt",revision).put("operationId",operationId).put("expectedSha256",sha).put("source",asset.toJson()));
        }
    }

    static Picked restorePicked(JSONObject exact)throws Exception{
        fields(exact,"projectId","expectedUpdatedAt","operationId","expectedSha256","source");id(exact,"projectId");revision(exact,"expectedUpdatedAt");id(exact,"operationId");digest(exact,"expectedSha256");
        ProjectStore.Asset asset=ProjectStore.Asset.fromJson(exact.getJSONObject("source"));basicVideo(asset,false);if(!"picked_document".equals(asset.id))throw bad("Invalid selected document identity");bounded(exact);return new Picked(exact);
    }

    static JSONObject executePicked(Context context,Picked picked,ProjectAssetImport.Progress progress,BooleanSupplier admission)throws Exception{
        worker();if(picked==null||admission==null)throw bad("A private selected document and current editor admission are required");return execute(context,picked.toJson(),progress,picked,admission);
    }

    private static JSONObject execute(Context context,JSONObject request,ProjectAssetImport.Progress progress,Picked picked,BooleanSupplier admission)throws Exception{
        if(progress==null)throw bad("Progress callback is required");COPY_LOCK.lockInterruptibly();try{
            cancelled();String destinationId=request.getString("projectId"),operation=request.getString("operationId");long destinationRevision=request.getLong("expectedUpdatedAt");
            JSONObject normalized=copy(request);if(picked==null)normalized.put("appendToTimeline",false);
            String fingerprint=hash(SketchDocument.canonical(normalized).getBytes(StandardCharsets.UTF_8)),expected=request.getString("expectedSha256");
            File directory=directory(context,destinationId,operation);
            try(SelectedVideoImportLock generationLock=SelectedVideoImportLock.acquire(context,directory)){
            generationLock.createGeneration();File receiptFile=new File(directory,"receipt.json"),output=new File(directory,"source.video"),partial=new File(directory,"source.partial");
            JSONObject journal=read(receiptFile);
            try(ProjectStore store=new ProjectStore(context)){
                if(journal!=null){
                    if(!fingerprint.equals(journal.getString("fingerprint")))throw bad("SELECTED_VIDEO_OPERATION_CONFLICT: keep the exact original request");
                    ProjectStore.Project current=store.get(destinationId);if(current==null)throw bad("Import project no longer exists");ProjectStore.Asset owned=current.asset(journal.getString("assetId"));
                    if(owned!=null)return result(context,current,owned,journal,output,true);
                    if(journal.optBoolean("completed",false))throw bad("The owned selected video was removed; it will not be recreated over later edits");
                }
                if(!admission.getAsBoolean())throw bad("The editing view changed; the selected video was not saved");project(store,destinationId,destinationRevision);
                Source source;
                if(picked==null){ProjectStore.Project selectedProject=project(store,request.getString("sourceProjectId"),request.getLong("sourceProjectUpdatedAt"));source=Source.registered(context,selectedProject.id,selectedProject.updatedAt,video(selectedProject.asset(request.getString("assetId"))));}
                else source=Source.picked(context,ProjectStore.Asset.fromJson(request.getJSONObject("source")));
                if(!expected.equals(sourceHash(context,source)))throw bad("SELECTED_VIDEO_SOURCE_CHANGED: source bytes differ from the pinned hash");
                String assetId=UUID.nameUUIDFromBytes((destinationId+"/selected-video/"+operation).getBytes(StandardCharsets.UTF_8)).toString();
                if(journal==null){
                    if(output.exists()||partial.exists())throw bad("Unreceipted selected video scratch already exists");
                    journal=new JSONObject().put("fingerprint",fingerprint).put("request",normalized).put("source",source.snapshot).put("bytes",source.bytes).put("sha256",expected).put("assetId",assetId).put("completed",false);write(receiptFile,journal);generationLock.syncGeneration();
                }else if(!assetId.equals(journal.getString("assetId"))||source.bytes!=journal.getLong("bytes")||!SketchDocument.canonical(source.snapshot).equals(SketchDocument.canonical(journal.getJSONObject("source"))))throw bad("Selected source metadata changed during retry");
                if(output.exists())verifyFile(context,output,source.bytes,expected);else copySource(context,source,partial,output,expected,progress);generationLock.syncGeneration();
                JSONObject nativeVideo=probe(context,output,source.asset.durationMs);if(!expected.equals(sourceHash(context,source)))throw bad("SELECTED_VIDEO_SOURCE_CHANGED: source bytes changed during copying");
                source.recheck(context);cancelled();if(!admission.getAsBoolean())throw bad("The editing view changed; the selected video was not saved");
                final JSONObject receipt=journal;final Source bound=source;final JSONObject metadata=nativeVideo;
                JSONObject committed;
                if(picked!=null||destinationId.equals(source.projectId))committed=store.guardedProject(destinationId,destinationRevision,current->{if(picked==null)bound.checkRegistered(current);return publish(context,store,current,bound,receipt,metadata,output,admission);});
                else committed=store.guardedProject(source.projectId,source.revision,currentSource->{bound.checkRegistered(currentSource);return store.guardedProject(destinationId,destinationRevision,current->publish(context,store,current,bound,receipt,metadata,output,admission));});
                receipt.put("completed",true);write(receiptFile,receipt);generationLock.syncGeneration();return committed;
            }
            }
        }finally{COPY_LOCK.unlock();}
    }

    private static JSONObject publish(Context context,ProjectStore store,ProjectStore.Project destination,Source source,JSONObject journal,JSONObject metadata,File output,BooleanSupplier admission)throws Exception{
        cancelled();if(!admission.getAsBoolean())throw bad("The editing view changed; the selected video was not saved");grant(context,source.uri);verifyFile(context,output,journal.getLong("bytes"),journal.getString("sha256"));
        if(destination.asset(journal.getString("assetId"))!=null)throw bad("Owned selected video identity already exists");
        ProjectStore.Asset asset=ProjectStore.Asset.fromJson(source.asset.toJson());asset.id=journal.getString("assetId");asset.uri=Uri.fromFile(output).toString();asset.mime=metadata.getString("mime");asset.durationMs=metadata.getLong("durationMs");asset.sizeBytes=source.bytes;asset.seekable=true;asset.persistedReadAccess=true;asset.providerAuthority="";asset.generated=false;asset.role="source";asset.createdAt=System.currentTimeMillis();
        asset.generationMetadata=copy(source.asset.generationMetadata).put("selectedDocumentImport",new JSONObject().put("fingerprint",journal.getString("fingerprint")).put("operationId",journal.getJSONObject("request").getString("operationId")).put("sha256",journal.getString("sha256")).put("bytes",source.bytes).put("sourceAssetId",source.asset.id).put("nativeVideo",metadata));
        destination.assets.add(asset);store.save(destination);return result(context,destination,asset,journal,output,false);
    }

    private static JSONObject result(Context context,ProjectStore.Project destination,ProjectStore.Asset asset,JSONObject journal,File output,boolean replayed)throws Exception{
        if(!Uri.fromFile(output).toString().equals(asset.uri)||asset.generated||!"source".equals(asset.role)||asset.sizeBytes!=journal.getLong("bytes")||!asset.seekable)throw bad("Owned selected video metadata changed");
        JSONObject provenance=asset.generationMetadata.optJSONObject("selectedDocumentImport");if(provenance==null||!journal.getString("fingerprint").equals(provenance.optString("fingerprint"))||!journal.getString("sha256").equals(provenance.optString("sha256")))throw bad("Owned selected video provenance changed");
        JSONObject nativeVideo=provenance.getJSONObject("nativeVideo");if(asset.durationMs!=nativeVideo.getLong("durationMs")||!asset.mime.equals(nativeVideo.getString("mime")))throw bad("Owned selected video native metadata changed");verifyFile(context,output,journal.getLong("bytes"),journal.getString("sha256"));
        return new JSONObject().put("ok",true).put("selectedDocument",true).put("projectId",destination.id).put("updatedAt",destination.updatedAt).put("assetId",asset.id).put("operationId",journal.getJSONObject("request").getString("operationId")).put("sha256",journal.getString("sha256")).put("bytes",asset.sizeBytes).put("mime",asset.mime).put("durationMs",asset.durationMs).put("nativeVideo",copy(nativeVideo)).put("independentCopy",true).put("appendedToTimeline",false).put("replayed",replayed);
    }

    private static void copySource(Context context,Source source,File partial,File output,String expected,ProjectAssetImport.Progress progress)throws Exception{
        plain(context,partial);long prior=partial.isFile()?partial.length():0;if(prior>source.bytes)throw bad("Selected video partial exceeds its pinned length");
        StorageBudget.Check budget=StorageBudget.checkTransfer(partial.getParentFile().getUsableSpace(),source.bytes,prior,ProjectAssetImport.STORAGE_RESERVE_BYTES);if(!budget.allowed)throw bad("SELECTED_VIDEO_WAITING_STORAGE: need "+budget.requiredWithReserveBytes+" bytes including reserve; free "+budget.freeBytes);
        MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[BUFFER],saved=new byte[BUFFER];long copied=0;progress.update(0,source.bytes);
        try(InputStream input=open(context,source.uri);RandomAccessFile stage=new RandomAccessFile(partial,"rw")){
            for(int count;(count=input.read(buffer))!=-1;){cancelled();if(count==0)continue;if(count>source.bytes-copied)throw bad("Selected source exceeds its pinned byte length");
                int prefix=(int)Math.min(count,Math.max(0,prior-copied));if(prefix>0){stage.readFully(saved,0,prefix);for(int i=0;i<prefix;i++)if(saved[i]!=buffer[i])throw bad("SELECTED_VIDEO_PARTIAL_CHANGED: staged prefix differs from the selected source");}
                if(prefix<count)stage.write(buffer,prefix,count-prefix);digest.update(buffer,0,count);copied+=count;progress.update(copied,source.bytes);
            }
            cancelled();if(copied!=source.bytes||!expected.equals(hex(digest.digest())))throw bad("Selected source length or SHA256 changed during copying");stage.getFD().sync();
        }
        source.recheck(context);if(output.exists())throw bad("Owned selected video output already exists");Files.move(partial.toPath(),output.toPath(),StandardCopyOption.ATOMIC_MOVE);
    }

    private static JSONObject probe(Context context,File output,long expectedDuration)throws Exception{
        cancelled();MediaMetadataRetriever retriever=new MediaMetadataRetriever();try{
            retriever.setDataSource(context,Uri.fromFile(output));int width=positive(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),"width"),height=positive(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT),"height");String rawRotation=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);if(rawRotation==null||!rawRotation.matches("-?[0-9]+"))throw bad("Selected video rotation is unavailable");int rotation=Math.floorMod(Integer.parseInt(rawRotation),360);if(rotation%90!=0)throw bad("Unsupported selected video rotation");
            long duration=Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));if(duration<100||duration>Long.MAX_VALUE/1000L||expectedDuration>0&&duration!=expectedDuration)throw bad("Selected video native duration differs from the source metadata");String mime=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE);if(mime==null||!mime.startsWith("video/"))throw bad("Selected bytes are not a native readable video");
            return new JSONObject().put("width",width).put("height",height).put("rotation",rotation).put("durationMs",duration).put("mime",mime);
        }finally{retriever.release();}
    }

    private static final class Metadata {final String name,mime;final long bytes;Metadata(String name,String mime,long bytes){this.name=name;this.mime=mime;this.bytes=bytes;}}
    private static Metadata metadata(Context context,Uri uri)throws Exception{
        grant(context,uri);String mime=context.getContentResolver().getType(uri);if(mime==null||!mime.startsWith("video/"))throw bad("Choose an explicitly selected VIDEO document");String name=null;long bytes=-1;
        try(Cursor cursor=context.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},null,null,null)){
            if(cursor!=null&&cursor.moveToFirst()){int n=cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME),s=cursor.getColumnIndex(OpenableColumns.SIZE);if(n>=0&&!cursor.isNull(n))name=cursor.getString(n);if(s>=0&&!cursor.isNull(s))bytes=cursor.getLong(s);}
        }
        if(name==null||name.isEmpty()||name.length()>512||bytes<1||bytes>ProjectAssetImport.MAX_BYTES)throw bad("Selected VIDEO requires a known name and byte length in 1 byte..10 GiB");cancelled();return new Metadata(name,mime,bytes);
    }

    private static final class Source {
        final ProjectStore.Asset asset;final JSONObject snapshot;final Uri uri;final long bytes,revision;final String projectId;
        Source(ProjectStore.Asset asset,String projectId,long revision)throws Exception{this.asset=ProjectStore.Asset.fromJson(copy(asset.toJson()));snapshot=this.asset.toJson();bounded(snapshot);uri=Uri.parse(asset.uri);bytes=asset.sizeBytes;this.projectId=projectId;this.revision=revision;}
        static Source registered(Context context,String projectId,long revision,ProjectStore.Asset asset)throws Exception{basicVideo(asset,true);Source source=new Source(asset,projectId,revision);source.recheck(context);return source;}
        static Source picked(Context context,ProjectStore.Asset asset)throws Exception{basicVideo(asset,false);Source source=new Source(asset,null,-1);source.recheck(context);return source;}
        void checkRegistered(ProjectStore.Project current)throws Exception{ProjectStore.Asset latest=current.asset(asset.id);if(latest==null||!SketchDocument.canonical(snapshot).equals(SketchDocument.canonical(latest.toJson())))throw bad("Selected registered source changed");}
        void recheck(Context context)throws Exception{Metadata current=metadata(context,uri);if(bytes!=current.bytes||!asset.mime.equals(current.mime)||!asset.name.equals(current.name))throw bad("Selected document metadata changed");}
    }
    private static ProjectStore.Asset video(ProjectStore.Asset asset)throws Exception{basicVideo(asset,true);return asset;}
    private static void basicVideo(ProjectStore.Asset asset,boolean registered){if(asset==null||asset.mime==null||!asset.mime.startsWith("video/")||asset.uri==null||asset.name==null||asset.name.isEmpty()||asset.name.length()>512||asset.sizeBytes<1||asset.sizeBytes>ProjectAssetImport.MAX_BYTES||asset.durationMs<0||asset.durationMs>Long.MAX_VALUE/1000L||registered&&asset.durationMs<100)throw bad("Choose a registered VIDEO with known bounded metadata");}
    private static void grant(Context context,Uri uri){
        if(uri==null||!"content".equals(uri.getScheme())||uri.getAuthority()==null||uri.getAuthority().isEmpty()||uri.getFragment()!=null)throw bad("Only the exact user-selected content document is supported");
        for(UriPermission permission:context.getContentResolver().getPersistedUriPermissions())if(permission.isReadPermission()&&uri.equals(permission.getUri()))return;
        throw bad("The selected document has no exact persisted READ grant; choose it again in Files");
    }
    private static String sourceHash(Context context,Source source)throws Exception{
        source.recheck(context);MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[BUFFER];long bytes=0;try(InputStream input=open(context,source.uri)){for(int count;(count=input.read(buffer))!=-1;){cancelled();if(count==0)continue;if(count>source.bytes-bytes)throw bad("Selected source exceeds its pinned byte length");bytes+=count;digest.update(buffer,0,count);}}if(bytes!=source.bytes)throw bad("Selected source length changed");source.recheck(context);return hex(digest.digest());
    }
    private static InputStream open(Context context,Uri uri)throws Exception{grant(context,uri);cancelled();InputStream input=context.getContentResolver().openInputStream(uri);if(input==null)throw bad("Selected document is unreadable");return input;}
    private static File directory(Context context,String projectId,String operation)throws Exception{
        File root=context.getFilesDir().getCanonicalFile(),directory=new File(new File(new File(root,"selected_video_imports"),projectId),hash(operation.getBytes(StandardCharsets.UTF_8)));plain(context,directory);return directory;
    }
    private static void plain(Context context,File file)throws Exception{
        Path root=context.getFilesDir().getCanonicalFile().toPath(),path=file.toPath().toAbsolutePath().normalize();if(!path.startsWith(root))throw bad("Selected video storage must stay app-owned");for(Path cursor=path;cursor!=null&&!cursor.equals(root);cursor=cursor.getParent())if(Files.isSymbolicLink(cursor))throw bad("Selected video storage symlinks are unavailable");
    }
    private static void verifyFile(Context context,File file,long expectedBytes,String expected)throws Exception{plain(context,file);if(!file.isFile()||file.length()!=expectedBytes)throw bad("Owned selected video bytes changed");MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[BUFFER];try(InputStream input=new FileInputStream(file)){for(int count;(count=input.read(buffer))!=-1;){cancelled();digest.update(buffer,0,count);}}if(!expected.equals(hex(digest.digest())))throw bad("Owned selected video SHA256 changed");}
    private static JSONObject read(File file)throws Exception{
        atomicPaths(file);
        if(!file.isFile()&&!new File(file.getPath()+".bak").isFile())return null;try(InputStream input=new AtomicFile(file).openRead();ByteArrayOutputStream bytes=new ByteArrayOutputStream()){byte[] buffer=new byte[4096];for(int count;(count=input.read(buffer))!=-1;){cancelled();if(count>MAX_JSON-bytes.size())throw bad("Selected video receipt exceeds its bound");bytes.write(buffer,0,count);}return new JSONObject(bytes.toString("UTF-8"));}
    }
    private static void write(File file,JSONObject json)throws Exception{atomicPaths(file);byte[] bytes=bounded(json);cancelled();AtomicFile atomic=new AtomicFile(file);FileOutputStream output=null;try{output=atomic.startWrite();output.write(bytes);atomic.finishWrite(output);}catch(Exception error){if(output!=null)atomic.failWrite(output);throw error;}}
    private static void atomicPaths(File file){for(File candidate:new File[]{file,new File(file.getPath()+".bak"),new File(file.getPath()+".new")})if(Files.isSymbolicLink(candidate.toPath()))throw bad("Selected video receipt symlinks are unavailable");}
    private static byte[] bounded(JSONObject json)throws Exception{byte[] bytes=json.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_JSON)throw bad("Selected video metadata exceeds its bound");return bytes;}
    private static ProjectStore.Project project(ProjectStore store,String id,long revision){ProjectStore.Project project=store.get(id);if(project==null||project.updatedAt!=revision)throw bad("SOURCE_PROJECT_CHANGED: reopen the selected VIDEO source before copying");return project;}
    private static void selected(JSONObject json)throws Exception{if(!Boolean.TRUE.equals(json.get("selectedDocument")))throw bad("selectedDocument must be literal true");}
    private static void fields(JSONObject json,String... allowed){java.util.Set<String> names=new java.util.HashSet<>(java.util.Arrays.asList(allowed));for(java.util.Iterator<String> keys=json.keys();keys.hasNext();)if(!names.contains(keys.next()))throw bad("Unknown selected VIDEO property");}
    private static String id(JSONObject json,String key)throws Exception{Object value=json.get(key);if(!(value instanceof String)||!((String)value).matches("[A-Za-z0-9_-]{1,96}"))throw bad("Invalid "+key);return (String)value;}
    private static long revision(JSONObject json,String key)throws Exception{
        Object value=json.get(key);if(!(value instanceof Number)||!Double.isFinite(((Number)value).doubleValue()))throw bad("Invalid "+key);
        try{long exact=new java.math.BigDecimal(value.toString()).longValueExact();if(exact<0)throw bad("Invalid "+key);return exact;}
        catch(ArithmeticException|NumberFormatException invalid){throw bad("Invalid "+key);}
    }
    private static void digest(JSONObject json,String key)throws Exception{Object value=json.get(key);if(!(value instanceof String)||!((String)value).matches("[a-f0-9]{64}"))throw bad("An exact expected SHA256 is required");}
    private static int positive(String value,String label){int number=Integer.parseInt(value);if(number<1)throw bad("Selected video "+label+" is unavailable");return number;}
    private static String hash(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte value:bytes)out.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return out.toString();}
    private static JSONObject copy(JSONObject json)throws Exception{return new JSONObject(json.toString());}
    private static void worker(){if(Looper.myLooper()==Looper.getMainLooper())throw bad("Selected VIDEO reading/copying requires a worker");}
    private static void cancelled()throws InterruptedException{if(Thread.currentThread().isInterrupted())throw new InterruptedException("Selected VIDEO copy cancelled");}
    private static IllegalArgumentException bad(String message){return new IllegalArgumentException(message);}
}
