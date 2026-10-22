package com.rezoxnemesis.videostudio;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class RenderProductionTargetTest {
    @Test public void originalIsDefaultAndPreviewRequiresExplicitTarget()throws Exception{
        assertEquals("com.rezox.render",RenderNativeIpcClient.targetPackage(new JSONObject()));
        assertEquals("com.rezox.render.preview",RenderNativeIpcClient.targetPackage(new JSONObject().put("target_package","com.rezox.render.preview")));
        assertEquals("com.rezox.render",RenderNativeIpcClient.preferredRenderPackage(null));
    }
    @Test public void arbitraryApplicationsCannotReceivePayloads()throws Exception{
        for(String pkg:new String[]{"com.android.settings","com.rezox.render.fake","", "com.other.gallery"}){
            try{RenderNativeIpcClient.targetPackage(new JSONObject().put("target_package",pkg));fail(pkg);}catch(IllegalArgumentException expected){}
        }
    }
    @Test public void oneVersionedEnvelopeDoesNotEnableArbitraryCode(){
        assertTrue(RenderNativeIpcClient.supports("render.production.execute"));
        assertFalse(RenderNativeIpcClient.supports("render.production.execute_python"));
        assertFalse(RenderNativeIpcClient.supports("render.shell.exec"));
        assertFalse(RenderNativeIpcClient.supports("render.gallery.list"));
        assertEquals(1980000L,RenderNativeIpcClient.timeoutMillis("render.production.execute"));
    }
}
