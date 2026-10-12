package com.rezoxnemesis.videostudio;

import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StoryboardOwnerRequestsTest {
    @Test public void explicitEnvironmentalPhoneChoiceCarriesNonActionIntent(){
        Intent request=StoryboardOwnerRequests.series(RuntimeEnvironment.getApplication(),"source-project","landscape","CAMERA_PARALLAX","wind",8,-1,false,false);
        assertEquals(ControlService.ACTION_LOCAL_STORYBOARD_SERIES,request.getAction());assertEquals("environmental",request.getStringExtra("motionIntent"));assertEquals("CAMERA_PARALLAX",request.getStringExtra("backend"));assertEquals("source-project",request.getStringExtra("sourceProjectId"));assertEquals(-1,request.getIntExtra("batchIndex",0));assertFalse(request.getBooleanExtra("analysisOnly",true));
    }
    @Test public void automaticAndPortraitPhoneChoicesRemainLabelledDeformationDrafts(){
        for(String backend:new String[]{"AUTO_LAYERS","PERSON_SEGMENTATION"}){Intent request=StoryboardOwnerRequests.series(RuntimeEnvironment.getApplication(),"source-project","comic",backend,"",12,0,true,true);assertEquals("source_deformation",request.getStringExtra("motionIntent"));assertTrue(request.getBooleanExtra("analysisOnly",false));assertEquals(24,request.getIntExtra("fps",0));}
    }
    @Test public void cinematicSourceRigCannotFallThroughUnannotatedLegacyPhoneRequest(){
        try{StoryboardOwnerRequests.series(RuntimeEnvironment.getApplication(),"source-project","action","SOURCE_RIG","",8,0,true,false);fail("Cinematic scope must use saved authoring program");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("saved program"));}
    }
}
