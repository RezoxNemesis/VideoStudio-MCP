package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Regression for the owner's 7001 edited preview; diagnostics never include source text. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioPreviewDiagnosticsBudgetTest {
    @Test public void twentyEightTitleFailureStoresSafeResourceBudgetWithoutMediaContent()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        try(ProjectStore db=new ProjectStore(context)){
            ProjectStore.Project project=db.create("Private content must not leak to diagnostics");
            for(int i=0;i<28;i++){
                StudioLayer layer=new StudioLayer();
                layer.type=StudioLayer.TITLE;
                layer.startMs=i*1200;layer.durationMs=1200;
                layer.text="PRIVATE_DO_NOT_LOG_"+i;
                project.studioLayers.add(layer);
            }
            StudioPreviewDiagnostics.failed(context,project,new IllegalArgumentException("private source path"),
                    "asynchronous_playback");
            JSONObject receipt=StudioPreviewDiagnostics.last(context);
            assertEquals(28,receipt.getInt("titleLayers"));
            assertEquals(1,receipt.getInt("plannedOverlayTextures"));
            assertEquals(1,receipt.getInt("plannedShaderPasses"));
            assertEquals(6,receipt.getInt("maxTexturesPerShader"));
            assertTrue(receipt.getBoolean("denseTitleTimeline"));
            assertFalse(receipt.toString().contains("PRIVATE_DO_NOT_LOG"));
            assertFalse(receipt.toString().contains("private source path"));
            assertFalse(receipt.toString().contains("content must not leak"));
            assertTrue(NativeAgentMailboxActions.supports("studio_preview_diagnostics"));
        }
    }
}
