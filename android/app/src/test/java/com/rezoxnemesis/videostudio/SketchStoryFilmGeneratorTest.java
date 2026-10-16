package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.util.HashSet;
import static org.junit.Assert.*;

/** The native source format, timeline and actual Canvas must agree, not a mocked HTML preview. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchStoryFilmGeneratorTest {
    private final Context context=RuntimeEnvironment.getApplication();

    private JSONObject build(String story)throws Exception {
        return SketchStoryFilmGenerator.build("story_test_"+Math.abs(story.hashCode()),story,10,24);
    }
    @Test public void textPromptCreatesThreeDistinctEditableShotsWithNoBlankFrame()throws Exception{
        JSONObject document=build("A man struggles through the rain, falls, and finds the strength to stand");
        SketchDocument.validate(document);
        assertEquals(240,document.getInt("frameCount"));assertEquals(24,document.getInt("fps"));
        assertEquals(12,document.getJSONArray("layers").length());
        HashSet<String> ids=new HashSet<>(),colours=new HashSet<>(),kinds=new HashSet<>();
        int[] counts=new int[240];int movingObjects=0,gradientCount=0;
        JSONArray layers=document.getJSONArray("layers");
        for(int i=0;i<layers.length();i++){
            JSONObject layer=layers.getJSONObject(i);
            JSONArray objects=layer.getJSONArray("objects");
            assertTrue("Every stage has real vector objects",objects.length()>1);
            for(int n=0;n<objects.length();n++){
                JSONObject object=objects.getJSONObject(n);
                assertTrue("Stable unique editable object IDs",ids.add(object.getString("id")));
                colours.add(object.getString("color"));kinds.add(object.getString("kind"));
                if(object.has("gradient"))gradientCount++;
                if(object.getJSONArray("keys").length()>1)movingObjects++;
                int from=object.getInt("frame"),to=from+object.getInt("exposure");
                for(int f=from;f<to;f++)counts[f]++;
                assertNotEquals("Video frames cannot embed a flattened picture","image",object.getString("kind"));
            }
        }
        assertTrue("Background and character should have many distinct palette colours",colours.size()>9);
        assertTrue("Independently articulated body and rain elements",movingObjects>65);
        assertTrue("Layered colour shading is present",gradientCount>10);
        assertTrue(kinds.contains("polygon"));assertTrue(kinds.contains("ellipse"));
        assertTrue(kinds.contains("text"));
        for(int f=0;f<240;f++)assertTrue("Frame "+f+" loses its art",counts[f]>15);
    }
    @Test public void actionMovesMultipleIndependentPartsRatherThanOnlyCamera()throws Exception{
        JSONObject document=build("A man struggles to survive and then gets up");
        JSONObject left=SketchDocument.object(document,"arm_left_1"),
                right=SketchDocument.object(document,"arm_right_1"),
                head=SketchDocument.object(document,"face_1");
        double leftEarly=SketchDocument.transformAt(left,80).getDouble("rotation");
        double leftLate=SketchDocument.transformAt(left,158).getDouble("rotation");
        double rightEarly=SketchDocument.transformAt(right,80).getDouble("rotation");
        double rightLate=SketchDocument.transformAt(right,158).getDouble("rotation");
        assertTrue(Math.abs(leftLate-leftEarly)>40);
        assertTrue(Math.abs(rightLate-rightEarly)>30);
        assertTrue(Math.abs(SketchDocument.transformAt(head,150).getDouble("y")-
                    SketchDocument.transformAt(head,82).getDouble("y"))>.04);
        assertNotEquals(SketchDocument.transformAt(left,100).getDouble("rotation"),
                        SketchDocument.transformAt(right,100).getDouble("rotation"),.1);
    }
    @Test public void differentStoriesAdaptGenreAndRetainActualFrameTimings()throws Exception{
        assertEquals("race",SketchStoryFilmGenerator.classify("A car chase on the highway"));
        assertEquals("nature",SketchStoryFilmGenerator.classify("Adventure in a deep forest"));
        assertEquals("connection",SketchStoryFilmGenerator.classify("Two friends find love"));
        for(String text:new String[]{"A driver pushes his car through the final race","Two friends find a place to call home","A dragon crosses a green jungle"}){
            JSONObject film=SketchStoryFilmGenerator.build("genre_"+Math.abs(text.hashCode()),text,4,30);
            SketchDocument.validate(film);assertEquals(120,film.getInt("frameCount"));
            assertEquals(30,film.getInt("fps"));
            assertNotNull(SketchDocument.object(film,"caption_2"));
        }
    }
    @Test public void invalidInputFailsBeforeAnyOwnedProjectIsCreated()throws Exception{
        try{SketchStoryFilmGenerator.build("valid_id","a",10,24);fail();}catch(IllegalArgumentException expected){}
        try{SketchStoryFilmGenerator.build("valid_id","Reasonable original topic",90,24);fail();}catch(IllegalArgumentException expected){}
        try{SketchStoryFilmGenerator.build("valid_id","Reasonable original topic",10,23);fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void nativeCanvasRendersActualDistinctFramesAndCutWithOriginalArtwork()throws Exception{
        JSONObject film=build("The struggle of a man");
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,film)){
            Bitmap first=renderer.render(0),fall=renderer.render(116),recovery=renderer.render(232);
            try{
                assertEquals(1280,first.getWidth());assertEquals(720,first.getHeight());
                assertTrue("Actual Canvas changed shot palettes",first.getPixel(20,40)!=recovery.getPixel(20,40));
                assertTrue("The different shots render native Canvas pixels",first.getWidth()==fall.getWidth()&&fall.getHeight()==recovery.getHeight());
                int a=sampleSignature(first),b=sampleSignature(fall),c=sampleSignature(recovery);
                assertNotEquals(a,b);assertNotEquals(b,c);
                assertEquals(255,Color.alpha(first.getPixel(50,30)));
            }finally{first.recycle();fall.recycle();recovery.recycle();}
        }
    }
    private static int sampleSignature(Bitmap image){
        int hash=1;
        for(int y=23;y<image.getHeight();y+=57)for(int x=17;x<image.getWidth();x+=83)
            hash=hash*31+image.getPixel(x,y);
        return hash;
    }
    @Test public void finishedFilmAndLiveOriginalDrawingProduceMatchingOwnedCels()throws Exception{
        String script="A man walks alone through dark rain and rises at sunrise";
        JSONObject plain=SketchStoryFilmGenerator.create(context,script,4,24,false,null);
        assertFalse(plain.getBoolean("live"));
        final int[] updates={0},opened={0};
        JSONObject replay=SketchStoryFilmGenerator.create(context,script,4,24,true,
                new SketchStoryFilmGenerator.Listener(){
                    public void opened(String id,String session){opened[0]++;}
                    public void progress(int count,int total){assertTrue(count<=total);updates[0]++;}
                });
        assertEquals(1,opened[0]);assertTrue(updates[0]>6);assertTrue(replay.getBoolean("live"));
        assertNotEquals(plain.getString("sketchId"),replay.getString("sketchId"));
        try(SketchProjectStore store=new SketchProjectStore(context)){
            JSONObject original=store.get(plain.getString("sketchId")),streamed=store.get(replay.getString("sketchId"));
            SketchDocument.validate(original);SketchDocument.validate(streamed);
            assertEquals(original.getInt("frameCount"),streamed.getInt("frameCount"));
            assertEquals(original.getJSONArray("layers").length(),streamed.getJSONArray("layers").length());
            JSONArray one=original.getJSONArray("layers"),two=streamed.getJSONArray("layers");
            for(int i=0;i<one.length();i++)
                assertEquals("No part is lost across live source events",one.getJSONObject(i).getJSONArray("objects").length(),
                        two.getJSONObject(i).getJSONArray("objects").length());
            JSONObject session=store.liveSession(replay.getString("sketchId"),replay.getString("sessionId"));
            assertTrue("An ended journal can still be replayed",session.getBoolean("ended"));
            assertTrue(store.latestSequence(replay.getString("sketchId"))>0);
        }
    }
}
