package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StoryboardChoreographyTest {
    @Test public void allTwelveBeatsKeepSourceEndpointsAndMonotonicActorClocks() {
        assertEquals(12,StoryboardChoreography.Beat.values().length);
        for(StoryboardChoreography.Beat beat:StoryboardChoreography.Beat.values())for(boolean black:new boolean[]{true,false}) {
            assertEquals(0,StoryboardChoreography.phase(beat,black,0),0);
            assertEquals(1,StoryboardChoreography.phase(beat,black,1),0);
            double previous=0;
            for(int frame=1;frame<=120;frame++) {
                double phase=StoryboardChoreography.phase(beat,black,frame/120d);
                assertTrue("Source direction must not reverse or overshoot",phase>=previous && phase<=1);
                previous=phase;
            }
        }
    }
    @Test public void whiteKnockbackDoesNotAssignKnockbackToBlack() {
        assertEquals(StoryboardPoseMotion.Action.KNOCKBACK,StoryboardChoreography.actionFor(StoryboardChoreography.Beat.WHITE_KNOCKBACK,false));
        assertEquals(StoryboardPoseMotion.Action.ADVANCE,StoryboardChoreography.actionFor(StoryboardChoreography.Beat.WHITE_KNOCKBACK,true));
        assertTrue(StoryboardChoreography.phase(StoryboardChoreography.Beat.BLACK_STEP,true,.2)>
                StoryboardChoreography.phase(StoryboardChoreography.Beat.BLACK_STEP,false,.2));
    }
    @Test public void repeatedSourcePairsRetainDifferentOrderedBeats()throws Exception {
        JSONObject parameters=new JSONObject().put("choreography",new JSONArray()
                .put(shot("a","b","PRE_IMPACT")).put(shot("b","a","CLASH"))
                .put(shot("a","b","FOLLOW_THROUGH")));
        assertEquals(StoryboardChoreography.Beat.PRE_IMPACT,StoryboardChoreography.resolveBeat(parameters,0,"a","b"));
        assertEquals(StoryboardChoreography.Beat.FOLLOW_THROUGH,StoryboardChoreography.resolveBeat(parameters,2,"a","b"));
        try { StoryboardChoreography.resolveBeat(parameters,1,"a","b");fail("Foreign/reordered pair must reject"); }
        catch(IllegalArgumentException expected) {}
    }
    @Test(expected=IllegalArgumentException.class)public void genericFilenameCannotPretendToBeAuthoredChoreography(){StoryboardChoreography.parse("White_running_hit");}
    @Test public void unfinishedOwnerDraftRemainsAnExplicitMissingBeat()throws Exception{
        JSONObject parameters=new JSONObject().put("choreography",new JSONArray().put(shot("a","b","").put("shotIndex",0)));
        assertNull(StoryboardChoreography.resolveBeat(parameters,0,"a","b"));
        parameters.getJSONArray("choreography").getJSONObject(0).put("shotIndex",1);
        try{StoryboardChoreography.resolveBeat(parameters,0,"a","b");fail("Array index binding must be exact");}
        catch(IllegalArgumentException expected){}
    }
    private static JSONObject shot(String from,String to,String beat)throws Exception{return new JSONObject().put("fromAssetId",from).put("toAssetId",to).put("beat",beat);}
}
