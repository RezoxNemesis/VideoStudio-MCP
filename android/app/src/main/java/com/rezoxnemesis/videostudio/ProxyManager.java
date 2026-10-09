package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import androidx.media3.common.C;
import androidx.media3.common.Effect;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.effect.Presentation;
import androidx.media3.transformer.*;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Verified preview derivatives. Timeline exports always resolve original asset URIs. */
@UnstableApi
public final class ProxyManager {
    public static final long HEAVY_VIDEO_THRESHOLD_BYTES=512L*1024*1024;
    private final Context context;
    private final ProjectStore store;
    private final JobManager jobs;
    private final Handler main=new Handler(Looper.getMainLooper());
    public ProxyManager(Context context,ProjectStore store,JobManager jobs){this.context=context.getApplicationContext();this.store=store;this.jobs=jobs;}
    public static boolean shouldProxy(ProjectStore.Asset source){return source!=null&&source.mime!=null&&source.mime.startsWith("video/")&&source.sizeBytes>=HEAVY_VIDEO_THRESHOLD_BYTES;}
    public static String normaliseTier(String tier){
        String value=tier==null||tier.trim().isEmpty()?"540p":tier.trim().toLowerCase(Locale.US);
        if(!Arrays.asList("240p","360p","540p","720p").contains(value))throw new IllegalArgumentException("Proxy tier must be 240p, 360p, 540p or 720p");return value;
    }
    /** Fingerprint of known source identity/metadata, not a claim of a full-source checksum. */
    public static String sourceFingerprint(ProjectStore.Asset source){
        if(source==null)throw new IllegalArgumentException("Proxy source is required");
        long modified=source.generationMetadata.optLong("sourceLastModified",0);
        Uri uri=Uri.parse(originalUri(source));if("file".equals(uri.getScheme()))modified=new File(uri.getPath()).lastModified();
        String identity=source.id+"\u0000"+source.uri+"\u0000"+source.mime+"\u0000"+source.sizeBytes+"\u0000"+source.durationMs+"\u0000"+source.createdAt+"\u0000"+modified+"\u0000"+source.generationMetadata.optString("sourceSha256","");
        try{byte[] digest=MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));StringBuilder out=new StringBuilder();for(byte b:digest)out.append(String.format(Locale.US,"%02x",b&255));return out.toString();}catch(Exception error){throw new IllegalStateException(error);}
    }
    public static String originalUri(ProjectStore.Asset source){return source==null||source.uri==null?"":source.uri;}
    public static String previewUri(ProjectStore.Project project,ProjectStore.Asset source){return previewUri(project,source,project==null?"auto":project.settings.optString("previewTier","auto"));}
    public static String previewUri(ProjectStore.Project project,ProjectStore.Asset source,String tier){
        if(source==null)return "";if(project==null||"original".equals(tier))return originalUri(source);
        String requested=tier==null||tier.isEmpty()?"auto":tier;
        if(!"auto".equals(requested))requested=normaliseTier(requested);
        String fingerprint=sourceFingerprint(source);ProjectStore.Asset best=null;
        for(ProjectStore.Asset candidate:project.assets){
            if(candidate==null||!candidate.generated||!"preview_proxy".equals(candidate.role))continue;
            JSONObject metadata=candidate.generationMetadata;
            if(!source.id.equals(metadata.optString("originalAssetId"))||!fingerprint.equals(metadata.optString("sourceFingerprint"))||!metadata.optBoolean("complete")||!metadata.optBoolean("verified"))continue;
            if(!"auto".equals(requested)&&!requested.equals(metadata.optString("proxyTier")))continue;
            String value=candidate.uri==null?"":candidate.uri;if(value.isEmpty()||value.endsWith(".partial"))continue;
            if(!fileProofMatches(candidate))continue;
            if(best==null||candidate.createdAt>best.createdAt)best=candidate;
        }
        return best==null?originalUri(source):best.uri;
    }
    /** Cheap monitor check; background reuse also decodes and compares the complete byte checksum. */
    public static boolean fileProofMatches(ProjectStore.Asset candidate){
        if(candidate==null||candidate.uri==null)return false;Uri uri=Uri.parse(candidate.uri);
        if(!"file".equals(uri.getScheme())||uri.getPath()==null)return false;File file=new File(uri.getPath());
        JSONObject proof=candidate.generationMetadata.optJSONObject("verification");
        return proof!=null&&proof.optBoolean("ok")&&proof.optString("sha256").matches("[a-f0-9]{64}")
            &&file.isFile()&&file.length()>0&&file.length()==proof.optLong("sizeBytes",-1)
            &&file.lastModified()==candidate.generationMetadata.optLong("verifiedModifiedAt",-1);
    }
    public static JSONObject proxyMetadata(ProjectStore.Asset source,String tier,JSONObject proof){
        try{return new JSONObject().put("originalAssetId",source.id).put("sourceFingerprint",sourceFingerprint(source)).put("proxyTier",normaliseTier(tier)).put("complete",true).put("verified",proof.optBoolean("ok")).put("previewOnly",true).put("verification",new JSONObject(proof.toString()));}catch(Exception error){throw new IllegalArgumentException("Invalid proxy metadata",error);}
    }
    public JobManager.Job request(ProjectStore.Project project,ProjectStore.Asset source,String tier){
        if(jobs==null)throw new IllegalStateException("Proxy job scheduler is unavailable");
        String value=normaliseTier(tier);JobManager.Job job=jobs.submit("Preview proxy • "+source.name,JobManager.Kind.HEAVY,JobManager.Origin.OWNER,state->state.setResult(generate(project,source,value,state)));job.bindInputs(project.id,Collections.singleton(source.id));return job;
    }
    public JSONObject generate(ProjectStore.Project project,ProjectStore.Asset source,String tier,JobManager.Job state)throws Exception{
        if(project==null||source==null||source.mime==null||!source.mime.startsWith("video/"))throw new IllegalArgumentException("Select an owned video for a preview proxy");
        String value=normaliseTier(tier);ProjectStore.Project latest=store.get(project.id);ProjectStore.Asset current=latest==null?null:latest.asset(source.id);
        if(current==null)throw new IllegalStateException("Proxy source is no longer available");
        state.bindInputs(project.id,Collections.singleton(source.id));state.checkActive();
        String fingerprint=sourceFingerprint(current),expectedHash="";
        for(ProjectStore.Asset candidate:latest.assets)if(candidate.generated&&"preview_proxy".equals(candidate.role)&&current.id.equals(candidate.generationMetadata.optString("originalAssetId"))&&fingerprint.equals(candidate.generationMetadata.optString("sourceFingerprint"))&&value.equals(candidate.generationMetadata.optString("proxyTier"))){
            JSONObject saved=candidate.generationMetadata.optJSONObject("verification");if(saved!=null)expectedHash=saved.optString("sha256","");
        }
        state.checkpoint("proxy_prepare",2,"Preparing "+value+" preview proxy");
        File root=new File(context.getCacheDir(),"preview_proxies/"+project.id);if(!root.isDirectory()&&!root.mkdirs())throw new IllegalStateException("Could not create proxy workspace");
        if(root.getUsableSpace()<512L*1024*1024)throw new IllegalStateException("Preview cache needs at least 512 MB of free space");
        File output=new File(root,fingerprint+"_"+value+".mp4"),partial=new File(root,fingerprint+"_"+value+"_"+UUID.randomUUID()+".partial.mp4");JSONObject proof;
        if(output.isFile()){
            try{
                state.checkpoint("proxy_reverify",3,"Checking saved proxy frames and bytes");proof=PlayableMediaVerifier.verify(context,Uri.fromFile(output),true);
                if(!expectedHash.matches("[a-f0-9]{64}")||!expectedHash.equals(proof.optString("sha256")))throw new IllegalStateException("Saved proxy no longer matches its verified bytes");
                state.checkActive();JSONObject reused=register(latest,current,output,value,proof,state);reused.put("reused",true);return reused;
            }catch(InterruptedException|java.util.concurrent.CancellationException cancelled){throw cancelled;}catch(Exception invalid){Files.delete(output.toPath());}
        }
        int height=Integer.parseInt(value.substring(0,value.length()-1));boolean audio=MediaTrackProbe.inspect(context.getContentResolver(),Uri.parse(current.uri)).optBoolean("hasAudio",false);
        List<Effect> effects=Collections.singletonList(Presentation.createForHeight(height));
        EditedMediaItem item=new EditedMediaItem.Builder(MediaItem.fromUri(Uri.parse(current.uri))).setEffects(new Effects(Collections.<AudioProcessor>emptyList(),effects)).setRemoveAudio(!audio).build();
        Set<Integer> tracks=new HashSet<>();tracks.add(C.TRACK_TYPE_VIDEO);if(audio)tracks.add(C.TRACK_TYPE_AUDIO);
        Composition composition=new Composition.Builder(new EditedMediaItemSequence.Builder(tracks).addItem(item).build()).build();
        CountDownLatch done=new CountDownLatch(1);AtomicReference<String> error=new AtomicReference<>();AtomicInteger progress=new AtomicInteger(0);
        Transformer transformer=new Transformer.Builder(context).setVideoMimeType(MimeTypes.VIDEO_H264).setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(new DefaultEncoderFactory.Builder(context).setEnableFallback(true).build()).addListener(new Transformer.Listener(){
                @Override public void onCompleted(Composition composition,ExportResult result){done.countDown();}
                @Override public void onError(Composition composition,ExportResult result,ExportException failure){error.set(failure.toString());done.countDown();}
            }).build();
        main.post(()->{try{transformer.start(composition,partial.getAbsolutePath());}catch(Exception failure){error.set(failure.toString());done.countDown();}});
        long deadline=System.currentTimeMillis()+Math.max(180000L,Math.min(6*60*60*1000L,current.durationMs>0?current.durationMs*6+30000:900000));
        try{
            while(!done.await(500,TimeUnit.MILLISECONDS)){
                if(Thread.currentThread().isInterrupted())throw new InterruptedException("Proxy cancelled");
                if(System.currentTimeMillis()>deadline)throw new IllegalStateException("Preview proxy encoding timed out");
                if(root.getUsableSpace()<128L*1024*1024)throw new IllegalStateException("Preview proxy stopped before storage became full");
                main.post(()->{try{ProgressHolder holder=new ProgressHolder();if(transformer.getProgress(holder)==Transformer.PROGRESS_STATE_AVAILABLE)progress.set(holder.progress);}catch(Exception ignored){}});
                state.checkpoint("proxy_encode",Math.min(94,2+progress.get()*92/100),"Encoding "+value+" preview proxy");
            }
            if(error.get()!=null)throw new IllegalStateException(error.get());state.checkpoint("proxy_verify",96,"Decoding and checksumming preview proxy");
            proof=PlayableMediaVerifier.verify(context,Uri.fromFile(partial),true);
            if(current.durationMs>0&&Math.abs(proof.getLong("durationMs")-current.durationMs)>Math.max(250,current.durationMs/30))throw new IllegalStateException("Preview proxy duration differs from the source");
            ProjectStore.Project fresh=store.get(project.id);ProjectStore.Asset freshSource=fresh==null?null:fresh.asset(source.id);
            if(freshSource==null||!fingerprint.equals(sourceFingerprint(freshSource)))throw new IllegalStateException("Source changed before proxy publication; regenerate from the current source");
            state.commit(active->{if(output.exists())throw new IllegalStateException("Another proxy job already published this tier");Files.move(partial.toPath(),output.toPath());});return register(fresh,freshSource,output,value,proof,state);
        }finally{main.post(()->{try{transformer.cancel();}catch(Exception ignored){}});Files.deleteIfExists(partial.toPath());}
    }
    private JSONObject register(ProjectStore.Project project,ProjectStore.Asset source,File output,String tier,JSONObject proof,JobManager.Job state)throws Exception{
        JSONObject metadata=proxyMetadata(source,tier,proof).put("verifiedModifiedAt",output.lastModified());final ProjectStore.Asset[] published=new ProjectStore.Asset[1];
        state.commit(active->{published[0]=store.registerGeneratedAsset(project,Uri.fromFile(output),source.name+" ["+tier+" proxy].mp4","preview_proxy",false);store.updateAssetMetadata(project.id,published[0].id,metadata,output.length());});
        ProjectStore.Asset proxy=published[0];state.checkpoint("proxy_ready",100,"Verified "+tier+" preview proxy ready");
        return new JSONObject().put("ok",true).put("assetId",proxy.id).put("originalAssetId",source.id).put("uri",proxy.uri).put("proxyTier",tier).put("verification",proof);
    }
}
