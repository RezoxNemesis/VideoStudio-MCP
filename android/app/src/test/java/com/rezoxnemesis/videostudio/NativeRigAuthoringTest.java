package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeRigAuthoringTest {
    private static JSONArray coords(float[][] coordinates) throws Exception {
        JSONArray result = new JSONArray();
        for (float[] point : coordinates) result.put(new JSONArray().put(point[0]).put(point[1]));
        return result;
    }

    private static JSONObject request() throws Exception {
        return new JSONObject()
                .put("black", true)
                .put("joints", coords(Rig2DProfileTest.joints()))
                .put("outline", coords(Rig2DProfileTest.outline()));
    }

    @Test public void bindsSourceToProjectOwnedAssetId() throws Exception {
        JSONObject request = request();
        request.put("projectId", "forged-project-id");
        request.put("sourceAssetId", "forged-asset-id");
        Rig2DProfile result = NativeRigAuthoring.parse("project-id-01", "asset-id-01", request);
        assertEquals("project-id-01", result.projectId);
        assertEquals("asset-id-01", result.sourceAssetId);
        assertEquals(16, result.joints.length);
        assertEquals(6, result.outline.length);
        assertTrue(result.black);
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectsMissingCharacterPolarity() throws Exception {
        JSONObject request = request();
        request.remove("black");
        NativeRigAuthoring.parse("project-id-01", "asset-id-01", request);
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectsIncompleteJointList() throws Exception {
        JSONObject request = request();
        request.put("joints", new JSONArray());
        NativeRigAuthoring.parse("project-id-01", "asset-id-01", request);
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectsOutOfBoundsPolygonPoint() throws Exception {
        JSONObject request = request();
        JSONArray outline = request.getJSONArray("outline");
        outline.put(0, new JSONArray().put(-.1).put(.5));
        NativeRigAuthoring.parse("project-id-01", "asset-id-01", request);
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectsPathTraversalInUserRigId() throws Exception {
        JSONObject request = request().put("rigId", "../../Gallery");
        NativeRigAuthoring.parse("project-id-01", "asset-id-01", request);
    }

    @Test(expected=IllegalArgumentException.class)
    public void refusesOpaqueUnmaskedFullImage() throws Exception {
        JSONObject request = request().put("outline", new JSONArray());
        NativeRigAuthoring.parse("project-id-01", "asset-id-01", request);
    }

    @Test public void keepsCallerJointsImmutableAndSupportsStableId() throws Exception {
        JSONObject request = request().put("rigId", "11111111-2222-3333-4444-555555555555");
        Rig2DProfile result=NativeRigAuthoring.parse("project-id-01","asset-id-01",request);
        request.getJSONArray("joints").getJSONArray(0).put(0,.99);
        assertEquals(.42,result.joints[0][0],.00001);
        assertEquals("11111111-2222-3333-4444-555555555555",result.id);
    }

    @Test public void sourceSwordTipSurvivesTheActualNativeAuthoringPath() throws Exception {
        JSONObject supplied=request().put("swordTip",new JSONArray().put(.72).put(.52));
        Rig2DProfile result=NativeRigAuthoring.parse("project-id-01","asset-id-01",supplied);
        assertNotNull("A supplied authentic sword endpoint must not disappear before rig preflight",result.swordTip);
        assertArrayEquals(new float[]{.72f,.52f},result.swordTip,.000001f);
    }

    @Test public void sourceWeaponFlatAnnotationsSurviveAuthoringAndSerialization() throws Exception {
        JSONArray polygon=new JSONArray().put(new JSONArray().put(.62).put(.56))
                .put(new JSONArray().put(.62).put(.51)).put(new JSONArray().put(.75).put(.49))
                .put(new JSONArray().put(.75).put(.54));
        JSONObject supplied=request().put("swordTip",new JSONArray().put(.72).put(.52))
                .put("swordHilt",new JSONArray().put(.64).put(.54)).put("swordOutline",polygon);
        JSONObject stored=NativeRigAuthoring.parse("project-id-01","asset-id-01",supplied).toJson();
        assertTrue("Native authoring must retain the original source hilt",stored.has("swordHilt"));
        assertTrue("Native authoring must retain the actual painted sword mask",stored.has("swordOutline"));
        assertEquals(.64,stored.getJSONArray("swordHilt").getDouble(0),.000001);
        assertEquals(4,stored.getJSONArray("swordOutline").length());
        JSONObject recovered=Rig2DProfile.fromJson(stored).toJson();
        assertEquals(stored.getJSONArray("swordHilt").toString(),recovered.getJSONArray("swordHilt").toString());
        assertEquals(stored.getJSONArray("swordOutline").toString(),recovered.getJSONArray("swordOutline").toString());
    }
    @Test public void sourceByteProofSurvivesAndCannotBeAStringCoercionOrInvalidDigest() throws Exception {
        String digest="0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        Rig2DProfile profile=NativeRigAuthoring.parse("project-id-01","asset-id-01",request().put("sourceSha256",digest));
        assertEquals(digest,profile.sourceSha256);assertEquals(digest,Rig2DProfile.fromJson(profile.toJson()).sourceSha256);
        for(Object invalid:new Object[]{123,"","ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789ab",JSONObject.NULL}) {
            try{NativeRigAuthoring.parse("project-id-01","asset-id-01",request().put("sourceSha256",invalid));fail("Invalid source-byte proof accepted");}
            catch(IllegalArgumentException expected){}
        }
        assertNull(NativeRigAuthoring.parse("project-id-01","asset-id-01",request()).sourceSha256);
    }
}
