package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.UUID;
import static org.junit.Assert.*;

/** Strict native offline-video admission: no paid provider or fake procedural fallback. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeOfflineVideoPlanTest {
    private ProjectStore.Project project(ProjectStore store){
        ProjectStore.Project p=store.create("Owned storyboard "+UUID.randomUUID());
        for(int i=0;i<3;i++){
            ProjectStore.Asset a=new ProjectStore.Asset();
            a.id="owned-"+i;a.name="Frame "+i+".png";
            a.mime="image/png";a.uri="file:///private/storyboard-frame-"+i+".png";
            p.assets.add(a);
        }
        store.save(p);return store.get(p.id);
    }

    private JSONObject input(ProjectStore.Project source)throws Exception {
        return new JSONObject().put("sourceProjectId",source.id)
                .put("sourceUpdatedAt",source.updatedAt)
                .put("operationId","native_video_test_"+UUID.randomUUID().toString().substring(0,8))
                .put("mode","image_to_video")
                .put("prompt","Rain lashes against the samurai's robes as the camera circles him.")
                .put("sourceAssetIds",new JSONArray().put("owned-0").put("owned-1"))
                .put("seed",42);
    }

    @Test public void projectBoundReferencesPlanExactNewNeuralFramesWithoutFallback()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=project(store);
            String snapshot=SketchDocument.canonical(source.toJson());
            JSONObject plan=NativeOfflineVideoPlan.validate(source,input(source));
            assertEquals(2,plan.getInt("shotCount"));
            assertEquals(18,plan.getInt("totalFrames"));
            assertEquals(9,plan.getInt("fps"));
            assertEquals(128,plan.getInt("width"));
            assertTrue(plan.getBoolean("neuralGenerationRequired"));
            assertFalse(plan.getBoolean("proceduralFallback"));
            assertFalse(plan.getBoolean("paidApiAllowed"));
            assertFalse(plan.getBoolean("networkInferenceAllowed"));
            assertTrue(plan.getString("planSha256").matches("[0-9a-f]{64}"));
            assertEquals(snapshot,SketchDocument.canonical(store.get(source.id).toJson()));
        }
    }
    @Test public void acceptsGenuineTextToVideoWithNoReferenceAssets()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=project(store);
            JSONObject r=input(source).put("mode","text_to_video").put("sourceAssetIds",new JSONArray());
            assertEquals(9,NativeOfflineVideoPlan.validate(source,r).getInt("totalFrames"));
        }
    }
    @Test public void remoteProvidersTrialsAndMediaBrowserFieldsAreRejected()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=project(store);
            for(String unsafe:new String[]{"apiKey","providerUrl","credits","freeTrial","galleryUri","remoteEndpoint","fallback"}){
                JSONObject req=input(source).put(unsafe,unsafe.equals("apiKey")?"sk-test":"https://unsafe.invalid");
                assertThrows("Reject "+unsafe,IllegalArgumentException.class,
                        ()->NativeOfflineVideoPlan.validate(source,req));
            }
        }
    }
    @Test public void changedSourceRevisionMissingAssetAndDuplicateStillsAreRejected()throws Exception{
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project source=project(store);
            JSONObject valid=input(source);
            assertThrows(IllegalStateException.class,()->NativeOfflineVideoPlan.validate(source,
                    new JSONObject(valid.toString()).put("sourceUpdatedAt",source.updatedAt+1)));
            assertThrows(IllegalArgumentException.class,()->NativeOfflineVideoPlan.validate(source,
                    new JSONObject(valid.toString()).put("sourceAssetIds",new JSONArray().put("missing"))));
            assertThrows(IllegalArgumentException.class,()->NativeOfflineVideoPlan.validate(source,
                    new JSONObject(valid.toString()).put("sourceAssetIds",new JSONArray().put("owned-0").put("owned-0"))));
            assertThrows(IllegalArgumentException.class,()->NativeOfflineVideoPlan.validate(source,
                    new JSONObject(valid.toString()).put("seed",-1)));
        }
    }
    @Test public void missingRealModelHasNoFallbackAndNeverCreatesSyntheticVideoProject()throws Exception{
        Context app=RuntimeEnvironment.getApplication();
        try(ProjectStore store=new ProjectStore(app)){
            ProjectStore.Project source=project(store);
            String before=SketchDocument.canonical(source.toJson());
            NativeOfflineVideoGenerator generator=new NativeOfflineVideoGenerator(app);
            JSONObject status=generator.capabilities();
            assertFalse(status.getBoolean("paidApiRequests"));
            assertFalse(status.getBoolean("cloudInference"));
            assertFalse(status.getBoolean("neuralVideoWeightsBundled"));
            assertFalse(status.getBoolean("galleryEnumeration"));
            if(!status.getBoolean("modelPackInstalled")){
                assertThrows(IllegalStateException.class,()->generator.plan(store,input(source)));
                assertEquals(before,SketchDocument.canonical(store.get(source.id).toJson()));
            }
        }
    }
    @Test public void sdkAdmissionExposesExactlyTheThreePrivateModelOnlyActions(){
        assertTrue(NativeAgentMailboxActions.supports("native_offline_video_capabilities"));
        assertTrue(NativeAgentMailboxActions.supports("native_offline_video_plan"));
        assertTrue(NativeAgentMailboxActions.supports("native_offline_video_generate"));
        assertTrue(NativeCommandExecutionPolicy.requiresBackgroundThread("native_offline_video_plan"));
        assertTrue(NativeStartupGuard.heavy("native_offline_video_generate"));
        assertFalse(NativeStartupGuard.heavy("native_offline_video_capabilities"));
    }
}
