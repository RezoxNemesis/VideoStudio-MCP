package com.rezoxnemesis.videostudio;
import android.graphics.Canvas;
import androidx.media3.effect.CanvasOverlay;
import androidx.media3.common.util.UnstableApi;
import org.json.JSONObject;

/** Evaluates geometry at each encoded timestamp rather than moving a still screenshot. */
@UnstableApi
public final class ProceduralSceneOverlay extends CanvasOverlay {
    private final ProceduralScene scene;
    private final double duration;
    private final long sequenceStartUs;
    public ProceduralSceneOverlay(JSONObject graph, long durationUs) throws Exception {
        this(graph,durationUs,0);
    }
    public ProceduralSceneOverlay(JSONObject graph,long durationUs,long sequenceStartUs)throws Exception{
        super(true);scene=new ProceduralScene(graph);duration=Math.max(.001,durationUs/1000000.0);this.sequenceStartUs=sequenceStartUs;
    }
    @Override public void onDraw(Canvas canvas,long presentationTimeUs) {
        scene.draw(canvas,Math.max(0,presentationTimeUs-sequenceStartUs)/1000000.0,duration);
    }
}
