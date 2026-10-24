package com.rezoxnemesis.videostudio;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class StudioEditingCatalogTruthTest {
    @Test public void legacyNativePlanningNamesStayCompatibleButCannotClaimExecutableTools() throws Exception {
        JSONObject catalog=CreatorCatalog.describe();
        assertFalse(catalog.getBoolean("executable"));
        assertEquals("legacy_planning_catalog",catalog.getString("kind"));
        assertEquals("studioEditing",catalog.getString("executableCapabilitiesField"));
        assertTrue(catalog.getJSONArray("effects").toString().contains("film_burn"));
        assertTrue(catalog.getJSONArray("aiTools").toString().contains("beat_sync"));
        assertTrue(catalog.getJSONArray("transitions").toString().contains("luma_wipe"));
    }
}
