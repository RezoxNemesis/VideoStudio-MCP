package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
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
public class VaultManagerRecoveryTest {
    private Context context;private ProjectStore store;private ProjectStore.Project project;private Path source;private byte[] bytes;private VaultManager manager;private String manifest;
    @Before public void setup()throws Exception{
        context=RuntimeEnvironment.getApplication();context.deleteDatabase("videostudio_v3.db");context.deleteDatabase("videostudio_vault_replicas.db");store=new ProjectStore(context);project=store.create("Recovery owner");
        source=Files.createTempFile(context.getCacheDir().toPath(),"recovery-original-",".bin");Path recoveryRoot=context.getFilesDir().toPath().resolve("vault_restored_media");if(Files.exists(recoveryRoot))try(var files=Files.list(recoveryRoot)){for(Path f:files.toList())Files.delete(f);}
        bytes=new byte[8000];new Random(17).nextBytes(bytes);Files.write(source,bytes);
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned";asset.uri=source.toUri().toString();asset.mime="application/octet-stream";asset.name="Original";asset.sizeBytes=bytes.length;project.assets.add(asset);store.save(project);
        manager=new VaultManager(context,store);manifest=manager.create(project.id,"owned",false,null).getJSONObject("vault").getString("manifestId");
    }
    @After public void cleanup()throws Exception{Files.deleteIfExists(source);}
    @Test public void restoredMediaRelinksTheBoundAssetToIdenticalBytesAndPreservesItsOriginal()throws Exception{
        JSONObject result=manager.restore(project.id,"owned",manifest,null);assertTrue(result.getBoolean("complete"));
        ProjectStore.Asset restored=store.get(project.id).asset("owned");assertNotEquals(source.toUri().toString(),restored.uri);assertEquals(result.getString("uri"),restored.uri);
        assertArrayEquals(bytes,Files.readAllBytes(Path.of(java.net.URI.create(restored.uri))));assertArrayEquals(bytes,Files.readAllBytes(source));
        assertEquals(source.toUri().toString(),restored.generationMetadata.getJSONObject("vaultRecovery").getString("originalUri"));assertEquals(manifest,restored.generationMetadata.getJSONObject("vault").getString("manifestId"));
        long before=store.get(project.id).revision;JSONObject retry=manager.restore(project.id,"owned",manifest,null);assertEquals(restored.uri,retry.getString("uri"));assertEquals("Recovery replay must not replace original identity or add a revision",before,store.get(project.id).revision);
        assertEquals(restored.uri,manager.restore(project.id,"owned",manifest,source.toUri().toString(),null).getString("uri"));assertEquals(before,store.get(project.id).revision);
    }
    @Test public void sourceChangedDuringRecoveryKeepsTheOwnersReplacement()throws Exception{
        JobManager.Job job=new JobManager.Job("Recovery race",JobManager.Kind.HEAVY);boolean[] changed={false};
        job.setAuthorizationGuard(()->{if(!changed[0]&&"vault_restore_write".equals(job.stage)){changed[0]=true;try{ProjectStore.Project latest=store.get(project.id);latest.asset("owned").uri="file:///owners-new-source";latest.name="Owner newer project";store.save(latest);}catch(Exception invalid){throw new RuntimeException(invalid);}}});
        try{manager.restore(project.id,"owned",manifest,job);fail("Recovery rebound a changed asset");}catch(IllegalStateException expected){}
        assertTrue(changed[0]);assertEquals("file:///owners-new-source",store.get(project.id).asset("owned").uri);assertEquals("Owner newer project",store.get(project.id).name);assertArrayEquals(bytes,Files.readAllBytes(source));
    }
    @Test public void stoppedRecoveryCannotPublishMediaOrReplaceTheOriginal()throws Exception{
        JobManager.Job job=new JobManager.Job("Cancelled recovery",JobManager.Kind.HEAVY);job.state=JobManager.STATE_CANCELLED;
        try{manager.restore(project.id,"owned",manifest,job);fail("Stopped job restored media");}catch(java.util.concurrent.CancellationException expected){}
        assertEquals(source.toUri().toString(),store.get(project.id).asset("owned").uri);assertFalse(store.get(project.id).asset("owned").generationMetadata.has("vaultRecovery"));
    }
    @Test public void queuedRestoreNeverTreatsTheOwnersReplacementAsItsOriginalSource()throws Exception{
        String queuedSource=source.toUri().toString();ProjectStore.Project latest=store.get(project.id);latest.asset("owned").uri="file:///owner-replacement-after-queue";store.save(latest);
        try{manager.restore(project.id,"owned",manifest,queuedSource,null);fail("Queued restore overwrote owner replacement");}catch(IllegalArgumentException expected){}
        assertEquals("file:///owner-replacement-after-queue",store.get(project.id).asset("owned").uri);
    }
    @Test public void plaintextStageSurvivesProcessDeathAndResumesFromVerifiedWholeChunks()throws Exception{
        Path stage=context.getFilesDir().toPath().resolve("vault_restored_media/"+manifest+".restore.partial");JobManager.Job killed=new JobManager.Job("Process death",JobManager.Kind.HEAVY);
        killed.setAuthorizationGuard(()->{if("vault_restore_write".equals(killed.stage)&&Files.exists(stage)&&stage.toFile().length()==bytes.length)throw new SimulatedDeath();});
        try{manager.restore(project.id,"owned",manifest,killed);fail("Expected simulated death after file write");}catch(SimulatedDeath expected){}
        assertTrue(Files.exists(stage));assertEquals(source.toUri().toString(),store.get(project.id).asset("owned").uri);
        JSONObject resumed=manager.restore(project.id,"owned",manifest,null);assertEquals(bytes.length,resumed.getLong("resumedBytes"));assertFalse(Files.exists(stage));assertArrayEquals(bytes,Files.readAllBytes(Path.of(java.net.URI.create(resumed.getString("uri")))));
    }
    @Test public void partialRecoveryKeepsVerifiedPrefixAndDiscardsAnIncompleteCorruptTail()throws Exception{
        File root=new File(context.getFilesDir(),"videostudio_vault");VaultChunkStore.Manifest chunks=new VaultChunkStore(root,4096).pack(new ByteArrayInputStream(bytes),bytes.length,null,"",null);
        ProjectStore.Project latest=store.get(project.id);latest.asset("owned").generationMetadata.put("vault",manager.describe(chunks).put("complete",true));store.save(latest);
        Path stage=context.getFilesDir().toPath().resolve("vault_restored_media/"+manifest+".restore.partial");Files.createDirectories(stage.getParent());byte[] partial=Arrays.copyOf(bytes,5000);Arrays.fill(partial,4096,5000,(byte)0);Files.write(stage,partial);
        JSONObject resumed=manager.restore(project.id,"owned",manifest,null);assertEquals(4096,resumed.getLong("resumedBytes"));assertArrayEquals(bytes,Files.readAllBytes(Path.of(java.net.URI.create(resumed.getString("uri")))));assertFalse(Files.exists(stage));
    }
    private static final class SimulatedDeath extends Error {}
    @Test public void recoveryRejectsAnOutdatedManifestBinding()throws Exception{
        try{manager.restore(project.id,"owned","f".repeat(64),null);fail("Wrong manifest accepted");}catch(IllegalArgumentException expected){}
        assertEquals(source.toUri().toString(),store.get(project.id).asset("owned").uri);
    }
}
