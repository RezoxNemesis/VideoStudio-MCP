package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.DocumentsContract;
import android.system.Os;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Clock;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.FrameDropEffect;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.DefaultAssetLoaderFactory;
import androidx.media3.transformer.DefaultDecoderFactory;
import androidx.media3.transformer.DefaultEncoderFactory;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import androidx.media3.transformer.Effects;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.ProgressHolder;
import androidx.media3.transformer.Transformer;
import androidx.media3.transformer.VideoEncoderSettings;
import org.json.JSONObject;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Preview-only, bounded proxies. Original asset URIs remain the export inputs. */
@UnstableApi
public final class ProxyManager {
    public static final long HEAVY_VIDEO_THRESHOLD_BYTES=512L*1024L*1024L;
    private static final long DISK_RESERVE_BYTES=128L*1024L*1024L;
    private static final long PROVIDER_PROBE_TTL_MS=30_000L;
    private static final int VALIDATION_VERSION=2;
    private static final ConcurrentHashMap<String,IdentityCache> IDENTITIES=new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String,Boolean> FAILED_FILES=new ConcurrentHashMap<>();
    private static final ThreadPoolExecutor PROBES=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(128),task->{Thread thread=new Thread(task,"preview-proxy-identities");thread.setDaemon(true);return thread;});
    private final Context context;
    private final ProjectStore store;
    private final JobManager jobs;
    private final Handler main=new Handler(Looper.getMainLooper());

    public ProxyManager(Context context,ProjectStore store,JobManager jobs) {
        this.context=context.getApplicationContext();this.store=store;this.jobs=jobs;
    }
    /** Automatic import policy; an owner can explicitly request a smaller video too. */
    public static boolean shouldProxy(ProjectStore.Asset source) {
        return source!=null&&source.mime!=null&&source.mime.startsWith("video/")
                &&source.sizeBytes>=HEAVY_VIDEO_THRESHOLD_BYTES&&!"preview_proxy".equals(source.role);
    }
    public static String originalUri(ProjectStore.Asset source){return source==null||source.uri==null?"":source.uri;}
    /** A decoder failure excludes that exact cached inode state; source playback can retry immediately. */
    public static void invalidatePreview(String uri) {
        File file=localFile(uri);if(file==null)return;
        if(FAILED_FILES.size()>=128)FAILED_FILES.clear();FAILED_FILES.put(fileState(file),true);
    }
    public static String previewUri(ProjectStore.Project project,ProjectStore.Asset source){return previewUri(null,project,source,"720p");}
    public static String previewUri(ProjectStore.Project project,ProjectStore.Asset source,String tier){return previewUri(null,project,source,tier);}

    /** No provider query or media decode on the UI thread. Until an async probe is ready, use source. */
    public static String previewUri(Context context,ProjectStore.Project project,ProjectStore.Asset source,String tier) {
        if(source==null||project==null||source.id==null)return originalUri(source);
        String resolvedTier;
        try{resolvedTier=Tier.of(tier).name;}catch(IllegalArgumentException error){return originalUri(source);}
        SourceIdentity identity=previewIdentity(context,source);
        if(identity==null)return originalUri(source);
        ProjectStore.Asset best=null;
        for(ProjectStore.Asset candidate:project.assets) {
            if(!matches(candidate,source.id,resolvedTier,identity))continue;
            if(best==null||candidate.createdAt>best.createdAt)best=candidate;
        }
        return best==null?originalUri(source):best.uri;
    }
    /** This metadata alone does not make a proxy eligible for playback. */
    public static JSONObject proxyMetadata(String originalAssetId,String tier,boolean complete) {
        JSONObject metadata=new JSONObject();
        put(metadata,"originalAssetId",originalAssetId==null?"":originalAssetId);put(metadata,"proxyTier",Tier.of(tier).name);
        put(metadata,"complete",complete);put(metadata,"previewOnly",true);return metadata;
    }
    public JobManager.Job request(ProjectStore.Project project,ProjectStore.Asset source,String tier){return enqueue(project,source,tier,false);}
    public JobManager.Job requestOwner(ProjectStore.Project project,ProjectStore.Asset source,String tier){return enqueue(project,source,tier,true);}
    private JobManager.Job enqueue(ProjectStore.Project project,ProjectStore.Asset source,String tier,boolean owner) {
        if(project==null||source==null||source.id==null)throw new IllegalArgumentException("Project and source are required");
        if(source.mime==null||!source.mime.startsWith("video/")||"preview_proxy".equals(source.role))throw new IllegalArgumentException("A preview proxy requires an original video asset");
        Tier resolved=Tier.of(tier);String projectId=project.id,sourceId=source.id;
        JobManager.Work work=state->generate(projectId,sourceId,resolved,state);String name="Preview proxy • "+source.name+" • "+resolved.name;
        return owner?jobs.submitOwnerPriority(name,JobManager.Kind.HEAVY,work):jobs.submit(name,JobManager.Kind.HEAVY,work);
    }

    private void generate(String projectId,String sourceId,Tier tier,JobManager.Job state)throws Exception {
        state.checkpoint("proxy_prepare",1,"Probing original video and requested "+tier.name+" tier");
        ProjectStore.Project project=store.get(projectId);ProjectStore.Asset source=project==null?null:project.asset(sourceId);
        if(source==null)throw new IOException("Proxy source is no longer available");
        SourceIdentity identity=SourceIdentity.capture(context,source);SourceMedia media=SourceMedia.inspect(context,source);int[] size=tier.size(media.width,media.height);
        for(ProjectStore.Asset candidate:project.assets)if(matches(candidate,sourceId,tier.name,identity)) {
            Validation inspected;
            try{inspected=validate(localFile(candidate.uri),media,size);}
            catch(Exception invalidCache){invalidatePreview(candidate.uri);state.checkpoint("proxy_prepare",2,"Cached proxy failed decoded-frame inspection; regenerating requested tier");continue;}
            remember(source,identity);checkCancelled(state);
            state.setResult(result(candidate,identity,tier,inspected,true,project.revision));
            state.checkpoint("proxy_ready",100,"Validated "+tier.name+" preview proxy reused");return;
        }
        File root=new File(context.getCacheDir(),"preview_proxies/"+safeName(projectId));
        if(!root.isDirectory()&&!root.mkdirs())throw new IOException("Could not create proxy workspace");
        File target=new File(root,safeName(sourceId)+"_"+tier.name+"_"+identity.fingerprint.substring(0,32)+".mp4");
        if(target.isFile()&&!FAILED_FILES.containsKey(fileState(target))) {
            Validation inspected=null;
            try {
                inspected=validate(target,media,size);
            }catch(Exception invalidCache){state.checkpoint("proxy_prepare",2,"Cached proxy failed inspection; regenerating requested tier");}
            if(inspected!=null) {
                checkCancelled(state);ProjectStore.Asset cached=register(projectId,sourceId,target,tier,identity,inspected);
                remember(source,identity);state.setResult(result(cached,identity,tier,inspected,true,store.get(projectId).revision));
                state.checkpoint("proxy_ready",100,"Validated cached preview proxy registered");return;
            }
        }
        long estimatedBytes=estimatedBytes(media.durationMs,tier.videoBitrate,media.hasAudio);requireDisk(root,estimatedBytes+DISK_RESERVE_BYTES);
        File partial=new File(root,target.getName()+"."+state.id+".partial.mp4");
        if(partial.exists()&&!partial.delete())throw new IOException("Could not reset incomplete proxy");
        try {
            state.checkpoint("proxy_encode",3,"Encoding "+size[0]+"×"+size[1]+" preview with original audio");
            encode(source,media,size,tier,partial,root,state);checkCancelled(state);
            state.checkpoint("proxy_validate",97,"Inspecting container, audio and a bounded decoded video frame");
            Validation inspected=validate(partial,media,size);ProjectStore.Project fresh=store.get(projectId);ProjectStore.Asset freshSource=fresh==null?null:fresh.asset(sourceId);
            if(freshSource==null||!identity.fingerprint.equals(SourceIdentity.capture(context,freshSource).fingerprint))throw new IOException("Original media changed while the proxy was encoding");
            checkCancelled(state);requireDisk(root,DISK_RESERVE_BYTES);try(FileOutputStream stream=new FileOutputStream(partial,true)){stream.getFD().sync();}
            try{Os.rename(partial.getAbsolutePath(),target.getAbsolutePath());}catch(Exception error){throw new IOException("Could not atomically publish preview proxy",error);}
            inspected=new Validation(inspected.metadata,inspected.audioSample,target.length(),target.lastModified());
            ProjectStore.Asset registered=register(projectId,sourceId,target,tier,identity,inspected);remember(freshSource,identity);
            JSONObject details=result(registered,identity,tier,inspected,false,store.get(projectId).revision);put(details,"estimatedStorageBytes",estimatedBytes);state.setResult(details);
            state.checkpoint("proxy_ready",100,"Validated "+tier.name+" preview proxy ready");
        }finally{if(partial.exists())partial.delete();}
    }

    private void encode(ProjectStore.Asset source,SourceMedia media,int[] size,Tier tier,File partial,File root,JobManager.Job state)throws Exception {
        MediaItem input=MediaItem.fromUri(Uri.parse(source.uri));
        java.util.List<Effect> video=Arrays.asList(Presentation.createForWidthAndHeight(size[0],size[1],Presentation.LAYOUT_SCALE_TO_FIT),FrameDropEffect.createDefaultFrameDropEffect(30));
        EditedMediaItem item=new EditedMediaItem.Builder(input).setRemoveAudio(!media.hasAudio).setEffects(new Effects(Collections.emptyList(),video)).build();
        EditedMediaItemSequence sequence=media.hasAudio?EditedMediaItemSequence.withAudioAndVideoFrom(Collections.singletonList(item)):EditedMediaItemSequence.withVideoFrom(Collections.singletonList(item));
        Composition composition=new Composition.Builder(sequence).setHdrMode(Composition.HDR_MODE_TONE_MAP_HDR_TO_SDR_USING_OPEN_GL).build();
        CountDownLatch done=new CountDownLatch(1);AtomicReference<Transformer> running=new AtomicReference<>();AtomicReference<Exception> error=new AtomicReference<>();
        AtomicBoolean cancelled=new AtomicBoolean();AtomicInteger progress=new AtomicInteger(-1);
        main.post(()->{
            if(cancelled.get()){done.countDown();return;}
            try {
                DefaultEncoderFactory encoder=new DefaultEncoderFactory.Builder(context).setEnableFallback(true).setEnableFormatFallback(false)
                        .setRequestedVideoEncoderSettings(new VideoEncoderSettings.Builder().setBitrate(tier.videoBitrate).build()).build();
                DefaultDecoderFactory decoder=new DefaultDecoderFactory.Builder(context).setEnableDecoderFallback(true).build();
                Transformer transformer=new Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
                        .setEncoderFactory(encoder).setAssetLoaderFactory(new DefaultAssetLoaderFactory(context,decoder,Clock.DEFAULT,null))
                        .addListener(new Transformer.Listener(){
                            @Override public void onCompleted(Composition completed,ExportResult result){done.countDown();}
                            @Override public void onError(Composition failed,ExportResult result,ExportException failure){error.set(failure);done.countDown();}
                        }).build();
                running.set(transformer);transformer.start(composition,partial.getAbsolutePath());
            }catch(Exception failure){error.set(failure);done.countDown();}
        });
        try {
            int lastProgress=-2;long lastCheckpoint=0;
            while(!done.await(500,TimeUnit.MILLISECONDS)) {
                checkCancelled(state);requireDisk(root,DISK_RESERVE_BYTES);
                main.post(()->{
                    Transformer transformer=running.get();if(transformer==null||done.getCount()==0||cancelled.get())return;
                    try{ProgressHolder holder=new ProgressHolder();if(transformer.getProgress(holder)==Transformer.PROGRESS_STATE_AVAILABLE)progress.set(holder.progress);}catch(Exception ignored){}
                });
                int current=progress.get();long now=System.currentTimeMillis();
                if(current!=lastProgress||now-lastCheckpoint>=2000) {
                    state.checkpoint("proxy_encode",current<0?3:Math.min(96,Math.max(3,current)),current<0?"Encoding preview; native progress currently unavailable":"Encoding "+tier.name+" preview • "+current+"%");
                    lastProgress=current;lastCheckpoint=now;
                }
            }
            checkCancelled(state);if(error.get()!=null)throw new IOException("Native proxy encode failed: "+error.get().getMessage(),error.get());
        }finally{
            cancelled.set(true);CountDownLatch stopped=new CountDownLatch(1);
            main.post(()->{try{Transformer transformer=running.get();if(transformer!=null)transformer.cancel();}catch(Exception ignored){}finally{stopped.countDown();}});
            // Settle native cancellation before removing a file the codec still owns.
            boolean interrupted=Thread.interrupted();
            try{if(!stopped.await(10,TimeUnit.SECONDS))throw new IOException("Native proxy cancellation did not settle");}finally{if(interrupted)Thread.currentThread().interrupt();}
        }
    }

    private ProjectStore.Asset register(String projectId,String sourceId,File file,Tier tier,SourceIdentity identity,Validation validation)throws Exception {
        Uri uri=Uri.fromFile(file);AssetProbe.Result probe=AssetProbe.probe(context.getContentResolver(),uri);
        if(!probe.readable||probe.sizeBytes!=validation.length)throw new IOException("Published preview proxy is not readable");
        AtomicReference<String> registeredId=new AtomicReference<>();
        ProjectStore.Project updated=store.edit(projectId,ProjectStore.ANY_REVISION,"Register validated preview proxy",latest->{
            ProjectStore.Asset current=latest.asset(sourceId);
            if(current==null||!identity.fingerprint.equals(SourceIdentity.capture(context,current).fingerprint))throw new IOException("Original media changed before proxy registration");
            ProjectStore.Asset proxy=null;
            for(ProjectStore.Asset candidate:latest.assets)if(uri.toString().equals(candidate.uri)&&"preview_proxy".equals(candidate.role)){proxy=candidate;break;}
            if(proxy==null){proxy=new ProjectStore.Asset();proxy.id=UUID.randomUUID().toString();latest.assets.add(proxy);}
            proxy.uri=uri.toString();proxy.name=current.name+" • "+tier.name+" preview";proxy.mime=validation.metadata.mime;proxy.durationMs=validation.metadata.durationMs;
            proxy.width=validation.metadata.width;proxy.height=validation.metadata.height;proxy.rotation=validation.metadata.rotation;proxy.hasAudio=validation.metadata.hasAudio;proxy.sizeBytes=validation.length;
            proxy.seekable=probe.seekable;proxy.persistedReadAccess=probe.persistedReadAccess;proxy.providerAuthority=probe.providerAuthority;proxy.role="preview_proxy";proxy.generated=true;proxy.createdAt=System.currentTimeMillis();
            JSONObject metadata=proxyMetadata(sourceId,tier.name,true);put(metadata,"sourceFingerprint",identity.fingerprint);
            put(metadata,"sourceIdentityScope","URI, imported hash when present, declared metadata, current size and modification time when available");
            put(metadata,"validationVersion",VALIDATION_VERSION);put(metadata,"validation",validation.toJson());put(metadata,"originalAudioPreserved",validation.metadata.hasAudio);
            proxy.generationMetadata=metadata;registeredId.set(proxy.id);
        });
        return updated.asset(registeredId.get());
    }

    private static Validation validate(File file,SourceMedia source,int[] size)throws Exception {
        MediaImportInspector.Metadata metadata=MediaImportInspector.inspect(file,MimeTypes.VIDEO_MP4,"preview.mp4");
        int displayWidth=metadata.width,displayHeight=metadata.height;
        if(Math.floorMod(metadata.rotation,180)==90){int swap=displayWidth;displayWidth=displayHeight;displayHeight=swap;}
        if(!metadata.mime.startsWith("video/")||displayWidth!=size[0]||displayHeight!=size[1])throw new IOException("Proxy dimensions or media type do not match the requested tier");
        if(Math.abs(metadata.durationMs-source.durationMs)>Math.max(250,source.durationMs/100))throw new IOException("Proxy duration does not match original media");
        if(source.hasAudio&&!metadata.hasAudio)throw new IOException("Proxy lost the original audio track");
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();Bitmap frame=null;long length=file.length(),modified=file.lastModified();boolean audioSample=false;
        try(FileInputStream input=new FileInputStream(file)) {
            retriever.setDataSource(input.getFD());frame=retriever.getScaledFrameAtTime(Math.min(500_000L,metadata.durationMs*500L),MediaMetadataRetriever.OPTION_CLOSEST_SYNC,320,320);
            if(frame==null||frame.getWidth()==0||frame.getHeight()==0)throw new IOException("Proxy has no decodable representative video frame");
        }finally{if(frame!=null)frame.recycle();retriever.release();}
        MediaExtractor extractor=new MediaExtractor();
        try {
            extractor.setDataSource(file.getAbsolutePath());boolean avc=false;
            for(int i=0;i<extractor.getTrackCount();i++) {
                MediaFormat format=extractor.getTrackFormat(i);String mime=format.getString(MediaFormat.KEY_MIME);
                if(MimeTypes.VIDEO_H264.equals(mime))avc=true;
                if(mime!=null&&mime.startsWith("audio/")) {
                    if(!MimeTypes.AUDIO_AAC.equals(mime))throw new IOException("Proxy audio is not AAC");
                    extractor.selectTrack(i);audioSample=extractor.readSampleData(ByteBuffer.allocateDirect(65536),0)>0;extractor.unselectTrack(i);
                }
            }
            if(!avc)throw new IOException("Proxy does not contain the requested H.264 video track");
            if(source.hasAudio&&!audioSample)throw new IOException("Proxy audio track has no readable sample");
        }finally{extractor.release();}
        if(file.length()!=length||file.lastModified()!=modified)throw new IOException("Proxy changed during validation");
        FAILED_FILES.remove(fileState(file));
        return new Validation(metadata,audioSample,length,modified);
    }
    private static boolean matches(ProjectStore.Asset candidate,String sourceId,String tier,SourceIdentity identity) {
        if(candidate==null||!candidate.generated||!"preview_proxy".equals(candidate.role)||candidate.generationMetadata==null)return false;
        JSONObject metadata=candidate.generationMetadata,validation=metadata.optJSONObject("validation");
        if(!metadata.optBoolean("complete")||metadata.optInt("validationVersion")!=VALIDATION_VERSION||validation==null||!validation.optBoolean("videoFrameDecoded")
                ||!sourceId.equals(metadata.optString("originalAssetId"))||!tier.equals(metadata.optString("proxyTier"))||!identity.fingerprint.equals(metadata.optString("sourceFingerprint")))return false;
        File file=localFile(candidate.uri);
        return (!identity.requiresAudio||candidate.hasAudio)&&file!=null&&!FAILED_FILES.containsKey(fileState(file))&&file.isFile()&&file.canRead()&&file.length()>0&&file.length()==validation.optLong("fileLength",-1)
                &&file.lastModified()==validation.optLong("fileModifiedMs",-1)&&(!candidate.hasAudio||validation.optBoolean("audioSampleReadable"));
    }

    private static SourceIdentity previewIdentity(Context context,ProjectStore.Asset source) {
        Uri uri;try{uri=Uri.parse(originalUri(source));}catch(Exception error){return null;}
        if("file".equals(uri.getScheme()))try{return SourceIdentity.local(source);}catch(Exception error){return null;}
        if(context==null||!"content".equals(uri.getScheme()))return null;
        String key=declaredIdentity(source);IdentityCache cache=IDENTITIES.get(key);
        if(cache==null) {
            if(IDENTITIES.size()>=128)IDENTITIES.clear();IdentityCache created=new IdentityCache();IdentityCache previous=IDENTITIES.putIfAbsent(key,created);cache=previous==null?created:previous;
        }
        long age=System.currentTimeMillis()-cache.probedAtMs;boolean fresh=age>=0&&age<PROVIDER_PROBE_TTL_MS;
        if(age>=PROVIDER_PROBE_TTL_MS-5000&&cache.refreshing.compareAndSet(false,true)) {
            IdentityCache target=cache;ProjectStore.Asset snapshot=ProjectStore.Asset.fromJson(source.toJson());Context app=context.getApplicationContext();
            try{PROBES.execute(()->{try{target.identity=SourceIdentity.capture(app,snapshot);}catch(Exception error){target.identity=null;}
                finally{target.probedAtMs=System.currentTimeMillis();target.refreshing.set(false);}});}
            catch(RejectedExecutionException busy){target.probedAtMs=System.currentTimeMillis();target.refreshing.set(false);target.identity=null;}
        }
        return fresh?cache.identity:null;
    }
    private static void remember(ProjectStore.Asset source,SourceIdentity identity) {
        IdentityCache cache=new IdentityCache();cache.identity=identity;cache.probedAtMs=System.currentTimeMillis();
        if(IDENTITIES.size()>=128)IDENTITIES.clear();IDENTITIES.put(declaredIdentity(source),cache);
    }
    private static final class IdentityCache {volatile SourceIdentity identity;volatile long probedAtMs;final AtomicBoolean refreshing=new AtomicBoolean();}
    private static final class SourceIdentity {
        final String fingerprint;final boolean requiresAudio;
        SourceIdentity(ProjectStore.Asset source,long size,long modified)throws Exception {
            requiresAudio=source.hasAudio;String value=declaredIdentity(source)+"\nstatSize="+size+"\nmodified="+modified;
            byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            final char[] digits="0123456789abcdef".toCharArray();char[] hex=new char[bytes.length*2];
            for(int index=0;index<bytes.length;index++){int item=bytes[index]&255;hex[index*2]=digits[item>>>4];hex[index*2+1]=digits[item&15];}fingerprint=new String(hex);
        }
        static SourceIdentity local(ProjectStore.Asset source)throws Exception {
            File file=localFile(source.uri);if(file==null||!file.isFile()||!file.canRead()||file.length()<=0)throw new IOException("Original video is not readable");
            return new SourceIdentity(source,file.length(),file.lastModified());
        }
        static SourceIdentity capture(Context context,ProjectStore.Asset source)throws Exception {
            Uri uri=Uri.parse(originalUri(source));
            if(!"file".equals(uri.getScheme())&&!"content".equals(uri.getScheme()))throw new IOException("Proxy source must be local or an imported document URI");
            AssetProbe.Result probe=AssetProbe.probe(context.getContentResolver(),uri);if(!probe.readable)throw new IOException("Original video URI is no longer readable");
            if("file".equals(uri.getScheme()))return local(source);
            long modified=-1;
            try(Cursor cursor=context.getContentResolver().query(uri,new String[]{DocumentsContract.Document.COLUMN_LAST_MODIFIED},null,null,null)) {
                if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))modified=cursor.getLong(0);
            }catch(Exception ignored){}
            return new SourceIdentity(source,probe.sizeBytes,modified);
        }
    }
    private static String declaredIdentity(ProjectStore.Asset source) {
        JSONObject imported=source.importMetadata,generated=source.generationMetadata;
        String hash=imported==null?"":imported.optString("sha256",imported.optString("contentSha256",""));
        if(hash.isEmpty()&&generated!=null)hash=generated.optString("sha256","");
        return source.id+"\n"+source.uri+"\n"+source.mime+"\n"+source.sizeBytes+"\n"+source.durationMs+"\n"+source.width+"x"+source.height+"/"+source.rotation+"\naudio="+source.hasAudio+"\nsha256="+hash;
    }
    private static final class SourceMedia {
        final long durationMs;final int width,height;final boolean hasAudio;
        SourceMedia(long durationMs,int width,int height,boolean hasAudio){this.durationMs=durationMs;this.width=width;this.height=height;this.hasAudio=hasAudio;}
        static SourceMedia inspect(Context context,ProjectStore.Asset source)throws Exception {
            MediaMetadataRetriever retriever=new MediaMetadataRetriever();
            try {
                retriever.setDataSource(context,Uri.parse(source.uri));long duration=Long.parseLong(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                int width=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)),height=Integer.parseInt(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                String value=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);int rotation=value==null?0:Integer.parseInt(value);
                if(Math.floorMod(rotation,180)==90){int swap=width;width=height;height=swap;}
                if(duration<=0||width<2||height<2)throw new IOException("Original video has no readable duration or dimensions");
                boolean audio="yes".equalsIgnoreCase(retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO));
                if(source.hasAudio&&!audio)throw new IOException("Original audio track could not be inspected");return new SourceMedia(duration,width,height,audio);
            }catch(RuntimeException error){throw new IOException("Original video cannot be inspected for proxy generation",error);}finally{retriever.release();}
        }
    }
    private static final class Validation {
        final MediaImportInspector.Metadata metadata;final boolean audioSample;final long length,modified;
        Validation(MediaImportInspector.Metadata metadata,boolean audioSample,long length,long modified){this.metadata=metadata;this.audioSample=audioSample;this.length=length;this.modified=modified;}
        JSONObject toJson() {
            JSONObject result=new JSONObject();put(result,"scope","container metadata, duration/dimensions, one scaled320px video frame and one audio sample; not full playback");
            put(result,"videoFrameDecoded",true);put(result,"audioSampleReadable",audioSample);put(result,"fileLength",length);put(result,"fileModifiedMs",modified);
            put(result,"durationMs",metadata.durationMs);put(result,"width",metadata.width);put(result,"height",metadata.height);put(result,"hasAudio",metadata.hasAudio);return result;
        }
    }
    private static final class Tier {
        final String name;final int shortEdge,maxEdge,videoBitrate;
        Tier(String name,int shortEdge,int maxEdge,int videoBitrate){this.name=name;this.shortEdge=shortEdge;this.maxEdge=maxEdge;this.videoBitrate=videoBitrate;}
        static Tier of(String value) {
            String name=value==null||value.trim().isEmpty()?"720p":value.trim().toLowerCase(Locale.US);
            switch(name){case"360p":return new Tier(name,360,640,750_000);case"540p":return new Tier(name,540,960,1_400_000);case"720p":return new Tier(name,720,1280,2_500_000);
                default:throw new IllegalArgumentException("Proxy tier must be 360p, 540p or 720p");}
        }
        int[] size(int width,int height) {
            double factor=Math.min(1,Math.min((double)shortEdge/Math.min(width,height),(double)maxEdge/Math.max(width,height)));
            return new int[]{Math.max(2,((int)Math.floor(width*factor)/2)*2),Math.max(2,((int)Math.floor(height*factor)/2)*2)};
        }
    }
    private static JSONObject result(ProjectStore.Asset proxy,SourceIdentity identity,Tier tier,Validation validation,boolean reused,long revision) {
        JSONObject result=new JSONObject();put(result,"assetId",proxy.id);put(result,"proxyUri",proxy.uri);put(result,"originalAssetId",proxy.generationMetadata.optString("originalAssetId"));
        put(result,"previewOnly",true);put(result,"tier",tier.name);put(result,"sourceFingerprint",identity.fingerprint);put(result,"reused",reused);put(result,"projectRevision",revision);
        put(result,"validation",validation.toJson());put(result,"originalAudioPreserved",proxy.hasAudio);return result;
    }
    private static File localFile(String value){try{Uri uri=Uri.parse(value==null?"":value);return "file".equals(uri.getScheme())&&uri.getPath()!=null?new File(uri.getPath()):null;}catch(Exception error){return null;}}
    private static String fileState(File file){return file.getAbsolutePath()+"\n"+file.length()+"\n"+file.lastModified();}
    private static long estimatedBytes(long durationMs,int videoBitrate,boolean audio) {
        double estimate=(double)durationMs/1000*(videoBitrate+(audio?192_000:0))/8*1.5+16L*1024*1024;
        if(!Double.isFinite(estimate)||estimate>Long.MAX_VALUE-DISK_RESERVE_BYTES)throw new IllegalArgumentException("Video duration exceeds proxy storage budget");return(long)Math.ceil(estimate);
    }
    private static void requireDisk(File root,long required)throws IOException{long available=new StatFs(root.getAbsolutePath()).getAvailableBytes();if(available<required)throw new IOException("Insufficient proxy storage: needs "+required+" bytes including reserved free space; available "+available);}
    private static void checkCancelled(JobManager.Job job)throws InterruptedException{if(Thread.currentThread().isInterrupted()||JobManager.STATE_CANCELLED.equals(job.state))throw new InterruptedException("Preview proxy cancelled");}
    private static String safeName(String value){String safe=value==null?"asset":value.replaceAll("[^a-zA-Z0-9._-]+","_");if(safe.equals(".")||safe.equals(".."))safe="_"+safe.replace('.','_');return safe.isEmpty()?"asset":safe.substring(0,Math.min(128,safe.length()));}
    private static void put(JSONObject object,String key,Object value){try{object.put(key,value);}catch(Exception error){throw new IllegalArgumentException("Invalid proxy metadata",error);}}
}
