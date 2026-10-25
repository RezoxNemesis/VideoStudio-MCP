package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import static org.junit.Assert.*;

/** Actual Android/Media3 admission and pixel-change verification. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeOfflineVideoMotionTest {
    private static final int WIDTH=128,HEIGHT=128,FRAMES=9,SIZE=3*WIDTH*HEIGHT;

    private float[] samples(boolean moving){
        float[] data=new float[FRAMES*SIZE];
        for(int frame=0;frame<FRAMES;frame++)
            for(int channel=0;channel<3;channel++)
                for(int pixel=0;pixel<WIDTH*HEIGHT;pixel++)
                    data[frame*SIZE+channel*WIDTH*HEIGHT+pixel]=
                            moving?(float)(.3*Math.sin(frame*.18+channel*.2+pixel*.001)):.25f;
        return data;
    }

    @Test public void nativeTemporalMotionEvidenceRejectsStaticOrCorruptModelOutput()throws Exception {
        JSONObject valid=NativeOfflineVideoMotionAudit.inspect(samples(true),FRAMES,WIDTH,HEIGHT);
        assertEquals(9,valid.getInt("decodedFrameCount"));
        assertEquals(8,valid.getInt("differentFrameIntervals"));
        assertFalse(valid.getBoolean("characterContinuityVerified"));
        assertFalse(valid.getBoolean("cinematicQualityVerified"));
        assertThrows(IllegalStateException.class,()->NativeOfflineVideoMotionAudit.inspect(
                samples(false),FRAMES,WIDTH,HEIGHT));
        float[] invalid=samples(true);invalid[3*SIZE+42]=Float.NaN;
        assertThrows(IllegalArgumentException.class,()->NativeOfflineVideoMotionAudit.inspect(
                invalid,FRAMES,WIDTH,HEIGHT));
    }

    @Test public void mediaTekAndSnapdragonAreNotInterchanged()throws Exception {
        assertEquals("mediatek",NativeOfflineVideoHardware.processorFamily(
                "MediaTek","mt6878","realme"));
        assertEquals("qualcomm",NativeOfflineVideoHardware.processorFamily(
                "Qualcomm","qcom","taro"));
        assertEquals("unknown",NativeOfflineVideoHardware.processorFamily(
                "","",""));
        JSONObject profile=new JSONObject().put("cpuExecutionSupported",true)
                .put("residentModelBudgetMb",1700)
                .put("activeWorkingSetBudgetMb",2480)
                .put("thermalSafe",true).put("storageFreeMb",25800);
        JSONObject fitting=new JSONObject().put("backend","onnxruntime-cpu")
                .put("requiredResidentMb",1200).put("requiredWorkingSetMb",1800);
        NativeOfflineVideoHardware.requireCompatible(fitting,profile);
        assertThrows(IllegalArgumentException.class,()->NativeOfflineVideoHardware.requireCompatible(
                new JSONObject(fitting.toString()).put("backend","qnn-hexagon"),profile));
        assertThrows(IllegalArgumentException.class,()->NativeOfflineVideoHardware.requireCompatible(
                new JSONObject(fitting.toString()).put("backend","cloud-inference"),profile));
        assertThrows(IllegalStateException.class,()->NativeOfflineVideoHardware.requireCompatible(
                new JSONObject(fitting.toString()).put("requiredResidentMb",3300),profile));
        assertThrows(IllegalStateException.class,()->NativeOfflineVideoHardware.requireCompatible(
                fitting,new JSONObject(profile.toString()).put("thermalSafe",false)));
    }

    private static ProjectStore.Project sample(){
        ProjectStore.Project project=new ProjectStore.Project();
        project.id="native-real-temporal-frames-test";
        project.name="Native real ONNX frames";
        for(int i=0;i<9;i++){
            ProjectStore.Asset asset=new ProjectStore.Asset();
            asset.id="neural-"+i;
            asset.uri="file:///owned/generated_"+i+".png";
            asset.mime="image/png";asset.role="neural_video_frame";asset.generated=true;
            asset.generationMetadata=new JSONObject();
            try{
                asset.generationMetadata.put("provider","videostudio.local_temporal_onnx");
                asset.generationMetadata.put("neuralFrameIndex",i);
                asset.generationMetadata.put("neuralFps",9);
            }catch(Exception fail){throw new IllegalStateException(fail);}
            ProjectStore.Clip clip=new ProjectStore.Clip();
            clip.id="clip-"+i;clip.assetId=asset.id;
            clip.inMs=0;clip.outMs=Math.round((i+1)*1000d/9)-Math.round(i*1000d/9);
            try{
                clip.effects.put("nativeNeuralFrame",true).put("neuralFps",9)
                        .put("neuralFrameIndex",i);
            }catch(Exception fail){throw new IllegalStateException(fail);}
            project.assets.add(asset);project.clips.add(clip);
        }
        return project;
    }

    @Test public void exactNineSourceFramesRetainOneSecondInsteadOfPhotoSlideshow() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        ProjectStore.Project project=sample();
        assertEquals(1000,project.outputDurationMs());
        Composition composition=new NativeRenderEngine(context).buildPreviewComposition(
                project,"1:1","720p");
        assertEquals(1,composition.sequences.size());
        assertEquals(9,composition.sequences.get(0).editedMediaItems.size());
        long duration=0;
        for(EditedMediaItem item:composition.sequences.get(0).editedMediaItems){
            assertTrue("Frames must not expand to 250 ms still-image minimum",
                    item.durationUs>=110_000&&item.durationUs<=112_000);
            assertEquals(9,item.frameRate,0f);
            duration+=item.durationUs;
        }
        assertEquals(1_000_000L,duration);
        ProjectStore.Clip bad=project.clips.get(0);
        bad.effects.put("neuralFrameIndex",99);
        assertThrows(IllegalArgumentException.class,()->new NativeRenderEngine(context)
                .buildPreviewComposition(project,"1:1","720p"));
    }
}
