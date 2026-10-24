package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

/** Native manual title-motion admission/drafts/guards; unit graph does not render GPU pixels. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,qualifiers="w480dp-h853dp-port-mdpi",shadows=StudioEditorPreviewGraphFixture.BuilderShadow.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class StudioTitleMotionUiTest {
    @Test public void selectedTitleInspectorAndMotionFooterExposeTheNativeTitleMotionEditor()throws Exception{
        try(Fixture fixture=new Fixture()){
            assertNotNull(text(fixture.root,"Title"));AlertDialog manager=open(fixture);
            assertNotNull(text(manager.getWindow().getDecorView(),"No motion keys. Static placement is retained."));
            manager.dismiss();Button motion=button(fixture.root,"Motion");assertNotNull(motion);motion.performClick();ShadowLooper.idleMainLooper();
            AlertDialog footer=ShadowAlertDialog.getLatestAlertDialog();assertNotSame(manager,footer);
            assertEquals("Animate title",org.robolectric.Shadows.shadowOf(footer).getTitle().toString());assertTrue(footer.isShowing());footer.dismiss();
        }
    }

    @Test public void titleLocalSamplerShowsOpacityScaleRotationAndCancelCannotSaveItsDraft()throws Exception{
        try(Fixture fixture=new Fixture()){
            String before=canonical(fixture.store.get(fixture.project.id));byte[] original=Files.readAllBytes(fixture.source.toPath());AlertDialog manager=open(fixture);View form=manager.getWindow().getDecorView();
            assertEquals("Global1700 minus title start1200 gives local500",500,Long.parseLong(input(form,"Title key time (ms)").getText().toString()));
            addPair(form);input(form,"Title key time (ms)").setText("500");button(form,"Load time").performClick();
            assertValue(form,"x",.1);assertValue(form,"y",-.05);assertValue(form,"scaleX",1.25);assertValue(form,"scaleY",.875);assertValue(form,"rotate",22.5);assertValue(form,"opacity",.8);
            assertNotNull(text(form,"Sampled motion at 500 ms · new key"));assertEquals(before,canonical(fixture.store.get(fixture.project.id)));
            Button apply=manager.getButton(AlertDialog.BUTTON_POSITIVE);manager.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();assertFalse(manager.isShowing());
            apply.performClick();assertEquals("Closed draft callbacks cannot write",before,canonical(fixture.store.get(fixture.project.id)));assertArrayEquals(original,Files.readAllBytes(fixture.source.toPath()));
        }
    }

    @Test public void manualKeysApplyAsOneUndoableSaveWithoutChangingTypographyOtherLayersOrSource()throws Exception{
        try(Fixture fixture=new Fixture()){
            String originalTitle=canonical(fixture.title.toJson()),base=withoutKeys(fixture.title),other=canonical(fixture.project.studioLayers.get(1).toJson());byte[] source=Files.readAllBytes(fixture.source.toPath());
            long revision=fixture.project.updatedAt;AlertDialog manager=open(fixture);View form=manager.getWindow().getDecorView();addPair(form);
            putKey(form,500,.3,-.1,1.3,.7,31,.6,"ease_in_out");assertEquals(revision,fixture.store.get(fixture.project.id).updatedAt);
            button(form,"Remove key at entered time").performClick();assertNull(text(form,"Key at 500 ms"));putKey(form,500,.3,-.1,1.3,.7,31,.6,"ease_in_out");
            Button apply=manager.getButton(AlertDialog.BUTTON_POSITIVE);apply.performClick();assertFalse(manager.isShowing());ProjectStore.Project saved=fixture.store.get(fixture.project.id);StudioLayer title=saved.studioLayers.get(0);
            assertTrue(saved.updatedAt>revision);assertEquals(3,title.toJson().getJSONArray("motionKeyframes").length());assertEquals(500,title.toJson().getJSONArray("motionKeyframes").getJSONObject(1).getLong("timeMs"));
            assertEquals(base,withoutKeys(title));assertEquals(other,canonical(saved.studioLayers.get(1).toJson()));assertEquals(6000,saved.outputDurationMs());assertEquals(1,saved.clips.get(0).speed,0);assertEquals(1,saved.assets.size());assertArrayEquals(source,Files.readAllBytes(fixture.source.toPath()));
            long applied=saved.updatedAt;apply.performClick();assertEquals("Already-applied handler cannot save twice",applied,fixture.store.get(fixture.project.id).updatedAt);
            invoke(fixture.activity,"restoreStudioHistory",new Class<?>[]{boolean.class},false);assertEquals(originalTitle,canonical(fixture.store.get(fixture.project.id).studioLayers.get(0).toJson()));
            invoke(fixture.activity,"restoreStudioHistory",new Class<?>[]{boolean.class},true);assertEquals(3,fixture.store.get(fixture.project.id).studioLayers.get(0).toJson().getJSONArray("motionKeyframes").length());
        }
    }

    @Test public void aDifferentProjectAtTheSameRevisionCannotReceiveTheOldTitleDraft()throws Exception{
        try(Fixture fixture=new Fixture()){
            AlertDialog manager=open(fixture);putKey(manager.getWindow().getDecorView(),500,.2,0,1,1,0,1,"linear");
            ProjectStore.Project other=fixture.store.create("Different title target");String first=canonical(fixture.store.get(fixture.project.id)),second=canonical(fixture.store.get(other.id));other.updatedAt=fixture.project.updatedAt;
            set(fixture.activity,"activeProject",other);manager.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(manager.isShowing());
            assertEquals(first,canonical(fixture.store.get(fixture.project.id)));assertEquals(second,canonical(fixture.store.get(other.id)));
        }
    }

    @Test public void aNewerStoredRevisionRejectsTheTitleDraftAndKeepsItsEnteredValues()throws Exception{
        try(Fixture fixture=new Fixture()){
            AlertDialog manager=open(fixture);View form=manager.getWindow().getDecorView();putKey(form,500,.4,0,1,1,17,.7,"linear");
            ProjectStore.Project newer=fixture.store.get(fixture.project.id);newer.name="Preserve the newer edit";fixture.store.save(newer);String expected=canonical(fixture.store.get(newer.id));
            manager.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(manager.isShowing());assertEquals("0.4",input(form,"Title motion x").getText().toString());assertEquals(expected,canonical(fixture.store.get(newer.id)));
        }
    }

    @Test public void leavingAndReopeningTheSameEditorInvalidatesTheOldTitleDraft()throws Exception{
        try(Fixture fixture=new Fixture()){
            AlertDialog manager=open(fixture);putKey(manager.getWindow().getDecorView(),500,.4,0,1,1,0,1,"linear");String before=canonical(fixture.store.get(fixture.project.id));
            invoke(fixture.activity,"setScreen",new Class<?>[]{View.class,String.class},new FrameLayout(fixture.activity),"home");invoke(fixture.activity,"setScreen",new Class<?>[]{View.class,String.class},new FrameLayout(fixture.activity),"editor");
            manager.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(manager.isShowing());assertEquals(before,canonical(fixture.store.get(fixture.project.id)));
        }
    }

    @Test public void clearIsDraftOnlyAndExplicitEmptyKeysCanBeUndone()throws Exception{
        try(Fixture fixture=new Fixture()){
            AlertDialog first=open(fixture);addPair(first.getWindow().getDecorView());first.getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();fixture.layout();
            AlertDialog manager=open(fixture);View form=manager.getWindow().getDecorView();String keyed=canonical(fixture.store.get(fixture.project.id).studioLayers.get(0).toJson());
            button(form,"Clear draft keys").performClick();assertEquals(2,fixture.store.get(fixture.project.id).studioLayers.get(0).toJson().getJSONArray("motionKeyframes").length());
            assertValue(form,"x",0);assertValue(form,"scaleX",1);assertValue(form,"rotate",0);assertValue(form,"opacity",1);
            manager.getButton(AlertDialog.BUTTON_POSITIVE).performClick();JSONObject cleared=fixture.store.get(fixture.project.id).studioLayers.get(0).toJson();assertTrue(cleared.has("motionKeyframes"));assertEquals(0,cleared.getJSONArray("motionKeyframes").length());
            invoke(fixture.activity,"restoreStudioHistory",new Class<?>[]{boolean.class},false);assertEquals(keyed,canonical(fixture.store.get(fixture.project.id).studioLayers.get(0).toJson()));
        }
    }

    @Test public void ordinaryCaptionStyleEditingPreservesTheSavedMotionRecipe()throws Exception{
        try(Fixture fixture=new Fixture()){
            AlertDialog first=open(fixture);addPair(first.getWindow().getDecorView());first.getButton(AlertDialog.BUTTON_POSITIVE).performClick();ShadowLooper.idleMainLooper();fixture.layout();
            String keys=fixture.store.get(fixture.project.id).studioLayers.get(0).toJson().getJSONArray("motionKeyframes").toString();Button edit=button(fixture.root,"Edit caption");assertNotNull(edit);edit.performClick();ShadowLooper.idleMainLooper();
            AlertDialog caption=ShadowAlertDialog.getLatestAlertDialog();assertEquals("Timed Caption",org.robolectric.Shadows.shadowOf(caption).getTitle().toString());EditText size=caption.getWindow().getDecorView().findViewWithTag("fontSize");assertNotNull(size);size.setText("52");caption.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            StudioLayer saved=fixture.store.get(fixture.project.id).studioLayers.get(0);assertEquals(52,saved.fontSize);assertEquals(keys,saved.toJson().getJSONArray("motionKeyframes").toString());assertEquals(fixture.title.text,saved.text);assertEquals(fixture.title.position,saved.position);
        }
    }

    private static AlertDialog open(Fixture fixture)throws Exception{
        Button animate=button(fixture.root,"Animate title");assertNotNull("Selected TITLE inspector must expose Animate title",animate);animate.performClick();ShadowLooper.idleMainLooper();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);assertEquals("Animate title",org.robolectric.Shadows.shadowOf(dialog).getTitle().toString());assertTrue(dialog.isShowing());return dialog;
    }
    private static void addPair(View form){putKey(form,0,0,0,1,1,0,1,"linear");putKey(form,2000,.4,-.2,2,.5,90,.2,"hold");}
    private static void putKey(View form,long at,double x,double y,double sx,double sy,double rotate,double opacity,String ease){
        input(form,"Title key time (ms)").setText(Long.toString(at));String[] fields={"x","y","scaleX","scaleY","rotate","opacity"};double[] values={x,y,sx,sy,rotate,opacity};for(int i=0;i<fields.length;i++)input(form,"Title motion "+fields[i]).setText(Double.toString(values[i]));Spinner easing=(Spinner)description(form,"Title motion easing");assertNotNull(easing);easing.setSelection(java.util.Arrays.asList("linear","ease_in","ease_out","ease_in_out","hold").indexOf(ease));button(form,"Add / update key").performClick();assertNotNull(text(form,"Key at "+at+" ms"));
    }
    private static void assertValue(View form,String field,double expected){assertEquals(field,expected,Double.parseDouble(input(form,"Title motion "+field).getText().toString()),.000000001);}
    private static String withoutKeys(StudioLayer title)throws Exception{JSONObject copy=title.toJson();copy.remove("motionKeyframes");return canonical(copy);}
    private static String canonical(ProjectStore.Project project)throws Exception{return canonical(project.toJson());}
    private static String canonical(JSONObject value)throws Exception{return SketchDocument.canonical(value);}

    private static final class Fixture implements AutoCloseable {
        final MainActivity activity;final ProjectStore store;final ProjectStore.Project project;final StudioLayer title;final ViewGroup root;final File source;
        Fixture()throws Exception{
            Context context=RuntimeEnvironment.getApplication();store=new ProjectStore(context);project=store.create("Title motion manual QA");File directory=new File(context.getFilesDir(),"title_motion_ui_fixtures");assertTrue(directory.isDirectory()||directory.mkdirs());source=new File(directory,UUID.randomUUID()+".png");Bitmap bitmap=Bitmap.createBitmap(32,64,Bitmap.Config.ARGB_8888);bitmap.eraseColor(0xff536e60);try(FileOutputStream output=new FileOutputStream(source)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}
            ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned_"+UUID.randomUUID();asset.mime="image/png";asset.name="Native technical source.png";asset.uri=Uri.fromFile(source).toString();asset.sizeBytes=source.length();project.assets.add(asset);ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="clip_"+UUID.randomUUID();clip.assetId=asset.id;clip.outMs=6000;project.clips.add(clip);
            // Deliberately omit motionKeyframes so frozen525 can reach the missing manual entry.
            title=StudioLayer.fromJson(new JSONObject().put("id","title_"+UUID.randomUUID()).put("type","title").put("text","Keep title structure\nand detailed style").put("startMs",1200).put("durationMs",2000).put("position",0).put("fontSize",48).put("fontFamily","serif").put("fontStyle","bold_italic").put("textAlign","right").put("color",0xffb0c8d0).put("backgroundColor",0x88445566));project.studioLayers.add(title);
            StudioLayer image=StudioLayer.fromJson(new JSONObject().put("id","other_image_"+UUID.randomUUID()).put("type","image").put("assetId",asset.id).put("durationMs",6000).put("width",.2).put("height",.3));project.studioLayers.add(image);store.save(project);
            activity=Robolectric.buildActivity(MainActivity.class).get();set(activity,"store",store);set(activity,"prefs",context.getSharedPreferences("title-motion-ui",0));set(activity,"activeProject",project);set(activity,"selectedClip",clip);set(activity,"renderEngine",new NativeRenderEngine(activity));set(activity,"studioSelectedLayerId",title.id);set(activity,"studioInspectorProjectId",project.id);set(activity,"studioWorkspacePane","Inspector");set(activity,"studioPlayheadMs",1700L);
            root=(ViewGroup)invoke(activity,"buildShell",new Class<?>[]{});activity.setContentView(root);invoke(activity,"showEditor",new Class<?>[]{});ShadowLooper.idleMainLooper();layout();
        }
        void layout(){root.measure(View.MeasureSpec.makeMeasureSpec(480,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(853,View.MeasureSpec.EXACTLY));root.layout(0,0,480,853);}
        @Override public void close()throws Exception{AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();if(dialog!=null)dialog.dismiss();invoke(activity,"onDestroy",new Class<?>[]{});Field db=ProjectStore.class.getDeclaredField("db");db.setAccessible(true);((android.database.sqlite.SQLiteDatabase)db.get(store)).close();assertTrue(source.delete());}
    }
    private static Object invoke(Object object,String name,Class<?>[] types,Object... values)throws Exception{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(name,types);method.setAccessible(true);return method.invoke(object,values);}
    private static void set(Object object,String name,Object value)throws Exception{Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(object,value);}
    private static View description(View view,String value){if(value.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=description(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static EditText input(View view,String value){View found=description(view,value);assertTrue("Missing native title control: "+value,found instanceof EditText);return (EditText)found;}
    private static TextView text(View view,String value){if(view instanceof TextView&&value.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){TextView found=text(((ViewGroup)view).getChildAt(i),value);if(found!=null)return found;}return null;}
    private static Button button(View view,String value){TextView found=text(view,value);return found instanceof Button?(Button)found:null;}
}
