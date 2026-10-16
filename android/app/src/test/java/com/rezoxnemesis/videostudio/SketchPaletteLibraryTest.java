package com.rezoxnemesis.videostudio;

import org.junit.Test;
import org.json.JSONObject;
import java.util.HashSet;
import static org.junit.Assert.*;

/** Palette data is finite, duplicate-free per category and suitable for persisted native pigment fields. */
public final class SketchPaletteLibraryTest {
    @Test public void providesRichVerifiedPaletteForDetailedSketchPainting()throws Exception{
        assertEquals(11,SketchPaletteLibrary.groupCount());int count=0;
        HashSet<String> all=new HashSet<>();
        for(int i=0;i<SketchPaletteLibrary.groupCount();i++){
            assertNotNull(SketchPaletteLibrary.name(i));
            String[] colors=SketchPaletteLibrary.colors(i);
            assertEquals(16,colors.length);
            HashSet<String> group=new HashSet<>();
            for(String c:colors){
                assertTrue(c.matches("#[0-9A-F]{8}"));
                assertEquals(255,(SketchDocument.color(c)>>>24)&255);
                assertTrue("A palette must not repeat its own pigment",group.add(c));
                all.add(c);count++;
            }
        }
        assertEquals(176,count);
        assertTrue("Sketch colours must have many distinct tones",all.size()>160);
    }
    @Test public void groupsAreImmutableAndNearestColorIsDeterministic(){
        String[] source=SketchPaletteLibrary.colors(4),previous=source.clone();
        source[0]="#00000000";
        assertArrayEquals(previous,SketchPaletteLibrary.colors(4));
        assertEquals("#FF060A14",SketchPaletteLibrary.nearest("#FF060A14",previous));
        String nearest=SketchPaletteLibrary.nearest("#FF121A27",previous);
        assertTrue(java.util.Arrays.asList(previous).contains(nearest));
        try{SketchPaletteLibrary.colors(20);fail();}catch(IllegalArgumentException expected){}
        try{SketchPaletteLibrary.nearest("#FFFFFFFF",new String[0]);fail();}catch(IllegalArgumentException expected){}
    }
}
