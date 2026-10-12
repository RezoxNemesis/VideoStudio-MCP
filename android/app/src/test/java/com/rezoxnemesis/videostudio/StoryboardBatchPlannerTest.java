package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Locale;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class StoryboardBatchPlannerTest {
    private ProjectStore.Project project(int frames) {
        ProjectStore.Project project=new ProjectStore.Project();
        project.id="film-test-project";
        project.name="Revenge of Ashes";
        project.sourcePrompt="Cinematic illustrated duel";
        for(int i=0;i<frames;i++){
            ProjectStore.Asset image=new ProjectStore.Asset();
            image.id="image-"+i+"-source";
            image.name=String.format(Locale.US,"%03d_Frame.webp",i);
            image.uri="file:///private/storyboard/img_"+i+".webp";
            image.mime="image/webp";
            image.sizeBytes=50000+i*3;
            project.assets.add(image);
            ProjectStore.Clip clip=new ProjectStore.Clip();
            clip.id="clip-"+i+"-source";
            clip.assetId=image.id;
            clip.outMs=3000;
            project.clips.add(clip);
        }
        return project;
    }

    @Test public void fiveBatchesOfTwentyRealFramesRetainContinuousBoundaries()
            throws Exception {
        ProjectStore.Project source=project(100);
        StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,
                new JSONObject().put("genre","comic")
                        .put("fps",24).put("framesPerPair",8)
                        .put("maxFramesPerBatch",20));
        assertEquals(100,plan.assets.size());
        assertEquals(5,plan.batches.size());
        assertEquals(0,plan.batches.get(0).start);
        assertEquals(19,plan.batches.get(0).end);
        assertEquals(19,plan.batches.get(1).start);
        assertEquals(39,plan.batches.get(1).end);
        assertEquals(79,plan.batches.get(4).start);
        assertEquals(99,plan.batches.get(4).end);
        for(int i=1;i<5;i++){
            assertEquals(plan.batches.get(i-1).end,plan.batches.get(i).start);
            assertEquals(20,plan.batches.get(i).end-plan.batches.get(i).start);
        }
        assertEquals(1+99*8,StoryboardSeriesOrchestrator.predictedFrames(plan,-1));
        JSONObject serialized=plan.toJson();
        assertEquals(5,serialized.getJSONArray("batches").length());
        assertEquals(20,serialized.getJSONArray("batches").getJSONObject(1)
                .getInt("newOriginalFrames"));
        assertEquals(21,serialized.getJSONArray("batches").getJSONObject(1)
                .getInt("inputFramesIncludingOverlap"));
        assertTrue(serialized.getJSONArray("batches").getJSONObject(1)
                .getBoolean("sharesFirstAnchorWithPrevious"));
        assertEquals(100,source.clips.size()); // source never mutated
    }

    @Test public void declaredVariantGroupsChooseRepresentativesWithoutSortingStoryOutOfOrder()
            throws Exception {
        ProjectStore.Project source=new ProjectStore.Project();
        source.id="photos-test-id";
        source.name="Comic";
        String[] names={"00_Original_Standoff.webp","000_Original_Standoff.png",
            "01_Black_Step_480.jpg","01_Black_Step.webp","02_White_Advance.webp"};
        for(int i=0;i<names.length;i++){
            ProjectStore.Asset asset=new ProjectStore.Asset();
            asset.id="source-image-"+i;
            asset.name=names[i];asset.mime="image/"+(i==2?"jpeg":"webp");
            asset.uri="file:///approved/"+names[i];asset.sizeBytes=100000+i*100000;
            ProjectStore.Clip clip=new ProjectStore.Clip();
            clip.assetId=asset.id;clip.outMs=3000;
            source.assets.add(asset);source.clips.add(clip);
        }
        JSONArray groups=new JSONArray()
                .put(new JSONObject().put("representativeAssetId","source-image-1").put("assetIds",new JSONArray().put("source-image-0").put("source-image-1")))
                .put(new JSONObject().put("representativeAssetId","source-image-3").put("assetIds",new JSONArray().put("source-image-2").put("source-image-3")))
                .put(new JSONObject().put("representativeAssetId","source-image-4").put("assetIds",new JSONArray().put("source-image-4")));
        StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,new JSONObject().put("anchorGroups",groups));
        assertEquals(3,plan.assets.size());
        assertEquals(2,plan.deduplicatedCount);
        assertEquals("source-image-1",plan.assets.get(0));
        assertEquals("source-image-3",plan.assets.get(1));
        assertEquals("source-image-4",plan.assets.get(2));
    }

    @Test public void unsupportedBackendsAndUnboundedFrameRatesFailClosed()
            throws Exception {
        try {
            StoryboardBatchPlanner.plan(project(5),new JSONObject().put("fps",120));
            fail("Phone cannot silently run unsupported video FPS");
        }catch(IllegalArgumentException expected){}
        try {
            StoryboardBatchPlanner.plan(project(5),
                    new JSONObject().put("backend","magic_neural_model"));
            fail("Model never declared");
        }catch(IllegalArgumentException expected){}
        assertEquals("1_black_step",StoryboardBatchPlanner.normalizeFrameName("01_Black_Step_480.jpg"));
        assertEquals("0_original_standoff",StoryboardBatchPlanner.normalizeFrameName("000_Original_Standoff.png"));
    }

    @Test public void identicalDisplayNamesDoNotProveDifferentImportedAssetsAreDuplicates()
            throws Exception {
        ProjectStore.Project source=project(3);
        source.assets.get(0).name="01_Clash.webp";
        source.assets.get(1).name="01_Clash.webp";
        String original=source.toJson().toString();

        StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,new JSONObject());

        assertEquals(Arrays.asList("image-0-source","image-1-source","image-2-source"),plan.assets);
        assertEquals(0,plan.deduplicatedCount);
        assertEquals(original,source.toJson().toString());
    }

    @Test public void unicodeAndUnnamedDrawingsKeepTheirSeparateImportedIdentities()
            throws Exception {
        ProjectStore.Project source=project(4);
        source.assets.get(0).name="開始.webp";
        source.assets.get(1).name="反撃.webp";
        source.assets.get(2).name="";
        source.assets.get(3).name="";

        StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,new JSONObject());

        assertEquals(Arrays.asList("image-0-source","image-1-source","image-2-source","image-3-source"),plan.assets);
        assertEquals(0,plan.deduplicatedCount);
    }

    @Test public void explicitRepeatedAnchorIsARealStoryBeatRatherThanAnImportDuplicate()
            throws Exception {
        ProjectStore.Project source=project(3);
        JSONArray order=new JSONArray().put("image-2-source").put("image-0-source")
                .put("image-2-source").put("image-1-source");

        StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,
                new JSONObject().put("assetIds",order).put("maxFramesPerBatch",2)
                        .put("framesPerPair",8));

        assertEquals(Arrays.asList("image-2-source","image-0-source","image-2-source","image-1-source"),plan.assets);
        assertEquals(2,plan.batches.size());
        assertEquals("image-0-source",plan.batches.get(0).shots.get(0).toId);
        assertEquals("image-2-source",plan.batches.get(1).shots.get(0).toId);
        assertEquals(25,StoryboardSeriesOrchestrator.predictedFrames(plan,-1));
    }

    @Test public void malformedExplicitOrderCannotSilentlyFallBackToTheTimeline()
            throws Exception {
        ProjectStore.Project source=project(3);
        assertThrows(IllegalArgumentException.class,()->StoryboardBatchPlanner.plan(source,
                new JSONObject().put("assetIds","image-2-source,image-0-source")));
    }

    @Test public void sixtyFpsIsAnExplicitSupportedStoryboardClock()throws Exception{
        assertEquals(60,StoryboardBatchPlanner.plan(project(3),new JSONObject().put("fps",60)).fps);
        for(int fps:new int[]{0,25,48,59,61,120})assertThrows(IllegalArgumentException.class,()->StoryboardBatchPlanner.plan(project(3),new JSONObject().put("fps",fps)));
        for(Object fps:new Object[]{60.5,"60",4294967320L,JSONObject.NULL})assertThrows(IllegalArgumentException.class,()->StoryboardBatchPlanner.plan(project(3),new JSONObject().put("fps",fps)));
    }

    @Test public void authoredChoreographyBindsEveryExactPairAcrossBatchBoundaries()throws Exception{
        ProjectStore.Project source=project(4);JSONArray choreography=new JSONArray();
        StoryboardChoreography.Beat[] beats={StoryboardChoreography.Beat.BLACK_STEP,StoryboardChoreography.Beat.WHITE_ADVANCE,StoryboardChoreography.Beat.BLADE_LOCK};
        for(int index=0;index<3;index++)choreography.put(new JSONObject().put("fromAssetId","image-"+index+"-source")
                .put("toAssetId","image-"+(index+1)+"-source").put("beat",beats[index].name()));
        StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,new JSONObject().put("maxFramesPerBatch",2).put("choreography",choreography));
        assertEquals(beats[0],plan.batches.get(0).shots.get(0).beat);
        assertEquals(beats[1],plan.batches.get(1).shots.get(0).beat);
        assertEquals(beats[2],plan.batches.get(1).shots.get(1).beat);
        assertEquals("BLADE_LOCK",plan.toJson().getJSONArray("batches").getJSONObject(1).getJSONArray("shots").getJSONObject(1).getString("beat"));
        JSONArray extra=new JSONArray(choreography.toString()).put(choreography.getJSONObject(0));
        assertThrows(IllegalArgumentException.class,()->StoryboardBatchPlanner.plan(source,new JSONObject().put("choreography",extra)));
        choreography.getJSONObject(1).put("fromAssetId","image-0-source");
        assertThrows(IllegalArgumentException.class,()->StoryboardBatchPlanner.plan(source,new JSONObject().put("choreography",choreography)));
    }

    @Test public void allTwelveAuthoredBeatsRemainDistinctFromGenericFilenameHints()throws Exception{
        ProjectStore.Project source=project(13);JSONArray choreography=new JSONArray();StoryboardChoreography.Beat[] beats=StoryboardChoreography.Beat.values();
        for(int index=0;index<13;index++)source.assets.get(index).name="clash.png";
        for(int index=0;index<beats.length;index++)choreography.put(new JSONObject().put("fromAssetId","image-"+index+"-source")
                .put("toAssetId","image-"+(index+1)+"-source").put("beat",beats[index].name()));
        StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,new JSONObject().put("choreography",choreography));
        assertEquals(12,plan.batches.get(0).shots.size());
        for(int index=0;index<beats.length;index++)assertEquals(beats[index],plan.batches.get(0).shots.get(index).beat);
        assertEquals(121,StoryboardSeriesOrchestrator.predictedFrames(plan,-1));
    }
}
