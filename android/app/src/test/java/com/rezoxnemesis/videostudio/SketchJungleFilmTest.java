package com.rezoxnemesis.videostudio;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class SketchJungleFilmTest {
    @Test public void openedFutureCelsAndHumanEditsArePreservedWithoutBlockingLaterDrawing()throws Exception {
        android.content.Context c=org.robolectric.RuntimeEnvironment.getApplication();String id="opened_"+java.util.UUID.randomUUID();SketchJungleFilm.populateRange(c,id,0,1,null);
        for(int page:new int[]{1,2})SketchBookStore.execute(c,"sketch_book_open_page",new JSONObject().put("bookId",id).put("page",page));
        JSONObject human=SketchJungleFilm.drawCel(id+"_p2",2);
        try(SketchProjectStore store=new SketchProjectStore(c)){store.mutate(id+"_p2","artist_drawing",1,"original_artist_geometry",ignored->human);}
        String before;try(SketchProjectStore store=new SketchProjectStore(c)){before=SketchDocument.canonical(store.get(id+"_p2"));}
        SketchJungleFilm.populateRange(c,id,1,3,null);
        try(SketchProjectStore store=new SketchProjectStore(c)){
            assertEquals(before,SketchDocument.canonical(store.get(id+"_p2")));
            assertEquals(1,store.get(id+"_p1").getInt("revision"));
            assertTrue(SketchDocument.layer(store.get(id+"_p3"),"background").getJSONArray("objects").length()>0);
        }
    }
    @Test public void constructionSheetRetainsDistinctEditableViewsAndFacialStudies()throws Exception {
        JSONObject sheet=SketchJungleFilm.modelSheet("traveller_turns");SketchDocument.validate(sheet);for(String id:new String[]{"front","quarter","profile","back","overhead","anger","realize","shame"})assertTrue(SketchDocument.layer(sheet,id).getJSONArray("objects").length()>20);assertFalse(SketchDocument.layer(sheet,"profile").toString().contains("\"reference\""));
    }
    @Test public void originalSoundtrackIsRealStereoPcmWithHeadroom()throws Exception {
        java.io.File file=java.io.File.createTempFile("jungle_score_",".wav");file.delete();try{JSONObject result=JungleFilmSoundtrack.write(file,2);assertNotNull("A real original PCM score must be written",result);assertTrue(file.isFile());JSONObject measured=PcmWaveFile.inspect(file);assertEquals(2000,measured.getInt("durationMs"));assertEquals(2,measured.getInt("channels"));byte[] bytes=java.nio.file.Files.readAllBytes(file.toPath());java.nio.ByteBuffer pcm=java.nio.ByteBuffer.wrap(bytes,44,bytes.length-44).order(java.nio.ByteOrder.LITTLE_ENDIAN);int peak=0;while(pcm.remaining()>=2)peak=Math.max(peak,Math.abs((int)pcm.getShort()));assertTrue(peak>300);assertTrue(peak<32767);}finally{file.delete();}
    }
    @Test public void originalBookHasTheFullFilmClockAndResumePreservesEditedCels()throws Exception {
        android.content.Context c=org.robolectric.RuntimeEnvironment.getApplication();String id="jungle_"+java.util.UUID.randomUUID();JSONObject result=SketchJungleFilm.populateRange(c,id,0,2,null);assertNotNull("Native original-film book authoring is required",result);
        JSONObject book;try(SketchBookStore books=new SketchBookStore(c)){book=books.get(id);}SketchSequenceTimeline timing=new SketchSequenceTimeline(book,0,book.getInt("pageCount"));assertEquals(2880,timing.frames());assertEquals(2,book.getInt("createdPages"));assertEquals(1,timing.page(2));
        String cel=id+"_p0";JSONObject before;try(SketchProjectStore store=new SketchProjectStore(c)){before=store.get(cel);}String object=before.getJSONArray("layers").getJSONObject(0).getJSONArray("objects").getJSONObject(0).getString("id");
        SketchEditOps.execute(c,"sketch_edit",new JSONObject().put("sketchId",cel).put("expectedRevision",1).put("operationId","human_colour").put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId",object).put("patch",new JSONObject().put("color","#FF194356")))));
        SketchJungleFilm.populateRange(c,id,0,2,null);try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject after=store.get(cel);assertEquals(2,after.getInt("revision"));assertEquals("#FF194356",SketchDocument.object(after,object).getString("color"));}
    }
    @Test public void originalStoryCelsContainEditableGeometryWithoutAnyImageReference()throws Exception {
        for(int frame:new int[]{300,600,1100,1752,2208}){
            JSONObject doc=SketchJungleFilm.drawCel("jungle_test_"+frame,frame);assertNotNull("Original dragon-story drawing is required",doc);SketchDocument.validate(doc);
            assertEquals(1,doc.getInt("frameCount"));assertEquals(24,doc.getInt("fps"));int curves=0;
            JSONArray layers=doc.getJSONArray("layers");for(int i=0;i<layers.length();i++){JSONArray objects=layers.getJSONObject(i).getJSONArray("objects");for(int j=0;j<objects.length();j++){JSONObject object=objects.getJSONObject(j);assertFalse(object.has("reference"));assertFalse(object.has("paint"));assertNotEquals("image",object.getString("kind"));if(object.getString("kind").equals("path"))curves++;}}
            assertTrue("Cel must contain actual editable drawn curves",curves>40);
        }
    }
}
