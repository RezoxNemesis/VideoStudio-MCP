package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

/** Synthetic source-bound persistence checks; these never establish cinematic acceptance. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeRigBatchAuthoringTest {
    @Test public void invalidLastEntryPrevalidatesWithoutAnyRigOrBatchWrite() throws Exception {
        Fixture f=new Fixture();JSONObject request=f.batch(f.profile(0,true),f.profile(1,false));
        request.getJSONArray("profiles").getJSONObject(1).put("joints",new JSONArray());
        reject(f,request,"sixteen");
        assertEquals(0,f.rigs.list(f.project.id).size());
        assertFalse(f.annotationFile("rig_batches",request.getString("batchId")).exists());
    }
    @Test public void duplicateActorAndMissingByteProofRejectBeforePublication() throws Exception {
        Fixture f=new Fixture();reject(f,f.batch(f.profile(0,true),f.profile(0,true)),"Duplicate");
        JSONObject missing=f.profile(0,true);missing.remove("sourceSha256");
        reject(f,f.batch(missing),"sourceSha256");assertEquals(0,f.rigs.list(f.project.id).size());
    }
    @Test public void batchBoundsRejectZeroAndNineProfiles() throws Exception {
        Fixture f=new Fixture();reject(f,f.batch(),"1..8");JSONObject request=f.batch(f.profile(0,true));
        for(int i=0;i<8;i++)request.getJSONArray("profiles").put(f.profile(1,i%2==0));
        reject(f,request,"1..8");assertEquals(0,f.rigs.list(f.project.id).size());
    }
    @Test public void exactReplayRetainsStableIdsReceiptsAndOriginalBytes() throws Exception {
        Fixture f=new Fixture();byte[] original=Files.readAllBytes(f.files[0].toPath());JSONObject request=f.batch(f.profile(0,true),f.profile(0,false));
        JSONObject first=save(f,request);
        assertEquals("APPLIED",first.getString("state"));assertFalse(first.getBoolean("allOrNothing"));
        assertEquals(2,first.getInt("appliedCount"));assertTrue(first.getBoolean("sourceBytesVerified"));
        // Equivalent accepted float geometry must not depend on JSON key order or lexical number spelling.
        request.getJSONArray("profiles").getJSONObject(0).getJSONArray("joints").getJSONArray(0).put(0,.42);
        JSONObject second=save(f.context,new ProjectStore(f.context),new JSONObject(request.toString()),null);
        assertEquals("REPLAYED",second.getString("state"));assertEquals(2,f.rigs.list(f.project.id).size());
        for(int i=0;i<2;i++){
            JSONObject a=first.getJSONArray("receipts").getJSONObject(i),b=second.getJSONArray("receipts").getJSONObject(i);
            assertEquals(a.getString("rigId"),b.getString("rigId"));assertEquals(a.getString("sha256"),b.getString("sha256"));
            assertTrue(b.getBoolean("replayed"));assertEquals(i,b.getInt("index"));
            assertEquals(a.getString("sha256"),f.rigs.captureBinding(f.project.id,a.getString("rigId")).getString("sha256"));
        }
        assertArrayEquals(original,Files.readAllBytes(f.files[0].toPath()));
    }
    @Test public void sameBatchIdWithDifferentAcceptedGeometryIsConflict() throws Exception {
        Fixture f=new Fixture();JSONObject request=f.batch(f.profile(0,true));JSONObject first=save(f,request);
        request.getJSONArray("profiles").getJSONObject(0).getJSONArray("joints").getJSONArray(0).put(0,.44);
        reject(f,request,"BATCH_REPLAY_CONFLICT");
        JSONObject receipt=first.getJSONArray("receipts").getJSONObject(0);
        assertEquals(receipt.getString("sha256"),f.rigs.captureBinding(f.project.id,receipt.getString("rigId")).getString("sha256"));
    }
    @Test public void sourceByteMutationInvalidatesEvenIdenticalReplay() throws Exception {
        Fixture f=new Fixture();JSONObject request=f.batch(f.profile(0,true));JSONObject first=save(f,request);
        f.write(f.files[0],Color.MAGENTA);reject(f,request,"SOURCE_IMAGE_CHANGED");
        JSONObject receipt=first.getJSONArray("receipts").getJSONObject(0);
        assertEquals(receipt.getString("sha256"),f.rigs.captureBinding(f.project.id,receipt.getString("rigId")).getString("sha256"));
    }
    @Test public void invalidLaterCasCannotWriteEarlierValidDraft() throws Exception {
        Fixture f=new Fixture();JSONObject initial=f.batch(f.profile(1,false));JSONObject saved=save(f,initial);
        JSONObject old=saved.getJSONArray("receipts").getJSONObject(0),changed=f.profile(1,false).put("rigId",old.getString("rigId"));
        changed.getJSONArray("joints").getJSONArray(0).put(0,.44);changed.put("expectedRigSha256",repeat('0'));
        reject(f,f.batch(f.profile(0,true),changed),"SOURCE_RIG_CHANGED");assertEquals(1,f.rigs.list(f.project.id).size());
        assertEquals(old.getString("sha256"),f.rigs.captureBinding(f.project.id,old.getString("rigId")).getString("sha256"));
    }
    @Test public void interruptionReturnsPartialAndFreshInstanceResumesWithoutDuplicateProfiles() throws Exception {
        Fixture f=new Fixture();JSONObject request=f.batch(f.profile(0,true),f.profile(1,false));
        JSONObject partial=save(f,request,(index,receipt)->{if(index==0)throw new InterruptedException("Synthetic interruption");});
        assertEquals("PARTIAL_APPLIED",partial.getString("state"));assertEquals(1,partial.getInt("appliedCount"));
        assertEquals(1,partial.getJSONArray("pendingIndices").getInt(0));assertEquals(1,f.rigs.list(f.project.id).size());
        JSONObject complete=save(f.context,new ProjectStore(f.context),request,null);
        assertEquals("APPLIED",complete.getString("state"));assertEquals(2,complete.getInt("appliedCount"));
        assertTrue(complete.getJSONArray("receipts").getJSONObject(0).getBoolean("replayed"));
        assertEquals(2,f.rigs.list(f.project.id).size());assertEquals("REPLAYED",save(f,request).getString("state"));
    }
    @Test public void interruptedReplayRetainsPreviouslyPublishedReceiptsAndHasNoFalsePendingProfiles() throws Exception {
        Fixture f=new Fixture();JSONObject request=f.batch(f.profile(0,true),f.profile(1,false)),first=save(f,request);
        // The real progress checkpoint stops after rechecking receipt zero. Receipt one was already
        // durably published by the completed first call and must never become "pending publication".
        JSONObject interrupted=save(f,request,(index,receipt)->{if(index==0)throw new InterruptedException("Stop replay progress");});
        assertEquals("PARTIAL_APPLIED",interrupted.getString("state"));assertEquals(2,interrupted.getInt("appliedCount"));
        assertEquals(2,interrupted.getJSONArray("receipts").length());assertEquals(0,interrupted.getJSONArray("pendingIndices").length());
        assertEquals(0,interrupted.getInt("newlyAppliedCount"));assertEquals(2,interrupted.getInt("replayedCount"));
        assertFalse(interrupted.getBoolean("sourceBytesVerified"));
        for(int i=0;i<2;i++){JSONObject old=first.getJSONArray("receipts").getJSONObject(i),retained=interrupted.getJSONArray("receipts").getJSONObject(i);
            assertEquals(old.getString("rigId"),retained.getString("rigId"));assertEquals(old.getString("sha256"),retained.getString("sha256"));}
        assertEquals(2,f.rigs.list(f.project.id).size());
    }
    @Test public void progressObserverRejectsSynchronousNestedBatchBeforeEffects() throws Exception {
        Fixture f=new Fixture();JSONObject nested=f.batch(f.profile(1,false));
        JSONObject saved=save(f,f.batch(f.profile(0,true)),(index,receipt)->{
            try{save(f,nested);fail("A progress checkpoint must not recursively start another batch");}
            catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("Nested batch"));}
        });
        assertEquals("APPLIED",saved.getString("state"));assertEquals(1,f.rigs.list(f.project.id).size());
        assertFalse(f.annotationFile("rig_batches",nested.getString("batchId")).exists());
    }
    @Test public void concurrentEditAfterPartialIsPreservedInsteadOfRolledBackOrOverwritten() throws Exception {
        Fixture f=new Fixture();JSONObject request=f.batch(f.profile(0,true),f.profile(1,false));
        JSONObject partial=save(f,request,(index,receipt)->{throw new IllegalStateException("Synthetic later failure");});
        JSONObject old=partial.getJSONArray("receipts").getJSONObject(0);Rig2DProfile accepted=f.rigs.load(f.project.id,old.getString("rigId"));
        JSONObject record=accepted.toJson();record.getJSONArray("joints").getJSONArray(0).put(0,.45);
        JSONObject owner=f.rigs.saveWithBinding(Rig2DProfile.fromJson(record),f.project.updatedAt,old.getString("sha256"));
        reject(f,request,"SOURCE_RIG_CHANGED");
        assertEquals(owner.getString("sha256"),f.rigs.captureBinding(f.project.id,old.getString("rigId")).getString("sha256"));assertEquals(1,f.rigs.list(f.project.id).size());
    }
    @Test public void combinedCapacityCheckedBeforeFirstNewSave() throws Exception {
        Fixture f=new Fixture();for(int i=0;i<99;i++)f.rigs.save(Rig2DProfile.create(f.project.id,f.assets[0],true,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline()));
        reject(f,f.batch(f.profile(0,false),f.profile(1,true)),"100");assertEquals(99,f.rigs.list(f.project.id).size());
    }
    @Test public void readinessPreservesRepeatedOccurrencesAndNeverClaimsByteOrAnatomyVerification() throws Exception {
        Fixture f=new Fixture();JSONObject incomplete=f.profile(0,true);incomplete.remove("swordHilt");incomplete.remove("swordOutline");
        save(f,f.batch(incomplete,f.profile(0,false)));
        JSONObject request=new JSONObject().put("projectId",f.project.id).put("assetIds",new JSONArray().put(f.assets[0]).put(f.assets[1]).put(f.assets[0]));
        JSONObject status=status(f,request);assertFalse(status.getBoolean("sourceBytesVerified"));
        assertEquals("UNAVAILABLE",status.getString("anatomyStatus"));assertEquals("UNAVAILABLE",status.getString("cinematicStatus"));
        assertFalse(status.getBoolean("cinematicApproved"));assertFalse(status.getBoolean("preparationComplete"));
        JSONArray order=status.getJSONArray("occurrences");assertEquals(3,order.length());assertEquals(f.assets[0],order.getJSONObject(2).getString("assetId"));
        JSONObject black=order.getJSONObject(0).getJSONObject("black");assertFalse(black.getBoolean("annotationPresent"));
        assertTrue(black.getJSONArray("missing").toString().contains("swordHilt"));assertTrue(black.getJSONArray("missing").toString().contains("swordOutline"));
        assertTrue(order.getJSONObject(0).getJSONObject("white").getBoolean("annotationPresent"));
        assertEquals(f.assets[0],status.getJSONObject("nextMissing").getString("assetId"));assertEquals("black",status.getJSONObject("nextMissing").getString("actor"));
    }
    @Test public void completeStructuralChecklistStillRequiresOriginalInspectionAndRenderEvidence() throws Exception {
        Fixture f=new Fixture();save(f,f.batch(f.profile(0,true),f.profile(0,false)));
        JSONObject status=status(f,new JSONObject().put("projectId",f.project.id).put("assetIds",new JSONArray().put(f.assets[0])));
        assertTrue(status.getBoolean("preparationComplete"));assertTrue(status.getBoolean("inspectionRequired"));assertFalse(status.getBoolean("sourceBytesVerified"));
        assertEquals("UNAVAILABLE",status.getString("identityStatus"));assertFalse(status.getBoolean("cinematicApproved"));
    }
    @Test public void statusRejectsForeignSourceAndDoesNotOpenOriginalUris() throws Exception {
        Fixture f=new Fixture();JSONObject request=new JSONObject().put("projectId",f.project.id).put("assetIds",new JSONArray().put(UUID.randomUUID().toString()));
        try{status(f,request);fail("Foreign asset must be rejected");}catch(IllegalArgumentException expected){}
        f.project.asset(f.assets[0]).uri="file:///no-original-to-open.png";f.projects.save(f.project);
        JSONObject status=status(f,new JSONObject().put("projectId",f.project.id).put("assetIds",new JSONArray().put(f.assets[0])));
        assertFalse(status.getBoolean("sourceBytesVerified"));assertEquals(0,status.getJSONArray("occurrences").getJSONObject(0).getJSONObject("black").getJSONArray("profiles").length());
    }
    @Test public void duplicateExplicitRigIdsRejectAcrossDifferentActorsBeforeWriting() throws Exception {
        Fixture f=new Fixture();String id=UUID.randomUUID().toString();
        reject(f,f.batch(f.profile(0,true).put("rigId",id),f.profile(1,false).put("rigId",id)),"Duplicate");assertEquals(0,f.rigs.list(f.project.id).size());
    }
    @Test public void unsafeIdentityForeignSourceAndFractionalRevisionRejectBeforeEffects() throws Exception {
        Fixture f=new Fixture();reject(f,f.batch(f.profile(0,true)).put("batchId","../../Gallery"),"identity");
        reject(f,f.batch(f.profile(0,true).put("sourceAssetId",UUID.randomUUID().toString())),"project-owned");
        reject(f,f.batch(f.profile(0,true)).put("sourceProjectUpdatedAt",f.project.updatedAt+.5),"integer");
        assertEquals(0,f.rigs.list(f.project.id).size());
    }
    @Test public void sourceRevisionAndRegisteredOwnershipAreCheckedAgainOnReplay() throws Exception {
        Fixture f=new Fixture();JSONObject request=f.batch(f.profile(0,true));save(f,request);
        f.project.name="Owner revision changed";f.projects.save(f.project);reject(f,request,"SOURCE_PROJECT_CHANGED");
        request.put("sourceProjectUpdatedAt",f.project.updatedAt);reject(f,request,"BATCH_REPLAY_CONFLICT");
        f.project.assets.removeIf(asset->f.assets[0].equals(asset.id));f.projects.save(f.project);
        request.put("sourceProjectUpdatedAt",f.project.updatedAt);reject(f,request,"project-owned");
    }
    @Test public void updatePlusOneCreateFitsAtNinetyNineWithoutChargingUpdateAsNew() throws Exception {
        Fixture f=new Fixture();Rig2DProfile old=null;
        for(int i=0;i<99;i++){Rig2DProfile rig=Rig2DProfile.create(f.project.id,f.assets[0],true,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline());f.rigs.save(rig);if(old==null)old=rig;}
        JSONObject update=f.profile(0,true).put("rigId",old.id).put("expectedRigSha256",Rig2DProjectStore.sha256(old));
        JSONObject saved=save(f,f.batch(update,f.profile(1,false)));assertEquals("APPLIED",saved.getString("state"));assertEquals(100,f.rigs.list(f.project.id).size());
        assertEquals(old.id,saved.getJSONArray("receipts").getJSONObject(0).getString("rigId"));
    }
    @Test public void crashBetweenProfileMoveAndReceiptCheckpointRecoversOnlyExactAcceptedProfile() throws Exception {
        Fixture f=new Fixture();JSONObject request=f.batch(f.profile(0,true),f.profile(1,false));
        save(f,request,(index,receipt)->{throw new InterruptedException("Synthetic interruption");});
        JSONObject journal=f.readJournal(request);journal.put("receipts",new JSONArray()).put("inFlightIndex",0);f.writeJournal(request,journal);
        JSONObject resumed=save(f.context,new ProjectStore(f.context),request,null);assertEquals("APPLIED",resumed.getString("state"));
        assertTrue(resumed.getJSONArray("receipts").getJSONObject(0).getBoolean("replayed"));assertEquals(2,f.rigs.list(f.project.id).size());
    }
    @Test public void uncertainCrashCannotOverwriteAnOldOrConcurrentProfile() throws Exception {
        Fixture f=new Fixture();Rig2DProfile initial=Rig2DProfile.create(f.project.id,f.assets[0],true,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline());f.rigs.save(initial);
        JSONObject update=f.profile(0,true).put("rigId",initial.id).put("expectedRigSha256",Rig2DProjectStore.sha256(initial));
        JSONObject request=f.batch(update,f.profile(1,false)),partial=save(f,request,(index,receipt)->{throw new InterruptedException("Synthetic interruption");});
        JSONObject accepted=partial.getJSONArray("receipts").getJSONObject(0);
        f.rigs.saveWithBinding(initial,f.project.updatedAt,accepted.getString("sha256"));
        JSONObject journal=f.readJournal(request);journal.put("receipts",new JSONArray()).put("inFlightIndex",0);f.writeJournal(request,journal);
        reject(f,request,"BATCH_APPLICATION_UNCERTAIN");assertEquals(Rig2DProjectStore.sha256(initial),f.rigs.captureBinding(f.project.id,initial.id).getString("sha256"));assertEquals(1,f.rigs.list(f.project.id).size());
    }
    @Test public void corruptOrMissingJournalNeverAuthorizesAnOverwrite() throws Exception {
        Fixture f=new Fixture();JSONObject request=f.batch(f.profile(0,true));JSONObject saved=save(f,request),receipt=saved.getJSONArray("receipts").getJSONObject(0);
        JSONObject corrupt=f.readJournal(request);corrupt.put("receipts",new JSONArray());
        Files.write(f.annotationFile("rig_batches",request.getString("batchId")).toPath(),corrupt.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        reject(f,request,"BATCH_RECORD_INVALID");Files.delete(f.annotationFile("rig_batches",request.getString("batchId")).toPath());
        reject(f,request,"SOURCE_RIG_CHANGED");assertEquals(receipt.getString("sha256"),f.rigs.captureBinding(f.project.id,receipt.getString("rigId")).getString("sha256"));
    }
    @Test public void readinessRequiresExplicitSelectionWhenMultipleProfilesMatchAndStaysBounded() throws Exception {
        Fixture f=new Fixture();for(int i=0;i<9;i++)save(f,f.batch(f.profile(0,true)));
        JSONObject value=status(f,new JSONObject().put("projectId",f.project.id).put("assetIds",new JSONArray().put(f.assets[0])));
        JSONObject black=value.getJSONArray("occurrences").getJSONObject(0).getJSONObject("black");
        assertFalse(black.getBoolean("annotationPresent"));assertTrue(black.getBoolean("selectionRequired"));
        assertEquals(9,black.getInt("totalProfileCount"));assertEquals(8,black.getJSONArray("profiles").length());assertTrue(black.getBoolean("profilesTruncated"));
        assertTrue(black.getJSONArray("missing").toString().contains("rigSelection"));assertFalse(value.getBoolean("preparationComplete"));
    }
    @Test public void savedProgramStatusPreservesOrderAndExactProfileBindings() throws Exception {
        Fixture f=new Fixture();JSONObject saved=save(f,f.batch(f.profile(0,true),f.profile(0,false),f.profile(1,true),f.profile(1,false)));
        JSONObject bindings=new JSONObject();for(int i=0;i<4;i++){JSONObject receipt=saved.getJSONArray("receipts").getJSONObject(i);String assetId=receipt.getString("sourceAssetId"),actor=receipt.getBoolean("black")?"black":"white";
            if(!bindings.has(assetId))bindings.put(assetId,new JSONObject());bindings.getJSONObject(assetId).put(actor+"RigId",receipt.getString("rigId")).put(actor+"RigSha256",receipt.getString("sha256"));}
        JSONArray order=new JSONArray().put(f.assets[1]).put(f.assets[0]).put(f.assets[1]),contacts=new JSONArray(),beats=new JSONArray();
        for(int i=0;i<2;i++){contacts.put(new JSONObject().put("shotIndex",i).put("fromAssetId",order.getString(i)).put("toAssetId",order.getString(i+1)).put("sourceWidth",64).put("sourceHeight",64).put("contactPlan",new JSONObject().put("black",new JSONArray()).put("white",new JSONArray())));
            beats.put(new JSONObject().put("shotIndex",i).put("fromAssetId",order.getString(i)).put("toAssetId",order.getString(i+1)).put("beat","BLACK_STEP"));}
        JSONObject program=new StoryboardProgramStore(f.context,f.projects).save(f.project.id,f.project.updatedAt,"",new JSONObject().put("sourceAssetIds",order).put("sourceRigBindings",bindings).put("contactPlans",contacts).put("choreography",beats));
        JSONObject request=new JSONObject().put("projectId",f.project.id).put("programId",program.getString("programId")).put("expectedProgramSha256",program.getString("sha256"));
        JSONObject first=status(f,request);assertEquals(order.toString(),first.getJSONArray("assetIds").toString());assertTrue(first.getBoolean("preparationComplete"));assertFalse(first.getBoolean("sourceBytesVerified"));
        JSONObject bound=bindings.getJSONObject(f.assets[1]);Rig2DProfile old=f.rigs.load(f.project.id,bound.getString("blackRigId"));JSONObject record=old.toJson();record.getJSONArray("joints").getJSONArray(0).put(0,.45);
        f.rigs.saveWithBinding(Rig2DProfile.fromJson(record),f.project.updatedAt,bound.getString("blackRigSha256"));JSONObject changed=status(f,request);
        assertTrue(changed.getBoolean("programStale"));assertFalse(changed.getBoolean("preparationComplete"));
        assertTrue(changed.getJSONArray("occurrences").getJSONObject(0).getJSONObject("black").getJSONArray("missing").toString().contains("sourceRigBinding"));
        request.put("expectedProgramSha256",repeat('0'));try{status(f,request);fail("Stale selected program digest must reject");}catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("SOURCE_PROGRAM_CHANGED"));}
    }
    private interface TestObserver { void published(int index,JSONObject receipt) throws Exception; }
    private static JSONObject save(Fixture f,JSONObject request) throws Exception {return save(f.context,f.projects,request,null);}
    private static JSONObject save(Fixture f,JSONObject request,TestObserver observer) throws Exception {return save(f.context,f.projects,request,observer);}
    private static JSONObject save(Context context,ProjectStore projects,JSONObject request,TestObserver observer) throws Exception {
        Class<?> type=featureClass();
        if(observer==null)return invoke(type.getMethod("save",Context.class,ProjectStore.class,JSONObject.class),context,projects,request);
        Class<?> listener=Class.forName("com.rezoxnemesis.videostudio.NativeRigBatchAuthoring$PublicationObserver");
        Object proxy=java.lang.reflect.Proxy.newProxyInstance(listener.getClassLoader(),new Class<?>[]{listener},(unused,method,args)->{
            if("published".equals(method.getName()))observer.published((Integer)args[0],(JSONObject)args[1]);return null;
        });
        return invoke(type.getMethod("save",Context.class,ProjectStore.class,JSONObject.class,listener),context,projects,request,proxy);
    }
    private static JSONObject status(Fixture f,JSONObject request) throws Exception {return invoke(featureClass().getMethod("status",Context.class,ProjectStore.class,JSONObject.class),f.context,f.projects,request);}
    private static Class<?> featureClass() {
        try{return Class.forName("com.rezoxnemesis.videostudio.NativeRigBatchAuthoring");}
        catch(ClassNotFoundException missing){throw new AssertionError("Native bounded source-rig batch authoring is unavailable",missing);}
    }
    private static JSONObject invoke(java.lang.reflect.Method method,Object... arguments) throws Exception {
        try{return (JSONObject)method.invoke(null,arguments);}
        catch(java.lang.reflect.InvocationTargetException wrapped){Throwable cause=wrapped.getCause();if(cause instanceof Exception)throw (Exception)cause;if(cause instanceof Error)throw (Error)cause;throw new AssertionError(cause);}
    }
    private static void reject(Fixture f,JSONObject request,String message) throws Exception {
        try{save(f,request);fail("Invalid batch was accepted");}
        catch(IllegalArgumentException|IllegalStateException expected){assertTrue(expected.getMessage(),expected.getMessage().contains(message));}
    }
    private static String repeat(char value){char[] text=new char[64];java.util.Arrays.fill(text,value);return new String(text);}
    private static JSONArray pairs(float[][] points){JSONArray array=new JSONArray();for(float[] point:points)array.put(new JSONArray().put((Object)point[0]).put((Object)point[1]));return array;}
    private static final class Fixture {
        final Context context=RuntimeEnvironment.getApplication();final ProjectStore projects=new ProjectStore(context);
        final ProjectStore.Project project=projects.create("Synthetic rig batch originals");final Rig2DProjectStore rigs=new Rig2DProjectStore(context,projects);
        final String[] assets=new String[2],sha=new String[2];final File[] files=new File[2];
        Fixture() throws Exception {
            for(int i=0;i<2;i++){ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=UUID.randomUUID().toString();assets[i]=asset.id;asset.mime="image/png";
                files[i]=new File(context.getFilesDir(),UUID.randomUUID()+".png");write(files[i],i==0?Color.DKGRAY:Color.GRAY);asset.uri=Uri.fromFile(files[i]).toString();project.assets.add(asset);}
            projects.save(project);for(int i=0;i<2;i++)sha[i]=StoryboardSourceManifest.captureSource(context,project.asset(assets[i])).getString("sourceSha256");
        }
        JSONObject profile(int asset,boolean black) throws Exception {
            return new JSONObject().put("sourceAssetId",assets[asset]).put("black",black).put("sourceSha256",sha[asset])
                .put("joints",pairs(Rig2DProfileTest.joints())).put("outline",pairs(Rig2DProfileTest.outline()))
                .put("swordHilt",new JSONArray().put(.64).put(.54)).put("swordTip",new JSONArray().put(.72).put(.52))
                .put("swordOutline",new JSONArray().put(new JSONArray().put(.62).put(.56)).put(new JSONArray().put(.62).put(.51))
                    .put(new JSONArray().put(.75).put(.49)).put(new JSONArray().put(.75).put(.54)));
        }
        JSONObject batch(JSONObject... entries) throws Exception {JSONArray profiles=new JSONArray();for(JSONObject entry:entries)profiles.put(entry);return new JSONObject().put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("batchId",UUID.randomUUID().toString()).put("profiles",profiles);}
        File annotationFile(String child,String id){return new File(context.getFilesDir(),"creative_workspace/projects/"+project.id+"/"+child+"/"+id+".json");}
        JSONObject readJournal(JSONObject request) throws Exception {return new JSONObject(new String(Files.readAllBytes(annotationFile("rig_batches",request.getString("batchId")).toPath()),java.nio.charset.StandardCharsets.UTF_8));}
        void writeJournal(JSONObject request,JSONObject journal) throws Exception {journal.remove("sha256");journal.put("sha256",Rig2DProjectStore.sha256Json(journal));Files.write(annotationFile("rig_batches",request.getString("batchId")).toPath(),journal.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));}
        void write(File file,int color) throws Exception {Bitmap bitmap=Bitmap.createBitmap(64,64,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,out));}finally{bitmap.recycle();}}
    }
}
