package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class CinematicOutputFramingTest {
    @Test public void threeReferenceFilmShapesFitExistingExactNativeGpuBudget(){
        int[][] shapes={{720,1280},{1280,720},{960,720}};
        for(int i=0;i<CinematicOutputFraming.MODES.length;i++){
            String mode=CinematicOutputFraming.MODES[i];
            int[] bounds=CinematicOutputFraming.dimensions(mode);
            assertArrayEquals(shapes[i],bounds);
            assertEquals(mode,CinematicOutputFraming.matching(bounds[0],bounds[1]));
            assertEquals(bounds[0]*bounds[1]*4,
                NativeCinematicSceneComposer.requireDimensions(bounds[0],bounds[1]));
        }
        assertEquals(CinematicOutputFraming.PORTRAIT,
                CinematicOutputFraming.matching(720,1280));
        assertThrows(IllegalArgumentException.class,
                ()->CinematicOutputFraming.matching(2048,2048));
        assertThrows(IllegalArgumentException.class,
                ()->CinematicOutputFraming.matching(1280,721));
        assertThrows(IllegalArgumentException.class,
                ()->CinematicOutputFraming.dimensions("invalid"));
    }

    private static Button button(View root,String name) {
        if(root instanceof Button && name.equals(((Button)root).getText().toString()))
            return (Button)root;
        if(root instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)root;
            for(int i=0;i<group.getChildCount();i++){
                Button item=button(group.getChildAt(i),name);
                if(item!=null)return item;
            }
        }
        return null;
    }

    @Test public void nativeStudioAspectSelectionAffectsBothPreviewAndExportConfig()
            throws Exception {
        Context ctx=RuntimeEnvironment.getApplication();
        ProjectStore.Project project=new ProjectStore(ctx)
                .create("Owner-selected widescreen camera film");
        Bundle state=new Bundle();
        state.putLong("sourceRevision",project.updatedAt);
        JSONObject example=Cinematic3DStudioActivityTest.timeline();
        state.putString("draft",example.toString());
        state.putString("acceptedTimeline",example.toString());
        state.putString("bundleId","synthetic-bundle");
        ActivityController<Cinematic3DStudioActivity> activity=Robolectric.buildActivity(
                Cinematic3DStudioActivity.class,
                new Intent(ctx,Cinematic3DStudioActivity.class)
                    .putExtra("projectId",project.id))
                .create(state).start().resume().visible();
        try{
            Cinematic3DStudioActivity studio=activity.get();
            Button select=button(studio.getWindow().getDecorView(),"Output framing");
            assertNotNull("Framing selector must exist in real Android Studio",select);
            Field field=Cinematic3DStudioActivity.class.getDeclaredField("outputFraming");
            field.setAccessible(true);
            assertEquals(CinematicOutputFraming.PORTRAIT,field.get(studio));
            select.performClick();
            AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(dialog);
            assertTrue(dialog.isShowing());
            ListView entries=dialog.getListView();
            assertNotNull(entries);
            View row=entries.getAdapter().getView(1,null,entries);
            assertTrue(entries.performItemClick(row,1,entries.getAdapter().getItemId(1)));
            assertEquals(CinematicOutputFraming.LANDSCAPE,field.get(studio));
            // Same dimensions bound to the actual native Filament preview
            // Intent and to the source-owned export parameters.
            Method framed=Cinematic3DStudioActivity.class
                    .getDeclaredMethod("framedPreview",Intent.class);
            framed.setAccessible(true);
            Intent preview=(Intent)framed.invoke(studio,new Intent());
            assertEquals(1280,preview.getIntExtra("cinematicOutputWidth",0));
            assertEquals(720,preview.getIntExtra("cinematicOutputHeight",0));
            assertEquals(CinematicOutputFraming.LANDSCAPE,
                    CinematicOutputFraming.matching(
                        preview.getIntExtra("cinematicOutputWidth",0),
                        preview.getIntExtra("cinematicOutputHeight",0)));
            assertEquals("Changing output frame cannot alter actor source timeline",
                    Cinematic3DTimeline.canonical(example),
                    Cinematic3DTimeline.canonical((JSONObject)Cinematic3DStudioActivityTest
                        .class.getDeclaredMethod("timeline").invoke(null)));
        }finally{activity.pause().stop().destroy();}
    }
}
