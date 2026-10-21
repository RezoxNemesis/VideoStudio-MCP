package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import java.io.File;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Opt-in import of a real authored artifact. This does not execute the phone GPU. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class Cinematic3DAuthoredBundleProofTest {
    @Test public void realAuthoredSceneImportsAndSavesThroughNativeStores() throws Exception {
        String path=System.getenv("VIDEOSTUDIO_AUTHORED_BUNDLE");
        Assume.assumeTrue("A separately authored bundle is required",path!=null&&!path.isEmpty());
        File file=new File(path);
        assertTrue("The explicitly supplied bundle must exist",file.isFile());
        Context context=RuntimeEnvironment.getApplication();
        ProjectStore projects=new ProjectStore(context);
        ProjectStore.Project project=projects.create("Authored character scene import proof");
        ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id="authored_scene_bundle";asset.name=file.getName();asset.uri=Uri.fromFile(file).toString();
        asset.mime="application/zip";asset.sizeBytes=file.length();project.assets.add(asset);projects.save(project);
        JSONObject imported=NativeCinematicBundleStore.install(context,projects,new JSONObject()
                .put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("bundleAssetId",asset.id));
        assertTrue(imported.getBoolean("artifactIntegrityVerified"));
        assertFalse(imported.getBoolean("nativeExecutionVerified"));
        JSONObject saved=Cinematic3DProgramStore.save(context,projects,new JSONObject()
                .put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt)
                .put("bundleId",imported.getString("bundleId")).put("timeline",imported.getJSONObject("timeline")));
        JSONObject planned=Cinematic3DProgramStore.plan(context,projects,new JSONObject()
                .put("projectId",project.id).put("programId",saved.getString("programId")));
        assertEquals(108,planned.getInt("frameCount"));
        assertEquals("authored_3d",planned.getJSONObject("timeline").getString("motionProvider"));
        JSONObject scene=planned.getJSONArray("scenes").getJSONObject(0);
        assertEquals("IDLE",scene.getString("action"));
        assertEquals("authored",scene.getString("cameraReplay"));
        assertEquals("PortraitCamera",scene.getString("cameraName"));
        assertTrue(planned.getBoolean("requiresNativeSceneValidation"));
        assertFalse(planned.getBoolean("nativeExecutionVerified"));
        assertFalse(planned.getBoolean("cinematicApproved"));
    }
}
