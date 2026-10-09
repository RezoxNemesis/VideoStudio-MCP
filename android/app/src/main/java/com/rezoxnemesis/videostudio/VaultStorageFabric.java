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
