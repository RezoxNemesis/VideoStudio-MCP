package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class NativeCommandResultPolicyTest {
    @Test public void realBuiltInRegistryReadIsAcknowledgedWithoutInventingProvider()throws Exception{
        CapabilityRegistry registry=new CapabilityRegistry(RuntimeEnvironment.getApplication());
        JSONObject catalog=registry.describe();
        assertFalse(catalog.has("ok"));
        assertTrue(catalog.getInt("providerCount")>=10);
        assertTrue(NativeCommandResultPolicy.successful("capability_registry",catalog));
        JSONObject receipt=NativeCommandResultPolicy.normalizedResult("capability_registry",catalog);
        assertTrue(receipt.getBoolean("ok"));
        assertEquals(catalog.getInt("providerCount"),receipt.getJSONArray("providers").length());
    }
    @Test public void resolverReportsNotFoundAsCompletedReadNotAnEditFailure()throws Exception{
        CapabilityRegistry registry=new CapabilityRegistry(RuntimeEnvironment.getApplication());
        JSONObject valid=registry.resolve("render.video","balanced");
        assertTrue(valid.getBoolean("resolved"));
        assertTrue(NativeCommandResultPolicy.normalizedResult("resolve_capability",valid).getBoolean("ok"));
        JSONObject missing=registry.resolve("omega.nonexistent.capability","balanced");
        assertFalse(missing.getBoolean("resolved"));
        assertTrue(NativeCommandResultPolicy.normalizedResult("resolve_capability",missing).getBoolean("ok"));
        assertFalse(missing.has("provider"));
    }
    @Test public void explicitFailuresAlwaysWinAndUnknownWritesNeverInferSuccess()throws Exception{
        JSONObject denied=new JSONObject().put("ok",false).put("resolved",true)
                .put("provider",new JSONObject().put("id","injected"));
        assertFalse(NativeCommandResultPolicy.successful("resolve_capability",denied));
        assertFalse(NativeCommandResultPolicy.normalizedResult("resolve_capability",denied).getBoolean("ok"));
        JSONObject fake=new JSONObject().put("resolved",true).put("capability","render.video")
                .put("provider",new JSONObject().put("id","provider"));
        for(String operation:new String[]{"sketch_edit","sketch_render","import_url","export_project",
                "install_model_pack","cancel_all_jobs","unknown_action"}){
            assertFalse("Must require explicit native success for "+operation,
                    NativeCommandResultPolicy.successful(operation,fake));
            assertFalse(NativeCommandResultPolicy.normalizedResult(operation,
                    new JSONObject(fake.toString())).has("ok"));
        }
    }
    @Test public void malformedReadOnlyPayloadCannotBecomeSuccessful()throws Exception{
        assertFalse(NativeCommandResultPolicy.successful("capability_registry",
                new JSONObject().put("providerCount",3).put("providers",new JSONArray())));
        assertFalse(NativeCommandResultPolicy.successful("resolve_capability",
                new JSONObject().put("resolved",true).put("capability","render.video")));
        assertFalse(NativeCommandResultPolicy.successful("resolve_capability",
                new JSONObject().put("resolved","true").put("capability","render.video")));
        assertFalse(NativeCommandResultPolicy.successful("model_pack_status",new JSONObject()));
        assertFalse(NativeCommandResultPolicy.successful("capability_registry",
                new JSONObject().put("error","registry broken")
                    .put("providerCount",0).put("providers",new JSONArray())));
        assertFalse(NativeCommandResultPolicy.successful("sketch_get",new JSONObject()));
        assertTrue(NativeCommandResultPolicy.successful("sketch_edit",
                new JSONObject().put("ok",true)));
    }
}
