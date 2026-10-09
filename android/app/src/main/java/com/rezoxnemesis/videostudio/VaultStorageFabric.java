package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import java.io.*;
import java.util.*;
import java.security.MessageDigest;

/** Verified replication of existing Vault objects to explicitly connected profiles. */
public final class VaultStorageFabric implements AutoCloseable {
    public interface Progress {void update(long verified,long total)throws Exception;}
    public interface BlobStore {
        long freeBytes(String profileId)throws Exception;
        String resolve(String profileId,String objectName,String uploadToken)throws Exception;
        String put(String profileId,String objectName,String uploadToken,File source,Progress progress)throws Exception;
        String repair(String profileId,String location,File source,Progress progress)throws Exception;
        InputStream open(String profileId,String location)throws Exception;
        void delete(String profileId,String location)throws Exception;
    }
    private final File root;private final BlobStore blobs;private final VaultReplicaStore index;
    private static final Object COPY_LOCK=new Object();
    public VaultStorageFabric(android.content.Context context,File vaultRoot,BlobStore blobs){root=vaultRoot;this.blobs=blobs;index=new VaultReplicaStore(context);}
    private static final class ObjectCopy {
        final String name,sha,profile;final File file;final long bytes;final boolean manifest;
        ObjectCopy(String name,String sha,String profile,File file,long bytes,boolean manifest){this.name=name;this.sha=sha;this.profile=profile;this.file=file;this.bytes=bytes;this.manifest=manifest;}
    }
    public JSONObject replicate(String manifestId,List<String> profiles,int copies,Progress progress)throws Exception{
        synchronized(COPY_LOCK){
            if(profiles==null||profiles.isEmpty()||profiles.size()>5||new HashSet<>(profiles).size()!=profiles.size()||copies<1||copies>profiles.size())throw new IllegalArgumentException("Choose 1–5 distinct profiles and a valid replica count");
            for(String profile:profiles)if(profile==null||profile.isEmpty()||profile.length()>120)throw new IllegalArgumentException("Connected profile ID is required");
            VaultChunkStore vault=new VaultChunkStore(root,VaultChunkStore.DEFAULT_CHUNK_BYTES);VaultChunkStore.Manifest manifest=vault.load(manifestId);
            Map<String,Long> available=new HashMap<>();for(String profile:profiles)available.put(profile,blobs.freeBytes(profile));
            LinkedHashMap<String,ObjectCopy> plan=new LinkedHashMap<>();int ordinal=0;
            for(VaultChunkStore.Chunk chunk:manifest.chunks){
                for(int replica=0;replica<copies;replica++){
                    String profile=profiles.get((ordinal+replica)%profiles.size());
                    plan.putIfAbsent(chunk.objectName+":"+profile,new ObjectCopy(chunk.objectName,chunk.storedSha256,profile,new File(root,"objects/"+chunk.objectName),chunk.storedBytes,false));
                }ordinal++;
            }
            File manifestFile=new File(root,"manifests/"+manifest.id+".manifest");
            String manifestSha=verifyFile(manifestFile,manifestFile.length(),null,null);
            for(String profile:profiles){String name=manifest.id+".manifest";plan.put(name+":"+profile,new ObjectCopy(name,manifestSha,profile,manifestFile,manifestFile.length(),true));}
            long total=0;for(ObjectCopy item:plan.values())total=Math.addExact(total,item.bytes);
            final long plannedTotal=total;
            long done=0;int chunkReplicas=0,manifestReplicas=0;org.json.JSONArray verifiedLocations=new org.json.JSONArray();
            for(ObjectCopy item:plan.values()){
                active(progress,done,total);
                final long previouslyVerified=done;
                verifyFile(item.file,item.bytes,item.sha,()->active(progress,previouslyVerified,plannedTotal));
                JSONObject prior=index.get(manifestId,item.name,item.profile);boolean verified=false,corrupt=false;
                String token=prior==null?UUID.randomUUID().toString():prior.optString("uploadToken"),location=prior==null?"":prior.optString("location");
                if(prior==null)index.pending(manifestId,item.name,item.profile,"",item.bytes,item.sha,token);
                if(location.isEmpty()&&!token.isEmpty()){String recovered=blobs.resolve(item.profile,item.name,token);if(recovered!=null)location=recovered;}
                if(!location.isEmpty()){
                    try(InputStream input=blobs.open(item.profile,location)){verify(input,item.bytes,item.sha,()->active(progress,0,0));verified=true;}
                    catch(IntegrityException invalid){corrupt=true;}
                    // Cancellation and temporary provider failures retain the durable location for retry.
                }
                if(!verified){
                    long free=available.get(item.profile);if(!corrupt&&free>=0&&free<item.bytes)throw new IOException("Reported profile quota cannot fit the next Vault object");
                    index.pending(manifestId,item.name,item.profile,location,item.bytes,item.sha,token);
                    String uploaded=null;
                    try{
                        uploaded=corrupt?blobs.repair(item.profile,location,item.file,(bytes,size)->active(progress,0,0)):blobs.put(item.profile,item.name,token,item.file,(bytes,size)->active(progress,0,0));
                        try(InputStream input=blobs.open(item.profile,uploaded)){verify(input,item.bytes,item.sha,()->active(progress,0,0));}
                        active(null,0,0);index.verified(manifestId,item.name,item.profile,uploaded,item.bytes,item.sha,token);location=uploaded;
                    }catch(IntegrityException failure){if(!corrupt&&uploaded!=null)try{blobs.delete(item.profile,uploaded);}catch(Exception cleanup){failure.addSuppressed(cleanup);}throw failure;}
                    if(!corrupt&&free>=0)available.put(item.profile,free-item.bytes);
                }else index.verified(manifestId,item.name,item.profile,location,item.bytes,item.sha,token);
                verifiedLocations.put(index.get(manifestId,item.name,item.profile));
                done=Math.addExact(done,item.bytes);if(item.manifest)manifestReplicas++;else chunkReplicas++;active(progress,done,total);
            }
            return new JSONObject().put("ok",true).put("complete",true).put("manifestId",manifestId).put("logicalChunks",manifest.chunks.size()).put("chunkReplicas",chunkReplicas).put("manifestReplicas",manifestReplicas).put("verifiedBytes",done).put("replicasPerChunk",copies).put("profileIds",new org.json.JSONArray(profiles)).put("locations",verifiedLocations).put("originalRetained",true).put("localVaultRetained",true).put("scope","owner-connected-document-folders");
        }
    }
    /** Download whole intersecting stored chunks, verify them, then publish atomic local cache entries. */
    public JSONObject hydrate(String manifestId,long offset,long length,Progress progress)throws Exception{
        synchronized(COPY_LOCK){
            VaultChunkStore vault=new VaultChunkStore(root,VaultChunkStore.DEFAULT_CHUNK_BYTES);active(progress,0,0);
            VaultChunkStore.Manifest manifest=localOrRecoveredManifest(vault,manifestId,progress);
            if(length==-1){if(offset<0||offset>manifest.totalBytes)throw new IllegalArgumentException("Vault range is outside the asset");length=manifest.totalBytes-offset;}
            if(offset<0||length<0||offset>manifest.totalBytes||length>manifest.totalBytes-offset)throw new IllegalArgumentException("Vault range is outside the asset");
            LinkedHashMap<String,VaultChunkStore.Chunk> needed=new LinkedHashMap<>();long end=offset+length;
            for(VaultChunkStore.Chunk chunk:manifest.chunks)if(length>0&&chunk.offset<end&&chunk.offset+chunk.size>offset)needed.putIfAbsent(chunk.objectName,chunk);
            long total=0;for(VaultChunkStore.Chunk chunk:needed.values())total=Math.addExact(total,chunk.storedBytes);
            long done=0;int downloaded=0;
            for(VaultChunkStore.Chunk chunk:needed.values()){
                active(progress,done,total);File local=new File(root,"objects/"+chunk.objectName);boolean valid=false;
                try{verifyFile(local,chunk.storedBytes,chunk.storedSha256,()->active(progress,0,0));valid=true;}catch(InterruptedIOException cancelled){throw cancelled;}catch(IOException missingOrCorrupt){}
                if(!valid){
                    File staged=download(manifest.id,chunk.objectName,chunk.storedBytes,chunk.storedSha256,progress,null);
                    try{active(progress,0,0);publish(staged,local);}finally{java.nio.file.Files.deleteIfExists(staged.toPath());}downloaded++;
                }
                done=Math.addExact(done,chunk.storedBytes);active(progress,done,total);
            }
            return new JSONObject().put("ok",true).put("manifestId",manifest.id).put("complete",offset==0&&length==manifest.totalBytes).put("rangeOffset",offset).put("rangeBytes",length).put("verifiedStoredBytes",done).put("requiredObjects",needed.size()).put("downloadedObjects",downloaded).put("remoteObjectsRetained",true).put("originalRetained",true);
        }
    }
    private VaultChunkStore.Manifest localOrRecoveredManifest(VaultChunkStore vault,String id,Progress progress)throws Exception{
        try{
            File local=new File(root,"manifests/"+id+".manifest");org.json.JSONArray rows=index.list(id);boolean hasProof=false,matches=false;
            for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);if((id+".manifest").equals(row.optString("object"))&&"verified".equals(row.optString("state"))){hasProof=true;try{verifyFile(local,row.getLong("bytes"),row.getString("sha256"),()->active(progress,0,0));matches=true;break;}catch(InterruptedIOException cancelled){throw cancelled;}catch(IOException invalid){}}}
            if(hasProof&&!matches)throw new IntegrityException("Local binary manifest differs from its verified replica proof");return vault.load(id);
        }catch(InterruptedIOException cancelled){throw cancelled;}catch(IOException missingOrCorrupt){
            File staged=download(id,id+".manifest",-1,null,progress,file->VaultChunkStore.validateManifest(file,id));
            try{active(progress,0,0);publish(staged,new File(root,"manifests/"+id+".manifest"));return vault.load(id);}finally{java.nio.file.Files.deleteIfExists(staged.toPath());}
        }
    }
    private interface StagedCheck {void validate(File file)throws Exception;}
    private File download(String manifest,String object,long expected,String sha,Progress progress,StagedCheck validate)throws Exception{
        org.json.JSONArray locations=index.list(manifest);IOException unavailable=new IOException("No connected, valid replica can restore this Vault object");
        for(int i=0;i<locations.length();i++){
            JSONObject row=locations.getJSONObject(i);if(!object.equals(row.optString("object"))||!"verified".equals(row.optString("state")))continue;
            long bytes=expected<0?row.optLong("bytes",-1):expected;String hash=sha==null?row.optString("sha256"):sha;
            if(bytes<0||expected<0&&bytes>64L*1024*1024||!hash.matches("[a-f0-9]{64}")||row.optLong("bytes",-1)!=bytes||!hash.equals(row.optString("sha256")))continue;
            active(progress,0,0);File staged=new File(root,"partial/"+object+".download.partial");java.nio.file.Files.deleteIfExists(staged.toPath());
            long free=root.getUsableSpace();if(free<bytes+64L*1024*1024)throw new IOException("Insufficient local space to safely restore a Vault object");boolean accepted=false;
            try{
                try(InputStream input=blobs.open(row.getString("profileId"),row.getString("location"));FileOutputStream output=new FileOutputStream(staged)){
                    MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[256*1024];long copied=0;int count;
                    while((count=input.read(buffer))!=-1){downloadActive(progress);if(count==0){int one=input.read();if(one<0)break;buffer[0]=(byte)one;count=1;}copied=Math.addExact(copied,count);if(copied>bytes)throw new IntegrityException("Downloaded Vault replica exceeds its recorded size");digest.update(buffer,0,count);output.write(buffer,0,count);}
                    StringBuilder actual=new StringBuilder();for(byte value:digest.digest())actual.append(String.format(java.util.Locale.US,"%02x",value&255));
                    if(copied!=bytes||!hash.equals(actual.toString()))throw new IntegrityException("Downloaded Vault replica checksum/size mismatch");output.flush();output.getFD().sync();
                }
                if(validate!=null)validate.validate(staged);downloadActive(progress);accepted=true;return staged;
            }catch(ProgressFailure stopped){throw stopped.failure;}catch(java.util.concurrent.CancellationException cancelled){throw cancelled;}catch(Exception failure){active(progress,0,0);unavailable.addSuppressed(failure);}finally{if(!accepted)java.nio.file.Files.deleteIfExists(staged.toPath());}
        }throw unavailable;
    }
    private static final class ProgressFailure extends Exception {final Exception failure;ProgressFailure(Exception failure){this.failure=failure;}}
    private static void downloadActive(Progress progress)throws ProgressFailure{try{active(progress,0,0);}catch(Exception failure){throw new ProgressFailure(failure);}}
    private static void publish(File staged,File target)throws IOException{
        try{java.nio.file.Files.move(staged.toPath(),target.toPath(),java.nio.file.StandardCopyOption.ATOMIC_MOVE,java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(java.nio.file.AtomicMoveNotSupportedException unsupported){java.nio.file.Files.move(staged.toPath(),target.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}
    }
    private interface Check {void run()throws Exception;}
    private static final class IntegrityException extends IOException {IntegrityException(String detail){super(detail);}}
    private static void active(Progress progress,long done,long total)throws Exception{if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Vault replication cancelled");if(progress!=null)progress.update(done,total);}
    private static String verifyFile(File file,long bytes,String sha,Check check)throws Exception{try(InputStream input=new FileInputStream(file)){return verify(input,bytes,sha,check);}}
    private static String verify(InputStream input,long expected,String expectedSha,Check check)throws Exception{
        if(input==null)throw new IOException("Vault replica cannot be opened");MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[256*1024];long bytes=0;int n;
        while((n=input.read(buffer))!=-1){if(check!=null)check.run();else active(null,0,0);if(n==0){int one=input.read();if(one<0)break;buffer[0]=(byte)one;n=1;}bytes=Math.addExact(bytes,n);if(bytes>expected)throw new IntegrityException("Vault object exceeds recorded size");digest.update(buffer,0,n);}
        StringBuilder hash=new StringBuilder();for(byte value:digest.digest())hash.append(String.format(java.util.Locale.US,"%02x",value&255));String sha=hash.toString();
        if(bytes!=expected||expectedSha!=null&&!expectedSha.equals(sha))throw new IntegrityException("Vault replica size/checksum verification failed");return sha;
    }
    @Override public void close(){index.close();}
}
