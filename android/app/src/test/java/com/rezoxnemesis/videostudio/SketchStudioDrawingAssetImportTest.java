package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchStudioDrawingAssetImportTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final class Fixture {
        final ProjectStore projects=new ProjectStore(context);
        final ProjectStore.Project project=projects.create("Portrait source project");
        final String drawing="portrait_"+UUID.randomUUID().toString().replace("-","");
        final String current="current_"+UUID.randomUUID().toString().replace("-","");
        Fixture()throws Exception {
            SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",current).put("operationId","create").put("width",256).put("height",256));
            byte[] source="Draw an original pencil portrait.".getBytes(StandardCharsets.UTF_8);
            JSONObject header=new JSONObject().put("schema","videostudio.sketch.frame-ops.v1")
                    .put("targetSketchId",drawing).put("sourceSha256",SketchDocument.sha256(source))
                    .put("targetWidth",256).put("targetHeight",256).put("fps",24).put("frameCount",1)
                    .put("name","Pencil portrait").put("layers",new JSONArray());
            JSONObject mark=new JSONObject().put("type","add_object").put("layerId","layer_1")
                    .put("object",new JSONObject().put("id","authored_ink").put("kind","path").put("frame",0).put("exposure",1)
                            .put("commands",new JSONArray("[[\"M\",0.1,0.2],[\"L\",0.7,0.8]]")));
            byte[] plain=(new JSONObject().put("header",header).put("backgroundOperations",new JSONArray())+"\n"+
                    new JSONObject().put("frame",0).put("operations",new JSONArray().put(mark))+"\n").getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream encoded=new ByteArrayOutputStream();try(GZIPOutputStream gzip=new GZIPOutputStream(encoded)){gzip.write(plain);}
            File folder=new File(context.getFilesDir(),"imports/"+UUID.randomUUID());assertTrue(folder.mkdirs());
            File packet=new File(folder,"packet.media"),prompt=new File(folder,"source.media");
            Files.write(packet.toPath(),encoded.toByteArray());Files.write(prompt.toPath(),source);
            projects.registerImportedAsset(project.id,Uri.fromFile(packet),"portrait.jsonl.gz","application/octet-stream",0,packet.length());
            projects.registerImportedAsset(project.id,Uri.fromFile(prompt),"prompt.txt","text/plain",0,prompt.length());
        }
    }
    private ActivityController<SketchStudioActivity> open(Fixture fixture)throws Exception {
        ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",fixture.current));
        controller.get().getPreferences(0).edit().putBoolean("watchLiveDrawing",false).putBoolean("canvasFocus",false).commit();
        controller.create().start().resume().visible();settle(controller.get());return controller;
    }
    private void settings(SketchStudioActivity studio)throws Exception {
        invoke(studio,"importDrawingDialog");choose("Imported project files");settle(studio);
        choose("Portrait source project");settle(studio);choose("portrait.jsonl.gz");settle(studio);
        choose("prompt.txt");settle(studio);
    }
    @Test public void humanImportSelectsOwnedFilesWithoutEnteringIdsOrHashesAndRequiresADeclaredMethod()throws Exception {
        Fixture fixture=new Fixture();
        try(ActivityController<SketchStudioActivity> controller=open(fixture)) {
            SketchStudioActivity studio=controller.get();settings(studio);
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            Spinner method=(Spinner)find(dialog.getWindow().getDecorView(),"Drawing method");assertNotNull(method);
            assertEquals("Original provenance must never be preselected",0,method.getSelectedItemPosition());
            assertNull(find(dialog.getWindow().getDecorView(),"New drawing ID"));
            assertNull(find(dialog.getWindow().getDecorView(),"SHA-256"));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertTrue(dialog.isShowing());
            method.setSelection(2);dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(studio);
            assertEquals(fixture.drawing,field(studio,"sketchId"));
            try(SketchProjectStore sketches=new SketchProjectStore(context)) {
                assertNotNull(SketchDocument.object(sketches.get(fixture.drawing),"authored_ink"));
                JSONObject live=sketches.nextLiveSession(0);assertNotNull(live);
                assertEquals("original_strokes",SketchDrawingPolicy.method(live));assertTrue(live.getBoolean("ended"));
                assertEquals(1,sketches.get(fixture.current).getLong("revision"));
            }
        }
    }
    @Test public void humanImportRetainsTheChosenProjectRevisionUntilNativeAdmission()throws Exception {
        Fixture fixture=new Fixture();
        try(ActivityController<SketchStudioActivity> controller=open(fixture)) {
            SketchStudioActivity studio=controller.get();settings(studio);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            ((Spinner)find(dialog.getWindow().getDecorView(),"Drawing method")).setSelection(1);
            ProjectStore.Project changed=fixture.projects.get(fixture.project.id);changed.name="Human renamed source";fixture.projects.save(changed);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();settle(studio);
            assertEquals(fixture.current,field(studio,"sketchId"));
            try(SketchProjectStore sketches=new SketchProjectStore(context)){try{sketches.get(fixture.drawing);fail("Stale selection created a drawing");}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().startsWith("UNKNOWN_SKETCH"));}}
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("SOURCE_PROJECT_CHANGED"));
        }
    }
    @Test public void linkedWorkspaceImportRemainsAvailableWithoutAnInternalIdentityField()throws Exception {
        Fixture fixture=new Fixture();
        try(ActivityController<SketchStudioActivity> controller=open(fixture)) {
            SketchStudioActivity studio=controller.get();invoke(studio,"importDrawingDialog");choose("Linked workspace folder");
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(find(dialog.getWindow().getDecorView(),"JSONL in your linked workspace"));
            assertNotNull(find(dialog.getWindow().getDecorView(),"Source image, video or prompt TXT in that folder"));
            assertNull(find(dialog.getWindow().getDecorView(),"New drawing ID"));
        }
    }
    private static void settle(SketchStudioActivity studio)throws Exception {for(int i=0;i<4;i++){((ExecutorService)field(studio,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
    private static Object field(Object owner,String name)throws Exception {Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);}
    private static void invoke(Object owner,String name)throws Exception {Method m=owner.getClass().getDeclaredMethod(name);m.setAccessible(true);m.invoke(owner);}
    private static void choose(String text){AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();ListView list=dialog.getListView();assertNotNull("Missing source choice "+text,list);for(int i=0;i<list.getCount();i++)if(text.equals(String.valueOf(list.getAdapter().getItem(i)))){list.performItemClick(list.getAdapter().getView(i,null,list),i,i);return;}fail("Missing import choice "+text);}
    private static View find(View view,String description){if(description.contentEquals(view.getContentDescription()==null?"":view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=find(group.getChildAt(i),description);if(found!=null)return found;}}return null;}
}
