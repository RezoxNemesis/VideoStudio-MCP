package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/** Synthetic source material only; these fixtures do not certify actor anatomy. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class StoryboardPreviewMaterialRepairTest {
    private static final int W=96,H=160;
    private static final int BACKGROUND=0xffb59673;
    private static final int LEFT_MARKER=0xffdb3159,RIGHT_MARKER=0xff3ab96b;

    private static int[] scene(){
        int[] pixels=new int[W*H];Arrays.fill(pixels,BACKGROUND);return pixels;
    }
    private static void paint(int[] pixels,int left,int top,int width,int height,int color){
        for(int y=top;y<top+height;y++)for(int x=left;x<left+width;x++)
            pixels[y*W+x]=color;
    }
    private static boolean[] mask(int left,int top,int width,int height){
        boolean[] mask=new boolean[W*H];
        for(int y=top;y<top+height;y++)for(int x=left;x<left+width;x++)
            mask[y*W+x]=true;
        return mask;
    }
    private static StoryboardLayerIsolation.Result isolated(int[] first,int[] last,
                                                            boolean[] a,boolean[] b){
        return StoryboardLayerIsolation.fromMasks(first,last,W,H,
                Collections.singletonList(a),Collections.singletonList(b),.95f);
    }

    @Test public void ambiguousColorFloodCannotBecomeReadyWithCoarseDifferenceStrips(){
        int[] first=scene();
        // A small changed strip opens onto a much larger same-color scene area.
        paint(first,21,37,18,50,0xff171717);
        first[61*W+25]=first[61*W+34]=BACKGROUND;
        first[40*W+29]=first[83*W+29]=BACKGROUND;
        boolean[] partial=mask(28,44,4,36);
        StoryboardLayerIsolation.Result coarse=isolated(first,first.clone(),partial,partial);
        assertTrue(coarse.autoRenderable());
        StoryboardLayerIsolation.Result result=StoryboardColorMatteRefiner.refine(
                coarse,first,first.clone(),W,H);
        assertEquals("Ambiguous expansion needs authored source masks",
                StoryboardLayerIsolation.Status.NEEDS_SOURCE_MASKS,result.status);
        assertFalse(result.autoRenderable());
    }

    @Test public void oneUnresolvedCandidateCannotBeSilentlyDroppedFromRefinement(){
        int[] first=scene();paint(first,12,40,8,28,0xff191919);
        boolean[] dark=mask(12,40,8,28),unresolved=mask(67,44,8,28);
        StoryboardLayerIsolation.Result coarse=StoryboardLayerIsolation.fromMasks(
                first,first.clone(),W,H,Arrays.asList(dark,unresolved),
                Arrays.asList(dark,unresolved),.95f);
        StoryboardLayerIsolation.Result result=StoryboardColorMatteRefiner.refine(
                coarse,first,first.clone(),W,H);
        assertEquals("Accepted neighbors cannot conceal an uncertain proposal",
                StoryboardLayerIsolation.Status.NEEDS_SOURCE_MASKS,result.status);
        assertFalse(result.autoRenderable());
    }

    @Test public void disappearingForegroundProposalRequiresSourceMasks(){
        int[] first=scene(),last=scene();
        paint(first,6,48,10,40,0xff141217);
        paint(first,72,52,14,35,0xfff4f2eb);
        paint(last,65,52,14,35,0xfff4f2eb);
        StoryboardLayerIsolation.Result result=StoryboardLayerIsolation.analyse(first,last,W,H);
        assertEquals("Every proposed source region must have a destination match",
                StoryboardLayerIsolation.Status.NEEDS_SOURCE_MASKS,result.status);
        assertFalse(result.autoRenderable());
    }

    @Test public void oversizedDisappearingForegroundCannotBeDroppedBeforeMatching(){
        int[] first=scene(),last=scene();
        paint(first,6,24,40,110,0xff141217);
        paint(first,72,64,8,24,0xfff4f2eb);
        paint(last,63,64,8,24,0xfff4f2eb);
        StoryboardLayerIsolation.Result result=StoryboardLayerIsolation.analyse(first,last,W,H);
        assertEquals("A large unresolved foreground cannot be concealed by a smaller retained neighbor",
                StoryboardLayerIsolation.Status.NEEDS_SOURCE_MASKS,result.status);
        assertFalse(result.autoRenderable());
    }

    @Test public void thinNonNoiseForegroundCannotBeDroppedBeforeMatching(){
        int[] first=scene(),last=scene();
        paint(first,8,45,2,50,0xff141217);
        paint(first,72,64,8,24,0xfff4f2eb);
        paint(last,63,64,8,24,0xfff4f2eb);
        StoryboardLayerIsolation.Result result=StoryboardLayerIsolation.analyse(first,last,W,H);
        assertEquals("Thin visible source material requires a mask rather than omission",
                StoryboardLayerIsolation.Status.NEEDS_SOURCE_MASKS,result.status);
        assertFalse(result.autoRenderable());
    }

    @Test public void boundaryForegroundCannotBeErasedBeforeCandidateDiagnostics(){
        int[] first=scene(),last=scene();
        paint(first,0,45,2,50,0xff141217);
        paint(first,72,64,8,24,0xfff4f2eb);
        paint(last,63,64,8,24,0xfff4f2eb);
        StoryboardLayerIsolation.Result result=StoryboardLayerIsolation.analyse(first,last,W,H);
        assertEquals("Visible material on the source edge cannot vanish during mask closing",
                StoryboardLayerIsolation.Status.NEEDS_SOURCE_MASKS,result.status);
        assertFalse(result.autoRenderable());
    }

    @Test public void tooManyForegroundCandidatesCannotBeTruncatedIntoReady(){
        int[] first=scene(),last=scene();
        for(int index=0;index<9;index++){
            paint(first,4+index*10,40,6,6,0xff171717);
            paint(last,4+index*10,52,6,6,0xff171717);
        }
        StoryboardLayerIsolation.Result result=StoryboardLayerIsolation.analyse(first,last,W,H);
        assertEquals(StoryboardLayerIsolation.Status.TOO_COMPLEX,result.status);
        assertFalse(result.autoRenderable());
    }

    @Test public void boundedSourceDeformationRetainsBothOutwardMovingEdgeMaterials(){
        int[] frame=scene();paint(frame,16,16,64,128,0xff27344e);
        StoryboardLayerIsolation.Region region=isolated(frame,frame.clone(),
                mask(16,16,64,128),mask(16,16,64,128)).tracks.get(0).from;
        int[] pixels=new int[64*128];Arrays.fill(pixels,0xff27344e);
        for(int y=112;y<128;y++){
            pixels[y*64]=pixels[y*64+1]=LEFT_MARKER;
            pixels[y*64+62]=pixels[y*64+63]=RIGHT_MARKER;
        }
        Bitmap source=Bitmap.createBitmap(pixels,64,128,Bitmap.Config.ARGB_8888);
        StoryboardDeformableSprite deformation=new StoryboardDeformableSprite(
                region,region,W,H,true,StoryboardMotionGrammar.Action.RUN,true);
        Bitmap warped=null;
        try{
            assertSame(source,deformation.deformBitmap(source,0,false,1));
            assertSame(source,deformation.deformBitmap(source,1,true,1));
            warped=deformation.deformBitmap(source,.75f,false,1);
            assertNotSame(source,warped);
            assertTrue("Left edge paint cannot be discarded by the original crop",
                    count(warped,LEFT_MARKER)>=24);
            assertTrue("Right edge paint cannot be discarded by the original crop",
                    count(warped,RIGHT_MARKER)>=24);
            assertTrue("Outward nonzero deformation needs room beyond the crop",
                    warped.getWidth()>source.getWidth());
            assertTrue("The diagnostic remains bounded",(long)warped.getWidth()*warped.getHeight()<4L*W*H);
            int[] unchanged=new int[pixels.length];
            source.getPixels(unchanged,0,64,0,0,64,128);
            assertArrayEquals("Caller source pixels remain unchanged",pixels,unchanged);
        }finally{
            if(warped!=null&&warped!=source)warped.recycle();
            source.recycle();
        }
    }

    @Test public void smallRemainingPatchCannotPassMaterialRetention(){
        int[] first=scene(),last=scene(),middle=scene();
        paint(first,20,40,20,60,0xff15151a);
        paint(last,40,40,20,60,0xff15151a);
        paint(middle,30,80,20,20,0xff15151a);
        StoryboardLayerIsolation.Result tracked=isolated(first,last,
                mask(20,40,20,60),mask(40,40,20,60));
        StoryboardSubjectRetentionGate.Report report=StoryboardSubjectRetentionGate.inspect(
                tracked,first,last,middle,W,H);
        assertFalse("One third of the measured mask is insufficient material retention",report.accepted);
        assertEquals("SOURCE_MASK_MATERIAL_ONLY",report.diagnosticScope);
        assertFalse("Material coverage cannot certify complete anatomy",report.anatomicalCoverageCertified);
    }

    @Test public void expandedMeshKeepsContinuousOpaqueMaterialAndOriginalCropCoordinates(){
        int[] frame=scene();paint(frame,16,16,64,128,0xff27344e);
        StoryboardLayerIsolation.Region region=isolated(frame,frame.clone(),
                mask(16,16,64,128),mask(16,16,64,128)).tracks.get(0).from;
        int[] pixels=new int[64*128];Arrays.fill(pixels,0xff27344e);
        Bitmap source=Bitmap.createBitmap(pixels,64,128,Bitmap.Config.ARGB_8888);
        StoryboardDeformableSprite deformation=new StoryboardDeformableSprite(
                region,region,W,H,true,StoryboardMotionGrammar.Action.RUN,true);
        try{
            for(float phase:new float[]{.25f,.75f}){
                StoryboardDeformableSprite.MaterialFrame material=deformation.deformMaterial(
                        source,phase,false,1);
                try{
                    assertEquals(64,material.originalWidth);assertEquals(128,material.originalHeight);
                    assertTrue(material.originX<0);assertTrue(material.originY<0);
                    int opaque=0;
                    for(int y=0;y<material.bitmap.getHeight();y++){
                        int left=-1,right=-1;
                        for(int x=0;x<material.bitmap.getWidth();x++){
                            if((material.bitmap.getPixel(x,y)>>>24)==0)continue;
                            if(left<0)left=x;right=x;opaque++;
                        }
                        if(left>=0)for(int x=left;x<=right;x++)
                            assertEquals("A continuous opaque source cannot develop mesh holes",255,
                                    material.bitmap.getPixel(x,y)>>>24);
                    }
                    assertTrue("Nonzero bounded deformation retains the material surface",opaque>7000);
                }finally{if(material.bitmap!=source)material.bitmap.recycle();}
            }
        }finally{source.recycle();}
    }

    @Test public void meshRetainsSourceTransparencyAndAntialiasedPaintWithoutInventingColors(){
        int[] frame=scene();paint(frame,16,16,64,128,0xff27344e);
        boolean[] body=mask(16,16,64,128);
        int[] pixels=new int[64*128];Arrays.fill(pixels,0xff27344e);
        for(int y=54;y<78;y++)for(int x=26;x<38;x++){
            pixels[y*64+x]=0;body[(16+y)*W+16+x]=false;
            frame[(16+y)*W+16+x]=BACKGROUND;
        }
        for(int y=112;y<128;y++)pixels[y*64]=pixels[y*64+1]=0x80db3159;
        StoryboardLayerIsolation.Region region=isolated(frame,frame.clone(),body,body).tracks.get(0).from;
        Bitmap source=Bitmap.createBitmap(pixels,64,128,Bitmap.Config.ARGB_8888);
        StoryboardDeformableSprite deformation=new StoryboardDeformableSprite(
                region,region,W,H,true,StoryboardMotionGrammar.Action.RUN,true);
        StoryboardDeformableSprite.MaterialFrame material=null;
        try{
            int[] original=new int[pixels.length];source.getPixels(original,0,64,0,0,64,128);
            Set<Integer> palette=new HashSet<>();for(int color:original)palette.add(color);
            material=deformation.deformMaterial(source,.75f,false,1);
            int antialiased=0;
            for(int y=0;y<material.bitmap.getHeight();y++)for(int x=0;x<material.bitmap.getWidth();x++){
                int color=material.bitmap.getPixel(x,y);
                assertTrue("Mesh pixels must belong to the actual native source palette",palette.contains(color));
                if((color>>>24)==128)antialiased++;
            }
            assertTrue("Outward antialiased source material survives the crop",antialiased>=24);
            assertEquals("An original transparent opening remains transparent",0,
                    material.bitmap.getPixel(32-material.originX,65-material.originY)>>>24);
            int[] unchanged=new int[pixels.length];source.getPixels(unchanged,0,64,0,0,64,128);
            assertArrayEquals(original,unchanged);
        }finally{
            if(material!=null&&material.bitmap!=source)material.bitmap.recycle();
            source.recycle();
        }
    }

    @Test public void rendererPlacesExpandedPaintOutsideOriginalCropWithoutRescalingPadding(){
        int[] first=scene();paint(first,32,24,32,96,0xff27344e);
        for(int y=108;y<120;y++){
            first[y*W+32]=first[y*W+33]=LEFT_MARKER;
            first[y*W+62]=first[y*W+63]=RIGHT_MARKER;
        }
        boolean[] body=mask(32,24,32,96);
        StoryboardLayerIsolation.Result tracked=isolated(first,first.clone(),body,body);
        assertTrue(tracked.reason,tracked.autoRenderable());
        try(StoryboardLayerRenderer renderer=new StoryboardLayerRenderer(
                first,first.clone(),W,H,tracked,StoryboardMotionGrammar.SceneKind.ACTION,
                StoryboardMotionGrammar.Action.RUN)){
            Bitmap rendered=renderer.render(.75f,18);
            try{
                double left=markerCentroid(rendered,LEFT_MARKER),right=markerCentroid(rendered,RIGHT_MARKER);
                assertTrue("Left marker must survive beyond its original crop",left<32);
                assertTrue("Right marker must survive beyond its original crop",right>63);
            }finally{rendered.recycle();}
        }
    }

    @Test public void appearanceBlendAlignsDifferentCropDimensionsAroundTheirOriginalPivots(){
        int[] first=scene(),last=scene();
        // Asymmetric painted props: the first crop's pivot is near its right
        // side, the second near its left. Their tips need a common envelope.
        boolean[] a=mask(16,40,24,56),b=mask(56,40,12,56);
        boolean[] leftTip=mask(8,56,8,8),rightTip=mask(68,56,16,8);
        for(int i=0;i<a.length;i++){
            a[i]|=leftTip[i];b[i]|=rightTip[i];
            if(a[i])first[i]=LEFT_MARKER;if(b[i])last[i]=RIGHT_MARKER;
        }
        StoryboardLayerIsolation.Result tracked=isolated(first,last,a,b);
        assertTrue(tracked.reason,tracked.autoRenderable());
        assertNotEquals(tracked.tracks.get(0).from.width(),tracked.tracks.get(0).to.width());
        try(StoryboardLayerRenderer renderer=new StoryboardLayerRenderer(
                first,last,W,H,tracked,StoryboardMotionGrammar.SceneKind.PRODUCT,
                StoryboardMotionGrammar.Action.TRACK)){
            Bitmap rendered=renderer.render(.5f,12);
            try{
                int left=rendered.getPixel(28,60),right=rendered.getPixel(65,60);
                assertTrue("First source tip survives left of the old shared crop",
                        (left>>>16&255)>(left>>>8&255)+65);
                assertTrue("Second source tip survives right of the old shared crop",
                        (right>>>8&255)>(right>>>16&255)+25);
                int mixed=rendered.getPixel(42,75);
                assertTrue("The interior sample must blend both endpoint appearances",
                        (mixed>>>16&255)>100&&(mixed>>>16&255)<180&&
                        (mixed>>>8&255)>90&&(mixed>>>8&255)<150);
            }finally{rendered.recycle();}
        }
    }

    private static int count(Bitmap bitmap,int color){
        int count=0;
        for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++)
            if(bitmap.getPixel(x,y)==color)count++;
        return count;
    }
    private static double markerCentroid(Bitmap bitmap,int marker){
        int count=0;long xSum=0;
        for(int y=0;y<bitmap.getHeight();y++)for(int x=0;x<bitmap.getWidth();x++){
            int color=bitmap.getPixel(x,y);
            int distance=Math.abs((color>>>16&255)-(marker>>>16&255))+
                    Math.abs((color>>>8&255)-(marker>>>8&255))+Math.abs((color&255)-(marker&255));
            if(distance<60){count++;xSum+=x;}
        }
        assertTrue("Actual native render must retain measurable edge material",count>=8);
        return xSum/(double)count;
    }
}
