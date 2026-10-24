package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class StudioEditingCapabilitiesTest {
    @Test public void executableEditorCapabilitiesDeclareTheActualLayerMotionContract() throws Exception {
        JSONObject editor=NativeWorkbenchBridge.capabilities().getJSONObject("studioEditing");
        assertEquals("videostudio.studio-editing-capabilities.v1",editor.getString("schema"));
        JSONObject motion=editor.getJSONObject("imageOverlayMotion");
        assertEquals("4.10.20",motion.getString("minimumNativeVersion"));
        assertEquals("set_studio_layers",motion.getString("action"));
        assertEquals("motionKeyframes",motion.getString("field"));
        assertEquals(64,motion.getInt("maximumKeys"));
        assertEquals("layer-local milliseconds",motion.getString("clock"));
        assertEquals("fixed center",motion.getString("pivot"));
        assertTrue(motion.getBoolean("explicitEmptyClears"));
        assertTrue(motion.getBoolean("legacyOmissionUnchanged"));
        assertTrue(motion.getBoolean("previewExportShared"));
        assertEquals("3.9.0",editor.getJSONObject("precisionEdits").getString("minimumNativeVersion"));
        assertEquals("3.9.6",editor.getJSONObject("precisionEdits").getString("cropMinimumNativeVersion"));
        assertEquals("3.9.0",editor.getJSONObject("layerTracks").getString("imageMinimumNativeVersion"));
    }
    @Test public void planningOnlyEffectsAreNotAdvertisedAsExecutableRendering() throws Exception {
        JSONObject editor=NativeWorkbenchBridge.capabilities().getJSONObject("studioEditing");
        assertEquals("[\"none\",\"cut\"]",editor.getJSONArray("renderedTransitions").toString());
        assertEquals("[\"none\",\"gaussian_blur\",\"dream\"]",editor.getJSONArray("renderedEffectPresets").toString());
        JSONArray unavailable=editor.getJSONArray("unavailable");
        for(String name:new String[]{"cross_dissolve","speed_ramp","motion_blur","video_overlay_lanes","beat_detection","kinetic_typography"}) {
            boolean found=false;for(int i=0;i<unavailable.length();i++)found|=name.equals(unavailable.getString(i));
            assertTrue(name,found);
        }
        assertFalse(editor.getJSONObject("sourceAnalysis").getBoolean("exactFrameTimes"));
        assertFalse(editor.getJSONObject("sourceAnalysis").getBoolean("beatDetection"));
    }
    @Test public void capabilitiesAreDetachedAndDoNotAddCommandsOrRaiseLegacyFloors() throws Exception {
        JSONObject first=NativeWorkbenchBridge.capabilities();
        String actions=first.getJSONArray("canonicalActions").toString();
        assertTrue(actions.contains("set_studio_layers"));
        first.getJSONObject("studioEditing").getJSONObject("imageOverlayMotion").put("maximumKeys",999);
        JSONObject second=NativeWorkbenchBridge.capabilities();
        assertEquals(64,second.getJSONObject("studioEditing").getJSONObject("imageOverlayMotion").getInt("maximumKeys"));
        assertEquals(actions,second.getJSONArray("canonicalActions").toString());
        assertEquals("3.9.0",second.getString("minimumNativeVersion"));
        assertEquals("4.3.0",second.getString("incrementalKeyframeMinimumNativeVersion"));
        assertEquals("4.1.0",second.getString("editableArchiveMinimumNativeVersion"));
    }
}
