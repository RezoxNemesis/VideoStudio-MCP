package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** Immutable, byte-bound original media for one captured export graph. */
final class RenderSourceIdentity {
    interface Opener {InputStream open(String uri)throws Exception;}
    static final class Snapshot {
        final ProjectStore.Project boundProject;final JSONObject manifest;final String sessionId;final File inputRoot;
        Snapshot(ProjectStore.Project boundProject,JSONObject manifest,String sessionId){this(boundProject,manifest,sessionId,null);}
        Snapshot(ProjectStore.Project boundProject,JSONObject manifest,String sessionId,File inputRoot){this.boundProject=boundProject;this.manifest=manifest;this.sessionId=sessionId;this.inputRoot=inputRoot;}
    }
    private static final java.util.concurrent.locks.ReentrantLock PIN_LOCK=new java.util.concurrent.locks.ReentrantLock(true);
    private static final String RENDERER="segmented-v1/media3-1.11.1";
    private final File inputs;private final Opener opener;
    RenderSourceIdentity(Context context){this(new File(context.getApplicationContext().getFilesDir(),"render_inputs_v1"),uri->context.getApplicationContext().getContentResolver().openInputStream(Uri.parse(uri)));}
    RenderSourceIdentity(File inputDirectory,Opener opener){this.inputs=inputDirectory;this.opener=opener;}
    Snapshot capture(ProjectStore.Project project,String aspect,String quality,BooleanSupplier cancelled)throws Exception{
        check(cancelled);
        if(project==null||project.id==null||project.id.isEmpty()||project.clips.isEmpty())throw new IllegalArgumentException("A bound nonempty project is required");
        ProjectStore.Project graph=ProjectStore.Project.fromJson(project.snapshotJson());
        ProjectStore.Project bound=ProjectStore.Project.fromJson(graph.snapshotJson());
        String graphHash=hash(canonical(graph.snapshotJson()).getBytes(StandardCharsets.UTF_8));
        boolean solo=false;for(ProjectStore.Track track:graph.tracks)solo|=track.solo;
        LinkedHashMap<String,Binding> bindings=new LinkedHashMap<>();
        for(ProjectStore.Clip clip:graph.clips){
            ProjectStore.Track track=graph.track(clip.trackId);if(track==null)throw new IllegalArgumentException("Clip refers to a missing track: "+clip.id);
            if((solo&&!track.solo)||(track.audioOnly()&&track.muted)||(!track.visible&&track.muted))continue;
            ProjectStore.Asset asset=graph.asset(clip.assetId);if(asset==null||asset.mime==null)throw new IllegalArgumentException("Missing source for clip "+clip.id);
            boolean video=track.visible&&!track.audioOnly()&&!asset.mime.startsWith("audio/"),audio=!track.muted&&(asset.mime.startsWith("audio/")||asset.mime.startsWith("video/"));
            if(!video&&!audio)continue;
            bindings.put("asset:"+asset.id,new Binding("asset:"+asset.id,asset.uri,asset.mime,uri->bound.asset(asset.id).uri=uri));
            if(video&&clip.effects.optBoolean("animatedScene"))for(String role:new String[]{"head","torso","lower","foreground","background"}){
                if("foreground".equals(role)&&!clip.effects.optString("headUri").isEmpty())continue;
                String field=role+"Uri",uri=clip.effects.optString(field);if(!uri.isEmpty())bindings.put("layer:"+clip.id+":"+role,new Binding("layer:"+clip.id+":"+role,uri,"image/png",pinned->bound.clip(clip.id).effects.put(field,pinned)));
            }
        }
        ArrayList<Binding> ordered=new ArrayList<>(bindings.values());ordered.sort(java.util.Comparator.comparing(b->b.id));
        JSONArray sourceIdentity=new JSONArray(),sources=new JSONArray();HashMap<String,Pinned> seen=new HashMap<>();
        for(Binding binding:ordered){
            check(cancelled);String key=binding.mime+":"+binding.uri;Pinned pinned=seen.get(key);
            if(pinned==null){Proof proof;try(InputStream input=opener.open(binding.uri)){proof=proof(input,cancelled);}pinned=pin(binding.uri,binding.mime,proof,cancelled);seen.put(key,pinned);}
            JSONObject identity=new JSONObject().put("binding",binding.id).put("uri",binding.uri).put("mime",binding.mime).put("sha256",pinned.proof.sha).put("sizeBytes",pinned.proof.bytes);
            sourceIdentity.put(identity);sources.put(new JSONObject(identity.toString()).put("snapshotUri",Uri.fromFile(pinned.file).toString()));binding.setter.set(Uri.fromFile(pinned.file).toString());
        }
        check(cancelled);
        JSONObject identity=new JSONObject().put("renderer",RENDERER).put("graphHash",graphHash).put("aspect",aspect).put("quality",quality).put("sources",sourceIdentity);
        String sessionId=hash(canonical(identity).getBytes(StandardCharsets.UTF_8));
        JSONObject manifest=new JSONObject(identity.toString()).put("sessionId",sessionId).put("projectId",graph.id).put("revision",graph.revision).put("sources",sources);
        return new Snapshot(bound,manifest,sessionId,inputs.getCanonicalFile());
    }
    private interface Setter{void set(String uri)throws Exception;}
    private static final class Binding{final String id,uri,mime;final Setter setter;Binding(String id,String uri,String mime,Setter setter){this.id=id;this.uri=uri;this.mime=mime;this.setter=setter;}}
    private static final class Proof{final long bytes;final String sha;Proof(long bytes,String sha){this.bytes=bytes;this.sha=sha;}}
    private static final class Pinned{final File file;final Proof proof;Pinned(File file,Proof proof){this.file=file;this.proof=proof;}}
    private Pinned pin(String uri,String mime,Proof expected,BooleanSupplier cancelled)throws Exception{
        lock(cancelled);
        try{
            check(cancelled);if(!inputs.isDirectory()&&!inputs.mkdirs())throw new IOException("Could not create render input storage");
            File[] abandoned=inputs.listFiles(file->file.getName().matches("[0-9a-f]{64}\\.(png|mp4|wav|mp3|m4a)\\.part"));
            if(abandoned==null)throw new IOException("Could not inspect interrupted render input stages");
            for(File dead:abandoned){check(cancelled);if(!dead.delete())throw new IOException("Could not reclaim interrupted render input stage");}
            String extension=mime.startsWith("image/")?".png":mime.startsWith("video/")?".mp4":mime.equals("audio/wav")?".wav":mime.equals("audio/mpeg")?".mp3":".m4a";
            File target=new File(inputs,expected.sha+extension),stage=new File(inputs,expected.sha+extension+".part");
            if(stage.exists()&&!stage.delete())throw new IOException("Could not reclaim interrupted render input stage");
            if(target.isFile()&&target.length()==expected.bytes){Proof cached;try(InputStream input=new FileInputStream(target)){cached=proof(input,cancelled);}if(expected.sha.equals(cached.sha))return new Pinned(target,expected);}
            if(inputs.getUsableSpace()<Math.addExact(expected.bytes,16L*1024*1024))throw new IOException("Not enough local space to pin captured render source");
            Exception failure=null;
            try{
                Proof copied;
                try(InputStream input=opener.open(uri);FileOutputStream output=new FileOutputStream(stage)){
                    if(input==null)throw new IOException("Render source is unreadable");MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;byte[] buffer=new byte[256*1024];int count;
                    while((count=input.read(buffer))!=-1){check(cancelled);if(count==0)continue;bytes=Math.addExact(bytes,count);if(bytes>expected.bytes)throw new IOException("Render source changed while capturing its snapshot");output.write(buffer,0,count);digest.update(buffer,0,count);}
                    check(cancelled);output.getFD().sync();copied=new Proof(bytes,hex(digest.digest()));
                }
                if(copied.bytes!=expected.bytes||!copied.sha.equals(expected.sha))throw new IOException("Render source changed while capturing its snapshot");
                check(cancelled);Files.move(stage.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
                return new Pinned(target,expected);
            }catch(Exception error){failure=error;throw error;}
            finally{if(stage.exists()&&!stage.delete()){IOException cleanup=new IOException("Could not clear render input stage");if(failure!=null)failure.addSuppressed(cleanup);else throw cleanup;}}
        }finally{PIN_LOCK.unlock();}
    }
    private static void lock(BooleanSupplier cancelled)throws InterruptedIOException{
        check(cancelled);
        try{while(!PIN_LOCK.tryLock(50,java.util.concurrent.TimeUnit.MILLISECONDS))check(cancelled);}
        catch(InterruptedException interrupted){Thread.currentThread().interrupt();InterruptedIOException failure=new InterruptedIOException("Render source capture cancelled while waiting for the input writer");failure.initCause(interrupted);throw failure;}
    }
    private static Proof proof(InputStream input,BooleanSupplier cancelled)throws Exception{
        if(input==null)throw new IOException("Render source is unreadable");MessageDigest digest=MessageDigest.getInstance("SHA-256");long bytes=0;byte[] buffer=new byte[256*1024];int count;
        check(cancelled);while((count=input.read(buffer))!=-1){check(cancelled);if(count==0)continue;digest.update(buffer,0,count);bytes=Math.addExact(bytes,count);}check(cancelled);if(bytes==0)throw new IOException("Render source is empty");return new Proof(bytes,hex(digest.digest()));
    }
    private static void check(BooleanSupplier cancelled)throws InterruptedIOException{if(Thread.currentThread().isInterrupted()||cancelled.getAsBoolean())throw new InterruptedIOException("Render source capture cancelled");}
    private static String hash(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String hex(byte[] bytes){char[] out=new char[bytes.length*2];String digits="0123456789abcdef";for(int i=0;i<bytes.length;i++){out[i*2]=digits.charAt((bytes[i]&255)>>>4);out[i*2+1]=digits.charAt(bytes[i]&15);}return new String(out);}
    /** Check mutable Java snapshot objects before allowing their identity into a durable journal. */
    static void validate(Snapshot snapshot)throws Exception{
        if(snapshot==null||snapshot.boundProject==null||snapshot.manifest==null||snapshot.inputRoot==null||snapshot.sessionId==null||!snapshot.sessionId.matches("[0-9a-f]{64}")||!snapshot.sessionId.equals(snapshot.manifest.optString("sessionId")))throw new IllegalArgumentException("Invalid captured render identity");
        JSONObject manifest=snapshot.manifest;ProjectStore.Project original=ProjectStore.Project.fromJson(snapshot.boundProject.snapshotJson());
        JSONArray sources=manifest.getJSONArray("sources"),identities=new JSONArray();HashMap<String,Proof> checked=new HashMap<>();
        for(int i=0;i<sources.length();i++){
            JSONObject source=sources.getJSONObject(i);String binding=source.getString("binding"),uri=source.getString("uri"),pinned=source.getString("snapshotUri"),mime=source.getString("mime"),sha=source.getString("sha256");long bytes=source.getLong("sizeBytes");
            if(!sha.matches("[0-9a-f]{64}")||bytes<=0)throw new IllegalArgumentException("Invalid captured source proof");
            Uri pinnedUri=Uri.parse(pinned);if(!"file".equals(pinnedUri.getScheme())||pinnedUri.getPath()==null)throw new IllegalArgumentException("Captured source is not a private pinned file");
            File file=new File(pinnedUri.getPath()).getCanonicalFile();
            if(!snapshot.inputRoot.getCanonicalFile().equals(file.getParentFile())||!file.getName().matches(sha+"\\.(png|mp4|wav|mp3|m4a)"))throw new IllegalArgumentException("Captured source was redirected outside its pinned object");
            Proof actual=checked.get(file.getPath());if(actual==null){try(InputStream input=new FileInputStream(file)){actual=proof(input,()->false);}checked.put(file.getPath(),actual);}
            if(actual.bytes!=bytes||!actual.sha.equals(sha))throw new IllegalArgumentException("Captured source bytes no longer match their proof");
            if(binding.startsWith("asset:")){
                ProjectStore.Asset asset=original.asset(binding.substring(6));if(asset==null||!pinned.equals(asset.uri)||!mime.equals(asset.mime))throw new IllegalArgumentException("Captured asset binding changed");asset.uri=uri;
            }else if(binding.startsWith("layer:")){
                int split=binding.lastIndexOf(':');ProjectStore.Clip clip=original.clip(binding.substring(6,split));String role=binding.substring(split+1);
                if(clip==null||!java.util.Arrays.asList("head","torso","lower","foreground","background").contains(role)||!pinned.equals(clip.effects.optString(role+"Uri")))throw new IllegalArgumentException("Captured layer binding changed");clip.effects.put(role+"Uri",uri);
            }else throw new IllegalArgumentException("Unknown captured source binding");
            identities.put(new JSONObject().put("binding",binding).put("uri",uri).put("mime",mime).put("sha256",sha).put("sizeBytes",bytes));
        }
        if(!original.id.equals(manifest.getString("projectId"))||original.revision!=manifest.getLong("revision")||!hash(canonical(original.snapshotJson()).getBytes(StandardCharsets.UTF_8)).equals(manifest.getString("graphHash")))throw new IllegalArgumentException("Captured graph no longer matches its identity");
        JSONObject identity=new JSONObject().put("renderer",manifest.getString("renderer")).put("graphHash",manifest.getString("graphHash")).put("aspect",manifest.getString("aspect")).put("quality",manifest.getString("quality")).put("sources",identities);
        if(!hash(canonical(identity).getBytes(StandardCharsets.UTF_8)).equals(snapshot.sessionId))throw new IllegalArgumentException("Captured manifest no longer matches its identity");
    }
    static String canonical(Object value)throws Exception{
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof JSONObject){JSONObject object=(JSONObject)value;ArrayList<String> keys=new ArrayList<>();object.keys().forEachRemaining(keys::add);java.util.Collections.sort(keys);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(object.get(key)));}return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray array=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<array.length();i++){if(i>0)out.append(',');out.append(canonical(array.get(i)));}return out.append(']').toString();}
        if(value instanceof String)return JSONObject.quote((String)value);if(value instanceof Number)return JSONObject.numberToString((Number)value);if(value instanceof Boolean)return value.toString();throw new IllegalArgumentException("Unsupported graph identity value");
    }
}
