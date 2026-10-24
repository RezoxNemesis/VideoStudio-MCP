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

/** Native View bounds and real existing handlers; not an app screenshot or GPU/export proof. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows=StudioEditorPreviewGraphFixture.BuilderShadow.class,qualifiers="w480dp-h853dp-port-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class StudioEditorWorkspaceUiTest {
    @Test public void portraitShowsActualTimelineAndSharedControlsWithoutWholePageScrolling()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            assertReachable(fixture,(View)field(fixture.activity,"studioTracks"),"Actual editable tracks must be visible when Editor opens");
            assertReachable(fixture,(View)field(fixture.activity,"studioPreviewFrame"),"The native preview must remain visible with the timeline");
            assertReachable(fixture,(View)field(fixture.activity,"studioSeek"),"The shared edited seek control must remain visible");
            assertReachable(fixture,(View)field(fixture.activity,"studioTimeLabel"),"The edited time readout must remain visible");
            assertReachable(fixture,button(fixture.root,"Export"),"Export must stay outside a scrolling pane");
            for(String tab:new String[]{"Media","Timeline","Inspector"})assertReachable(fixture,button(fixture.root,tab),"Lower workspace pane must be reachable: "+tab);
        }
    }

    @Test public void longProjectNameCannotWrapTheFixedHeaderOrDisplaceExport()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            Button selector=projectButton(fixture.root,fixture.project.name);assertNotNull("Preserve access to the exact complete project name",selector);
            assertEquals("Only the displayed project title is a single compact line",1,selector.getLineCount());
            assertTrue("The project header must not grow into a title card",selector.getHeight()<=StudioUi.dp(fixture.activity,64));
            assertReachable(fixture,selector,"Project selection must stay reachable");assertReachable(fixture,button(fixture.root,"Export"),"Export must not be pushed below the viewport");
            assertTrue(selector.getText().toString().contains(fixture.project.name)||String.valueOf(selector.getContentDescription()).contains(fixture.project.name));
        }
    }

    @Test public void editedPreviewUsesAppTransportWithoutASecondPlayerViewController()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            PlayerView player=find(fixture.root,PlayerView.class);assertNotNull("Retain the existing native PlayerView",player);
            assertFalse("Do not overlay a duplicate PlayerView controller over the edited picture",player.getUseController());
            assertReachable(fixture,(View)field(fixture.activity,"studioSeek"),"The app transport must retain edited seeking");
            assertReachable(fixture,(View)field(fixture.activity,"studioTimeLabel"),"The app transport must retain edited time");
        }
    }

    @Test @Config(qualifiers="w853dp-h480dp-land-mdpi")
    public void landscapeKeepsPreviewTracksExportAndPaneNavigationReachable()throws Exception{
        try(Fixture fixture=new Fixture(853,480)){
            assertReachable(fixture,(View)field(fixture.activity,"studioTracks"),"Landscape must adapt instead of vertically hiding its tracks");
            View preview=(View)field(fixture.activity,"studioPreviewFrame");assertReachable(fixture,preview,"Landscape preview must remain visible");
            assertTrue("Preview must retain usable drawable space",preview.getHeight()>=StudioUi.dp(fixture.activity,96));
            assertReachable(fixture,button(fixture.root,"Export"),"Landscape Export remains reachable");
            for(String tab:new String[]{"Media","Timeline","Inspector"})assertReachable(fixture,button(fixture.root,tab),"Landscape pane action must be reachable: "+tab);
        }
    }

    @Test public void switchingPanesPreservesPreviewProjectSelectionClockAndUndoHistory()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            StudioEditHistory history=(StudioEditHistory)field(fixture.activity,"editHistory");history.checkpoint(fixture.project);
            Object preview=field(fixture.activity,"studioPreviewFrame"),selected=field(fixture.activity,"selectedClip");
            long playhead=(long)field(fixture.activity,"studioPlayheadMs");String before=SketchDocument.canonical(fixture.store.get(fixture.project.id).toJson());
            for(String pane:new String[]{"Media","Inspector","Timeline","Media","Timeline"}){
                click(fixture,pane);fixture.layout();
                assertSame("Pane changes must not rebuild or reattach the native preview",preview,field(fixture.activity,"studioPreviewFrame"));
                assertEquals(((ProjectStore.Clip)selected).id,((ProjectStore.Clip)field(fixture.activity,"selectedClip")).id);
                assertEquals("Pane selection must not seek the video",playhead,(long)field(fixture.activity,"studioPlayheadMs"));
                assertTrue("Presentation navigation must retain ordinary Undo",history.canUndo());
                assertEquals("Pane changes must not write or replace a project",before,SketchDocument.canonical(fixture.store.get(fixture.project.id).toJson()));
            }
        }
    }

    @Test public void mediaAndBaseInspectorUseExistingImportCropAndKeyframeHandlers()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            String before=SketchDocument.canonical(fixture.store.get(fixture.project.id).toJson());click(fixture,"Media");fixture.layout();
            click(fixture,"+ Import");Intent picker=Shadows.shadowOf(fixture.activity).getNextStartedActivityForResult().intent;
            assertEquals(Intent.ACTION_OPEN_DOCUMENT,picker.getAction());assertTrue(picker.getCategories().contains(Intent.CATEGORY_OPENABLE));
            assertArrayEquals(new String[]{"video/*","image/*","audio/*"},picker.getStringArrayExtra(Intent.EXTRA_MIME_TYPES));
            click(fixture,"Import from project");AlertDialog imported=ShadowAlertDialog.getLatestAlertDialog();assertEquals("Import from project",Shadows.shadowOf(imported).getTitle().toString());imported.dismiss();ShadowLooper.idleMainLooper();
            click(fixture,"Inspector");fixture.layout();click(fixture,"Crop");AlertDialog crop=ShadowAlertDialog.getLatestAlertDialog();assertEquals("Crop / reframe source",Shadows.shadowOf(crop).getTitle().toString());crop.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();ShadowLooper.idleMainLooper();
            click(fixture,"Keyframes");AlertDialog keys=ShadowAlertDialog.getLatestAlertDialog();assertEquals("Transform keyframe manager",Shadows.shadowOf(keys).getTitle().toString());keys.dismiss();
            assertEquals(before,SketchDocument.canonical(fixture.store.get(fixture.project.id).toJson()));
        }
    }

    @Test public void selectingImageTrackExposesImageMotionInsteadOfBaseClipCrop()throws Exception{
        try(Fixture fixture=new Fixture(480,853)){
            click(fixture,"Timeline");fixture.layout();StudioTracksView tracks=(StudioTracksView)field(fixture.activity,"studioTracks");assertReachable(fixture,tracks,"Image track must be visible before selecting it");
            float density=fixture.activity.getResources().getDisplayMetrics().density;
            float x=56*density+(tracks.getWidth()-66*density)*.5f,y=90*density;
            MotionEvent down=MotionEvent.obtain(0,10,MotionEvent.ACTION_DOWN,x,y,0),up=MotionEvent.obtain(0,20,MotionEvent.ACTION_UP,x,y,0);
            try{assertTrue(tracks.onTouchEvent(down));assertTrue(tracks.onTouchEvent(up));}finally{down.recycle();up.recycle();}
            ShadowLooper.idleMainLooper();AlertDialog unexpected=ShadowAlertDialog.getLatestAlertDialog();
            assertTrue("Selecting a target must update its Inspector instead of launching an unrelated modal",unexpected==null||!unexpected.isShowing());
            click(fixture,"Inspector");fixture.layout();Button animate=button(fixture.root,"Animate overlay");assertReachable(fixture,animate,"The image Inspector must expose its existing native motion editor");
            Button crop=button(fixture.root,"Crop");assertTrue("Do not advertise base source crop for an image-overlay target",crop==null||!visible(fixture,crop));
            animate.performClick();ShadowLooper.idleMainLooper();AlertDialog motion=ShadowAlertDialog.getLatestAlertDialog();assertEquals("Animate image overlay",Shadows.shadowOf(motion).getTitle().toString());
            assertNotNull(text(motion.getWindow().getDecorView(),"Key at 1000 ms"));motion.dismiss();
        }
    }

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
