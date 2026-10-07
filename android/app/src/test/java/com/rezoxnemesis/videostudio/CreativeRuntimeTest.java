package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CreativeRuntimeTest {
    private Bitmap frame(ProceduralScene scene,double seconds) {
        Bitmap bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);
        scene.draw(new Canvas(bitmap),seconds,4);return bitmap;
    }
    private int differences(Bitmap a,Bitmap b) {
        int different=0;for(int y=0;y<256;y++)for(int x=0;x<256;x++)if(a.getPixel(x,y)!=b.getPixel(x,y))different++;
        return different;
    }
    @Test public void vectorVideoContainsActualMovingGeometry() throws Exception {
        ProceduralScene scene=new ProceduralScene(new JSONObject("{\"objects\":[{\"type\":\"circle\",\"x\":0.2,\"toX\":0.8,\"y\":0.5,\"width\":0.2}]}"));
        Bitmap a=frame(scene,0),b=frame(scene,2);
        try { assertTrue("Geometry must move between encoded timestamps",differences(a,b)>500); } finally {a.recycle();b.recycle();}
    }
    @Test public void meshVideoChangesProjectedGeometryAndLighting() throws Exception {
        ProceduralScene scene=new ProceduralScene(new JSONObject("{\"objects\":[{\"type\":\"cube\",\"size\":0.8,\"spin\":25,\"z\":4}]}"));
        Bitmap a=frame(scene,0),b=frame(scene,1);
        try { assertTrue("Mesh must rotate rather than reusing a still",differences(a,b)>500); } finally {a.recycle();b.recycle();}
    }
    @Test public void invalidMeshIndicesAreRejectedBeforeRendering() throws Exception {
        try { new ProceduralScene(new JSONObject("{\"objects\":[{\"type\":\"mesh\",\"vertices\":[[0,0,1],[1,0,1],[0,1,1]],\"faces\":[[0,1,9]]}]}"));fail("Bad mesh index accepted"); }
        catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("index")); }
    }
    @Test public void proceduralHumanRequestDoesNotMasqueradeAsNeuralGeneration() throws Exception {
        try { LocalSceneDirector.fromPrompt("a realistic human walking",0);fail("Human synthesis was misrepresented"); }
        catch(IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("local image/video model")); }
    }
    @Test public void resultOutboxSurvivesProcessStyleDatabaseReopenUntilAcknowledged() throws Exception {
        Context context=RuntimeEnvironment.getApplication();context.deleteDatabase("mcp_result_outbox.db");
        CommandOutbox first=new CommandOutbox(context);
        first.put(new JSONObject("{\"id\":\"cmd-17\",\"seq\":17}"),new JSONObject("{\"assetId\":\"generated-1\"}"),"completed");first.close();
        CommandOutbox reopened=new CommandOutbox(context);
        try {
            assertEquals(1,reopened.count());assertEquals("generated-1",reopened.pending().getJSONObject(0).getJSONObject("result").getString("assetId"));
            reopened.acknowledge("cmd-17");assertEquals(0,reopened.count());
        } finally {reopened.close();context.deleteDatabase("mcp_result_outbox.db");}
    }
    @Test public void stateReadsDoNotRecursivelyAccumulateInMutationJournal() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs=context.getSharedPreferences("videostudio_native_v1",Context.MODE_PRIVATE);
        prefs.edit().putString("mcp_v3_command_journal", "[{\"id\":\"old-state\",\"action\":\"get_state\",\"status\":\"completed\",\"result\":{\"commandJournal\":[{\"result\":{\"commandJournal\":[]}}]}},{\"id\":\"edit-1\",\"action\":\"apply_tool\",\"status\":\"completed\",\"result\":{\"ok\":true}}]").commit();
        CommandJournal journal=new CommandJournal(context);
        JSONObject read=new JSONObject("{\"id\":\"new-state\",\"action\":\"get_state\"}");
        journal.begin(read);journal.finish(read,new JSONObject("{\"commandJournal\":[]}"),"completed");
        assertEquals(1,journal.recent(20).length());assertNotNull(journal.terminal("edit-1"));
        assertNull(journal.terminal("old-state"));assertNull(journal.terminal("new-state"));
        assertFalse(prefs.getString("mcp_v3_command_journal", "").contains("commandJournal"));
    }

    @Test public void largePortraitIsSubsampledBeforeAnalysisAllocation() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        java.io.File file=new java.io.File(context.getCacheDir(),"large-portrait.png");
        Bitmap source=Bitmap.createBitmap(3000,2000,Bitmap.Config.ARGB_8888);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){ source.compress(Bitmap.CompressFormat.PNG,100,out); }
        source.recycle();
        java.lang.reflect.Method decode=NativePortraitMotionAnalyzer.class.getDeclaredMethod("decode",String.class);
        decode.setAccessible(true);
        Bitmap sampled=(Bitmap)decode.invoke(new NativePortraitMotionAnalyzer(context),android.net.Uri.fromFile(file).toString());
        try { assertTrue(Math.max(sampled.getWidth(),sampled.getHeight())<=1440);assertEquals(1.5,sampled.getWidth()/(double)sampled.getHeight(),.01); }
        finally {sampled.recycle();file.delete();}
    }

}
