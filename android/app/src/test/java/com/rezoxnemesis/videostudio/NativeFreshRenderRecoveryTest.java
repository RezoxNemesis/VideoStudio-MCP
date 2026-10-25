package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** A local owner grant unlocks only fresh work, not a crash-looping recovery plan. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeFreshRenderRecoveryTest {
    private NativeStartupGuard fresh(){
        Context c=RuntimeEnvironment.getApplication();
        c.getSharedPreferences("videostudio_startup_guard_v457",Context.MODE_PRIVATE)
                .edit().clear().commit();
        return new NativeStartupGuard(c);
    }

    @Test public void oldRecoveryRemainsHeldWhileOwnerAllowsOnlyNewRenders(){
        NativeStartupGuard guard=fresh();
        long now=1_000_000L;
        guard.start(now,"4.10.44","4.10.44");
        guard.hold("Previous interrupted Media3 export");
        assertFalse(guard.mayStartFreshHeavy("export_project",now+100));
        assertTrue(guard.mayStartFreshHeavy("sketch_get",now+100));
        assertFalse(guard.mayAutoResume("old-crashed-render","export_project",now+150));
        guard.allowFreshHeavyAfterOwnerCheck(now+200);
        assertTrue(guard.held());
        assertTrue(guard.mayStartFreshHeavy("export_project",now+201));
        assertTrue(guard.mayStartFreshHeavy("cinematic_3d_render",now+201));
        assertFalse("Model installation cannot use limited new-render consent",
                guard.mayStartFreshHeavy("install_model_pack",now+201));
        assertFalse("Arbitrary creative graphs require separate recovery approval",
                guard.mayStartFreshHeavy("run_creative_graph",now+201));
        assertFalse("Old unsafe recovery is never automatically resumed",
                guard.mayAutoResume("old-crashed-render","export_project",now+201));
    }

    @Test public void grantExpiresAndProcessRestartRevokesIt(){
        NativeStartupGuard guard=fresh();
        long now=2_000_000L;
        guard.start(now,"4.10.44","4.10.44");
        guard.hold("Crash-loop protection");
        guard.allowFreshHeavyAfterOwnerCheck(now+500);
        assertTrue(guard.mayStartFreshHeavy("sketch_render",
                now+500+NativeStartupGuard.FRESH_RENDER_WINDOW_MS));
        assertFalse(guard.mayStartFreshHeavy("sketch_render",
                now+501+NativeStartupGuard.FRESH_RENDER_WINDOW_MS));
        guard.allowFreshHeavyAfterOwnerCheck(now+1000);
        guard.start(now+2000,"4.10.44","4.10.44");
        assertTrue(guard.held());
        assertFalse("Native service restart revokes stale owner consent",
                guard.mayStartFreshHeavy("export_project",now+2100));
    }

    @Test public void freshRenderingRequiresExplicitForegroundOwnerOptIn(){
        NativeStartupGuard guard=fresh();
        assertThrows(IllegalStateException.class,()->guard.allowFreshHeavyAfterOwnerCheck(1_000_001));
        guard.start(1_000_000,"4.10.44","4.10.44");
        assertThrows(IllegalStateException.class,()->guard.allowFreshHeavyAfterOwnerCheck(1_000_001));
        guard.hold("Paused previous render");
        assertThrows(IllegalStateException.class,()->guard.allowFreshHeavyAfterOwnerCheck(0));
        assertFalse(guard.mayStartFreshHeavy("animate_images",1_000_001));
        guard.allowFreshHeavyAfterOwnerCheck(1_000_010);
        assertTrue(guard.mayStartFreshHeavy("animate_images",1_000_011));
        guard.hold("New risky operation");
        assertFalse("A new safety hold revokes previous consent",
                guard.mayStartFreshHeavy("animate_images",1_000_012));
    }

    @Test public void regularFullRetryAndLightweightWorkRetainOriginalSemantics(){
        NativeStartupGuard guard=fresh();
        guard.start(1_000_000,"4.10.44","4.10.44");
        guard.hold("Old interrupted render");
        assertTrue(guard.mayStartFreshHeavy("sketch_get",1_000_001));
        guard.allowFreshHeavyAfterOwnerCheck(1_000_002);
        guard.userRetry();
        assertFalse(guard.held());
        assertTrue(guard.mayAutoResume("old-render","export_project",1_000_010));
        assertTrue(guard.mayStartFreshHeavy("export_project",1_000_010));
    }
}
