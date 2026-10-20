package com.rezoxnemesis.videostudio;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

/** Fixed synthetic frames only. They test signals and compositing math, not film quality. */
public class OmegaVisualAuditTest {
    private static final int OPAQUE_BLACK = 0xff000000;
    private static final int OPAQUE_WHITE = 0xffffffff;
    private static OmegaVisualAudit.Rect r(int x0,int y0,int x1,int y1,int w,int h) {
        return new OmegaVisualAudit.Rect(x0,y0,x1,y1,w,h);
    }
    private static OmegaVisualAudit.Layer layer(String id,int w,int h,int[] p,double depth,
                                                double x,double y,double opacity,double grain,
                                                int seed,boolean shadow) {
        return new OmegaVisualAudit.Layer(id,w,h,p,depth,x,y,opacity,grain,1,seed,shadow);
    }
    private static int[] translated(int[] source,int w,int h,int dx,int dy) {
        int[] target = new int[source.length];
        for(int y=0;y<h;y++) for(int x=0;x<w;x++) {
            int tx=x+dx,ty=y+dy;
            if(tx>=0&&tx<w&&ty>=0&&ty<h)target[ty*w+tx]=source[y*w+x];
        }
        return target;
    }
    private static int[] texture(int w,int h) {
        int[] pixels=new int[w*h];
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            int n=(x*71+y*117+x*y*23) & 255;
            pixels[y*w+x]=0xff000000 | (n<<16) | (((n*31)&255)<<8) | ((n*73)&255);
        }
        return pixels;
    }
    private static void rejects(Runnable f) {
        try { f.run(); fail("Expected invalid input to fail closed"); }
        catch (IllegalArgumentException expected) { /* correct */ }
    }

    @Test public void keepsLegacyRegionMeasurementContract() {
        int[] a=new int[64],b=new int[64];
        b[3+3*8]=0x00ffffff;b[4+3*8]=0x00ffffff;
        OmegaVisualAudit.Evidence e=OmegaVisualAudit.measure(a,b,8,8,r(3,3,5,5,8,8),100);
        assertEquals(4,e.subjectPixels);assertEquals(2,e.subjectChanged);
        assertEquals(.5,e.subjectChangeRatio(),1e-10);
        assertEquals(0,e.backgroundChanged);assertFalse(e.backgroundDominated());
    }
    @Test public void flagsBackgroundChangeWithStaticSubjectAndIdenticalFrames() {
        int[] a=new int[100],b=new int[100]; Arrays.fill(b,OPAQUE_WHITE);
        for(int y=4;y<6;y++)for(int x=4;x<6;x++)b[x+y*10]=0;
        OmegaVisualAudit.Evidence e=OmegaVisualAudit.measure(a,b,10,10,r(4,4,6,6,10,10),100);
        assertTrue(e.staticSubject()); assertTrue(e.backgroundDominated());
        assertEquals(96,e.backgroundChanged);
        assertEquals(0,OmegaVisualAudit.measure(a,a,10,10,r(4,4,6,6,10,10),100).subjectChanged);
    }
    @Test public void cameraOnlyMovementGetsStabilizedAndWarning() {
        int w=64,h=64;
        int[] a=texture(w,h), b=translated(a,w,h,3,2);
        OmegaVisualAudit.Motion m=OmegaVisualAudit.measureMotion(a,b,w,h,r(20,20,40,40,w,h),50,4);
        assertTrue("textured camera registration",m.camera.reliable);
        assertEquals(3,m.camera.dx);assertEquals(2,m.camera.dy);
        assertTrue(m.raw.subjectChangeRatio()>.5);
        assertEquals(0,m.stabilized.subjectChanged);
        assertTrue("camera-only evidence requires native review",m.cameraOnlyCandidate);
    }
    @Test public void independentSubjectChangeRemainsAfterCameraStabilization() {
        int w=64,h=64;
        int[] a=texture(w,h),b=translated(a,w,h,-2,1);
        for(int y=23;y<31;y++)for(int x=23;x<31;x++)b[(y+1)*w+(x-2)]=OPAQUE_WHITE;
        OmegaVisualAudit.Motion m=OmegaVisualAudit.measureMotion(a,b,w,h,r(20,20,40,40,w,h),50,3);
        assertTrue(m.camera.reliable); assertEquals(-2,m.camera.dx); assertEquals(1,m.camera.dy);
        assertTrue(m.stabilized.subjectChangeRatio()>.03);
        assertFalse(m.cameraOnlyCandidate);
    }
    @Test public void cannotRegisterFeaturelessBackground() {
        int[] blank=new int[64*64],b=translated(blank,64,64,2,2);
        OmegaVisualAudit.Motion m=OmegaVisualAudit.measureMotion(blank,b,64,64,r(20,20,40,40,64,64),20,4);
        assertFalse(m.camera.reliable);assertFalse(m.cameraOnlyCandidate);
        assertEquals(0,m.camera.textureSamples);
    }
    @Test public void separateAuthoredRegionsExposeParallaxCandidate() {
        int w=80,h=60;int[] a=new int[w*h],b=new int[w*h];
        for(int y=10;y<45;y++)for(int x=10;x<29;x++) {
            int v=(x*91+y*53+x*y*7)&255;
            a[y*w+x]=0xff000000|(v<<16)|(((v*3)&255)<<8)|((v*7)&255);
            b[y*w+x+3]=a[y*w+x];
        }
        for(int y=10;y<45;y++)for(int x=47;x<67;x++) {
            int v=(x*59+y*67+x*y*11)&255;
            a[y*w+x]=0xff000000|(v<<16)|(((v*5)&255)<<8)|((v*7)&255);
            b[y*w+x+1]=a[y*w+x];
        }
        OmegaVisualAudit.Parallax p=OmegaVisualAudit.measureParallax(
            a,b,w,h,r(12,12,26,42,w,h),r(49,12,64,42,w,h),4);
        assertTrue("near texture provides shift",p.near.reliable);
        assertTrue("far texture provides shift",p.far.reliable);
        assertEquals(3,p.near.dx);assertEquals(1,p.far.dx);
        assertTrue(p.depthDifferentialCandidate);
    }
    @Test public void technicalAppearanceShowsFlatVsTextured() {
        int w=32,h=32;int[] solid=new int[w*h],checker=new int[w*h];
        Arrays.fill(solid,OPAQUE_BLACK);
        for(int y=0;y<h;y++)for(int x=0;x<w;x++)checker[y*w+x]=((x+y)%2==0)?OPAQUE_BLACK:OPAQUE_WHITE;
        OmegaVisualAudit.Appearance flat=OmegaVisualAudit.appearance(solid,w,h,r(0,0,w,h,w,h));
        OmegaVisualAudit.Appearance detail=OmegaVisualAudit.appearance(checker,w,h,r(0,0,w,h,w,h));
        assertEquals(0,flat.edgeFraction,0);assertEquals(0,flat.contrast,0);
        assertTrue(detail.edgeFraction>.99);assertTrue(detail.contrast>.4);
        assertEquals(.5,detail.blackFraction,1e-10);
        assertEquals(.5,detail.whiteFraction,1e-10);
    }
    @Test public void alphaLayersHaveRealSubpixelOffsetsAndPreserveOriginals() {
        int w=24,h=24;int[] red=new int[w*h],blue=new int[w*h];
        red[10*w+10]=0xffff0000;blue[10*w+10]=0xff0000ff;
        OmegaVisualAudit.Layer back=layer("back",w,h,red,0,0,0,1,0,1,false);
        OmegaVisualAudit.Layer front=layer("front",w,h,blue,1,0,0,.5,0,2,false);
        red[10*w+10]=0;blue[10*w+10]=0;
        int[] composited=OmegaVisualAudit.compose(w,h,OPAQUE_BLACK,new OmegaVisualAudit.Layer[]{back,front},OmegaVisualAudit.Look.neutral());
        assertEquals(0xff7f0080,composited[10*w+10]);
        assertEquals(OPAQUE_BLACK,composited[0]);
        assertEquals(0xffff0000,OmegaVisualAudit.compose(w,h,OPAQUE_BLACK,
            new OmegaVisualAudit.Layer[]{back},OmegaVisualAudit.Look.neutral())[10*w+10]);
    }
    @Test public void depthProducesDifferentPixelDisplacementsOnCameraMove() {
        int w=40,h=32;int[] back=new int[w*h],front=new int[w*h];
        back[10*w+10]=0xff00ff00;
        front[20*w+30]=0xffff0000;
        OmegaVisualAudit.Layer far=layer("far",w,h,back,.25,0,0,1,0,1,false);
        OmegaVisualAudit.Layer near=layer("near",w,h,front,1,0,0,1,0,2,false);
        OmegaVisualAudit.Look pan=new OmegaVisualAudit.Look(.1,0,1,0,.5,.5,.6,0,0,0);
        int[] a=OmegaVisualAudit.compose(w,h,OPAQUE_BLACK,new OmegaVisualAudit.Layer[]{far,near},pan);
        assertEquals(0xff00ff00,a[10*w+9]); // far moved by one pixel
        assertEquals(0xffff0000,a[20*w+26]); // near moved by four pixels
        assertEquals(OPAQUE_BLACK,a[20*w+30]);
    }
    @Test public void keyLightAndMaterialGrainAreDeterministicAndCameraAnchored() {
        int w=32,h=32;int[] px=new int[w*h];Arrays.fill(px,0xff606060);
        OmegaVisualAudit.Layer layer=layer("matte",w,h,px,1,0,0,1,.08,307,false);
        OmegaVisualAudit.Look neutral=OmegaVisualAudit.Look.neutral();
        int[] first=OmegaVisualAudit.compose(w,h,OPAQUE_BLACK,new OmegaVisualAudit.Layer[]{layer},neutral);
        int[] again=OmegaVisualAudit.compose(w,h,OPAQUE_BLACK,new OmegaVisualAudit.Layer[]{layer},neutral);
        assertArrayEquals(first,again);
        OmegaVisualAudit.Look pan=new OmegaVisualAudit.Look(1.0/w,0,1,0,.5,.5,.75,0,0,0);
        int[] second=OmegaVisualAudit.compose(w,h,OPAQUE_BLACK,new OmegaVisualAudit.Layer[]{layer},pan);
        assertEquals("grain belongs to artwork, not screen",first[16*w+16],second[16*w+15]);
        OmegaVisualAudit.Look light=new OmegaVisualAudit.Look(0,0,.6,.8,.5,.5,.2,0,0,0);
        int[] lit=OmegaVisualAudit.compose(w,h,OPAQUE_BLACK,new OmegaVisualAudit.Layer[]{layer},light);
        int center=lit[16*w+16]&255,corner=lit[1*w+1]&255;
        assertTrue("authored radial light has gradient",center>corner);
    }
    @Test public void authoredDropShadowSoftensBehindForeground() {
        int w=16,h=16;int[] art=new int[w*h];art[7*w+7]=OPAQUE_WHITE;
        OmegaVisualAudit.Layer layer=layer("shadowed",w,h,art,0,0,0,1,0,1,true);
        OmegaVisualAudit.Look look=new OmegaVisualAudit.Look(0,0,1,0,.5,.5,.6,1,3,0);
        int[] out=OmegaVisualAudit.compose(w,h,0xff999999,new OmegaVisualAudit.Layer[]{layer},look);
        assertEquals(OPAQUE_WHITE,out[7*w+7]);
        assertTrue((out[7*w+10]&255)<153);
        assertEquals(0xff999999,out[0]);
    }
    @Test public void premultipliedSamplingKeepsFeatheredAlphaEdgesLight() {
        int w=16,h=16;int[] art=new int[w*h];art[8*w+8]=OPAQUE_WHITE;
        OmegaVisualAudit.Layer halfPixel=layer("soft",w,h,art,0,.5/w,0,1,0,1,false);
        int[] frame=OmegaVisualAudit.compose(w,h,OPAQUE_BLACK,
                new OmegaVisualAudit.Layer[]{halfPixel},OmegaVisualAudit.Look.neutral());
        assertEquals(0xff808080,frame[8*w+8]);
        assertEquals(0xff808080,frame[8*w+9]);
        assertEquals(OPAQUE_BLACK,frame[8*w+10]);
    }
    @Test public void oversizedRasterInputBudgetFailsBeforeCompositing() {
        int w=1500,h=1000;int[] frame=new int[w*h];
        OmegaVisualAudit.Layer one=layer("one",w,h,frame,0,0,0,1,0,1,false);
        OmegaVisualAudit.Layer two=layer("two",w,h,frame,.5,0,0,1,0,2,false);
        OmegaVisualAudit.Layer three=layer("three",w,h,frame,1,0,0,1,0,3,false);
        rejects(()->OmegaVisualAudit.compose(w,h,OPAQUE_BLACK,
                new OmegaVisualAudit.Layer[]{one,two,three},OmegaVisualAudit.Look.neutral()));
    }
    @Test public void frameAnchorsUseOneNativeClockAndFiveDistinctPositions() {
        assertArrayEquals(new int[]{0,1,2,3,4},OmegaVisualAudit.anchorFrames(5));
        assertArrayEquals(new int[]{0,299,599,899,1199},OmegaVisualAudit.anchorFrames(1200));
        assertArrayEquals(new long[]{0,125000,250000,375000,500000},
            OmegaVisualAudit.anchorTimesUs(13,24));
    }
    @Test public void invalidBoundsArraysNumericsOwnershipAndRegistrationFailClosed() {
        int[] a=new int[64];
        rejects(()->r(0,0,0,1,8,8));
        rejects(()->r(0,0,1,1,1,8));
        rejects(()->OmegaVisualAudit.measure(a,a,8,8,r(1,1,3,3,8,8),766));
        rejects(()->OmegaVisualAudit.measure(a,a,8,8,r(1,1,3,3,9,8),1));
        rejects(()->OmegaVisualAudit.measure(a,new int[4],8,8,r(1,1,3,3,8,8),1));
        rejects(()->OmegaVisualAudit.measureMotion(a,a,8,8,r(1,1,3,3,8,8),1,9));
        rejects(()->OmegaVisualAudit.measureParallax(a,a,8,8,r(1,1,4,4,8,8),r(3,3,5,5,8,8),1));
        rejects(()->OmegaVisualAudit.anchorFrames(4));
        rejects(()->OmegaVisualAudit.anchorTimesUs(5,25));
        rejects(()->layer("bad id",8,8,a,0,0,0,1,0,0,false));
        rejects(()->layer("nan",8,8,a,Double.NaN,0,0,1,0,0,false));
        rejects(()->layer("inf",8,8,a,0,0,0,Double.POSITIVE_INFINITY,0,0,false));
        rejects(()->layer("grain",8,8,a,0,0,0,1,.2,0,false));
        rejects(()->new OmegaVisualAudit.Look(0,0,1,0,.5,.5,.75,0,17,0));
        rejects(()->new OmegaVisualAudit.Look(0,0,1,0,.5,.5,Double.NaN,0,0,0));
        OmegaVisualAudit.Layer one=layer("same",8,8,a,0,0,0,1,0,0,false);
        rejects(()->OmegaVisualAudit.compose(8,8,OPAQUE_BLACK,new OmegaVisualAudit.Layer[]{one,one},OmegaVisualAudit.Look.neutral()));
        rejects(()->OmegaVisualAudit.compose(8,8,0x00000000,new OmegaVisualAudit.Layer[]{one},OmegaVisualAudit.Look.neutral()));
        rejects(()->OmegaVisualAudit.compose(8,8,OPAQUE_BLACK,new OmegaVisualAudit.Layer[]{one},null));
    }
}
