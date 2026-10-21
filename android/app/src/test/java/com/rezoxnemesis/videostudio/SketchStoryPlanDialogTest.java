package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static org.junit.Assert.*;

/** Manual semantic JSON uses the same product build and native preview as saved programmes. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchStoryPlanDialogTest {
    private JSONObject plan()throws Exception {
        JSONArray commands=new JSONArray().put(new JSONArray().put("M").put(.35).put(.35)).put(new JSONArray().put("L").put(.65).put(.35))
                .put(new JSONArray().put("L").put(.65).put(.65)).put(new JSONArray().put("L").put(.35).put(.65)).put(new JSONArray().put("Z"));
        JSONArray ops=new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject()
                .put("id","subject").put("kind","path").put("commands",commands).put("fill",true).put("color","#FFEDB25C").put("exposure",24)));
        JSONObject shot=new JSONObject().put("shotId","arrival").put("frameCount",24).put("operations",ops).put("camera",new JSONArray()
                .put(new JSONObject().put("frame",0).put("zoom",1)).put(new JSONObject().put("frame",23).put("zoom",1.15)));
        return new JSONObject().put("bookId","manual_"+UUID.randomUUID().toString().replace("-","")).put("operationId","build")
                .put("name","Manual semantic plan").put("width",320).put("height",480).put("fps",24).put("shots",new JSONArray().put(shot));
    }
    private AlertDialog dialog(){return ShadowAlertDialog.getLatestAlertDialog();}
    private View root(){return dialog().getWindow().getDecorView();}
    private <T extends View>T field(View v,String name,Class<T> type){if(type.isInstance(v)&&name.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()))return type.cast(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){T f=field(((ViewGroup)v).getChildAt(i),name,type);if(f!=null)return f;}return null;}
    private Button button(View v,String name){if(v instanceof Button&&name.contentEquals(((Button)v).getText()))return (Button)v;if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){Button b=button(((ViewGroup)v).getChildAt(i),name);if(b!=null)return b;}return null;}
    private EditText json(){return field(root(),"Story plan JSON",EditText.class);}
    private TextView status(){return field(root(),"Story plan status",TextView.class);}
    private void click(String label){Button b=button(root(),label);assertNotNull(label,b);assertTrue(label,b.isEnabled());b.performClick();}
    private void await(BooleanSupplier condition)throws Exception {long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(30);while(System.nanoTime()<until){ShadowLooper.idleMainLooper();if(condition.getAsBoolean())return;Thread.sleep(15);}fail(status().getText().toString());}
    private void idle()throws Exception {await(()->button(root(),"Build / resume").isEnabled());}
    private void noBook(Activity a,JSONObject p)throws Exception{try(SketchBookStore books=new SketchBookStore(a)){assertThrows(IllegalArgumentException.class,()->books.get(p.getString("bookId")));}assertEquals(0,SketchAnimationOps.execute(a,"sketch_animation_list",new JSONObject()).getJSONArray("animations").length());}

    @Test public void selectedNativePreviewDoesNotPublishAndMatchesTheRealBuiltBookPixels()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject p=plan();List<String> opened=new ArrayList<>();SketchStoryPlanDialog.show(a.get(),p,opened::add);
            assertEquals(SketchDocument.canonical(p),SketchDocument.canonical(new JSONObject(json().getText().toString())));
            field(root(),"Preview frame (1 based)",EditText.class).setText("18");click("Preview shot");idle();assertTrue(status().getText().toString().contains("Native preview"));noBook(a.get(),p);
            ImageView preview=field(root(),"Story plan preview",ImageView.class);assertTrue(preview.getDrawable() instanceof BitmapDrawable);Bitmap sample=((BitmapDrawable)preview.getDrawable()).getBitmap().copy(Bitmap.Config.ARGB_8888,false);
            try{
                click("Build / resume");idle();assertTrue(status().getText().toString(),status().getText().toString().contains("Book ready"));assertTrue(opened.isEmpty());
                try(SketchProjectStore sketches=new SketchProjectStore(a.get());SketchFrameRenderer renderer=new SketchFrameRenderer(a.get(),sketches.get(p.getString("bookId")+"_p17"))){Bitmap cel=renderer.render(0);try{assertTrue("Manual preview and shared native product build must agree",sample.sameAs(cel));}finally{cel.recycle();}}
                click("Open book");assertEquals(Collections.singletonList(p.getString("bookId")),opened);
            }finally{sample.recycle();}
        }
    }

    @Test public void malformedJsonInvalidPreviewClockAndLateNativeErrorRetainControlsAndPublishNothing()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject p=plan();SketchStoryPlanDialog.show(a.get(),p,id->fail("Must not open a failed build"));
            json().setText("bad JSON");click("Build / resume");assertEquals("bad JSON",json().getText().toString());noBook(a.get(),p);
            json().setText(p.toString());field(root(),"Preview frame (1 based)",EditText.class).setText("25");click("Preview shot");idle();assertTrue(status().getText().toString().contains("frame"));noBook(a.get(),p);
            p.getJSONArray("shots").getJSONObject(0).getJSONArray("operations").put(new JSONObject().put("type","unknown_native_operation"));json().setText(p.toString());click("Build / resume");idle();
            assertEquals(p.toString(),json().getText().toString());assertFalse(button(root(),"Open book").isEnabled());noBook(a.get(),p);dialog().dismiss();
        }
    }

    @Test public void savedExactRequestReopensAndCompletedRetryPreservesHumanPageEdits()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject p=plan();SketchStoryPlanDialog.show(a.get(),p,id->{});click("Build / resume");idle();assertTrue(status().getText().toString().contains("Book ready"));dialog().dismiss();
            String id=p.getString("bookId")+"_p0";try(SketchProjectStore sketches=new SketchProjectStore(a.get())){sketches.mutate(id,"human",1,"a".repeat(64),d->d.put("name","Human cel edits"));}
            SketchStoryPlanDialog.show(a.get(),id2->{});idle();assertEquals(SketchDocument.canonical(p),SketchDocument.canonical(new JSONObject(json().getText().toString())));
            click("Build / resume");idle();assertTrue(status().getText().toString().contains("Book ready"));try(SketchProjectStore sketches=new SketchProjectStore(a.get())){assertEquals("Human cel edits",sketches.get(id).getString("name"));}dialog().dismiss();
        }
    }

    @Test public void cancellationKeepsExactJsonAndAllowsRetryWithoutChangingOperationIdentity()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject p=plan();SketchStoryPlanDialog.show(a.get(),p,id->{});String original=json().getText().toString();click("Build / resume");assertFalse(json().isEnabled());click("Cancel work");idle();
            assertEquals(original,json().getText().toString());assertTrue(status().getText().toString().contains("cancel")||status().getText().toString().contains("Book ready"));
            click("Build / resume");idle();assertTrue(status().getText().toString(),status().getText().toString().contains("Book ready"));assertEquals("build",new JSONObject(json().getText().toString()).getString("operationId"));dialog().dismiss();
        }
    }

    @Test public void editedInputClearsStalePixelsAndOpenBookTarget()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject p=plan();SketchStoryPlanDialog.show(a.get(),p,id->{});click("Preview shot");idle();assertNotNull(field(root(),"Story plan preview",ImageView.class).getDrawable());
            json().setText(p.toString(2));assertNull(field(root(),"Story plan preview",ImageView.class).getDrawable());click("Build / resume");idle();assertTrue(button(root(),"Open book").isEnabled());
            json().setText("{}");assertFalse(button(root(),"Open book").isEnabled());dialog().dismiss();
        }
    }

    @Test public void originalExampleGetsFreshIdsOnlyOnExplicitLoadAndKeepsExactRetryIdentity()throws Exception {
        try(ActivityController<Activity> a=Robolectric.buildActivity(Activity.class).setup()){
            JSONObject shipped;try(java.io.InputStream in=a.get().getAssets().open("animation_stories/the-last-lantern.json");java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream()){
                byte[] buffer=new byte[32768];int count;while((count=in.read(buffer))!=-1)bytes.write(buffer,0,count);shipped=new JSONObject(new String(bytes.toByteArray(),java.nio.charset.StandardCharsets.UTF_8));
            }
            SketchStoryPlanDialog.show(a.get(),plan(),id->{});click("Load original example");idle();JSONObject first=new JSONObject(json().getText().toString());
            assertNotEquals(shipped.getString("bookId"),first.getString("bookId"));assertNotEquals(shipped.getString("operationId"),first.getString("operationId"));assertTrue(first.getString("bookId").length()<=48);
            JSONObject expected=SketchDocument.copy(shipped).put("bookId",first.getString("bookId")).put("operationId",first.getString("operationId"));assertEquals(SketchDocument.canonical(expected),SketchDocument.canonical(first));noBook(a.get(),first);
            // Cancel before full-plan publication: this exercises the real Build / resume control
            // without publishing thousands of story cels solely to test identity management.
            for(int retry=0;retry<2;retry++){click("Build / resume");click("Cancel work");idle();assertEquals(SketchDocument.canonical(first),SketchDocument.canonical(new JSONObject(json().getText().toString())));noBook(a.get(),first);}
            dialog().dismiss();SketchStoryPlanDialog.show(a.get(),id->{});idle();assertEquals(SketchDocument.canonical(first),SketchDocument.canonical(new JSONObject(json().getText().toString())));
            click("Load original example");idle();JSONObject second=new JSONObject(json().getText().toString());assertNotEquals(first.getString("bookId"),second.getString("bookId"));assertNotEquals(first.getString("operationId"),second.getString("operationId"));
            dialog().dismiss();SketchStoryPlanDialog.show(a.get(),id->{});idle();assertEquals(SketchDocument.canonical(second),SketchDocument.canonical(new JSONObject(json().getText().toString())));noBook(a.get(),second);dialog().dismiss();
        }
    }
}
