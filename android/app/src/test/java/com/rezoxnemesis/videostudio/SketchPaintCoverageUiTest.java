package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Spinner;
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
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchPaintCoverageUiTest {
    private Object field(Object target,String name)throws Exception {Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private Object invoke(Object target,String name)throws Exception {Method m=target.getClass().getDeclaredMethod(name);m.setAccessible(true);return m.invoke(target);}
    private void waitIo(SketchStudioActivity studio)throws Exception {((ExecutorService)field(studio,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    private Spinner coverage(View view) {
        if(view instanceof Spinner && "Stroke coverage".equals(String.valueOf(view.getContentDescription())))return (Spinner)view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){Spinner found=coverage(((ViewGroup)view).getChildAt(i));if(found!=null)return found;}
        return null;
    }
    private void select(Spinner spinner,String text) {for(int i=0;i<spinner.getCount();i++)if(text.equals(String.valueOf(spinner.getItemAtPosition(i)))){spinner.setSelection(i);return;}fail("Missing actual raster coverage option "+text);}
    @Test public void humanCoverageControlKeepsOmittedDefaultAndRestoresExplicitBrushChoice()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String id=UUID.randomUUID().toString();
        SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new").put("width",256).put("height",256).put("frameCount",2));
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()) {
            SketchStudioActivity studio=controller.get();((ExecutorService)field(studio,"io")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();
            JSONObject original=(JSONObject)invoke(studio,"paintStyle");assertFalse(original.has("coverage"));
            invoke(studio,"paintSettings");AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();Spinner spinner=coverage(dialog.getWindow().getDecorView());
            assertNotNull("Paint settings need an actual shared raster Stroke coverage control",spinner);
            select(spinner,"Keep soft profile");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();waitIo(studio);
            JSONObject maximum=(JSONObject)invoke(studio,"paintStyle");assertEquals("maximum",maximum.getString("coverage"));
            JSONObject stripped=maximum;stripped.remove("coverage");assertEquals(SketchDocument.canonical(original),SketchDocument.canonical(stripped));
            invoke(studio,"paintSettings");dialog=ShadowAlertDialog.getLatestAlertDialog();spinner=coverage(dialog.getWindow().getDecorView());assertEquals("Keep soft profile",spinner.getSelectedItem().toString());
            select(spinner,"Build up");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();waitIo(studio);assertEquals("accumulate",((JSONObject)invoke(studio,"paintStyle")).getString("coverage"));
            invoke(studio,"paintSettings");dialog=ShadowAlertDialog.getLatestAlertDialog();spinner=coverage(dialog.getWindow().getDecorView());select(spinner,"Brush default (build up)");dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();waitIo(studio);
            assertEquals(SketchDocument.canonical(original),SketchDocument.canonical((JSONObject)invoke(studio,"paintStyle")));
        }
    }
}
