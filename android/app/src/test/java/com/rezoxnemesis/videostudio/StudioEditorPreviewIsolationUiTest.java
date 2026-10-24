package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
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

/** Explicit edit seek and external-preview isolation; UI/native admission, not decode proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows=StudioEditorPreviewGraphFixture.BuilderShadow.class,qualifiers="w480dp-h853dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class StudioEditorPreviewIsolationUiTest {
    @Test public void keyframePreviewLoadedTimeActuallySeeksAnUnchangedPausedComposition()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            LiveEditPlayer live=(LiveEditPlayer)field(fixture.activity,"livePlayer");assertTrue(live.isEditedPreview());assertFalse(live.snapshotState().playWhenReady);
            String before=SketchDocument.canonical(fixture.store.get(fixture.project.id).toJson());
            invoke(fixture.activity,"studioKeyframeManager");ShadowLooper.idleMainLooper();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            EditText time=(EditText)description(dialog.getWindow().getDecorView(),"Keyframe time (ms)");assertNotNull(time);time.setText("1000");button(dialog.getWindow().getDecorView(),"Preview loaded time").performClick();ShadowLooper.idleMainLooper();
            assertSame(live,field(fixture.activity,"livePlayer"));assertTrue(live.isEditedPreview());assertFalse("A loaded-time preview retains paused intent",live.snapshotState().playWhenReady);
            assertEquals("Explicit Preview loaded time must seek even when source revision is unchanged",1000,live.currentPositionMs());
            ((Runnable)field(fixture.activity,"studioCaptionClock")).run();assertEquals(1000,(long)field(fixture.activity,"studioPlayheadMs"));
            assertEquals("Previewing a keyframe does not write the project",before,SketchDocument.canonical(fixture.store.get(fixture.project.id).toJson()));dialog.dismiss();
        }
    }

    @Test public void blockedEditedSeekCannotRelabelARetainedSavedRenderAsTheEdit()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            LiveEditPlayer live=(LiveEditPlayer)field(fixture.activity,"livePlayer");java.lang.reflect.Field candidate=LiveEditPlayer.class.getDeclaredField("editPlayer");candidate.setAccessible(true);
            LiveEditPlayerRecoveryTest.failPlayback((androidx.media3.common.Player)candidate.get(live));
            assertEquals(View.VISIBLE,fixture.root.findViewWithTag("studio-preview-error").getVisibility());
            fixture.project.latestExportUri="file:///not-decoded-ui-isolation-render.mp4";invoke(fixture.activity,"playStudioLatest");String source=live.snapshotState().mediaUri;assertFalse(live.isEditedPreview());
            java.lang.reflect.Method scrub=MainActivity.class.getDeclaredMethod("scrubStudioTo",long.class);scrub.setAccessible(true);scrub.invoke(fixture.activity,1000L);fixture.layout();
            assertFalse("The existing error guard did not admit a replacement composition",live.isEditedPreview());assertEquals(source,live.snapshotState().mediaUri);
            assertTrue("The retained ordinary render must keep native controls",find(fixture.root,PlayerView.class).getUseController());
            assertReachable(fixture,button(fixture.root,"Return to edit"),"Retained render needs a truthful explicit edit action");
            assertFalse("Edited seeking cannot be active over a retained ordinary render",visible(fixture,(View)field(fixture.activity,"studioSeek")));
        }
    }

    @Test public void externalRenderRebuildCannotCompositeEditedTitleLayersOverTheRender()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            StudioLayer title=StudioLayer.fromJson(new JSONObject().put("id","ui_title_"+UUID.randomUUID()).put("type","title").put("text","Edited-only title").put("startMs",0).put("durationMs",4000));fixture.project.studioLayers.add(title);fixture.store.save(fixture.project);
            invoke(fixture.activity,"showEditor");ShadowLooper.idleMainLooper();fixture.layout();
            fixture.project.latestExportUri="file:///not-decoded-ui-title-isolation.mp4";invoke(fixture.activity,"playStudioLatest");LiveEditPlayer live=(LiveEditPlayer)field(fixture.activity,"livePlayer");assertFalse(live.isEditedPreview());
            invoke(fixture.activity,"showEditor");fixture.layout();assertFalse(live.isEditedPreview());
            TextView caption=(TextView)field(fixture.activity,"studioCaptionPreview");
            assertEquals("A selected external render must not receive edited TITLE overlays",View.GONE,caption.getVisibility());assertEquals("",caption.getText().toString());
        }
    }

    private static View description(View view,String value){if(value.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=description(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}return null;}

    private static final class Fixture implements AutoCloseable {
        final MainActivity activity;final ProjectStore store;final ProjectStore.Project project;final ViewGroup root;final int width,height;final File source;
        Fixture(int width,int height)throws Exception{
            this.width=width;this.height=height;Context context=RuntimeEnvironment.getApplication();store=new ProjectStore(context);project=store.create("SOURCE_ASSISTED native photo panel - keep this complete project identity - registered-"+UUID.randomUUID()+"-long-title");
            File folder=new File(context.getFilesDir(),"editor_workspace_ui_fixtures");assertTrue(folder.isDirectory()||folder.mkdirs());source=new File(folder,UUID.randomUUID()+".png");Bitmap bitmap=Bitmap.createBitmap(64,128,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff447788);bitmap.setPixel(1,2,0xffcc2244);try(FileOutputStream output=new FileOutputStream(source)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}
            ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned_"+UUID.randomUUID();asset.mime="image/png";asset.name="Own technical panel.png";asset.uri=Uri.fromFile(source).toString();asset.role="source";asset.sizeBytes=source.length();project.assets.add(asset);
            ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="base_"+UUID.randomUUID();clip.assetId=asset.id;clip.outMs=4000;clip.effects=new JSONObject().put("motionPreset","none");project.clips.add(clip);
            StudioLayer image=StudioLayer.fromJson(new JSONObject().put("id","image_"+UUID.randomUUID()).put("type","image").put("assetId",asset.id).put("startMs",0).put("durationMs",4000).put("width",.35).put("height",.39375).put("motionKeyframes",new JSONArray().put(new JSONObject().put("timeMs",0).put("x",-.1)).put(new JSONObject().put("timeMs",1000).put("x",0))));project.studioLayers.add(image);store.save(project);
            activity=Robolectric.buildActivity(MainActivity.class).get();set(activity,"store",store);set(activity,"prefs",context.getSharedPreferences("editor-workspace-ui",0));set(activity,"activeProject",project);set(activity,"selectedClip",clip);set(activity,"renderEngine",new NativeRenderEngine(activity));
            root=(ViewGroup)invoke(activity,"buildShell");activity.setContentView(root);invoke(activity,"showEditor");ShadowLooper.idleMainLooper();layout();
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
