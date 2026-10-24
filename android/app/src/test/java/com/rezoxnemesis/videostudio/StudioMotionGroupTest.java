package com.rezoxnemesis.videostudio;

import android.util.Pair;
import androidx.media3.common.OverlaySettings;
import androidx.media3.effect.StaticOverlaySettings;
import androidx.media3.effect.TextureOverlay;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

/** True native overlay parenting; device GPU and export tests remain separate. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class StudioMotionGroupTest {
    private static ProjectStore.Project source() {
        ProjectStore.Project p=new ProjectStore.Project();p.id="original-film";
        ProjectStore.Asset still=new ProjectStore.Asset();
        still.id="image-asset";still.mime="image/png";still.uri="file:///owned/image.png";
        p.assets.add(still);
        ProjectStore.Clip clip=new ProjectStore.Clip();
        clip.id="clip";clip.assetId=still.id;clip.inMs=0;clip.outMs=6000;
        p.clips.add(clip);
        return p;
    }

    private static StudioLayer group() throws Exception {
        StudioLayer parent=new StudioLayer();parent.id="group-1";parent.type=StudioLayer.GROUP;
        parent.startMs=0;parent.durationMs=6000;
        parent.motionKeyframes=new JSONArray()
                .put(new JSONObject().put("timeMs",0).put("x",0).put("scale",1)
                        .put("opacity",1).put("easing","linear"))
                .put(new JSONObject().put("timeMs",6000).put("x",.25).put("scale",1.5)
                        .put("rotate",90).put("opacity",.4));
        parent.validate();return parent;
    }

    @Test public void groupTransformSamplesCompositionClockAndMultipliesChildChannels() throws Exception {
        StudioParentGroupTransform parent=new StudioParentGroupTransform(group());
        OverlaySettings child=new StaticOverlaySettings.Builder()
                .setBackgroundFrameAnchor(.4f,-.1f)
                .setOverlayFrameAnchor(0,0).setScale(1,2).setAlphaScale(.5f).build();
        OverlaySettings first=parent.apply(child,0);
        OverlaySettings mid=parent.apply(child,3_000_000);
        OverlaySettings last=parent.apply(child,6_000_000);
        assertEquals(.4f,first.getBackgroundFrameAnchor().first,.0001f);
        assertTrue(mid.getBackgroundFrameAnchor().first>.4f);
        assertTrue(mid.getScale().first>1f);
        assertTrue(mid.getRotationDegrees()>0);
        assertTrue(mid.getAlphaScale()<first.getAlphaScale());
        assertEquals(.2f,last.getAlphaScale(),.0001f);
    }

    @Test public void parentedShapeAndTitleRenderAsTwoNotThreeOverlays() throws Exception {
        ProjectStore.Project p=source();StudioLayer g=group();
        StudioLayer shape=StudioShapePresets.create("ring","drift",100,2200);
        shape.parentId=g.id;
        StudioLayer title=new StudioLayer();title.id="title";title.type=StudioLayer.TITLE;
        title.text="Grouped motion";title.startMs=200;title.durationMs=1800;
        title.parentId=g.id;
        p.studioLayers.add(g);p.studioLayers.add(shape);p.studioLayers.add(title);
        StudioLayerContract.fromJson(p,StudioLayerContract.toJson(p.studioLayers));
        StudioCompositionOverlayEffect fx=
                new StudioCompositionOverlayEffect(RuntimeEnvironment.getApplication(),p);
        List<TextureOverlay> overlays=fx.createOverlays();
        try {
            assertEquals(2,overlays.size());
            assertTrue(overlays.get(0) instanceof StudioTimedShapeOverlay);
            assertTrue(overlays.get(1) instanceof StudioTimedTitleOverlay);
        } finally {
            for(TextureOverlay item:overlays)item.release();
        }
    }

    @Test public void parentedLayersAndSourceDataRoundTripWithoutMutation() throws Exception {
        ProjectStore.Project p=source();String before=p.assets.get(0).uri;
        StudioLayer g=group();
        StudioLayer shape=StudioShapePresets.create("diamond","pulse",1000,1800);
        shape.parentId=g.id;p.studioLayers.add(g);p.studioLayers.add(shape);
        ProjectStore.Project restored=ProjectStore.Project.fromJson(p.toJson());
        assertEquals(before,restored.assets.get(0).uri);
        assertEquals(StudioLayer.GROUP,restored.studioLayers.get(0).type);
        assertEquals(g.id,restored.studioLayers.get(1).parentId);
        StudioLayerContract.fromJson(restored,StudioLayerContract.toJson(restored.studioLayers));
    }

    @Test public void danglingNestedAndOutOfRangeGroupLinksRejectBeforeSaving() throws Exception {
        ProjectStore.Project p=source();
        StudioLayer shape=StudioShapePresets.create("grid","hold",100,1800);
        shape.parentId="unowned-parent";p.studioLayers.add(shape);
        assertThrows(IllegalArgumentException.class,()->
                StudioLayerContract.fromJson(p,StudioLayerContract.toJson(p.studioLayers)));
        p.studioLayers.clear();
        StudioLayer g=group();g.startMs=3000;g.durationMs=3000;
        p.studioLayers.add(g);shape.parentId=g.id;p.studioLayers.add(shape);
        assertThrows(IllegalArgumentException.class,()->
                StudioLayerContract.fromJson(p,StudioLayerContract.toJson(p.studioLayers)));
        g.parentId=shape.id;
        assertThrows(IllegalArgumentException.class,g::validate);
    }

    @Test public void legacyNoParentSerializationStaysByteIdentical() throws Exception {
        StudioLayer title=new StudioLayer();title.type=StudioLayer.TITLE;title.text="Original";
        JSONObject baseline=title.toJson();
        assertFalse(baseline.has("parentId"));
        assertEquals(baseline.toString(),StudioLayer.fromJson(baseline).toJson().toString());
        StudioLayer group=group();
        JSONObject encoded=group.toJson();
        assertFalse(encoded.has("assetId"));
        assertEquals(group.id,StudioLayer.fromJson(encoded).id);
    }
}
