package com.rezoxnemesis.videostudio;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Locale;
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

    @Test public void realVariantsDeduplicateWithoutSortingStoryOutOfOrder()
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
        StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,new JSONObject());
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
}
