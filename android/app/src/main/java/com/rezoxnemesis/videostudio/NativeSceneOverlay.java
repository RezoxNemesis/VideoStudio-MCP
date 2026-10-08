package com.rezoxnemesis.videostudio;
import android.content.Context;
import android.graphics.Canvas;
import androidx.media3.effect.CanvasOverlay;
import androidx.media3.common.util.UnstableApi;
import org.json.JSONObject;

@UnstableApi
public final class NativeSceneOverlay extends CanvasOverlay {
    private final NativeSceneRenderer renderer;
    public NativeSceneOverlay(Context context,JSONObject scene) throws Exception {super(true); renderer=new NativeSceneRenderer(context,scene);}
    @Override public void onDraw(Canvas canvas,long timeUs) {
        try {renderer.draw(canvas,Math.max(0,timeUs/1000000d));}
        catch(Exception e) {throw new IllegalStateException("Native scene evaluation failed",e);}
    }
    @Override public void release() throws androidx.media3.common.VideoFrameProcessingException {try {super.release();} finally {renderer.close();}}
}
