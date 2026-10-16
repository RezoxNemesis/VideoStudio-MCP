package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;

/** Movie subjects must match real story nouns instead of always inserting a stock person. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchStorySubjectsTest {
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void exactStoryNounsDetermineEditableSubject(){
        assertEquals("dragon",SketchStoryFilmGenerator.subject("A dragon flies over the jungle"));
        assertEquals("car",SketchStoryFilmGenerator.subject("The greatest car race begins"));
        assertEquals("car",SketchStoryFilmGenerator.subject("Two racers race through the city"));
        assertEquals("person",SketchStoryFilmGenerator.subject("A man struggles with his career"));
        assertEquals("struggle",SketchStoryFilmGenerator.classify("The career of a determined man"));
    }
    @Test public void carRacingDrawsMovingWheelsAndARealBodyRatherThanAnUnrelatedHuman()throws Exception{
        JSONObject film=SketchStoryFilmGenerator.build("car_film","Car race: the final lap before victory",10,24);
        assertEquals(240,film.getInt("frameCount"));
        SketchDocument.validate(film);
        assertEquals("polygon",SketchDocument.object(film,"car_body_0").getString("kind"));
        JSONObject rim=SketchDocument.object(film,"car_rim_0_0");
        double turn=SketchDocument.transformAt(rim,78).getDouble("rotation")
                -SketchDocument.transformAt(rim,0).getDouble("rotation");
        assertTrue("Wheel has authored rotation",Math.abs(turn)>100);
        assertNull("Car shot may not contain unrelated human face",lookUp(film,"face_0"));
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,film)){
            Bitmap beginning=renderer.render(4),later=renderer.render(40);
            try{
                assertNotEquals("Moving race wheels change the native pixels",sample(beginning),sample(later));
            }finally{beginning.recycle();later.recycle();}
        }
    }
    @Test public void dragonHasIndependentWingAndTailFlapsAndReadableViewpoint()throws Exception{
        JSONObject film=SketchStoryFilmGenerator.build("dragon_film","A dragon flies across an ancient forest",10,24);
        SketchDocument.validate(film);
        JSONObject wing=SketchDocument.object(film,"dragon_wing_front_0"),tail=SketchDocument.object(film,"dragon_tail_0");
        assertTrue("Wing flap is animated instead of sliding the entire picture",
                Math.abs(SketchDocument.transformAt(wing,0).getDouble("rotation")
                        -SketchDocument.transformAt(wing,20).getDouble("rotation"))>5);
        assertTrue("Tail moves independently",
                Math.abs(SketchDocument.transformAt(tail,0).getDouble("rotation")
                        -SketchDocument.transformAt(tail,25).getDouble("rotation"))>2);
        assertNotNull(SketchDocument.object(film,"dragon_eye_2"));
        assertNull(lookUp(film,"face_0"));
        try(SketchFrameRenderer renderer=new SketchFrameRenderer(context,film)){
            Bitmap early=renderer.render(0),later=renderer.render(22);
            try{assertNotEquals(sample(early),sample(later));}
            finally{early.recycle();later.recycle();}
        }
    }
    private static JSONObject lookUp(JSONObject film,String id){
        try{return SketchDocument.object(film,id);}catch(IllegalArgumentException expected){return null;}
        catch(Exception invalid){throw new AssertionError(invalid);}
    }
    private static int sample(Bitmap bitmap){
        int hash=1;
        for(int y=12;y<bitmap.getHeight();y+=27)
            for(int x=15;x<bitmap.getWidth();x+=35)
                hash=hash*31+bitmap.getPixel(x,y);
        return hash;
    }
}
