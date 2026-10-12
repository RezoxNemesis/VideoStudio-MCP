package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class Rig2DProfileTest {
    static float[][] joints(){
        return new float[][]{
            {.42f,.57f},{.42f,.38f},{.42f,.32f},{.42f,.24f},
            {.48f,.40f},{.55f,.50f},{.64f,.54f},
            {.36f,.40f},{.29f,.48f},{.22f,.54f},
            {.47f,.59f},{.54f,.75f},{.55f,.90f},
            {.37f,.59f},{.32f,.75f},{.25f,.90f}
        };
    }
    static float[][] outline(){
        return new float[][]{
            {.18f,.16f},{.62f,.16f},{.78f,.47f},
            {.64f,.96f},{.20f,.96f},{.10f,.51f}
        };
    }

    @Test public void savesAndRestoresTwoSourceActorsWithoutGalleryBrowsing() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        ProjectStore projects=new ProjectStore(c);
        ProjectStore.Project p=projects.create("Rig test source");
        ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id=java.util.UUID.randomUUID().toString();
        asset.name="black_fighter.png";asset.mime="image/png";asset.uri="file:///data/local/tmp/test.png";
        p.assets.add(asset);projects.save(p);
        Rig2DProjectStore repo=new Rig2DProjectStore(c,projects);
        Rig2DProfile rig=Rig2DProfile.create(p.id,asset.id,true,false,joints(),outline());
        File written=repo.save(rig);
        assertTrue(written.isFile());
        assertTrue(written.getPath().contains("creative_workspace/projects/"+p.id+"/rigs/"));
        Rig2DProfile restored=repo.load(p.id,rig.id);
        assertEquals(rig.id,restored.id);
        assertEquals(asset.id,restored.sourceAssetId);
        assertTrue(restored.black);
        assertEquals(16,restored.joints.length);
        assertEquals(6,restored.outline.length);
        List<Rig2DProfile> found=repo.list(p.id);
        assertTrue(found.stream().anyMatch(v->v.id.equals(rig.id)));
        // Persistence uses the VideoStudio private filesystem exclusively.
        assertFalse(written.getPath().contains("/DCIM/"));
    }

    @Test public void authoredProfileIsImmutableFromCallingArrays() throws Exception {
        float[][] points=joints(),mask=outline();
        Rig2DProfile r=Rig2DProfile.create(
                "12345678","87654321",true,false,points,mask);
        points[0][0]=.99f;mask[0][1]=.99f;
        assertEquals(.42,r.joints[0][0],.00001);
        assertEquals(.16,r.outline[0][1],.00001);
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectsZeroLengthBone() {
        float[][] p=joints();
        p[Rig2DProfile.NECK]=p[Rig2DProfile.CHEST];
        Rig2DProfile.create("12345678","87654321",true,false,p,outline());
    }

    @Test(expected=IllegalArgumentException.class)
    public void refusesOpaqueActorWithoutMask() {
        Rig2DProfile.create("12345678","87654321",true,false,joints(),new float[0][2]);
    }

    @Test(expected=IllegalArgumentException.class)
    public void rejectsRigFileTraversal() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        new Rig2DProjectStore(c,new ProjectStore(c))
                .load("../../DCIM/","../../secret");
    }

    @Test public void distantActorGeometryIsNotRejectedByAnArbitrarySceneScale() {
        float[][] points=joints(),mask=outline();
        for(float[] point:points){point[0]=.7f+point[0]*.01f;point[1]=.3f+point[1]*.01f;}
        for(float[] point:mask){point[0]=.7f+point[0]*.01f;point[1]=.3f+point[1]*.01f;}
        Rig2DProfile distant=Rig2DProfile.create("project-1111","source-1111",false,false,points,mask);
        assertEquals(16,distant.joints.length);
        assertFalse(distant.black);
        assertTrue("Tiny but nonzero source anatomy is valid annotation data",
                Math.hypot(distant.joints[Rig2DProfile.NECK][0]-distant.joints[Rig2DProfile.HEAD][0],
                        distant.joints[Rig2DProfile.NECK][1]-distant.joints[Rig2DProfile.HEAD][1])<.002);
    }

    @Test(expected=IllegalArgumentException.class)
    public void aSelfIntersectingActorMaskCannotBeAcceptedAsAnAuthenticCutout() {
        Rig2DProfile.create("project-1111","source-1111",true,false,joints(),new float[][]{
                {.1f,.1f},{.8f,.8f},{.1f,.8f},{.8f,.1f},{.95f,.4f}});
    }
}
