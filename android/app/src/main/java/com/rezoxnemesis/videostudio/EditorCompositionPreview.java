package com.rezoxnemesis.videostudio;
import android.content.Context;import android.view.*;import androidx.media3.common.*;import androidx.media3.transformer.CompositionPlayer;import androidx.media3.ui.PlayerView;

/** Live composition uses the export effects and tracks at proxy resolution, without an export job. */
final class EditorCompositionPreview {
    private final CompositionPlayer player;private final PlayerView view;private final NativeRenderEngine renderer;private long version=-1;private boolean rendered;private Runnable firstFrame=()->{};
    EditorCompositionPreview(Context c,java.util.function.Consumer<String> error){renderer=new NativeRenderEngine(c);player=new CompositionPlayer.Builder(c.getApplicationContext()).build();view=new PlayerView(c);view.setPlayer(player);player.addListener(new Player.Listener(){@Override public void onPlayerError(PlaybackException e){error.accept(e.getMessage());}@Override public void onRenderedFirstFrame(){rendered=true;firstFrame.run();}});}
    void attach(ViewGroup canvas){if(view.getParent() instanceof ViewGroup)((ViewGroup)view.getParent()).removeView(view);canvas.addView(view,new ViewGroup.LayoutParams(-1,-1));}
    void show(ProjectStore.Project p,long position,boolean playing){view.setVisibility(View.VISIBLE);if(version!=p.updatedAt){rendered=false;player.setComposition(renderer.previewComposition(p,"9:16"),Math.max(0,position));player.prepare();version=p.updatedAt;}else player.seekTo(Math.max(0,position));player.setPlayWhenReady(playing);}
    void onFirstFrame(Runnable listener){firstFrame=listener;}
    boolean hasRenderedFrame(){return rendered;}
    void hide(){player.pause();view.setVisibility(View.GONE);}
    void toggle(boolean playing){player.setPlayWhenReady(playing);}
    long position(){return Math.max(0,player.getCurrentPosition());}
    void release(){view.setPlayer(null);player.release();}
}
