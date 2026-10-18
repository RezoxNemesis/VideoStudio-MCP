package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

/** Geographic acceptance tests: no flat spinning disc, seam, pole flip or false daylight. */
public final class EarthSphereProjectionTest {
    @Test public void centerOfDiscIsCorrectLongitudeAndEquator() {
        EarthSphereProjection.Point p = EarthSphereProjection.project(200, 100, 200, 100, 90, 23.44, 25);
        assertTrue(p.visible);
        assertEquals(25, p.longitudeDeg, 1e-8);
        assertEquals(0, p.latitudeDeg, 1e-8);
        assertEquals((25 + 180.0) / 360.0, p.texU, 1e-8);
        assertEquals(.5, p.texV, 1e-8);
    }

    @Test public void farSideIsHiddenAndAntarcticaIsNotMirrored() {
        assertFalse(EarthSphereProjection.project(400, 100, 200, 100, 90, 0, 0).visible);
        assertTrue(EarthSphereProjection.project(200, 181, 200, 100, 90, 0, 0).latitudeDeg < 0);
        assertTrue(EarthSphereProjection.project(200, 19, 200, 100, 90, 0, 0).latitudeDeg > 0);
    }

    @Test public void geographicSurfaceRotatesAcrossFrames() {
        double a=EarthSphereProjection.project(250, 130, 250, 130, 100, 23.44, 25).texU;
        double b=EarthSphereProjection.project(250, 130, 250, 130, 100, 23.44, 145).texU;
        assertEquals(1.0/3.0, b-a, 1e-9);
    }

    @Test public void textureSeamWrapsWithoutBlackBorder() {
        int[] pixels = { 0xff000000,0xff808080,0xffffffff,0xffff0000,
                         0xff000000,0xff808080,0xffffffff,0xffff0000 };
        assertEquals(EarthSphereProjection.bilinear(pixels,4,2,0,0.5),
                     EarthSphereProjection.bilinear(pixels,4,2,1,0.5));
    }

    @Test public void fixedSunProducesNightSideAndDaySide() {
        EarthSphereProjection.Point near = EarthSphereProjection.project(100,100,100,100,90,0,0);
        assertEquals(1.0,EarthSphereProjection.daylight(EarthSphereProjection.sunCosine(near,0,0,1)),1e-9);
        EarthSphereProjection.Point edge = EarthSphereProjection.project(190,100,100,100,90,0,0);
        assertEquals(.5, EarthSphereProjection.daylight(EarthSphereProjection.sunCosine(edge,0,1,0)),1e-9);
    }

    @Test public void poleLatitudeAndUVRemainBounded() {
        for(int i=0;i<361;i++){
            EarthSphereProjection.Point p=EarthSphereProjection.project(50+40*Math.cos(i*Math.PI/180),
               50+40*Math.sin(i*Math.PI/180),50,50,40,23.44,179);
            assertTrue(p.visible);
            assertTrue(p.texU>=0&&p.texU<1);
            assertTrue(p.texV>=0&&p.texV<=1);
        }
    }
}
