package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class RenderNativeIpcClientTest {
    @Test public void supportsAllNativeRenderActions() {
        String[] actions = {
            "engine.status", "agent.diagnostics", "agent.capabilities",
            "gpu.info", "projects.list", "projects.inspect",
            "blender.scene.summary", "blender.object.add_cube",
            "blender.scene.set_frame", "blender.object.set_transform",
            "blender.object.keyframe", "blender.project.save_copy",
            "blender.object.add_primitive", "blender.object.add_camera",
            "blender.object.add_light", "blender.object.modifier_add",
            "blender.material.assign", "blender.scene.render_settings",
            "blender.scene.render_still", "blender.project.restore_scene",
            "blender.scene.create_lively_project", "blender.scene.batch_assembly",
            "blender.scene.visual_audit", "blender.scene.populate_lively",
            "blender.scene.render_video"
        };
        for (String name : actions)
            assertTrue(RenderNativeIpcClient.supports("render." + name));
    }
    @Test public void namesTheCorrectTargetApplication() {
        assertTrue(RenderNativeIpcClient.displayLabel("render.blender.object.add_cube")
            .startsWith("Render Studio"));
        assertEquals("VideoStudio",RenderNativeIpcClient.displayLabel("apply_tool"));
    }
    @Test public void nativeVideoTimeoutExceedsRenderWorkerAndUnknownsStayBounded() {
        assertEquals(1_980_000L,RenderNativeIpcClient.timeoutMillis("render.blender.scene.render_video"));
        assertEquals(150_000L,RenderNativeIpcClient.timeoutMillis("render.blender.scene.populate_lively"));
        assertEquals(150_000L,RenderNativeIpcClient.timeoutMillis("render.blender.scene.create_lively_project"));
        assertEquals(150_000L,RenderNativeIpcClient.timeoutMillis("render.blender.scene.batch_assembly"));
        assertEquals(120_000L,RenderNativeIpcClient.timeoutMillis("render.blender.project.restore_scene"));
        assertEquals(40_000L,RenderNativeIpcClient.timeoutMillis("render.engine.status"));
    }
    @Test public void neverForwardsUnknownPythonOrVideoStudioTools() {
        String[] invalid = {null, "", "render.", "render.python.eval",
            "render.shell.exec", "render.gallery.list",
            "render.blender.execute_arbitrary_code", "render.blender.scene.render_video_with_path", "blender.scene.summary",
            "videostudio.export_project", "render.media_library.enumerate"};
        for (String value : invalid)
            assertFalse(RenderNativeIpcClient.supports(value));
    }
}

/* Side-by-side signed Render preview is explicitly discoverable without
 * granting broad package enumeration. Its own data is isolated from old Render.
 */
