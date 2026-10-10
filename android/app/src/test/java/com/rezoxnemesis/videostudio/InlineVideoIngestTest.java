package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Base64;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
public class InlineVideoIngestTest {
    @Test public void privateInlineFallbackRejectsAnFtypHeaderWithoutPlayableMedia() throws Exception {
        ProjectStore store = new ProjectStore(RuntimeEnvironment.getApplication());
        ProjectStore.Project project = store.create("Inline MP4 regression");

        ServiceController<ControlService> controller = Robolectric.buildService(ControlService.class);
        ControlService service = controller.get();

        Field storeField = ControlService.class.getDeclaredField("store");
        storeField.setAccessible(true);
        storeField.set(service, store);

        byte[] mp4 = new byte[32];
        mp4[0] = 0;
        mp4[1] = 0;
        mp4[2] = 0;
        mp4[3] = 24;
        mp4[4] = 'f';
        mp4[5] = 't';
        mp4[6] = 'y';
        mp4[7] = 'p';
        mp4[8] = 'i';
        mp4[9] = 's';
        mp4[10] = 'o';
        mp4[11] = 'm';

        JSONObject params = new JSONObject()
                .put("projectId", project.id)
                .put("name", "chatgpt-source.mp4")
                .put("mime", "video/mp4")
                .put("base64", Base64.getEncoder().encodeToString(mp4));

        Method method = ControlService.class.getDeclaredMethod("importInlineBase64", JSONObject.class);
        method.setAccessible(true);

        try {
            method.invoke(service, params);
            fail("A container signature without real audio/video streams must not be imported");
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            assertNotNull(cause);
        }
        ProjectStore.Project reloaded = store.get(project.id);
        assertNotNull(reloaded);
        assertTrue(reloaded.assets.isEmpty());
        assertTrue(reloaded.clips.isEmpty());
    }
}
