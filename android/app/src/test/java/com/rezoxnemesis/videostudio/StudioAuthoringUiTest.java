package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;

/** Checks the human entry points, so an isolated helper cannot stand in for reachable controls. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class StudioAuthoringUiTest {
    @Test public void subHundredMillisecondImageTimelineExplainsCaptionMinimumWithoutOpeningForm()throws Exception{
        ProjectStore.Project p=project();ProjectStore.Asset image=new ProjectStore.Asset();image.id="short-image";
        image.mime="image/png";image.uri="file:///unused.png";p.assets.add(image);
        p.clips.get(0).assetId=image.id;p.clips.get(0).outMs=50;
        MainActivity activity=activity(p);invoke(activity,"editStudioTitle",StudioLayer.class,null);
        assertNull("A timeline shorter than the minimum caption duration must not open the form",ShadowAlertDialog.getLatestAlertDialog());
        String message=ShadowToast.getTextOfLatestToast();assertNotNull("Explain why caption authoring is unavailable",message);assertFalse(message.trim().isEmpty());
    }

    @Test public void addCaptionEntryOpensScrollableStyleFieldsWithAccessibleNames()throws Exception{
        MainActivity activity=activity(project());invoke(activity,"editStudioTitle",StudioLayer.class,null);
        AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
        try{
            View form=dialog.getWindow().getDecorView();
            for(String[] field:new String[][]{{"text","Caption (1–160 characters)"},{"startSeconds","Start (seconds, up to 3 decimals)"},
                    {"durationSeconds","Duration (seconds, up to 3 decimals)"},{"fontSize","Font size (12–96 DIP)"},
                    {"color","Text color (#RRGGBB or #AARRGGBB)"},{"backgroundColor","Background (#AARRGGBB; #00000000 is transparent)"}}){
                View input=form.findViewWithTag(field[0]);assertTrue("Reachable caption field missing: "+field[0],input instanceof EditText);
                assertEquals(field[1],input.getContentDescription());assertTrue("Caption form must scroll",insideScroll(input));
            }
            for(String[] choice:new String[][]{{"fontFamily","Font family"},{"fontStyle","Font style"},{"textAlign","Text alignment"},{"position","On-screen position"}}){
                View input=form.findViewWithTag(choice[0]);assertTrue("Reachable caption choice missing: "+choice[0],input instanceof Spinner);assertEquals(choice[1],input.getContentDescription());
            }
            assertEquals("",((EditText)form.findViewWithTag("text")).getText().toString());
        }finally{dialog.dismiss();}
    }

    @Test public void editCaptionEntryLoadsSavedStyleWithoutChangingDraftOnCancel()throws Exception{
        ProjectStore.Project p=project();StudioLayer original=StudioLayer.fromJson(new JSONObject().put("id","saved-caption").put("text","Styled caption")
                .put("startMs",250).put("durationMs",1000).put("fontFamily","serif").put("fontStyle","bold_italic")
                .put("fontSize",48).put("textAlign","right").put("backgroundColor",0x80556677));
        p.studioLayers.add(original);String before=original.toJson().toString();MainActivity activity=activity(p);
        invoke(activity,"editStudioTitle",StudioLayer.class,original);AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
        try{
            View form=dialog.getWindow().getDecorView();View size=form.findViewWithTag("fontSize");assertTrue("Saved caption styles must be reachable",size instanceof EditText);
            assertEquals("48",((EditText)size).getText().toString());
            assertEquals("serif",((Spinner)form.findViewWithTag("fontFamily")).getSelectedItem());
            assertEquals("bold_italic",((Spinner)form.findViewWithTag("fontStyle")).getSelectedItem());
            assertEquals("right",((Spinner)form.findViewWithTag("textAlign")).getSelectedItem());
            assertEquals("#80556677",((EditText)form.findViewWithTag("backgroundColor")).getText().toString());
            ((EditText)form.findViewWithTag("text")).setText("Unsaved draft");dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            assertEquals(before,original.toJson().toString());
        }finally{dialog.dismiss();}
    }

    @Test public void volumeEntryLoadsSavedStereoBalanceAndMonoIntoScrollableControls()throws Exception{
        double[] pans={-.75,0,.4};
        for(int i=0;i<pans.length;i++){
            ProjectStore.Project p=project();boolean mono=i!=1;p.clips.get(0).effects.put("audioPan",pans[i]).put("audioMono",mono);
            String before=p.clips.get(0).effects.toString();MainActivity activity=activity(p);invoke(activity,"studioVolumeDialog");
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
            try{
                View form=dialog.getWindow().getDecorView();View balance=description(form,"Stereo balance"),fold=description(form,"Fold stereo L/R to mono");
                assertTrue("Volume entry must expose stereo balance",balance instanceof SeekBar);assertTrue("Volume entry must expose mono folding",fold instanceof CheckBox);
                SeekBar pan=(SeekBar)balance;assertEquals(200,pan.getMax());assertEquals((int)Math.round((pans[i]+1)*100),pan.getProgress());assertEquals(mono,((CheckBox)fold).isChecked());
                assertTrue("Expanded volume controls must scroll",insideScroll(balance));assertTrue(insideScroll(fold));
                assertEquals(before,p.clips.get(0).effects.toString());
            }finally{dialog.dismiss();}
        }
    }

    private static MainActivity activity(ProjectStore.Project p)throws Exception{
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();set(activity,"activeProject",p);set(activity,"selectedClip",p.clips.get(0));return activity;
    }
    private static ProjectStore.Project project(){ProjectStore.Project p=new ProjectStore.Project();p.id="authoring-project";ProjectStore.Clip c=new ProjectStore.Clip();c.id="selected-clip";c.outMs=4000;p.clips.add(c);return p;}
    private static void set(MainActivity activity,String name,Object value)throws Exception{java.lang.reflect.Field field=MainActivity.class.getDeclaredField(name);field.setAccessible(true);field.set(activity,value);}
    private static void invoke(MainActivity activity,String name)throws Exception{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(name);method.setAccessible(true);method.invoke(activity);}
    private static void invoke(MainActivity activity,String name,Class<?> type,Object value)throws Exception{java.lang.reflect.Method method=MainActivity.class.getDeclaredMethod(name,type);method.setAccessible(true);method.invoke(activity,value);}
    private static boolean insideScroll(View view){ViewParent parent=view.getParent();while(parent!=null){if(parent instanceof ScrollView)return true;parent=parent.getParent();}return false;}
    private static View description(View view,String name){if(name.equals(view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=description(group.getChildAt(i),name);if(found!=null)return found;}}return null;}
}
