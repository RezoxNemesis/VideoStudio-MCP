package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class RenderNativeIpcClientTest {
    @Test public void supportsAllNativeRenderActions() {
        String[] actions = {
            "engine.status", "gpu.info", "projects.list", "projects.inspect",
            "blender.scene.summary", "blender.object.add_cube",
            "blender.scene.set_frame", "blender.object.set_transform",
            "blender.object.keyframe", "blender.project.save_copy"
        };
        for (String name : actions)
            assertTrue(RenderNativeIpcClient.supports("render." + name));
    }
    @Test public void namesTheCorrectTargetApplication() {
        assertTrue(RenderNativeIpcClient.displayLabel("render.blender.object.add_cube")
            .startsWith("Render Studio"));
        assertEquals("VideoStudio",RenderNativeIpcClient.displayLabel("apply_tool"));
    }
    @Test public void neverForwardsUnknownPythonOrVideoStudioTools() {
        String[] invalid = {null, "", "render.", "render.python.eval",
            "render.shell.exec", "render.gallery.list",
            "render.blender.execute_arbitrary_code", "blender.scene.summary",
            "videostudio.export_project", "render.media_library.enumerate"};
        for (String value : invalid)
            assertFalse(RenderNativeIpcClient.supports(value));
    }
}
