package com.rezoxnemesis.videostudio;
import android.content.Context;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchLiveSessionTest {
    JSONObject create(Context c) throws Exception {String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new"));try(SketchProjectStore s=new SketchProjectStore(c)){return s.get(id);}}
    JSONArray stroke(String id) throws Exception{return new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id",id).put("kind","stroke").put("points",new JSONArray("[[0.2,0.2,1],[0.5,0.5,1]]"))));}
    @Test public void rapidStrokesDisplayImmediatelyAndPersistInOrderAfterClose() throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject doc=create(c);Class<?> type=Class.forName("com.rezoxnemesis.videostudio.SketchEditorSession");Object session=type.getConstructor(Context.class,JSONObject.class).newInstance(c,doc);
        for(int i=0;i<8;i++)type.getMethod("submit",JSONArray.class).invoke(session,stroke("s"+i));
        JSONObject visible=(JSONObject)type.getMethod("document").invoke(session);assertEquals(8,SketchDocument.layer(visible,"layer_1").getJSONArray("objects").length());
        type.getMethod("close").invoke(session);type.getMethod("awaitIdle",long.class).invoke(session,10000L);
        try(SketchProjectStore s=new SketchProjectStore(c)){assertEquals(9,s.get(doc.getString("id")).getLong("revision"));assertEquals(8,SketchDocument.layer(s.get(doc.getString("id")),"layer_1").getJSONArray("objects").length());}
    }
    @Test public void agentConflictPreservesLocalDraftAndDoesNotOverwriteNewerSource() throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject doc=create(c);Class<?> type=Class.forName("com.rezoxnemesis.videostudio.SketchEditorSession");Object session=type.getConstructor(Context.class,JSONObject.class).newInstance(c,doc);
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",doc.getString("id")).put("operationId","agent").put("expectedRevision",1).put("operations",stroke("agent")));
        type.getMethod("submit",JSONArray.class).invoke(session,stroke("local"));type.getMethod("awaitIdle",long.class).invoke(session,10000L);
        assertNotNull(type.getMethod("error").invoke(session));assertTrue(((java.io.File)type.getMethod("recoveryFile").invoke(session)).isFile());
        try(SketchProjectStore s=new SketchProjectStore(c)){JSONObject current=s.get(doc.getString("id"));assertEquals("agent",SketchDocument.layer(current,"layer_1").getJSONArray("objects").getJSONObject(0).getString("id"));assertEquals(2,current.getLong("revision"));}
        type.getMethod("close").invoke(session);
    }
    @Test public void reopeningAConflictCannotOverwriteTheRetainedLocalDraft() throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject doc=create(c);SketchEditorSession first=new SketchEditorSession(c,doc);SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",doc.getString("id")).put("operationId","agent").put("expectedRevision",1).put("operations",stroke("agent")));first.submit(stroke("local"));first.awaitIdle(10000);first.close();byte[] before=java.nio.file.Files.readAllBytes(first.recoveryFile().toPath());JSONObject current;try(SketchProjectStore store=new SketchProjectStore(c)){current=store.get(doc.getString("id"));}SketchEditorSession reopened=new SketchEditorSession(c,current);try{reopened.submit(stroke("new"));fail("Conflict draft was overwritten");}catch(IllegalStateException expected){}finally{reopened.close();}assertArrayEquals(before,java.nio.file.Files.readAllBytes(first.recoveryFile().toPath()));
    }
    @Test public void recoveredCopyRetainsLocalArtworkAndOriginalReopensForLiveWork() throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject doc=create(c);SketchEditorSession original=new SketchEditorSession(c,doc);SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",doc.getString("id")).put("operationId","agent").put("expectedRevision",1).put("operations",stroke("agent")));original.submit(stroke("local"));original.awaitIdle(10000);original.close();String copy=SketchEditorSession.recoverAsCopy(c,doc.getString("id"));assertFalse(original.recoveryFile().exists());try(SketchProjectStore store=new SketchProjectStore(c)){assertEquals("local",SketchDocument.layer(store.get(copy),"layer_1").getJSONArray("objects").getJSONObject(0).getString("id"));SketchEditorSession reopened=new SketchEditorSession(c,store.get(doc.getString("id")));assertNull(reopened.error());reopened.close();}
    }
    @Test public void incrementalAgentStrokeKeepsStableIdentityAndAppendsVisibleGeometry() throws Exception {
        Context c=RuntimeEnvironment.getApplication();JSONObject doc=create(c);
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",doc.getString("id")).put("operationId","start").put("expectedRevision",1).put("operations",stroke("live")));
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",doc.getString("id")).put("operationId","step").put("expectedRevision",2).put("operations",new JSONArray().put(new JSONObject().put("type","append_points").put("objectId","live").put("points",new JSONArray("[[0.6,0.7,0.4]]")))));
        try(SketchProjectStore s=new SketchProjectStore(c)){JSONObject current=s.get(doc.getString("id"));assertEquals(3,SketchDocument.object(current,"live").getJSONArray("points").length());}
    }
}
