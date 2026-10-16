package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.UUID;
import static org.junit.Assert.*;

/** The same native operation protocol serves touch and remote studio agents. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchXsheetNativeFacadeTest {
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void nativeXsheetQueryIsPagedRevisionPinnedAndCannotMutateLiveArt()throws Exception{
        String id="xsheet_native_"+UUID.randomUUID();
        JSONObject created=SketchEditOps.execute(context,"sketch_create",
                new JSONObject().put("sketchId",id).put("operationId","create")
                        .put("width",256).put("height",256).put("frameCount",24));
        assertEquals(1,created.getLong("revision"));
        JSONObject source;
        try(SketchProjectStore store=new SketchProjectStore(context)){
            source=store.get(id);
        }
        String frozen=SketchDocument.canonical(source);
        JSONObject query=SketchEditOps.execute(context,"sketch_xsheet",
                new JSONObject().put("sketchId",id).put("expectedRevision",1)
                        .put("frameOffset",5).put("frameLimit",8));
        assertTrue(query.getBoolean("ok"));
        assertEquals("videostudio.sketch-xsheet.v1",query.getString("schema"));
        assertEquals(8,query.getInt("returnedFrames"));
        assertEquals(13,query.getInt("nextFrameOffset"));
        assertEquals(1,query.getInt("levelCount"));
        assertEquals(8,query.getJSONArray("columns").getJSONObject(0).getJSONArray("cells").length());
        assertEquals(0,query.getInt("drawingCells"));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            assertEquals(frozen,SketchDocument.canonical(store.get(id)));
        }
        try{
            SketchEditOps.execute(context,"sketch_xsheet",
                    new JSONObject().put("sketchId",id).put("expectedRevision",2)
                            .put("frameOffset",0).put("frameLimit",6));
            fail("Read-only Xsheet queries must reject stale revision data");
        }catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("REVISION"));}
    }

    @Test public void namedStyleMutationsUseExactlyTheExistingRevisionSafeSketchEditPath()throws Exception{
        String id="palette_native_"+UUID.randomUUID();
        SketchEditOps.execute(context,"sketch_create",new JSONObject()
                .put("sketchId",id).put("operationId","create")
                .put("width",256).put("height",256).put("frameCount",24));
        JSONObject before;
        try(SketchProjectStore store=new SketchProjectStore(context)){before=store.get(id);}
        String fingerprint=SketchDocument.canonical(before);
        JSONArray operations=new JSONArray().put(new JSONObject()
                .put("type","palette_upsert").put("styleId","lineart")
                .put("name","Line Art").put("color","#FF203A60"));
        JSONObject edited=SketchEditOps.execute(context,"sketch_edit",new JSONObject()
                .put("sketchId",id).put("operationId","style_first")
                .put("expectedRevision",1).put("operations",operations));
        assertTrue(edited.getBoolean("ok"));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            JSONObject saved=store.get(id);
            assertEquals(2,saved.getLong("revision"));
            assertEquals(1,saved.getJSONArray("paletteStyles").length());
            assertEquals("#FF203A60",saved.getJSONArray("paletteStyles")
                    .getJSONObject(0).getString("color"));
        }
        try{
            SketchEditOps.execute(context,"sketch_edit",new JSONObject()
                    .put("sketchId",id).put("operationId","style_conflict")
                    .put("expectedRevision",1).put("operations",operations));
            fail("Stale palette edits should not overwrite real artwork revisions");
        }catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("REVISION"));}
        assertNotEquals(fingerprint,SketchDocument.canonical(edited));
    }
}
