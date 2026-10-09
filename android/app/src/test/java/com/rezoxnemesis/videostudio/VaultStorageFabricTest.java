package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class VaultStorageFabricTest {
    private Context context;private Path root;private VaultChunkStore.Manifest manifest;private Remote remote;
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("videostudio_vault_replicas.db");
        root=Files.createTempDirectory(context.getCacheDir().toPath(),"fabric-");remote=new Remote(root.resolve("remote"));
        byte[] source=new byte[9000];new Random(77).nextBytes(source);
        manifest=new VaultChunkStore(root.resolve("vault").toFile(),4096).pack(new ByteArrayInputStream(source),source.length,null,"",null);
    }
    @After public void cleanup()throws Exception{try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    private VaultStorageFabric fabric(){return new VaultStorageFabric(context,root.resolve("vault").toFile(),remote);}
    private JSONObject replicate(String id,List<String> profiles,int copies,VaultStorageFabric.Progress progress)throws Exception{try(VaultStorageFabric fabric=fabric()){return fabric.replicate(id,profiles,copies,progress);}}
    @Test public void distributedCopiesAndManifestAreVerifiedBeforeSuccessAndReusedAfterRestart()throws Exception{
        JSONObject first=replicate(manifest.id,List.of("a","b"),1,(done,total)->{});
        assertTrue(first.toString(),first.getBoolean("complete"));assertEquals(3,first.getInt("chunkReplicas"));assertEquals(2,first.getInt("manifestReplicas"));
        assertEquals(5,remote.puts);assertTrue(remote.writtenProfiles.containsAll(List.of("a","b")));
        replicate(manifest.id,List.of("a","b"),1,(done,total)->{});assertEquals("Restart reuses only reverified objects",5,remote.puts);
    }
    @Test public void cancellationPreservesVerifiedCopiesAndRetryCopiesOnlyRemainingObjects()throws Exception{
        try{replicate(manifest.id,List.of("a","b"),1,(done,total)->{if(done>0)throw new InterruptedIOException("Stop");});fail("Cancellation ignored");}catch(InterruptedIOException expected){}
        assertEquals(1,remote.puts);
        JSONObject result=replicate(manifest.id,List.of("a","b"),1,(done,total)->{});assertTrue(result.getBoolean("complete"));assertEquals(5,remote.puts);
    }
    @Test public void corruptedRemoteUploadCannotBecomeAVerifiedReplica()throws Exception{
        remote.corrupt=true;
        try{replicate(manifest.id,List.of("a"),1,(done,total)->{});fail("Corrupt uploaded object accepted");}catch(IOException expected){}
        assertEquals(1,remote.deletes);remote.corrupt=false;
        assertTrue(replicate(manifest.id,List.of("a"),1,(done,total)->{}).getBoolean("complete"));
    }
    @Test public void corruptionAfterSuccessIsDetectedAndRepairedOnRetry()throws Exception{
        replicate(manifest.id,List.of("a"),1,(done,total)->{});int initial=remote.puts;
        Path object=remote.files.values().iterator().next();Files.write(object,new byte[]{1,2,3});
        assertTrue(replicate(manifest.id,List.of("a"),1,(done,total)->{}).getBoolean("complete"));assertEquals(initial+1,remote.puts);
    }
    @Test public void reportedQuotaAndDistinctReplicaProfilesAreEnforced()throws Exception{
        remote.free=10;
        try{replicate(manifest.id,List.of("a"),1,(done,total)->{});fail("Known quota exceeded");}catch(IOException expected){}
        assertEquals(0,remote.puts);
        try{replicate(manifest.id,List.of("a","a"),2,(done,total)->{});fail("Duplicate profile counted as two replicas");}catch(IllegalArgumentException expected){}
        remote.free=-1;assertTrue(replicate(manifest.id,List.of("a","b"),2,(done,total)->{}).getBoolean("complete"));assertEquals(8,remote.puts);
    }
    @Test public void identicalPlainChunksDeduplicatePerProfileWithoutLosingLogicalChunks()throws Exception{
        byte[] repeated=new byte[8192];Arrays.fill(repeated,(byte)17);
        VaultChunkStore.Manifest duplicate=new VaultChunkStore(root.resolve("vault").toFile(),4096).pack(new ByteArrayInputStream(repeated),repeated.length,null,"",null);
        JSONObject result=replicate(duplicate.id,List.of("a"),1,(done,total)->{});
        assertEquals(2,result.getInt("logicalChunks"));assertEquals(1,result.getInt("chunkReplicas"));assertEquals(2,remote.puts);
    }
    @Test public void currentVerificationDoesNotClaimOlderUnselectedLocationsAreAvailable()throws Exception{
        replicate(manifest.id,List.of("a","b"),1,(done,total)->{});
        JSONObject result=replicate(manifest.id,List.of("a"),1,(done,total)->{});
        org.json.JSONArray locations=result.getJSONArray("locations");
        assertEquals(4,locations.length());for(int i=0;i<locations.length();i++)assertEquals("a",locations.getJSONObject(i).getString("profileId"));
    }
    @Test public void encryptedObjectsReplicateWithoutExposingOrExportingTheirKey()throws Exception{
        byte[] bytes=new byte[9000];new Random(17).nextBytes(bytes);javax.crypto.SecretKey key=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");
        VaultChunkStore vault=new VaultChunkStore(root.resolve("vault").toFile(),4096);
        VaultChunkStore.Manifest encrypted=vault.pack(new ByteArrayInputStream(bytes),bytes.length,key,"private-test-key",null);
        JSONObject result=replicate(encrypted.id,List.of("a","b"),2,(done,total)->{});assertTrue(result.getBoolean("complete"));assertEquals(8,remote.puts);
        for(VaultChunkStore.Chunk chunk:encrypted.chunks){for(String profile:List.of("a","b")){String prefix=profile+":";Path file=remote.files.entrySet().stream().filter(entry->entry.getKey().startsWith(prefix)&&entry.getValue().getFileName().toString().endsWith(chunk.objectName)).findFirst().orElseThrow().getValue();assertArrayEquals(Files.readAllBytes(root.resolve("vault/objects/"+chunk.objectName)),Files.readAllBytes(file));}}
        try(InputStream restored=vault.openRange(encrypted,0,bytes.length,key)){assertArrayEquals(bytes,restored.readAllBytes());}
        assertFalse(result.toString().contains("keyBytes"));
    }
    @Test public void processDeathAfterRemoteWriteResolvesTheDurableObjectWithoutUploadingAgain()throws Exception{
        remote.dieAfterWrite=true;
        try{replicate(manifest.id,List.of("a","b"),1,null);fail("Expected simulated process death");}catch(SimulatedDeath expected){}
        assertEquals(1,remote.puts);remote.dieAfterWrite=false;
        assertTrue(replicate(manifest.id,List.of("a","b"),1,null).getBoolean("complete"));
        assertEquals("Recovered upload must not create an orphan or consume quota twice",5,remote.puts);assertEquals(5,remote.files.size());
    }
    @Test public void transientReadFailurePreservesTheLocationAndDoesNotAllocateOrDelete()throws Exception{
        replicate(manifest.id,List.of("a"),1,null);int before=remote.puts;remote.unavailable=true;
        try{replicate(manifest.id,List.of("a"),1,null);fail("Transient outage should fail recoverably");}catch(IOException expected){}
        assertEquals(before,remote.puts);assertEquals(0,remote.deletes);
        try(VaultReplicaStore index=new VaultReplicaStore(context)){assertEquals(4,index.list(manifest.id).length());}
        remote.unavailable=false;assertTrue(replicate(manifest.id,List.of("a"),1,null).getBoolean("complete"));assertEquals(before,remote.puts);
    }
    @Test public void confirmedCorruptionRepairsTheSameDocumentAtZeroReportedFreeQuota()throws Exception{
        replicate(manifest.id,List.of("a"),1,null);int before=remote.puts;Set<String> locations=new HashSet<>(remote.files.keySet());
        Path file=remote.files.values().iterator().next();byte[] bytes=Files.readAllBytes(file);bytes[0]^=1;Files.write(file,bytes);remote.free=0;
        assertTrue(replicate(manifest.id,List.of("a"),1,null).getBoolean("complete"));
        assertEquals(before+1,remote.puts);assertEquals(locations,remote.files.keySet());assertEquals(0,remote.deletes);
    }
    @Test public void replicaIndexMigrationPreservesExistingVerifiedLocations()throws Exception{
        context.deleteDatabase("videostudio_vault_replicas.db");
        try(android.database.sqlite.SQLiteDatabase db=context.openOrCreateDatabase("videostudio_vault_replicas.db",0,null)){
            db.execSQL("CREATE TABLE replicas(manifest TEXT NOT NULL,object TEXT NOT NULL,profile TEXT NOT NULL,location TEXT NOT NULL,bytes INTEGER NOT NULL,sha TEXT NOT NULL,verified_at INTEGER NOT NULL,PRIMARY KEY(manifest,object,profile))");
            db.execSQL("INSERT INTO replicas VALUES(?,?,?,?,?,?,?)",new Object[]{manifest.id,"object","a","content://verified",42,"hash",123});db.setVersion(1);
        }
        try(VaultReplicaStore migrated=new VaultReplicaStore(context)){
            JSONObject row=migrated.get(manifest.id,"object","a");assertEquals("content://verified",row.getString("location"));assertEquals(123,row.getLong("verifiedAt"));assertEquals("verified",row.getString("state"));assertEquals("",row.getString("uploadToken"));
            migrated.pending(manifest.id,"new","a","",1,"hash",UUID.randomUUID().toString());assertEquals("pending",migrated.get(manifest.id,"new","a").getString("state"));
        }
    }
    @Test public void missingManifestAndChunksRestoreFromVerifiedReplicaBytes()throws Exception{
        replicate(manifest.id,List.of("a","b"),2,null);int writes=remote.puts;
        Files.delete(root.resolve("vault/manifests/"+manifest.id+".manifest"));for(VaultChunkStore.Chunk c:manifest.chunks)Files.delete(root.resolve("vault/objects/"+c.objectName));
        try(VaultStorageFabric restored=fabric()){JSONObject result=restored.hydrate(manifest.id,0,-1,null);assertTrue(result.getBoolean("complete"));assertEquals(3,result.getInt("downloadedObjects"));}
        VaultChunkStore vault=new VaultChunkStore(root.resolve("vault").toFile(),4096);VaultChunkStore.Manifest loaded=vault.load(manifest.id);try(InputStream original=vault.openRange(loaded,0,loaded.totalBytes,null)){assertEquals(9000,original.readAllBytes().length);}
        assertEquals(writes,remote.puts);assertEquals(0,remote.deletes);
    }
    @Test public void rangeHydrationDownloadsOnlyIntersectingChunksAndRetriesReuseThem()throws Exception{
        replicate(manifest.id,List.of("a"),1,null);for(VaultChunkStore.Chunk c:manifest.chunks)Files.delete(root.resolve("vault/objects/"+c.objectName));
        try(VaultStorageFabric restored=fabric()){
            JSONObject result=restored.hydrate(manifest.id,4100,100,null);assertFalse(result.getBoolean("complete"));assertEquals(1,result.getInt("downloadedObjects"));
            assertFalse(Files.exists(root.resolve("vault/objects/"+manifest.chunks.get(0).objectName)));assertTrue(Files.exists(root.resolve("vault/objects/"+manifest.chunks.get(1).objectName)));
            assertEquals(0,restored.hydrate(manifest.id,4100,100,null).getInt("downloadedObjects"));
            try{restored.hydrate(manifest.id,Long.MAX_VALUE,1,null);fail("Invalid range accepted");}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void hydrationFallsBackFromCorruptReplicaWithoutModifyingRemoteObjects()throws Exception{
        replicate(manifest.id,List.of("a","b"),2,null);VaultChunkStore.Chunk chunk=manifest.chunks.get(0);Files.delete(root.resolve("vault/objects/"+chunk.objectName));
        Path bad=remote.files.entrySet().stream().filter(e->e.getKey().startsWith("a:")&&e.getValue().getFileName().toString().endsWith(chunk.objectName)).findFirst().orElseThrow().getValue();Files.write(bad,new byte[]{1});
        try(VaultStorageFabric restored=fabric()){assertEquals(1,restored.hydrate(manifest.id,0,1,null).getInt("downloadedObjects"));}
        assertEquals(8,remote.puts);assertEquals(0,remote.deletes);assertEquals(1,Files.size(bad));
    }
    @Test public void failedHydrationLeavesOldLocalBytesIntactAndNeverPublishesCorruptDownload()throws Exception{
        replicate(manifest.id,List.of("a"),1,null);VaultChunkStore.Chunk chunk=manifest.chunks.get(0);Path local=root.resolve("vault/objects/"+chunk.objectName);byte[] old={7,8,9};Files.write(local,old);
        Path remoteFile=remote.files.values().stream().filter(p->p.getFileName().toString().endsWith(chunk.objectName)).findFirst().orElseThrow();Files.write(remoteFile,new byte[]{1});
        try(VaultStorageFabric restored=fabric()){try{restored.hydrate(manifest.id,0,1,null);fail("Bad replica accepted");}catch(IOException expected){}}
        assertArrayEquals(old,Files.readAllBytes(local));try(var files=Files.list(root.resolve("vault/partial"))){assertEquals(0,files.count());}assertEquals(0,remote.deletes);
    }
    @Test public void cancelledHydrationRestartsFromPreviouslyVerifiedDownloads()throws Exception{
        replicate(manifest.id,List.of("a"),1,null);for(VaultChunkStore.Chunk c:manifest.chunks)Files.delete(root.resolve("vault/objects/"+c.objectName));
        try(VaultStorageFabric restored=fabric()){try{restored.hydrate(manifest.id,0,-1,(done,total)->{if(done>=4096)throw new InterruptedIOException("Stop");});fail("Cancellation ignored");}catch(InterruptedIOException expected){}}
        try(VaultStorageFabric restored=fabric()){assertEquals(2,restored.hydrate(manifest.id,0,-1,null).getInt("downloadedObjects"));}assertEquals(4,remote.puts);
    }
    @Test public void encryptedHydrationPreservesAuthenticatedBytesAndOriginalKeyRestoresSource()throws Exception{
        byte[] source=new byte[9000];new Random(27).nextBytes(source);javax.crypto.SecretKey key=new javax.crypto.spec.SecretKeySpec(new byte[32],"AES");VaultChunkStore vault=new VaultChunkStore(root.resolve("vault").toFile(),4096);VaultChunkStore.Manifest encrypted=vault.pack(new ByteArrayInputStream(source),source.length,key,"private",null);
        replicate(encrypted.id,List.of("a"),1,null);for(VaultChunkStore.Chunk c:encrypted.chunks)Files.delete(root.resolve("vault/objects/"+c.objectName));
        try(VaultStorageFabric restored=fabric()){assertTrue(restored.hydrate(encrypted.id,0,-1,null).getBoolean("complete"));}
        try(InputStream input=vault.openRange(vault.load(encrypted.id),0,source.length,key)){assertArrayEquals(source,input.readAllBytes());}
    }
    @Test public void revokedProviderGrantFallsBackToAnotherReplica()throws Exception{
        replicate(manifest.id,List.of("a","b"),2,null);VaultChunkStore.Chunk chunk=manifest.chunks.get(0);Files.delete(root.resolve("vault/objects/"+chunk.objectName));remote.deniedProfile="a";
        try(VaultStorageFabric restored=fabric()){assertEquals(1,restored.hydrate(manifest.id,0,1,null).getInt("downloadedObjects"));}assertEquals(8,remote.puts);assertEquals(0,remote.deletes);
    }
    @Test public void syntacticallyValidManifestCorruptionRestoresTheVerifiedBinaryManifest()throws Exception{
        replicate(manifest.id,List.of("a"),1,null);Path file=root.resolve("vault/manifests/"+manifest.id+".manifest");byte[] valid=Files.readAllBytes(file),corrupt=valid.clone();byte[] marker=manifest.chunks.get(0).sha256.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        for(int i=0;i<=corrupt.length-marker.length;i++){boolean same=true;for(int j=0;j<marker.length;j++)if(corrupt[i+j]!=marker[j]){same=false;break;}if(same){corrupt[i]=corrupt[i]=='a'?(byte)'b':(byte)'a';break;}}
        Files.write(file,corrupt);assertNotEquals(manifest.chunks.get(0).sha256,new VaultChunkStore(root.resolve("vault").toFile(),4096).load(manifest.id).chunks.get(0).sha256);
        try(VaultStorageFabric restored=fabric()){restored.hydrate(manifest.id,0,-1,null);}assertArrayEquals(valid,Files.readAllBytes(file));assertEquals(4,remote.puts);
    }
    @Test public void abandonedDownloadStageIsReclaimedBeforeRestartWithoutLeavingDuplicates()throws Exception{
        replicate(manifest.id,List.of("a"),1,null);VaultChunkStore.Chunk chunk=manifest.chunks.get(0);Files.delete(root.resolve("vault/objects/"+chunk.objectName));Path stale=root.resolve("vault/partial/"+chunk.objectName+".download.partial");Files.write(stale,new byte[4000]);
        try(VaultStorageFabric restored=fabric()){restored.hydrate(manifest.id,0,1,null);}assertFalse(Files.exists(stale));try(var files=Files.list(root.resolve("vault/partial"))){assertEquals(0,files.count());}
    }
    @Test public void cancellationDuringLocalManifestVerificationCannotFallThroughToRemoteDownload()throws Exception{
        replicate(manifest.id,List.of("a"),1,null);int before=remote.opens;int[] checks={0};
        try(VaultStorageFabric restored=fabric()){try{restored.hydrate(manifest.id,0,-1,(done,total)->{if(++checks[0]==2)throw new InterruptedIOException("Owner stopped during manifest checksum");});fail("Cancellation ignored");}catch(InterruptedIOException expected){}}
        assertEquals("Cancellation must not trigger a provider read",before,remote.opens);
    }
    private static final class SimulatedDeath extends Error {}
    private static final class Remote implements VaultStorageFabric.BlobStore {
        final Path root;final Map<String,Path> files=new LinkedHashMap<>();final Set<String> writtenProfiles=new HashSet<>();int puts,deletes,opens;boolean corrupt,dieAfterWrite,unavailable;long free=-1;String deniedProfile="";
        Remote(Path root)throws IOException{this.root=root;Files.createDirectories(root);}
        public long freeBytes(String profile){return free;}
        public String put(String profile,String name,String token,File source,VaultStorageFabric.Progress progress)throws Exception{
            Path target=root.resolve(profile+"-"+token+"-"+name);Files.copy(source.toPath(),target,StandardCopyOption.REPLACE_EXISTING);puts++;writtenProfiles.add(profile);
            if(corrupt)Files.write(target,new byte[]{3});String uri=target.toUri().toString();files.put(profile+":"+uri,target);if(dieAfterWrite)throw new SimulatedDeath();return uri;
        }
        public String resolve(String profile,String name,String token){Path target=root.resolve(profile+"-"+token+"-"+name);String location=target.toUri().toString();return files.containsKey(profile+":"+location)?location:null;}
        public String repair(String profile,String location,File source,VaultStorageFabric.Progress progress)throws Exception{Path target=files.get(profile+":"+location);Files.copy(source.toPath(),target,StandardCopyOption.REPLACE_EXISTING);puts++;return location;}
        public InputStream open(String profile,String location)throws Exception{opens++;if(profile.equals(deniedProfile))throw new SecurityException("Owner revoked this provider grant");if(unavailable)throw new IOException("Temporary provider outage");Path path=files.get(profile+":"+location);if(path==null)throw new FileNotFoundException();return Files.newInputStream(path);}
        public void delete(String profile,String location)throws Exception{deletes++;Path path=files.remove(profile+":"+location);if(path!=null)Files.deleteIfExists(path);}
    }
}
