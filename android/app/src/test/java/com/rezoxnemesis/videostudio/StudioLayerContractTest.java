package com.rezoxnemesis.videostudio;

import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioLayerContractTest {
    private static ProjectStore.Project scene(){
        ProjectStore.Project p=new ProjectStore.Project();
        p.id=UUID.randomUUID().toString();
        ProjectStore.Clip c=new ProjectStore.Clip();
        c.id=UUID.randomUUID().toString();c.assetId="video";
        c.inMs=0;c.outMs=5000;p.clips.add(c);
        ProjectStore.Asset audio=new ProjectStore.Asset();
        audio.id="sound";audio.mime="audio/mp4";audio.durationMs=4200;
        p.assets.add(audio);return p;
    }
    private static StudioLayer music(){
        StudioLayer m=new StudioLayer();
        m.type=StudioLayer.AUDIO;m.assetId="sound";
        m.startMs=1000;m.durationMs=2000;m.sourceInMs=500;m.gain=.7f;
        return m;
    }
    @Test public void audioAndCaptionTracksRoundTripWithImportedSources() throws Exception{
        ProjectStore.Project scene=scene();
        StudioLayer audio=music();
        StudioLayer caption=new StudioLayer();
        caption.type=StudioLayer.TITLE;
        caption.text="Swords collide!";
        caption.startMs=1500;caption.durationMs=1800;
        JSONArray input=new JSONArray().put(audio.toJson()).put(caption.toJson());
        java.util.List<StudioLayer> recovered=StudioLayerContract.fromJson(scene,input);
        assertEquals(2,recovered.size());
        assertEquals("Swords collide!",recovered.get(1).text);
        assertEquals(2,StudioLayerContract.toJson(recovered).length());
    }
    @Test(expected=IllegalArgumentException.class)
    public void privateSourceMustBeImportedIntoSelectedProject() throws Exception {
        ProjectStore.Project p=scene();
        StudioLayer unknown=music();unknown.assetId="other_project_private_audio";
        StudioLayerContract.fromJson(p,new JSONArray().put(unknown.toJson()));
    }
    @Test(expected=IllegalArgumentException.class)
    public void audioCannotExceedSourceDuration() throws Exception {
        ProjectStore.Project p=scene();
        StudioLayer tooLong=music();tooLong.sourceInMs=3000;
        StudioLayerContract.fromJson(p,new JSONArray().put(tooLong.toJson()));
    }
    @Test(expected=IllegalArgumentException.class)
    public void duplicateLayerIdentityMustBeRejected() throws Exception {
        ProjectStore.Project p=scene();
        StudioLayer audio=music();
        StudioLayerContract.fromJson(p,
                new JSONArray().put(audio.toJson()).put(audio.toJson()));
    }
}
