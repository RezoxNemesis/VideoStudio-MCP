package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class ProjectAssetImportTest {
    static final class Fixture {
        final Context context=RuntimeEnvironment.getApplication();final ProjectStore store=new ProjectStore(context);
        final ProjectStore.Project source=store.create("Picture"),destination=store.create("Story audio");
        final File file;final byte[] bytes=new byte[192*1024];final JSONObject request;
        Fixture()throws Exception {
            for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*37+11);file=new File(context.getFilesDir(),UUID.randomUUID()+".mp4");Files.write(file.toPath(),bytes);
            ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="picture";asset.name="Native picture.mp4";asset.mime="video/mp4";asset.uri=Uri.fromFile(file).toString();asset.durationMs=124000;asset.sizeBytes=bytes.length;asset.generated=true;asset.role="generated_sketch_video";asset.generationMetadata.put("outputSha256",SketchStoryAudio.sha256(file)).put("frameCount",2976);source.assets.add(asset);store.save(source);
            ProjectStore.Asset audio=new ProjectStore.Asset();audio.id="mix";audio.name="Story mix.wav";audio.mime="audio/wav";audio.uri="file:///unused-test-mix.wav";audio.durationMs=124000;destination.assets.add(audio);
            StudioLayer lane=new StudioLayer();lane.type=StudioLayer.AUDIO;lane.assetId="mix";lane.startMs=0;lane.sourceInMs=0;lane.durationMs=124000;destination.studioLayers.add(lane);store.save(destination);
            request=new JSONObject().put("projectId",destination.id).put("expectedUpdatedAt",destination.updatedAt).put("sourceProjectId",source.id).put("sourceProjectUpdatedAt",source.updatedAt).put("assetId","picture").put("expectedSha256",SketchStoryAudio.sha256(file)).put("operationId","copy_picture");
        }
        JSONObject execute()throws Exception{return call("execute",context,request);}
        ProjectStore.Project output(){return store.get(destination.id);}
    }
    private static JSONObject call(String name,Context c,JSONObject request)throws Exception {
        try{Class<?> type=Class.forName("com.rezoxnemesis.videostudio.ProjectAssetImport");Method method=type.getMethod(name,Context.class,JSONObject.class);return (JSONObject)method.invoke(null,c,request);}
        catch(InvocationTargetException error){if(error.getCause() instanceof Exception)throw (Exception)error.getCause();throw error;}
    }
    @Test public void explicitOwnedAssetInfoHashesBytesWithoutPublishingAnyDestinationChanges()throws Exception {
        Fixture f=new Fixture();String before=f.output().toJson().toString();JSONObject info=call("inspect",f.context,new JSONObject().put("projectId",f.source.id).put("expectedUpdatedAt",f.source.updatedAt).put("assetId","picture"));
        assertTrue(info.getBoolean("ok"));assertTrue(info.getBoolean("readOnly"));assertEquals(f.request.getString("expectedSha256"),info.getString("sha256"));assertEquals(f.bytes.length,info.getLong("bytes"));assertEquals(before,f.output().toJson().toString());
    }
    @Test public void copiesExactIndependentBytesAndProvenanceWithoutChangingSourcesOrAudioLanes()throws Exception {
        Fixture f=new Fixture();String sourceBefore=f.store.get(f.source.id).toJson().toString(),lanes=StudioLayerContract.toJson(f.destination.studioLayers).toString();JSONObject result=f.execute();
        assertTrue(result.getBoolean("ok"));assertFalse(result.getBoolean("appendedToTimeline"));assertEquals(f.destination.id,result.getString("projectId"));assertEquals(f.request.getString("expectedSha256"),result.getString("sha256"));
        ProjectStore.Project output=f.output();assertEquals(0,output.clips.size());assertEquals(lanes,StudioLayerContract.toJson(output.studioLayers).toString());ProjectStore.Asset copied=output.asset(result.getString("assetId"));
        File target=new File(Uri.parse(copied.uri).getPath());assertNotEquals(f.file.getCanonicalPath(),target.getCanonicalPath());assertArrayEquals(f.bytes,Files.readAllBytes(target.toPath()));assertEquals(2976,copied.generationMetadata.getInt("frameCount"));assertEquals(sourceBefore,f.store.get(f.source.id).toJson().toString());
        f.store.delete(f.source.id);Files.delete(f.file.toPath());assertArrayEquals(f.bytes,Files.readAllBytes(target.toPath()));
        JSONObject replay=f.execute();assertTrue(replay.getBoolean("replayed"));assertEquals(result.getString("assetId"),replay.getString("assetId"));
    }
    @Test public void optionalAppendAddsOneExactVideoClipAndReplayPreservesLaterHumanEdits()throws Exception {
        Fixture f=new Fixture();f.request.put("appendToTimeline",true);JSONObject first=f.execute();ProjectStore.Project output=f.output();assertEquals(1,output.clips.size());
        ProjectStore.Clip clip=output.clips.get(0);assertEquals(first.getString("assetId"),clip.assetId);assertEquals(0,clip.inMs);assertEquals(124000,clip.outMs);assertEquals(1,clip.speed,0);clip.title="Human edit";f.store.save(output);
        long revision=f.output().updatedAt;JSONObject replay=f.execute();assertTrue(replay.getBoolean("replayed"));assertEquals(1,f.output().clips.size());assertEquals("Human edit",f.output().clips.get(0).title);assertEquals(revision,f.output().updatedAt);
    }
    @Test public void rejectsChangedInputsHashesRevisionsAndExternalFilesWithoutPublishingAssets()throws Exception {
        for(String invalid:new String[]{"hash","source_revision","destination_revision","same_project","extra_field","external","symlink","mime"}){
            Fixture f=new Fixture();String before=f.output().toJson().toString();
            if(invalid.equals("hash"))f.request.put("expectedSha256","0".repeat(64));
            if(invalid.equals("source_revision"))f.request.put("sourceProjectUpdatedAt",0);
            if(invalid.equals("destination_revision"))f.request.put("expectedUpdatedAt",0);
            if(invalid.equals("same_project"))f.request.put("projectId",f.source.id);
            if(invalid.equals("extra_field"))f.request.put("uri",f.file.toURI().toString());
            if(invalid.equals("external")||invalid.equals("symlink")||invalid.equals("mime")){
                ProjectStore.Project source=f.store.get(f.source.id);
                if(invalid.equals("external")){File outside=Files.createTempFile("external_asset_",".mp4").toFile();Files.write(outside.toPath(),f.bytes);source.assets.get(0).uri=Uri.fromFile(outside).toString();}
                if(invalid.equals("symlink")){File link=new File(f.context.getFilesDir(),UUID.randomUUID()+".mp4");Files.createSymbolicLink(link.toPath(),f.file.toPath());source.assets.get(0).uri=Uri.fromFile(link).toString();}
                if(invalid.equals("mime"))source.assets.get(0).mime="application/octet-stream";
                f.store.save(source);f.request.put("sourceProjectUpdatedAt",source.updatedAt);
            }
            assertThrows(invalid,Exception.class,f::execute);assertEquals(before,f.output().toJson().toString());
        }
    }
    @Test public void sameOperationCannotChangeItsMeaningOrSilentlyRepairModifiedCopiedBytes()throws Exception {
        Fixture f=new Fixture();JSONObject result=f.execute();String before=f.output().toJson().toString();f.request.put("appendToTimeline",true);assertThrows(Exception.class,f::execute);assertEquals(before,f.output().toJson().toString());
        f.request.remove("appendToTimeline");File copied=new File(Uri.parse(f.output().asset(result.getString("assetId")).uri).getPath());Files.write(copied.toPath(),new byte[]{7});assertThrows(Exception.class,f::execute);assertArrayEquals(new byte[]{7},Files.readAllBytes(copied.toPath()));
    }
    @Test public void preInterruptedWorkPublishesNothingAndIdenticalRetrySucceeds()throws Exception {
        Fixture f=new Fixture();Thread.currentThread().interrupt();try{assertThrows(Exception.class,f::execute);}finally{Thread.interrupted();}assertEquals(1,f.output().assets.size());assertEquals(0,f.output().clips.size());assertTrue(f.execute().getBoolean("ok"));
    }
    @Test public void interruptedCopyRetainsVerifiedPartialAndIdenticalRetryPublishesOnce()throws Exception {
        Fixture f=new Fixture();f.request.put("appendToTimeline",true);AtomicBoolean copied=new AtomicBoolean();
        try{assertThrows(InterruptedException.class,()->ProjectAssetImport.execute(f.context,f.request,(n,total)->{if(n>0){copied.set(true);Thread.currentThread().interrupt();}}));}finally{Thread.interrupted();}
        assertTrue(copied.get());assertEquals(1,f.output().assets.size());assertEquals(0,f.output().clips.size());
        try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(new File(f.context.getFilesDir(),"project_asset_imports/"+f.destination.id).toPath())){
            assertTrue("The interrupted copy must preserve a resumable private partial",paths.anyMatch(p->p.toString().endsWith(".partial")&&p.toFile().length()>0));
        }
        assertTrue(f.execute().getBoolean("ok"));assertEquals(2,f.output().assets.size());assertEquals(1,f.output().clips.size());
        try(java.util.stream.Stream<java.nio.file.Path> paths=Files.walk(new File(f.context.getFilesDir(),"project_asset_imports/"+f.destination.id).toPath())){
            assertFalse("The published copy must consume its staged partial",paths.anyMatch(p->p.toString().endsWith(".partial")));
        }
    }
    @Test public void destinationEditDuringCopyRejectsStalePublicationAndPreservesTheHumanEdit()throws Exception {
        Fixture f=new Fixture();AtomicBoolean changed=new AtomicBoolean();
        assertThrows(Exception.class,()->ProjectAssetImport.execute(f.context,f.request,(n,total)->{if(n>0&&changed.compareAndSet(false,true)){ProjectStore.Project current=f.output();current.name="Human renamed project";f.store.save(current);}}));
        assertEquals("Human renamed project",f.output().name);assertEquals(1,f.output().assets.size());assertEquals(0,f.output().clips.size());
        assertThrows(Exception.class,f::execute);f.request.put("expectedUpdatedAt",f.output().updatedAt).put("operationId","new_copy");assertTrue(f.execute().getBoolean("ok"));assertEquals("Human renamed project",f.output().name);
    }
    @Test public void sourceBytesChangedAfterTheirChunkWasReadRejectBeforePublication()throws Exception {
        Fixture f=new Fixture();AtomicBoolean changed=new AtomicBoolean();
        assertThrows(Exception.class,()->ProjectAssetImport.execute(f.context,f.request,(n,total)->{if(n>0&&changed.compareAndSet(false,true)){try(java.io.RandomAccessFile file=new java.io.RandomAccessFile(f.file,"rw")){file.write(99);}}}));
        assertTrue(changed.get());assertEquals(1,f.output().assets.size());assertEquals(0,f.output().clips.size());
    }
    @Test public void copiedAudioRemainsAnAssetWithoutCreatingOrDuplicatingAnAudioLane()throws Exception {
        Fixture f=new Fixture();f.source.assets.get(0).mime="audio/wav";f.source.assets.get(0).name="Owned mix.wav";f.store.save(f.source);f.request.put("sourceProjectUpdatedAt",f.source.updatedAt).put("appendToTimeline",true);
        JSONObject result=f.execute();assertFalse(result.getBoolean("appendedToTimeline"));assertEquals(0,f.output().clips.size());assertEquals(1,f.output().studioLayers.size());assertEquals("mix",f.output().studioLayers.get(0).assetId);
    }
    @Test public void copiedRenderAssetWorksWithExistingTimelinePlansAndPreservesTheFullMasterOrigin()throws Exception {
        Fixture f=new Fixture();JSONObject plan=new JSONObject().put("projectId",f.destination.id).put("expectedUpdatedAt",f.destination.updatedAt).put("clips",new org.json.JSONArray().put(new JSONObject().put("clipId","film").put("assetId","picture").put("inMs",0).put("outMs",124000).put("volume",0)));
        assertThrows(Exception.class,()->StudioEditPlan.apply(f.store,plan));JSONObject copied=f.execute();plan.put("expectedUpdatedAt",f.output().updatedAt);plan.getJSONArray("clips").getJSONObject(0).put("assetId",copied.getString("assetId"));
        assertEquals(124000,StudioEditPlan.apply(f.store,plan).getLong("durationMs"));ProjectStore.Project output=f.output();assertEquals(1,output.clips.size());assertEquals(0,output.clips.get(0).volume,0);assertEquals(0,output.studioLayers.get(0).startMs);assertEquals(0,output.studioLayers.get(0).sourceInMs);assertEquals(124000,output.studioLayers.get(0).durationMs);
    }
    @Test public void waitingForAnotherCopyIsInterruptibleWithoutWaitingForItsWorkerToFinish()throws Exception {
        Fixture first=new Fixture(),second=new Fixture();java.util.concurrent.CountDownLatch entered=new java.util.concurrent.CountDownLatch(1),release=new java.util.concurrent.CountDownLatch(1),waiting=new java.util.concurrent.CountDownLatch(1);java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        Thread holder=new Thread(()->{try{ProjectAssetImport.execute(first.context,first.request,(n,total)->{if(n==0){entered.countDown();release.await();}});}catch(Exception ignored){}});
        Thread waiter=new Thread(()->{waiting.countDown();try{second.execute();}catch(Throwable error){failure.set(error);}});
        holder.start();try{assertTrue(entered.await(10,java.util.concurrent.TimeUnit.SECONDS));waiter.start();assertTrue(waiting.await(10,java.util.concurrent.TimeUnit.SECONDS));waiter.interrupt();waiter.join(1000);assertFalse("Cancellation must not wait for the unrelated copy lock",waiter.isAlive());assertTrue(String.valueOf(failure.get()),failure.get() instanceof InterruptedException);assertEquals(1,second.output().assets.size());}finally{release.countDown();holder.join(10000);waiter.interrupt();waiter.join(10000);}
    }

    @Test public void hugeVideoAndAudioLimitsAreLongSafeWithoutAllocatingTenGigabytes()throws Exception{
        long gb=1024L*1024*1024;
        assertEquals(10*gb,ProjectAssetImport.MAX_BYTES);
        assertTrue(ProjectAssetImport.supportedSize("video/mp4",5*gb));
        assertTrue(ProjectAssetImport.supportedSize("audio/wav",10*gb));
        assertFalse(ProjectAssetImport.supportedSize("video/mp4",10*gb+1));
        assertTrue(ProjectAssetImport.supportedSize("image/png",256L*1024*1024));
        assertFalse(ProjectAssetImport.supportedSize("image/png",257L*1024*1024));
        assertFalse(ProjectAssetImport.supportedSize("application/octet-stream",10));
        JSONObject caps=ProjectAssetImport.capabilities();
        assertEquals(10*gb,caps.getLong("maximumBytes"));
        assertTrue(caps.getBoolean("partialResume"));
        assertEquals(ProjectAssetImport.STORAGE_RESERVE_BYTES,caps.getLong("storageReserveBytes"));
        StorageBudget.Check free=StorageBudget.checkTransfer(6*gb,10*gb,5*gb,ProjectAssetImport.STORAGE_RESERVE_BYTES);
        assertTrue(free.allowed);
        StorageBudget.Check blocked=StorageBudget.checkTransfer(1*gb,10*gb,5*gb,ProjectAssetImport.STORAGE_RESERVE_BYTES);
        assertFalse(blocked.allowed);
        assertEquals(5*gb+ProjectAssetImport.STORAGE_RESERVE_BYTES,blocked.requiredWithReserveBytes);
    }

    @Test public void tamperedResumablePrefixFailsWithoutPublishingOrOverwriting()throws Exception {
        Fixture f=new Fixture();AtomicBoolean copied=new AtomicBoolean();
        try{assertThrows(InterruptedException.class,()->ProjectAssetImport.execute(f.context,f.request,(n,total)->{
            if(n>0){copied.set(true);Thread.currentThread().interrupt();}
        }));}finally{Thread.interrupted();}
        assertTrue(copied.get());
        File root=new File(f.context.getFilesDir(),"project_asset_imports/"+f.destination.id);
        java.nio.file.Path[] parts;
        try(java.util.stream.Stream<java.nio.file.Path> files=Files.walk(root)){
            parts=files.filter(p->p.toString().endsWith(".partial")).toArray(java.nio.file.Path[]::new);
        }
        assertEquals(1,parts.length);
        try(java.io.RandomAccessFile partial=new java.io.RandomAccessFile(parts[0].toFile(),"rw")){
            partial.seek(5);partial.write(0);
        }
        Exception rejected=assertThrows(Exception.class,f::execute);
        assertTrue(String.valueOf(rejected.getMessage()),rejected.getMessage().contains("PARTIAL_CHANGED"));
        assertEquals(1,f.output().assets.size());
        assertEquals(0,f.output().clips.size());
    }

}
