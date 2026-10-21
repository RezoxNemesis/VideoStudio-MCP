package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

/** Parameter-level proof only; final Filament GPU illumination needs device QA. */
public final class CinematicStudioLightingTest {
    @Test public void physicallyBoundedLightingPreservesOriginalMaterials(){
        CinematicStudioLighting.Light[] lights=CinematicStudioLighting.threePoint();
        assertEquals(3,lights.length);
        int shadowCasters=0;boolean coolFill=false,warmRim=false;
        for(CinematicStudioLighting.Light light:lights){
            assertTrue(light.intensity>=1000);
            assertTrue(light.intensity<=130000);
            assertTrue(Float.isFinite(light.dx));
            assertTrue(Math.hypot(Math.hypot(light.dx,light.dy),light.dz)>.5);
            if(light.shadow)shadowCasters++;
            if(light.b>light.r)coolFill=true;
            if(light.r>light.b)warmRim=true;
        }
        assertEquals("Only one shadow map is required on the phone",1,shadowCasters);
        assertTrue(coolFill);
        assertTrue(warmRim);
        assertTrue("Key should be brightest",lights[0].intensity>lights[1].intensity);
    }
    @Test public void invalidOrNonfiniteLightingRejectsRatherThanOverheats(){
        try{new CinematicStudioLighting.Light(1,1,1,Float.POSITIVE_INFINITY,0,-1,0,false);fail();}
        catch(IllegalArgumentException expected){}
        try{new CinematicStudioLighting.Light(1,1,1,145000,0,-1,0,true);fail();}
        catch(IllegalArgumentException expected){}
        try{new CinematicStudioLighting.Light(1,1,1,10000,0,0,0,true);fail();}
        catch(IllegalArgumentException expected){}
    }
}
