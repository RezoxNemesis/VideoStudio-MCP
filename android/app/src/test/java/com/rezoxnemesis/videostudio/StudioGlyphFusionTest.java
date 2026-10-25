package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Fusion UI/MCP scene compiler accepts actual stored glyph programs, not inert labels. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioGlyphFusionTest {
    private static ProjectStore.Project source(ProjectStore store) {
        ProjectStore.Project project=store.create("Reference source");
        ProjectStore.Asset image=new ProjectStore.Asset();
        image.id="owned-image";image.mime="image/png";
        image.uri="file:///app/private/owned.png";
        project.assets.add(image);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id="base";clip.assetId=image.id;clip.inMs=0;clip.outMs=9000;
        project.clips.add(clip);
        store.save(project);
        return store.get(project.id);
    }

    private static JSONObject request(ProjectStore.Project source,JSONObject node) throws Exception {
        return new JSONObject().put("projectId",source.id)
                .put("expectedUpdatedAt",source.updatedAt)
                .put("operationId","fusion-glyph-regression-20261025")
                .put("name","Editable per-character typography")
                .put("nodes",new JSONArray().put(node));
    }

    @Test public void fusionTitleCompilesToNativeEditableGlyphsWithoutOverwritingVideo() throws Exception {
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project input=source(store);
            String before=SketchDocument.canonical(input.toJson());
            JSONObject title=new JSONObject().put("id","glyph")
                    .put("type","title").put("text","NEVER STOP")
                    .put("startMs",0).put("durationMs",4400)
                    .put("fontSize",58)
                    .put("glyphAnimation",new JSONObject().put("mode","cascade")
                            .put("staggerMs",50).put("spread",.19));
            JSONObject req=request(input,title);
            JSONObject plan=StudioFusionSceneCompiler.plan(input,req);
            assertTrue(plan.getBoolean("dryRun"));
            assertEquals(1,plan.getInt("newVisualLayers"));
            JSONObject applied=StudioFusionSceneCompiler.apply(store,req);
            ProjectStore.Project output=store.get(applied.getString("outputProjectId"));
            assertEquals(1,output.studioLayers.size());
            StudioLayer actual=output.studioLayers.get(0);
            assertNotNull(actual.glyphAnimation);
            assertEquals("cascade",actual.glyphAnimation.getString("mode"));
            assertEquals(9,output.outputDurationMs()/1000L);
            assertEquals(before,SketchDocument.canonical(store.get(input.id).toJson()));
            assertEquals(output.id,StudioFusionSceneCompiler.apply(store,req).getString("outputProjectId"));
        }
    }

    @Test public void onlyRealNativeTitleNodesMayRequestGlyphPrograms() throws Exception {
        try(ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication())){
            ProjectStore.Project input=source(store);
            JSONObject bad=new JSONObject().put("id","invalid")
                    .put("type","shape").put("shapeKind","ring")
                    .put("glyphAnimation",new JSONObject().put("mode","wave"));
            assertThrows(IllegalArgumentException.class,
                    ()->StudioFusionSceneCompiler.plan(input,request(input,bad)));
        }
    }
}
