package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StoryboardRigSwordTipTest {
    @Test public void originalSwordEndpointSurvivesPrivateRigSerialization() throws Exception {
        float[] sourceTip={.72f,.55f};
        Rig2DProfile rig=Rig2DProfile.create("project-1234","asset-1234",
                true,false,Rig2DProfileTest.joints(),
                Rig2DProfileTest.outline(),sourceTip);
        sourceTip[0]=.01f;
        assertEquals(.72f,rig.swordTip[0],.000001f);
        Rig2DProfile decoded=Rig2DProfile.fromJson(rig.toJson());
        assertEquals(.72f,decoded.swordTip[0],.000001f);
        assertEquals(.55f,decoded.swordTip[1],.000001f);
    }
    @Test public void olderRigProfilesStillLoadWithNoSwordTip() throws Exception {
        Rig2DProfile old=Rig2DProfile.create("project-1234","asset-1234",
                false,false,Rig2DProfileTest.joints(),
                Rig2DProfileTest.outline());
        JSONObject doc=old.toJson();
        assertFalse(doc.has("swordTip"));
        assertNull(Rig2DProfile.fromJson(doc).swordTip);
    }
    @Test(expected=IllegalArgumentException.class)
    public void swordCannotBeAnnotatedOutsideOriginalImage() {
        Rig2DProfile.create("project-1234","asset-1234",
                false,false,Rig2DProfileTest.joints(),
                Rig2DProfileTest.outline(),new float[]{.2f,2f});
    }
}
