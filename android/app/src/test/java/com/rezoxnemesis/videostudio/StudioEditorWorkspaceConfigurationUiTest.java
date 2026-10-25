package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import androidx.media3.ui.PlayerView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.UUID;
import static org.junit.Assert.*;

/** Additional configuration/state regressions against the frozen workspace generation. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows=StudioEditorPreviewGraphFixture.BuilderShadow.class,qualifiers="w480dp-h853dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class StudioEditorWorkspaceConfigurationUiTest {
    @Test public void rotatingAnAlreadyOpenEditorReflowsExistingViewsWithoutReplacingItsPreview()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            Object preview=field(fixture.activity,"studioPreviewFrame"),live=field(fixture.activity,"livePlayer");String selected=((ProjectStore.Clip)field(fixture.activity,"selectedClip")).id;
            StudioEditHistory history=(StudioEditHistory)field(fixture.activity,"editHistory");history.checkpoint(fixture.project);
            String before=SketchDocument.canonical(fixture.store.get(fixture.project.id).toJson());long playhead=(long)field(fixture.activity,"studioPlayheadMs");
            for(int[] viewport:new int[][]{{853,480},{480,853}}){
                fixture.configure(viewport[0],viewport[1],1f);
                assertSame("Configuration reflow must retain the native preview frame",preview,field(fixture.activity,"studioPreviewFrame"));
                assertSame("Configuration reflow must retain the admitted native player",live,field(fixture.activity,"livePlayer"));
                assertEquals(selected,((ProjectStore.Clip)field(fixture.activity,"selectedClip")).id);assertEquals(playhead,(long)field(fixture.activity,"studioPlayheadMs"));assertTrue(history.canUndo());
                assertEquals(before,SketchDocument.canonical(fixture.store.get(fixture.project.id).toJson()));
                assertReachable(fixture,(View)preview,"Rotated preview remains visible");
                assertReachable(fixture,(View)field(fixture.activity,"studioTracks"),"In-place rotation must keep actual native tracks visible");
                assertReachable(fixture,button(fixture.root,"Export"),"Rotated Export remains reachable");
                for(String pane:new String[]{"Media","Timeline","Inspector"})assertReachable(fixture,button(fixture.root,pane),"Rotated pane remains reachable: "+pane);
            }
        }
    }

    @Test public void enlargedTextKeepsTheWholeActualTimelineBandAndFooterReachable()throws Exception{
        Context application=RuntimeEnvironment.getApplication();Configuration enlarged=new Configuration(application.getResources().getConfiguration());enlarged.fontScale=1.6f;
        application.getResources().updateConfiguration(enlarged,application.getResources().getDisplayMetrics());
        try(Fixture fixture=new Fixture(480,853)){
            assertReachable(fixture,(View)field(fixture.activity,"studioTracks"),"Font-aware layout must reserve the whole native timeline band");
            assertReachable(fixture,(View)field(fixture.activity,"studioSeek"),"Enlarged-text seeking remains reachable");
            assertReachable(fixture,button(fixture.root,"Export"),"Enlarged-text Export remains reachable");
            for(String action:new String[]{"Trim","Adjust","Motion","Audio","More"})assertReachable(fixture,button(fixture.root,action),"Common action remains reachable with enlarged text: "+action);
        }
    }

    @Test public void timelineViewSwitchLivesInExistingHeaderAndPreservesSourceAndPreview()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            final String before=SketchDocument.canonical(fixture.store.get(fixture.project.id).toJson());
            final Object player=field(fixture.activity,"livePlayer");
            Button mode=button(fixture.root,"Tracks");
            assertReachable(fixture,mode,"Compact-to-multitrack mode action remains in the Timeline header");
            mode.performClick();
            ShadowLooper.idleMainLooper();fixture.layout();
            Button returnCompact=button(fixture.root,"Compact");
            assertReachable(fixture,returnCompact,"Return-to-compact action remains visible");
            assertSame(player,field(fixture.activity,"livePlayer"));
            assertEquals(before,SketchDocument.canonical(fixture.store.get(fixture.project.id).toJson()));
            returnCompact.performClick();
            ShadowLooper.idleMainLooper();fixture.layout();
            assertReachable(fixture,(View)field(fixture.activity,"studioTracks"),
                    "Compact timeline is fully reachable again after switching");
            assertEquals(before,SketchDocument.canonical(fixture.store.get(fixture.project.id).toJson()));
        }
    }

    @Test public void rebuildingTheEditorWhileNativePlaybackContinuesShowsPause()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            invoke(fixture.activity,"toggleStudioEditedPlayback");LiveEditPlayer live=(LiveEditPlayer)field(fixture.activity,"livePlayer");assertTrue(live.snapshotState().playWhenReady);
            invoke(fixture.activity,"showEditor");ShadowLooper.idleMainLooper();fixture.layout();
            assertTrue("Rebuilding the layout must preserve actual native playback intent",live.snapshotState().playWhenReady);
            View toggle=(View)field(fixture.activity,"studioPlayToggle");
            assertTrue("The sole transport must show Pause for the playing native composition",String.valueOf(toggle.getContentDescription()).startsWith("Pause"));
        }
    }

    @Test public void nativePauseIsReflectedByTheSoleTransportWithoutASecondUserClick()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            invoke(fixture.activity,"toggleStudioEditedPlayback");LiveEditPlayer live=(LiveEditPlayer)field(fixture.activity,"livePlayer");assertTrue(live.snapshotState().playWhenReady);
            live.pause();((Runnable)field(fixture.activity,"studioCaptionClock")).run();
            assertFalse(live.snapshotState().playWhenReady);View toggle=(View)field(fixture.activity,"studioPlayToggle");
            assertTrue("The sole transport must reflect native paused intent",String.valueOf(toggle.getContentDescription()).contains("Play"));
        }
    }

    @Test public void choosingLatestRenderRestoresTheOriginalNativeRenderControls()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            PlayerView preview=find(fixture.root,PlayerView.class);assertFalse(preview.getUseController());
            // UI admission only: this declared render URI is never asserted to decode.
            fixture.project.latestExportUri="file:///not-decoded-ui-render-controls.mp4";fixture.project.latestExportAt=17;
            invoke(fixture.activity,"playStudioLatest");LiveEditPlayer live=(LiveEditPlayer)field(fixture.activity,"livePlayer");
            assertFalse("The public Latest MP4 route selected source-render playback",live.isEditedPreview());
            assertTrue("A saved render needs its own native pause/seek controls",preview.getUseController());
            invoke(fixture.activity,"playStudioTimeline");assertTrue(live.isEditedPreview());
            assertFalse("Returning to the edited timeline restores one shared app transport",preview.getUseController());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final MainActivity activity;final ProjectStore store;final ProjectStore.Project project;final ViewGroup root;int width,height;final File source;
        Fixture(int width,int height)throws Exception{
            this.width=width;this.height=height;Context context=RuntimeEnvironment.getApplication();store=new ProjectStore(context);project=store.create("SOURCE_ASSISTED native photo panel - keep this complete project identity - registered-"+UUID.randomUUID()+"-long-title");
            File folder=new File(context.getFilesDir(),"editor_workspace_ui_fixtures");assertTrue(folder.isDirectory()||folder.mkdirs());source=new File(folder,UUID.randomUUID()+".png");Bitmap bitmap=Bitmap.createBitmap(64,128,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff447788);bitmap.setPixel(1,2,0xffcc2244);try(FileOutputStream output=new FileOutputStream(source)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}
            ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned_"+UUID.randomUUID();asset.mime="image/png";asset.name="Own technical panel.png";asset.uri=Uri.fromFile(source).toString();asset.role="source";asset.sizeBytes=source.length();project.assets.add(asset);
            ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="base_"+UUID.randomUUID();clip.assetId=asset.id;clip.outMs=4000;clip.effects=new JSONObject().put("motionPreset","none");project.clips.add(clip);
            StudioLayer image=StudioLayer.fromJson(new JSONObject().put("id","image_"+UUID.randomUUID()).put("type","image").put("assetId",asset.id).put("startMs",0).put("durationMs",4000).put("width",.35).put("height",.39375).put("motionKeyframes",new JSONArray().put(new JSONObject().put("timeMs",0).put("x",-.1)).put(new JSONObject().put("timeMs",1000).put("x",0))));project.studioLayers.add(image);store.save(project);
            activity=Robolectric.buildActivity(MainActivity.class).get();set(activity,"store",store);set(activity,"prefs",context.getSharedPreferences("editor-workspace-ui",0));set(activity,"activeProject",project);set(activity,"selectedClip",clip);set(activity,"renderEngine",new NativeRenderEngine(activity));
            root=(ViewGroup)invoke(activity,"buildShell");activity.setContentView(root);invoke(activity,"showEditor");ShadowLooper.idleMainLooper();layout();
        }
        void configure(int width,int height,float fontScale){
            this.width=width;this.height=height;Configuration changed=new Configuration(activity.getResources().getConfiguration());
            changed.screenWidthDp=width;changed.screenHeightDp=height;changed.orientation=width>height?Configuration.ORIENTATION_LANDSCAPE:Configuration.ORIENTATION_PORTRAIT;changed.fontScale=fontScale;
            activity.getResources().updateConfiguration(changed,activity.getResources().getDisplayMetrics());root.dispatchConfigurationChanged(changed);layout();
        }
        void layout(){root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);}
        @Override public void close()throws Exception{invoke(activity,"onDestroy");Field db=ProjectStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(store)).close();assertTrue(source.delete());}
    }
    private static void click(Fixture fixture,String label){Button button=button(fixture.root,label);assertReachable(fixture,button,"Missing visible native action: "+label);button.performClick();ShadowLooper.idleMainLooper();fixture.layout();}
    private static void assertReachable(Fixture fixture,View view,String message){assertNotNull(message,view);assertTrue(message+"; measured="+bounds(fixture,view),visible(fixture,view));if(view instanceof Button){assertTrue(message+" requires a48dp touch height",view.getHeight()>=StudioUi.dp(fixture.activity,48));assertTrue(message+" requires a48dp touch width",view.getWidth()>=StudioUi.dp(fixture.activity,48));}}
    private static boolean visible(Fixture fixture,View view){
        if(view==null||view.getWidth()<=0||view.getHeight()<=0)return false;Rect rect=bounds(fixture,view);
        if(rect==null||!new Rect(0,0,fixture.width,fixture.height).contains(rect))return false;
        for(View current=view;;){
            if(current.getVisibility()!=View.VISIBLE)return false;if(current==fixture.root)return true;
            if(!(current.getParent() instanceof ViewGroup))return false;ViewGroup parent=(ViewGroup)current.getParent();
            if(parent.getClipChildren()){
                Rect clip=bounds(fixture,parent);if(clip==null)return false;
                if(parent.getClipToPadding()){clip.left+=parent.getPaddingLeft();clip.top+=parent.getPaddingTop();clip.right-=parent.getPaddingRight();clip.bottom-=parent.getPaddingBottom();}
                if(!clip.contains(rect))return false;
            }
            current=parent;
        }
    }
    private static Rect bounds(Fixture fixture,View view){if(view==null)return null;try{Rect rect=new Rect(0,0,view.getWidth(),view.getHeight());fixture.root.offsetDescendantRectToMyCoords(view,rect);return rect;}catch(IllegalArgumentException error){return null;}}
    private static Button projectButton(View view,String name){if(view instanceof Button&&(((Button)view).getText().toString().contains(name)||String.valueOf(view.getContentDescription()).contains(name)))return (Button)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Button found=projectButton(((ViewGroup)view).getChildAt(i),name);if(found!=null)return found;}return null;}
    private static <T> T find(View view,Class<T> type){if(type.isInstance(view))return type.cast(view);if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){T found=find(((ViewGroup)view).getChildAt(i),type);if(found!=null)return found;}return null;}
    private static TextView text(View view,String label){if(view instanceof TextView&&label.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=text(((ViewGroup)view).getChildAt(i),label);if(found!=null)return found;}return null;}
    private static Button button(View root,String label){TextView found=text(root,label);return found instanceof Button?(Button)found:null;}
    private static Object field(Object owner,String name)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static void set(Object owner,String name,Object value)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(owner,value);}
    private static Object invoke(Object owner,String name)throws Exception{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);return method.invoke(owner);}
}
