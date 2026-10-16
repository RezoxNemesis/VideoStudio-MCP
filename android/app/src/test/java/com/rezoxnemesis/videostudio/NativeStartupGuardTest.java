package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class NativeStartupGuardTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private static final String PREF="videostudio_startup_guard_v457";

    private NativeStartupGuard newGuard(){
        context.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit().clear().commit();
        return new NativeStartupGuard(context);
    }

    @Test public void repeatedProcessDeathsStopInfiniteMediaRecoveryWithoutClearingProjects()throws Exception{
        NativeStartupGuard guard=newGuard();
        ProjectStore db=new ProjectStore(context);
        ProjectStore.Project project=db.create("User-owned unmodified photo "+UUID.randomUUID());
        String projectId=project.id,original=db.get(projectId).toJson().toString();
        long t=1_000_000L;
        assertFalse(guard.start(t,"4.5.7","4.5.7").held);
        assertEquals(1,guard.start(t+5000,"4.5.7","4.5.7").rapidUncleanStarts);
        NativeStartupGuard.Start repeated=guard.start(t+9000,"4.5.7","4.5.7");
        assertTrue(repeated.held);
        assertEquals(2,repeated.rapidUncleanStarts);
        assertFalse(guard.mayAutoResume("native_photo_render_1","animate_images",t+11_000));
        assertTrue(guard.status().getBoolean("projectsPreserved"));
        assertEquals(original,db.get(projectId).toJson().toString());
        assertFalse(guard.status().getBoolean("sourceAssetsCleared"));
        guard.cleanStop();
        assertTrue("Orderly shutdown does not unquarantine unsafe render",
                new NativeStartupGuard(context).held());
    }

    @Test public void upgradeHoldsOnlyInterruptedHeavyWork()throws Exception{
        NativeStartupGuard guard=newGuard();
        NativeStartupGuard.Start upgraded=guard.start(2_000_000,"4.5.7","4.5.6");
        assertTrue(upgraded.upgraded);
        guard.holdInterruptedUpgrade(new JSONArray().put(new JSONObject()
                .put("id","light_metadata").put("action","generate_voice")),false);
        assertFalse(guard.held());
        guard.holdInterruptedUpgrade(new JSONArray().put(new JSONObject()
                .put("id","interrupted_portrait").put("action","animate_images")),true);
        assertTrue(guard.held());
        assertTrue(guard.reason().contains("Interrupted heavy"));
        guard.userRetry();
        assertFalse(guard.held());
    }

    @Test public void exactSavedPlanCannotBeAutoRelaunchedRepeatedlyDuringCrashWindow()throws Exception{
        NativeStartupGuard guard=newGuard();guard.start(1_000_000,"4.5.7","4.5.7");
        assertTrue(guard.mayAutoResume("plan_1","animate_images",1_000_010));
        assertFalse("Immediate same-plan retry would repeat the decoder crash",
                guard.mayAutoResume("plan_1","animate_images",1_000_015));
        assertTrue(guard.held());
        guard.userRetry();
        assertTrue(guard.mayAutoResume("plan_1","animate_images",1_000_020));
        assertTrue("Lightweight metadata recovery is safe without render risk",
                guard.mayAutoResume("light","get_state",1_000_021));
    }

    @Test public void cleanRestartsAndUnrelatedVersionsDoNotInventARecoveryCrash()throws Exception{
        NativeStartupGuard guard=newGuard();
        NativeStartupGuard.Start first=guard.start(1_000_000,"4.5.7","");
        assertEquals(0,first.rapidUncleanStarts);
        guard.cleanStop();
        NativeStartupGuard.Start second=guard.start(1_000_005,"4.5.7","4.5.7");
        assertEquals(0,second.rapidUncleanStarts);
        assertFalse(second.held);
        guard.cleanStop();
        assertEquals(0,guard.status().getInt("rapidUncleanStarts"));
    }

    @Test public void onlyKnownHeavyActionsAreRiskyAndNoGalleryEnumerationIsIntroduced(){
        assertTrue(NativeStartupGuard.heavy("animate_images"));
        assertTrue(NativeStartupGuard.heavy("render_rigged_2d"));
        assertFalse(NativeStartupGuard.heavy("sketch_get"));
        assertFalse(NativeStartupGuard.heavy("get_state"));
        assertFalse(NativeStartupGuard.heavy("sketch_review"));
        assertFalse(NativeStartupGuard.heavy("sketch_edit"));
    }

    @Test public void oldAndroidExitDiagnosticsRemainOptionalWithoutReadingOtherApps()throws Exception{
        JSONObject diagnostics=NativeProcessExitDiagnostics.recent(context);
        assertTrue(diagnostics.has("available"));
        assertFalse(diagnostics.has("trace"));
        assertFalse(diagnostics.has("gallery"));
    }
}
