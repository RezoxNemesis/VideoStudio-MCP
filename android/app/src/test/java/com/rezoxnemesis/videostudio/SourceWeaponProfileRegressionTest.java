package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Exercises the existing profile JSON API, without depending on future helper signatures. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SourceWeaponProfileRegressionTest {
    private static JSONObject profile() throws Exception {
        return Rig2DProfile.create("project-1111","source-1111",true,false,
                Rig2DProfileTest.joints(),Rig2DProfileTest.outline(),new float[]{.72f,.52f})
                .toJson().put("swordHilt",new JSONArray().put(.64).put(.54))
                .put("swordOutline",new JSONArray().put(new JSONArray().put(.62).put(.56))
                        .put(new JSONArray().put(.62).put(.51)).put(new JSONArray().put(.75).put(.49))
                        .put(new JSONArray().put(.75).put(.54)));
    }

    @Test public void optionalSourceWeaponFieldsRoundTripWithoutChangingLegacyActorData() throws Exception {
        JSONObject original=profile(),recovered=Rig2DProfile.fromJson(original).toJson();
        assertTrue("The profile decoder must not silently discard the source hilt",recovered.has("swordHilt"));
        assertTrue("The profile decoder must retain source weapon pixels' mask",recovered.has("swordOutline"));
        assertEquals(original.getJSONArray("joints").toString(),recovered.getJSONArray("joints").toString());
        assertEquals(original.getJSONArray("outline").toString(),recovered.getJSONArray("outline").toString());
        assertEquals(original.getJSONArray("swordHilt").toString(),recovered.getJSONArray("swordHilt").toString());
        assertEquals(original.getJSONArray("swordOutline").toString(),recovered.getJSONArray("swordOutline").toString());
    }

    @Test(expected=IllegalArgumentException.class)
    public void sourceWeaponMaskCannotContainOutOfImageCoordinates() throws Exception {
        JSONObject supplied=profile();
        supplied.getJSONArray("swordOutline").put(0,new JSONArray().put(1.1).put(.56));
        Rig2DProfile.fromJson(supplied);
    }

    @Test(expected=IllegalArgumentException.class)
    public void sourceHiltCannotContainNonNumericCoordinates() throws Exception {
        Rig2DProfile.fromJson(profile().put("swordHilt",new JSONArray().put(".64").put(.54)));
    }
}
