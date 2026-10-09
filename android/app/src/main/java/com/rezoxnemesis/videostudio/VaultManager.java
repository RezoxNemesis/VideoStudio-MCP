package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import org.json.JSONArray;
import org.json.JSONObject;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;

/** Owner-selected media -> optional checksummed/AES-GCM Vault copy. */
public final class VaultManager {
    private static final String KEY_ALIAS="videostudio_vault_aes_v1";
    private final Context context;
    private final ProjectStore store;
    private final File root;
    public VaultManager(Context context,ProjectStore store){this.context=context.getApplicationContext();this.store=store;root=new File(this.context.getFilesDir(),"videostudio_vault");}
    public JSONObject create(String projectId,String assetId,boolean encrypted,JobManager.Job job)throws Exception{
        ProjectStore.Project project=store.get(projectId);ProjectStore.Asset source=project==null?null:project.asset(assetId);
        if(source==null||!"ready".equals(source.importState))throw new IllegalArgumentException("Ready project-owned media is required");
        if(job!=null){job.bindInputs(projectId,java.util.Collections.singleton(assetId));job.checkActive();}
        String sourceUri=source.uri;SecretKey key=encrypted?key():null;VaultChunkStore vault=new VaultChunkStore(root,VaultChunkStore.DEFAULT_CHUNK_BYTES);
        // SAF documents can change without updating their size or modification metadata.
        // Hash the live source in bounded memory so a resume never splices two document versions.
        VaultChunkStore.SourceDigest identity;
        try(InputStream input=openSourceAt(Uri.parse(sourceUri),0)){
            identity=VaultChunkStore.sourceChecksum(input,(done,total)->{if(job!=null)job.checkpoint("vault_source_identity",1,"Checking selected source identity · "+done+" bytes");});
        }
        String resumeId=projectId+":"+assetId+":"+sourceUri+":"+identity.sha256+":"+(encrypted?KEY_ALIAS:"plain");
        long expected=identity.bytes;
        long completed=vault.completedBytes(resumeId,expected,encrypted,encrypted?KEY_ALIAS:"");
        long remainder=expected>=0?Math.max(0,expected-completed):VaultChunkStore.DEFAULT_CHUNK_BYTES;
        long required=TimelineMath.add(remainder,encrypted?TimelineMath.add(remainder/32768,1024*1024):0);
        StorageBudget.Check budget=StorageBudget.check(root.getParentFile().getUsableSpace(),required,StorageBudget.DEFAULT_TRANSFER_RESERVE_BYTES);
        if(!budget.allowed)throw new IOException("Insufficient local space for Vault copy: "+budget.shortfallBytes+" bytes needed");
        VaultChunkStore.Manifest manifest=vault.packResumable(resumeId,offset->openSourceAt(Uri.parse(sourceUri),offset),expected,key,encrypted?KEY_ALIAS:"",identity.sha256,(done,total)->{
                StorageBudget.Check remaining=StorageBudget.check(root.getUsableSpace(),Math.min(VaultChunkStore.DEFAULT_CHUNK_BYTES,total<0?VaultChunkStore.DEFAULT_CHUNK_BYTES:Math.max(0,total-done)),64L*1024*1024);
                if(!remaining.allowed)throw new IOException("Vault is waiting for storage");
                if(job!=null)job.checkpoint("vault_chunk",total<=0?50:(int)Math.min(95,95d*done/total),"Verified Vault chunks · "+done+" bytes");
            });
        if(job!=null)job.checkActive();
        try(InputStream input=openSourceAt(Uri.parse(sourceUri),0)){
            VaultChunkStore.SourceDigest current=VaultChunkStore.sourceChecksum(input,(done,total)->{if(job!=null)job.checkpoint("vault_source_reverify",97,"Rechecking selected source before publication · "+done+" bytes");});
            if(current.bytes!=identity.bytes||!current.sha256.equals(identity.sha256))throw new IOException("Selected source changed during Vault copy; retry from its current version");
        }
        JSONObject metadata=describe(manifest);File json=new File(root,"manifests/"+manifest.id+".json"),temp=new File(json.getAbsolutePath()+".partial");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(metadata.toString(2).getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        try{Files.move(temp.toPath(),json.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException fallback){Files.move(temp.toPath(),json.toPath(),StandardCopyOption.REPLACE_EXISTING);}
        metadata.put("manifestUri",Uri.fromFile(json).toString()).put("complete",true);
        JSONObject update=new JSONObject().put("vault",metadata);
        if(job==null)store.updateAssetMetadataIfSource(projectId,assetId,sourceUri,update,manifest.totalBytes);
        else job.commit(active->store.updateAssetMetadataIfSource(projectId,assetId,sourceUri,update,manifest.totalBytes));
        if(job!=null)job.checkpoint("vault_ready",100,"Verified Vault copy ready; original retained");
        return new JSONObject().put("ok",true).put("projectId",projectId).put("assetId",assetId).put("vault",metadata);
    }
    public JSONObject inspect(String projectId,String assetId)throws Exception{
        ProjectStore.Project project=store.get(projectId);ProjectStore.Asset asset=project==null?null:project.asset(assetId);if(asset==null)throw new IllegalArgumentException("Project-owned media is required");
        JSONObject info=asset.generationMetadata.optJSONObject("vault");return new JSONObject().put("ok",true).put("projectId",projectId).put("assetId",assetId).put("available",info!=null&&info.optBoolean("complete")).put("vault",info==null?JSONObject.NULL:info);
    }
    public InputStream openRange(String manifestId,long offset,long length)throws Exception{
        VaultChunkStore vault=new VaultChunkStore(root,VaultChunkStore.DEFAULT_CHUNK_BYTES);VaultChunkStore.Manifest manifest=vault.load(manifestId);
        return vault.openRange(manifest,offset,length,manifest.encrypted?key():null);
    }
    public JSONObject describe(VaultChunkStore.Manifest manifest)throws Exception{
        JSONArray chunks=new JSONArray();for(VaultChunkStore.Chunk c:manifest.chunks)chunks.put(new JSONObject().put("offset",c.offset).put("bytes",c.size).put("storedBytes",c.storedBytes).put("recordBytes",c.recordBytes).put("sha256",c.sha256).put("storedSha256",c.storedSha256).put("iv",c.iv).put("location",Uri.fromFile(new File(root,"objects/"+c.objectName)).toString()).put("storage","internal"));
        return new JSONObject().put("format","VideoStudioVault").put("version",manifest.version).put("manifestId",manifest.id).put("bytes",manifest.totalBytes).put("sha256",manifest.sha256).put("chunkBytes",manifest.chunkBytes).put("encrypted",manifest.encrypted).put("cipher",manifest.encrypted?"AES-256-GCM":"none").put("keyId",manifest.keyId).put("chunks",chunks);
    }
    private InputStream openSourceAt(Uri uri,long offset)throws IOException{
        InputStream input=context.getContentResolver().openInputStream(uri);if(input==null)throw new IOException("Cannot open selected media");
        try{
            if(offset==0)return input;
            if(input instanceof FileInputStream)try{
                java.nio.channels.FileChannel channel=((FileInputStream)input).getChannel();channel.position(Math.addExact(channel.position(),offset));return input;
            }catch(IOException notSeekable){input.close();input=context.getContentResolver().openInputStream(uri);if(input==null)throw new IOException("Cannot reopen selected media",notSeekable);}
            byte[] buffer=new byte[64*1024];long remaining=offset;
            while(remaining>0){if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("Vault resume cancelled");long skipped=input.skip(remaining);if(skipped>0){remaining-=skipped;continue;}int count=input.read(buffer,0,(int)Math.min(remaining,buffer.length));if(count<0)throw new EOFException("Vault source changed before resume offset");remaining-=count;}
            return input;
        }catch(Exception error){input.close();if(error instanceof IOException)throw (IOException)error;throw new IOException("Cannot seek selected media",error);}
    }
    private static synchronized SecretKey key()throws Exception{
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);
        if(store.containsAlias(KEY_ALIAS))return (SecretKey)store.getKey(KEY_ALIAS,null);
        KeyGenerator generator=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).setRandomizedEncryptionRequired(true).build());return generator.generateKey();
    }
}
