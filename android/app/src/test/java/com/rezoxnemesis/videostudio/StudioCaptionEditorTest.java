package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.os.Looper;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class StudioCaptionEditorTest {
    private ActivityController<Activity> controller;
    private Activity activity;
    private AlertDialog dialog;
    @Before public void createActivity(){controller=Robolectric.buildActivity(Activity.class).setup();activity=controller.get();}
    @After public void closeActivity(){if(dialog!=null)dialog.dismiss();controller.pause().stop().destroy();}

    private AlertDialog show(StudioLayer initial,long duration,Predicate<StudioLayer> apply)throws Exception{
        dialog=StudioCaptionEditor.show(activity,initial,duration,apply);shadowOf(Looper.getMainLooper()).idle();return dialog;
    }
    private EditText field(String tag){View view=dialog.getWindow().getDecorView().findViewWithTag(tag);assertTrue("Missing caption field: "+tag,view instanceof EditText);return (EditText)view;}
    private Spinner choice(String tag){View view=dialog.getWindow().getDecorView().findViewWithTag(tag);assertTrue("Missing caption choice: "+tag,view instanceof Spinner);return (Spinner)view;}
    private void apply(){dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();shadowOf(Looper.getMainLooper()).idle();}
    private StudioLayer existing()throws Exception{return StudioLayer.fromJson(new JSONObject().put("id","existing-caption").put("text","Original").put("startMs",1234).put("durationMs",1567));}

    @Test public void blankNewCaptionOpensAndCancellationPreservesInitial()throws Exception{
        StudioLayer blank=new StudioLayer();AtomicInteger saves=new AtomicInteger();
        show(blank,10000,caption->{saves.incrementAndGet();return true;});
        assertTrue(dialog.isShowing());assertEquals("",field("text").getText().toString());
        assertEquals("left",choice("textAlign").getSelectedItem());
        assertEquals("0.000",field("startSeconds").getText().toString());
        assertEquals("2.500",field("durationSeconds").getText().toString());
        field("text").setText("Unsaved");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();shadowOf(Looper.getMainLooper()).idle();
        assertFalse(dialog.isShowing());assertEquals(0,saves.get());assertEquals("",blank.text);
    }
    @Test public void validCommitCopiesExactTimingAndEveryStyleBeforeDismissal()throws Exception{
        StudioLayer original=existing();String before=original.toJson().toString();AtomicReference<StudioLayer> saved=new AtomicReference<>();
        show(original,10000,caption->{assertTrue(dialog.isShowing());saved.set(caption);return true;});
        assertEquals("1.234",field("startSeconds").getText().toString());
        assertEquals("1.567",field("durationSeconds").getText().toString());
        field("text").setText("Styled\ncaption");field("startSeconds").setText("2.001");field("durationSeconds").setText("3.099");
        field("color").setText("#123456");field("backgroundColor").setText("#80556677");field("fontSize").setText("48");
        choice("position").setSelection(2);choice("fontFamily").setSelection(2);choice("fontStyle").setSelection(3);choice("textAlign").setSelection(2);
        apply();assertFalse(dialog.isShowing());assertNotNull(saved.get());assertNotSame(original,saved.get());
        JSONObject json=saved.get().toJson();assertEquals(original.id,saved.get().id);assertEquals("Styled\ncaption",saved.get().text);
        assertEquals(2001,saved.get().startMs);assertEquals(3099,saved.get().durationMs);assertEquals(2,saved.get().position);
        assertEquals(0xff123456,saved.get().color);assertEquals(48,json.getInt("fontSize"));assertEquals("serif",json.getString("fontFamily"));
        assertEquals("bold_italic",json.getString("fontStyle"));assertEquals("right",json.getString("textAlign"));assertEquals(0x80556677,json.getInt("backgroundColor"));
        assertEquals(before,original.toJson().toString());
    }
    @Test public void invalidFieldsKeepDialogOpenWithoutCallingCommit()throws Exception{
        StudioLayer original=existing();AtomicInteger saves=new AtomicInteger();
        show(original,10000,caption->{saves.incrementAndGet();return true;});
        for(String size:new String[]{"11","97","48.5","NaN",""}){
            field("fontSize").setText(size);apply();assertTrue(dialog.isShowing());assertEquals(0,saves.get());
        }
        field("fontSize").setText("32");field("text").setText("   ");apply();assertTrue(dialog.isShowing());
        field("text").setText("Valid");field("backgroundColor").setText("blue");apply();assertTrue(dialog.isShowing());
        field("backgroundColor").setText("#00000000");field("startSeconds").setText("8.500");field("durationSeconds").setText("1.501");
        apply();assertTrue(dialog.isShowing());assertEquals(0,saves.get());assertEquals("Original",original.text);
        field("startSeconds").setText("0.0001");apply();assertTrue(dialog.isShowing());assertEquals(0,saves.get());
    }
    @Test public void rejectedRevisionKeepsEditsForRetry()throws Exception{
        StudioLayer original=existing();AtomicInteger attempts=new AtomicInteger();
        show(original,10000,caption->attempts.incrementAndGet()>1);
        field("text").setText("Keep this edit");apply();assertTrue(dialog.isShowing());assertEquals(1,attempts.get());
        assertEquals("Keep this edit",field("text").getText().toString());assertEquals("Original",original.text);
        apply();assertFalse(dialog.isShowing());assertEquals(2,attempts.get());
    }
    @Test public void destroyedActivityNeverCommitsCaption()throws Exception{
        AtomicInteger saves=new AtomicInteger();show(existing(),10000,caption->{saves.incrementAndGet();return true;});
        activity.finish();apply();assertEquals(0,saves.get());
    }
}
