package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import java.io.*;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class RenderSessionStoreTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final File root=new File(context.getFilesDir(),"session-fixture-"+java.util.UUID.randomUUID());
    private final ArrayList<RenderSessionStore> stores=new ArrayList<>();
    @After public void closeStores(){for(var store:stores)store.close();}
    private RenderSessionStore store(String process,RenderSessionStore.Faults faults){var store=new RenderSessionStore(context,new File(root,"sessions"),process,this::proof,faults);stores.add(store);return store;}
    private RenderSessionStore store(String process){return store(process,point->{});}
    private RenderSourceIdentity.Snapshot snapshot(int revision)throws Exception{
        assertTrue(root.isDirectory()||root.mkdirs());File original=new File(root,"original.png");if(!original.exists())Files.write(original.toPath(),new byte[]{1,2,3});
        ProjectStore.Project p=new ProjectStore.Project();p.id="journal-project";p.name="Original graph";p.revision=revision;p.ensureTimelineDefaults();ProjectStore.Asset a=new ProjectStore.Asset();a.id="image";a.mime="image/png";a.uri=Uri.fromFile(original).toString();p.assets.add(a);ProjectStore.Clip c=new ProjectStore.Clip();c.id="clip";c.assetId=a.id;c.trackId=p.tracks.get(0).id;c.outMs=12000;p.clips.add(c);
        return new RenderSourceIdentity(new File(root,"inputs"),uri->new FileInputStream(new File(Uri.parse(uri).getPath()))).capture(p,"16:9","720p",()->false);
    }
    private JSONObject proof(File file,boolean video)throws Exception{
        byte[] bytes=Files.readAllBytes(file.toPath());StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        return new JSONObject().put("playable",true).put("sha256",hash.toString()).put("sizeBytes",bytes.length).put("hasVideo",video).put("hasAudio",!video).put("durationMs",5000);
    }
    private File stage(RenderSessionStore store,RenderSessionStore.Writer writer,RenderSessionStore.Kind kind,int index,long start,long end)throws Exception{
        File stage=store.begin(writer,kind,index,start,end,new JSONObject().put("clipId","clip").put("sourceInUs",start));Files.write(stage.toPath(),new byte[]{4,5,6});return stage;
    }
    private JSONObject encoded(File file,boolean video)throws Exception{return proof(file,video).put("codecRoute","conservative").put("configuredBitrate",2_000_000);}
    @Test public void completedCheckpointAndCapturedGraphSurviveStoreRestart()throws Exception{
        var snapshot=snapshot(4);var first=store("process-a");var writer=first.acquire(snapshot);File stage=stage(first,writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);var complete=first.complete(writer,RenderSessionStore.Kind.VIDEO,0,encoded(stage,true));assertFalse(stage.exists());assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(complete.file.toPath()));writer.close();first.close();
        var reopened=store("process-a");try(var next=reopened.acquire(snapshot)){var reused=reopened.reusable(next,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);assertNotNull(reused);assertEquals(complete.proof.getString("sha256"),reused.proof.getString("sha256"));assertEquals("conservative",reused.proof.getString("codecRoute"));assertEquals("journal-project",reopened.session(next).getJSONObject("project").getString("id"));assertEquals(snapshot.sessionId,reopened.session(next).getJSONObject("manifest").getString("sessionId"));}
    }
    @Test public void deathAfterEncodedIntentReusesVerifiedStageWithoutRenderingAgain()throws Exception{
        var snapshot=snapshot(4);var dying=store("old-process",point->{if(point.equals("encoded"))throw new IOException("Injected death after encoded intent");});var old=dying.acquire(snapshot);File stage=stage(dying,old,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);String sha=proof(stage,true).getString("sha256");
        try{dying.complete(old,RenderSessionStore.Kind.VIDEO,0,encoded(stage,true));fail("Fault not injected");}catch(IOException expected){}
        var restarted=store("new-process");try(var writer=restarted.acquire(snapshot)){var recovered=restarted.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);assertNotNull(recovered);assertEquals(sha,recovered.proof.getString("sha256"));assertFalse(stage.exists());assertTrue(recovered.file.exists());}
    }
    @Test public void deathAfterAtomicRenameRecoversTheSameFinalBytes()throws Exception{
        var snapshot=snapshot(4);var dying=store("old-process",point->{if(point.equals("renamed"))throw new IOException("Injected death after rename");});var old=dying.acquire(snapshot);File stage=stage(dying,old,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);String sha=proof(stage,true).getString("sha256");
        try{dying.complete(old,RenderSessionStore.Kind.VIDEO,0,encoded(stage,true));fail("Fault not injected");}catch(IOException expected){}assertFalse(stage.exists());
        var restarted=store("new-process");try(var writer=restarted.acquire(snapshot)){assertEquals(sha,restarted.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000).proof.getString("sha256"));}
    }
    @Test public void corruptCheckpointIsNotReusedAndOriginalMediaSurvives()throws Exception{
        var snapshot=snapshot(4);var store=store("process");try(var writer=store.acquire(snapshot)){File stage=stage(store,writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);var complete=store.complete(writer,RenderSessionStore.Kind.VIDEO,0,encoded(stage,true));Files.write(complete.file.toPath(),new byte[]{8,8,8});assertNull(store.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000));File rerender=stage(store,writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);assertTrue(rerender.exists());assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(new File(root,"original.png").toPath()));}
    }
    @Test public void missingCheckpointRerendersOnlyThatWindow()throws Exception{
        var snapshot=snapshot(4);var store=store("process");try(var writer=store.acquire(snapshot)){File first=stage(store,writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);var saved=store.complete(writer,RenderSessionStore.Kind.VIDEO,0,encoded(first,true));File second=stage(store,writer,RenderSessionStore.Kind.VIDEO,1,5_000_000,10_000_000);var lost=store.complete(writer,RenderSessionStore.Kind.VIDEO,1,encoded(second,true));assertTrue(lost.file.delete());assertNull(store.reusable(writer,RenderSessionStore.Kind.VIDEO,1,5_000_000,10_000_000));assertEquals(saved.proof.getString("sha256"),store.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000).proof.getString("sha256"));}
    }
    @Test public void wrongProducerChecksumCannotPublishAStage()throws Exception{
        var snapshot=snapshot(4);var store=store("process");try(var writer=store.acquire(snapshot)){File file=stage(store,writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);JSONObject wrong=encoded(file,true).put("sha256","0".repeat(64));try{store.complete(writer,RenderSessionStore.Kind.VIDEO,0,wrong);fail("Wrong producer proof accepted");}catch(IllegalArgumentException expected){}assertNull(store.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000));assertTrue(file.exists());}
    }
    @Test public void changedProjectIdentityCannotReuseAnotherSessionsBytes()throws Exception{
        var first=snapshot(4);var second=snapshot(5);var store=store("process");try(var a=store.acquire(first);var b=store.acquire(second)){File file=stage(store,a,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);store.complete(a,RenderSessionStore.Kind.VIDEO,0,encoded(file,true));assertNull(store.reusable(b,RenderSessionStore.Kind.VIDEO,0,0,5_000_000));assertNotNull(store.reusable(a,RenderSessionStore.Kind.VIDEO,0,0,5_000_000));}
    }
    @Test public void differentWindowBoundsDoNotReuseOrOverwriteAVerifiedCheckpoint()throws Exception{
        var snapshot=snapshot(4);var store=store("process");try(var writer=store.acquire(snapshot)){File file=stage(store,writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);store.complete(writer,RenderSessionStore.Kind.VIDEO,0,encoded(file,true));assertNull(store.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,4_000_000));try{store.begin(writer,RenderSessionStore.Kind.VIDEO,0,0,4_000_000,new JSONObject());fail("Changed plan overwrote a verified window");}catch(IllegalArgumentException expected){}assertNotNull(store.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000));}
    }
    @Test public void simultaneousWritersAreRejectedAndReleasedGenerationsCannotCommit()throws Exception{
        var snapshot=snapshot(4);var one=store("process");var two=store("process");var old=one.acquire(snapshot);File oldStage=stage(one,old,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);JSONObject oldProof=encoded(oldStage,true);
        try{two.acquire(snapshot);fail("Conflicting session writer accepted");}catch(IllegalStateException expected){}old.close();
        try(var next=two.acquire(snapshot)){File newStage=stage(two,next,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);assertTrue(next.generation>old.generation);assertNotEquals("Old codecs must not share the new generation's stage",oldStage,newStage);try{one.complete(old,RenderSessionStore.Kind.VIDEO,0,oldProof);fail("Retired writer published");}catch(IllegalStateException expected){}assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(newStage.toPath()));}
    }
    @Test public void recoveredProcessGenerationRejectsLateOldCallbacks()throws Exception{
        var snapshot=snapshot(4);var oldStore=store("old-process");var old=oldStore.acquire(snapshot);File oldFile=stage(oldStore,old,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);JSONObject oldProof=encoded(oldFile,true);var recovered=store("new-process");try(var next=recovered.acquire(snapshot)){File current=stage(recovered,next,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);assertNotEquals(oldFile,current);try{oldStore.complete(old,RenderSessionStore.Kind.VIDEO,0,oldProof);fail("Old process generation published");}catch(IllegalStateException expected){}assertTrue(current.exists());}
    }
    @Test public void incompletePendingStageIsNotAReusableSuccess()throws Exception{
        var snapshot=snapshot(4);var store=store("process");try(var writer=store.acquire(snapshot)){File stage=stage(store,writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);assertTrue(stage.exists());assertNull(store.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000));}
    }
    @Test public void audioCheckpointIsWholeProgramAndIndependentOfVideoRows()throws Exception{
        var snapshot=snapshot(4);var store=store("process");try(var writer=store.acquire(snapshot)){File stage=stage(store,writer,RenderSessionStore.Kind.AUDIO,0,0,12_000_000);var audio=store.complete(writer,RenderSessionStore.Kind.AUDIO,0,encoded(stage,false));assertTrue(audio.proof.getBoolean("hasAudio"));assertNull(store.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000));assertNotNull(store.reusable(writer,RenderSessionStore.Kind.AUDIO,0,0,12_000_000));}
    }
    @Test public void restartedPendingStageIsReclaimedWithoutDeletingVerifiedWindows()throws Exception{
        var snapshot=snapshot(4);var dying=store("old-process");var old=dying.acquire(snapshot);File unfinished=stage(dying,old,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);File good=stage(dying,old,RenderSessionStore.Kind.VIDEO,1,5_000_000,10_000_000);var saved=dying.complete(old,RenderSessionStore.Kind.VIDEO,1,encoded(good,true));
        var restarted=store("new-process");try(var writer=restarted.acquire(snapshot)){File current=stage(restarted,writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);assertFalse("Retired pending stage leaked",unfinished.exists());assertTrue(current.exists());assertEquals(saved.proof.getString("sha256"),restarted.reusable(writer,RenderSessionStore.Kind.VIDEO,1,5_000_000,10_000_000).proof.getString("sha256"));}
    }
    @Test public void changedCapturedGraphIsRejectedBeforeTheFirstJournalWrite()throws Exception{
        var snapshot=snapshot(4);snapshot.boundProject.clips.get(0).speed=2;
        try{store("process").acquire(snapshot);fail("Changed graph accepted under the old captured identity");}catch(IllegalArgumentException expected){}
    }
    @Test public void inventedManifestIdIsRejectedBeforeTheFirstJournalWrite()throws Exception{
        var original=snapshot(4);JSONObject changed=new JSONObject(original.manifest.toString()).put("sessionId","0".repeat(64));
        try{store("process").acquire(new RenderSourceIdentity.Snapshot(original.boundProject,changed,"0".repeat(64),original.inputRoot));fail("Unbound manifest accepted");}catch(IllegalArgumentException expected){}
    }
    @Test public void transientDecoderFailureKeepsTheChecksumValidCheckpoint()throws Exception{
        var snapshot=snapshot(4);var unavailable=new java.util.concurrent.atomic.AtomicBoolean();var store=new RenderSessionStore(context,new File(root,"sessions"),"process",(file,video)->{if(unavailable.get())throw new IOException("Transient decoder unavailable");return proof(file,video);},point->{});stores.add(store);
        try(var writer=store.acquire(snapshot)){File stage=stage(store,writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);var saved=store.complete(writer,RenderSessionStore.Kind.VIDEO,0,encoded(stage,true));unavailable.set(true);try{store.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);fail("Decoder failure hidden");}catch(IOException expected){}assertTrue(saved.file.exists());unavailable.set(false);assertEquals(saved.proof.getString("sha256"),store.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000).proof.getString("sha256"));}
    }
    @Test public void duplicateCompletionIsIdempotentAndDifferentBytesAreRejected()throws Exception{
        var snapshot=snapshot(4);var store=store("process");try(var writer=store.acquire(snapshot)){File stage=stage(store,writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);JSONObject producer=encoded(stage,true);var saved=store.complete(writer,RenderSessionStore.Kind.VIDEO,0,producer);assertEquals(saved.file,store.complete(writer,RenderSessionStore.Kind.VIDEO,0,producer).file);try{store.complete(writer,RenderSessionStore.Kind.VIDEO,0,new JSONObject(producer.toString()).put("sha256","0".repeat(64)));fail("Conflicting duplicate accepted");}catch(IllegalArgumentException expected){}assertTrue(saved.file.exists());}
    }
    @Test public void interruptedReuseRetainsTheCheckpointAndRetiredReleaseDoesNotClearNewWriter()throws Exception{
        var snapshot=snapshot(4);var dying=store("old-process");var old=dying.acquire(snapshot);File stage=stage(dying,old,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);dying.complete(old,RenderSessionStore.Kind.VIDEO,0,encoded(stage,true));var restarted=store("new-process");try(var writer=restarted.acquire(snapshot)){old.close();Thread.currentThread().interrupt();try{restarted.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);fail("Interrupted verification completed");}catch(InterruptedIOException expected){}finally{Thread.interrupted();}assertNotNull(restarted.reusable(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000));}
    }
    @Test public void pairedPinnedUriRedirectionCannotHideChangedSourceBytes()throws Exception{
        var snapshot=snapshot(4);File other=new File(root,"redirect.png");Files.write(other.toPath(),new byte[]{8,8,8});String redirect=Uri.fromFile(other).toString();snapshot.boundProject.assets.get(0).uri=redirect;snapshot.manifest.getJSONArray("sources").getJSONObject(0).put("snapshotUri",redirect);
        try{store("process").acquire(snapshot);fail("Redirected source accepted under the original source proof");}catch(IllegalArgumentException expected){}
    }
    @Test public void changedPinnedBytesCannotClaimTheOriginalCapturedIdentity()throws Exception{
        var snapshot=snapshot(4);Files.write(new File(Uri.parse(snapshot.boundProject.assets.get(0).uri).getPath()).toPath(),new byte[]{9,9,9});
        try{store("process").acquire(snapshot);fail("Corrupt pinned bytes accepted under the original proof");}catch(IllegalArgumentException expected){}
    }
    @Test public void recordedCheckpointIsLightweightLeaseBoundMetadataForFreshMuxVerification()throws Exception{
        var snapshot=snapshot(4);var calls=new java.util.concurrent.atomic.AtomicInteger();var store=new RenderSessionStore(context,new File(root,"sessions"),"process",(file,video)->{calls.incrementAndGet();return proof(file,video);},point->{});stores.add(store);
        var writer=store.acquire(snapshot);File stage=stage(store,writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);var saved=store.complete(writer,RenderSessionStore.Kind.VIDEO,0,encoded(stage,true));int verified=calls.get();
        assertEquals(saved.proof.getString("sha256"),store.recorded(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000).proof.getString("sha256"));assertEquals(verified,calls.get());assertNull(store.recorded(writer,RenderSessionStore.Kind.VIDEO,0,0,4_000_000));assertNull(store.recorded(writer,RenderSessionStore.Kind.VIDEO,1,5_000_000,10_000_000));writer.close();assertThrows(IllegalStateException.class,()->store.recorded(writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000));
    }
    @Test public void routeRepairInvalidatesOnlyRequestedVideoAndCannotReuseOldGenerationStage()throws Exception{
        var snapshot=snapshot(4);var store=store("process");var old=store.acquire(snapshot);File first=stage(store,old,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);var stale=store.complete(old,RenderSessionStore.Kind.VIDEO,0,encoded(first,true));File second=stage(store,old,RenderSessionStore.Kind.VIDEO,1,5_000_000,10_000_000);var good=store.complete(old,RenderSessionStore.Kind.VIDEO,1,encoded(second,true));File sound=stage(store,old,RenderSessionStore.Kind.AUDIO,0,0,12_000_000);var audio=store.complete(old,RenderSessionStore.Kind.AUDIO,0,encoded(sound,false));
        store.invalidate(old,RenderSessionStore.Kind.VIDEO,0);assertFalse(stale.file.exists());assertNull(store.reusable(old,RenderSessionStore.Kind.VIDEO,0,0,5_000_000));assertEquals(good.proof.getString("sha256"),store.reusable(old,RenderSessionStore.Kind.VIDEO,1,5_000_000,10_000_000).proof.getString("sha256"));assertEquals(audio.proof.getString("sha256"),store.reusable(old,RenderSessionStore.Kind.AUDIO,0,0,12_000_000).proof.getString("sha256"));old.close();
        try(var next=store.acquire(snapshot)){File current=stage(store,next,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);assertNotEquals(first,current);assertThrows(IllegalStateException.class,()->store.invalidate(old,RenderSessionStore.Kind.VIDEO,1));assertTrue(good.file.exists());}
    }
    @Test public void interruptedInvalidationRetainsCleanupReferenceAndPreservesOtherCheckpoints()throws Exception{
        var snapshot=snapshot(4);var first=store("process-a",point->{if(point.equals("invalidated_final"))throw new IOException("Synthetic death during invalidation");});var writer=first.acquire(snapshot);
        File pending=stage(first,writer,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);File other=stage(first,writer,RenderSessionStore.Kind.VIDEO,1,5_000_000,10_000_000);var good=first.complete(writer,RenderSessionStore.Kind.VIDEO,1,encoded(other,true));File sound=stage(first,writer,RenderSessionStore.Kind.AUDIO,0,0,12_000_000);var audio=first.complete(writer,RenderSessionStore.Kind.AUDIO,0,encoded(sound,false));
        assertThrows(IOException.class,()->first.invalidate(writer,RenderSessionStore.Kind.VIDEO,0));first.close();
        var next=store("process-b");try(var lease=next.acquire(snapshot)){File replacement=stage(next,lease,RenderSessionStore.Kind.VIDEO,0,0,5_000_000);assertNotEquals(pending,replacement);assertFalse("Retired stage must be reclaimed before replacement rendering",pending.exists());assertEquals(good.proof.getString("sha256"),next.reusable(lease,RenderSessionStore.Kind.VIDEO,1,5_000_000,10_000_000).proof.getString("sha256"));assertEquals(audio.proof.getString("sha256"),next.reusable(lease,RenderSessionStore.Kind.AUDIO,0,0,12_000_000).proof.getString("sha256"));}
    }
    @Test public void schemaOneMigrationKeepsCapturedGraphAndDefaultsToConservativeRoute()throws Exception{
        var snapshot=snapshot(4);File journalRoot=new File(root,"sessions");assertTrue(journalRoot.mkdirs());
        try(var old=android.database.sqlite.SQLiteDatabase.openOrCreateDatabase(new File(journalRoot,"journal.sqlite"),null)){
            old.execSQL("CREATE TABLE sessions(id TEXT PRIMARY KEY,manifest TEXT NOT NULL,graph TEXT NOT NULL,generation INTEGER NOT NULL,writer TEXT NOT NULL,updated_at INTEGER NOT NULL)");
            old.execSQL("CREATE TABLE checkpoints(session TEXT NOT NULL,kind TEXT NOT NULL,ordinal INTEGER NOT NULL,start_us INTEGER NOT NULL,end_us INTEGER NOT NULL,state TEXT NOT NULL,stage_generation INTEGER NOT NULL,bytes INTEGER NOT NULL,sha TEXT NOT NULL,metadata TEXT NOT NULL,PRIMARY KEY(session,kind,ordinal),FOREIGN KEY(session) REFERENCES sessions(id))");
            old.execSQL("INSERT INTO sessions VALUES(?,?,?,?,?,?)",new Object[]{snapshot.sessionId,RenderSourceIdentity.canonical(snapshot.manifest),RenderSourceIdentity.canonical(snapshot.boundProject.snapshotJson()),5,"",0});old.setVersion(1);
        }
        var migrated=store("process");try(var writer=migrated.acquire(snapshot)){assertEquals(6,writer.generation);assertEquals("conservative",migrated.session(writer).getString("codecRoute"));assertEquals(RenderSourceIdentity.canonical(snapshot.boundProject.snapshotJson()),RenderSourceIdentity.canonical(migrated.session(writer).getJSONObject("project")));migrated.codecRoute(writer,RenderRetryController.Route.SOFTWARE_DECODER);assertThrows(IllegalArgumentException.class,()->migrated.codecRoute(writer,RenderRetryController.Route.CONSERVATIVE));assertThrows(IllegalArgumentException.class,()->migrated.codecRoute(writer,RenderRetryController.Route.DEFAULT));}
        try(var writer=migrated.acquire(snapshot)){assertEquals("software_decoder",migrated.session(writer).getString("codecRoute"));}
    }
}
