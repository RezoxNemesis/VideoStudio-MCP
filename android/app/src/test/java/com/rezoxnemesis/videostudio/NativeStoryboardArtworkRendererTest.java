package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import static org.junit.Assert.*;

/** Real native Canvas source-material regressions, not acceptance of Test 1 acting. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativeStoryboardArtworkRendererTest {
    @Test public void sourceSwordRgbSurvivesAnAuthoredEndpoint() throws Exception {
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(RuntimeEnvironment.getApplication(),false);
            NativeStoryboardArtworkRenderer renderer=fixture.renderer(StoryboardPoseMotion.Action.ADVANCE)){
            Bitmap frame=renderer.render(0,0,24);
            try {
                SourceArtworkRasterFixture.writeEvidence("source_sword_endpoint",frame);
                // The original green blade lies outside either actor polygon and inside its prop mask.
                int actual=frame.getPixel(142,242);
                assertEquals("The attached prop must keep its original source RGB, not a replacement metal triangle",
                        SourceArtworkRasterFixture.BLACK_SWORD,actual);
            }finally{frame.recycle();}
        }
    }

    @Test public void identicalActorTexturesKeepOpaqueMaterialAtAppearanceHandoff() throws Exception {
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(RuntimeEnvironment.getApplication(),false);
            NativeStoryboardArtworkRenderer renderer=fixture.renderer(StoryboardPoseMotion.Action.ADVANCE)){
            Bitmap first=renderer.render(0,0,24),middle=renderer.render(.5,12,24);
            try {
                SourceArtworkRasterFixture.writeEvidence("opaque_source_handoff",first,middle);
                int x=64,y=284;
                assertEquals("Fixture point is opaque source actor material",SourceArtworkRasterFixture.BLACK_MATERIAL,first.getPixel(x,y));
                assertEquals("Aligned opaque source textures must not reveal 25% of the world during their handoff",
                        first.getPixel(x,y),middle.getPixel(x,y));
                assertEquals(255,Color.alpha(middle.getPixel(x,y)));
            }finally{first.recycle();middle.recycle();}
        }
    }

    @Test public void appearanceHandoffActuallyMixesDifferentSourceMaterials() throws Exception {
        int secondMaterial=0xff503ca0;
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(RuntimeEnvironment.getApplication(),false,secondMaterial);
            NativeStoryboardArtworkRenderer renderer=fixture.renderer(StoryboardPoseMotion.Action.ADVANCE)){
            Bitmap first=renderer.render(0,0,24),middle=renderer.render(.5,12,24),last=renderer.render(1,23,24);
            try {
                SourceArtworkRasterFixture.writeEvidence("different_source_handoff",first,middle,last);
                int x=64,y=284,a=first.getPixel(x,y),b=last.getPixel(x,y),actual=middle.getPixel(x,y);
                assertEquals(SourceArtworkRasterFixture.BLACK_MATERIAL,a);assertEquals(secondMaterial,b);
                assertEquals("The handoff must mix the two source appearances rather than switch at its midpoint",(Color.red(a)+Color.red(b))/2,Color.red(actual),1);
                assertEquals((Color.green(a)+Color.green(b))/2,Color.green(actual),1);
                assertEquals((Color.blue(a)+Color.blue(b))/2,Color.blue(actual),1);
            }finally{first.recycle();middle.recycle();last.recycle();}
        }
    }

    @Test public void impactCameraMovesWorldAndActorByTheSameRasterTransform() throws Exception {
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(RuntimeEnvironment.getApplication(),true);
            NativeStoryboardArtworkRenderer renderer=fixture.renderer(StoryboardPoseMotion.Action.IMPACT)){
            Bitmap noShake=renderer.render(.5,0,24),shake=renderer.render(.5,1,24);
            try {
                SourceArtworkRasterFixture.writeEvidence("shared_impact_camera",noShake,shake);
                double[] actorA=SourceArtworkRasterFixture.centroid(noShake,0,true),actorB=SourceArtworkRasterFixture.centroid(shake,0,true);
                double[] worldA=SourceArtworkRasterFixture.centroid(noShake,SourceArtworkRasterFixture.WORLD_MARKER,false),worldB=SourceArtworkRasterFixture.centroid(shake,SourceArtworkRasterFixture.WORLD_MARKER,false);
                double actorDx=actorB[0]-actorA[0],worldDx=worldB[0]-worldA[0];
                assertTrue("Fixture must exercise a visible contact-camera impulse",actorDx>.5);
                assertEquals("The city marker must share the actual actor camera displacement",actorDx,worldDx,.6);
            }finally{noShake.recycle();shake.recycle();}
        }
    }
    @Test public void explicitCameraDisabledKeepsWorldAndActorsFixedDuringContact()throws Exception {
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(RuntimeEnvironment.getApplication(),true);
            NativeStoryboardArtworkRenderer renderer=fixture.renderer(StoryboardPoseMotion.Action.IMPACT,false)){
            Bitmap first=renderer.render(.5,0,24),second=renderer.render(.5,1,24);
            try{
                SourceArtworkRasterFixture.writeEvidence("contact_camera_disabled",first,second);
                assertTrue("Disabling the shared camera must disable its world, actor and prop raster motion",first.sameAs(second));
            }finally{first.recycle();second.recycle();}
        }
    }
}
