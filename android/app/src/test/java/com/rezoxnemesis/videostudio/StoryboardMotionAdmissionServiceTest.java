package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.UUID;

import static org.junit.Assert.*;

/** Admission evidence only: no service startup, native render, or network work. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 33, manifest = Config.NONE)
public final class StoryboardMotionAdmissionServiceTest {
    @Test public void defaultActionWithoutAuthenticAnnotationsRejectsBeforeProjectOrHeavyJob() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ProjectStore store = new ProjectStore(context);
        ProjectStore.Project source = store.create("Action admission source");
        source.sourcePrompt = "Two illustrated sword fighters";
        for (int index = 0; index < 2; index++) {
            File image = new File(context.getFilesDir(), "admission_anchor_" + UUID.randomUUID() + ".png");
            Bitmap pixels = Bitmap.createBitmap(32, 48, Bitmap.Config.ARGB_8888);
            pixels.eraseColor(index == 0 ? 0xff29212f : 0xff392b41);
            try (FileOutputStream output = new FileOutputStream(image)) {
                assertTrue(pixels.compress(Bitmap.CompressFormat.PNG, 100, output));
            } finally { pixels.recycle(); }
            ProjectStore.Asset asset = new ProjectStore.Asset();
            asset.id = UUID.randomUUID().toString();
            asset.name = index == 0 ? "00_Standoff.png" : "01_Black_Step.png";
            asset.uri = Uri.fromFile(image).toString();
            asset.mime = "image/png";
            asset.sizeBytes = image.length();
            asset.seekable = true;
            asset.persistedReadAccess = true;
            source.assets.add(asset);
            ProjectStore.Clip clip = new ProjectStore.Clip();
            clip.id = UUID.randomUUID().toString();
            clip.assetId = asset.id;
            clip.outMs = 1000;
            source.clips.add(clip);
        }
        store.save(source);
        String originalGraph = store.get(source.id).toJson().toString();
        int originalProjectCount = store.summaries().getJSONArray("projects").length();
        RecoveryPlanStore recovery = new RecoveryPlanStore(context);
        int originalRecoveryCount = recovery.recent(24).length();
        JobManager jobs = new JobManager(context, "storyboard_admission_test_jobs");
        // A closed executor is an admission tripwire: if the service reaches
        // submit, no renderer can start, and the test still observes any
        // wrongly allocated output project, recovery plan and queued job.
        jobs.shutdown();
        int originalJobCount = jobs.state().getJSONArray("jobs").length();
        ControlService service = Robolectric.buildService(ControlService.class).get();
        inject(service, "store", store);
        inject(service, "jobs", jobs);
        inject(service, "recoveryPlans", recovery);
        JSONObject request = new JSONObject().put("sourceProjectId", source.id)
                .put("genre", "action").put("backend", "AUTO_LAYERS")
                .put("fps", 24).put("framesPerPair", 6)
                .put("width", 256).put("height", 432)
                .put("render", false).put("foley", false);
        Method admission = ControlService.class.getDeclaredMethod("queueStoryboardSeries", JSONObject.class);
        admission.setAccessible(true);
        Throwable rejection = null;
        try {
            admission.invoke(service, request);
        } catch (InvocationTargetException invocation) {
            rejection = invocation.getCause();
        } finally { jobs.shutdown(); }

        assertEquals("Action admission must reject before allocating any output project",
                originalProjectCount, store.summaries().getJSONArray("projects").length());
        assertEquals("Action admission must reject before registering heavy work",
                originalJobCount, jobs.state().getJSONArray("jobs").length());
        assertEquals("Action admission must reject before creating a durable render plan",
                originalRecoveryCount, recovery.recent(24).length());
        assertEquals("The accepted source graph must remain intact", originalGraph,
                store.get(source.id).toJson().toString());
        assertNotNull("Missing actor/prop annotations must produce a synchronous provider rejection", rejection);
        assertTrue("Rejection must identify the missing motion provider: " + rejection,
                rejection.getMessage() != null && rejection.getMessage().contains("INSUFFICIENT_MOTION_PROVIDER"));
    }

    private static void inject(ControlService service, String name, Object value) throws Exception {
        Field field = ControlService.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(service, value);
    }
}
