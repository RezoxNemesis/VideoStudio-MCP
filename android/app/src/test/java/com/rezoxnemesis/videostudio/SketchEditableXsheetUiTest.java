package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Exercise the actual long-press source-drawing -> editor -> refreshed Xsheet UX. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class SketchEditableXsheetUiTest {
    private static JSONObject doc()throws Exception {
        JSONObject source=SketchDocument.create(new JSONObject()
                .put("sketchId","xsheet_ui_native").put("frameCount",16)
                .put("width",256).put("height",256));
        source.getJSONArray("layers").getJSONObject(0).getJSONArray("objects")
                .put(SketchEditOps.completeObject(new JSONObject()
                        .put("id","authored_ellipse").put("kind","ellipse").put("frame",0)
                        .put("exposure",1).put("fill",true)
                        .put("bounds",new JSONObject().put("x",.1).put("y",.1)
                                .put("width",.2).put("height",.2))));
        SketchDocument.validate(source);
        return source;
    }

    @Test public void longPressMakesAnActualEditableHoldAndReopensCorrectXsheetRevision() throws Exception{
        JSONObject input=doc();
        String frozen=SketchDocument.canonical(input);
        AtomicInteger commits=new AtomicInteger();
        AtomicReference<JSONObject> persisted=new AtomicReference<>();
        try(ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class)
                .create().start().resume().visible()){
            Activity activity=controller.get();
            SketchXsheetDialog.show(activity,input,0,0,(frame,layer)->{},
                    (sourceFrame,layerId,frames,expectedRevision)->{
                        assertEquals(0,sourceFrame);
                        assertEquals("layer_1",layerId);
                        assertEquals(1,expectedRevision);
                        assertEquals(3,frames);
                        JSONObject edited=SketchDocument.copy(input);
                        SketchEditOps.apply(edited,new JSONObject().put("type","set_exposure_cel")
                                .put("layerId",layerId).put("frameIndex",sourceFrame)
                                .put("exposureFrames",frames));
                        edited.put("revision",2);
                        persisted.set(edited);
                        commits.incrementAndGet();
                        return edited;
                    });
            AlertDialog sheet=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(sheet);
            View source=findCell(sheet.getWindow().getDecorView(),"Frame 1, ", "drawing");
            assertNotNull("Source cel should have a real accessible long-press target",source);
            assertTrue(source.performLongClick());
            AlertDialog hold=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(hold);
            assertNotSame(sheet,hold);
            EditText field=findExposure(hold.getWindow().getDecorView());
            assertNotNull("Native exposure field must be present",field);
            assertEquals("1",field.getText().toString());
            field.setText("3");
            hold.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals(1,commits.get());
            JSONObject result=persisted.get();assertNotNull(result);
            assertEquals(2,result.getLong("revision"));
            assertEquals(3,SketchDocument.object(result,"authored_ellipse").getInt("exposure"));
            assertEquals("hold",SketchXsheetProjection.project(result,1,1)
                    .getJSONArray("columns").getJSONObject(0)
                    .getJSONArray("cells").getJSONObject(0).getString("state"));
            assertEquals(frozen,SketchDocument.canonical(input));
            assertTrue("Xsheet should reopen with updated revision after successful editor callback",
                    ShadowAlertDialog.getLatestAlertDialog().isShowing());
        }
    }

    private static View findCell(View root,String starts,String contains){
        CharSequence label=root.getContentDescription();
        if(label!=null && label.toString().startsWith(starts)
                && label.toString().contains(contains))return root;
        if(root instanceof ViewGroup){
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++){
                View found=findCell(group.getChildAt(i),starts,contains);
                if(found!=null)return found;
            }
        }
        return null;
    }
    private static EditText findExposure(View root){
        if(root instanceof EditText && "Native Xsheet exposure in frames".contentEquals(
                root.getContentDescription()==null?"":root.getContentDescription()))
            return (EditText)root;
        if(root instanceof ViewGroup){
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++){
                EditText found=findExposure(group.getChildAt(i));
                if(found!=null)return found;
            }
        }
        return null;
    }
}
