package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33)
public class OwnerToolUiTest {
    private ProjectStore.Project fixture(ProjectStore store,boolean locked){
        ProjectStore.Project p=store.create("Owner tools");
        ProjectStore.Asset a=new ProjectStore.Asset();a.id="image";a.mime="image/png";a.uri="file:///not-loaded.png";p.assets.add(a);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="original";clip.assetId=a.id;clip.outMs=1000;p.clips.add(clip);
        p.tracks.get(0).locked=locked;store.save(p);return p;
    }
    private void apply(MainActivity activity,ProjectStore.Project p,String tool)throws Exception{
        java.lang.reflect.Field selected=MainActivity.class.getDeclaredField("selectedClip");selected.setAccessible(true);selected.set(activity,p.clips.get(0));
        java.lang.reflect.Method apply=MainActivity.class.getDeclaredMethod("applyTool",String.class);apply.setAccessible(true);apply.invoke(activity,tool);
    }
    @Test public void ownerToolDuplicateCannotBypassTrackLock()throws Exception{
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("videostudio_v3.db");
        ProjectStore store=new ProjectStore(c);ProjectStore.Project p=fixture(store,true);long revision=p.revision;
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            apply(controller.get(),p,"Duplicate");
            assertEquals("Owner tools must respect the same track lock as MCP",1,store.get(p.id).clips.size());
            assertEquals(revision,store.get(p.id).revision);
        }
    }
    @Test public void ownerToolDuplicatePlacesItsCopyAfterTheSource()throws Exception{
        Context c=RuntimeEnvironment.getApplication();c.deleteDatabase("videostudio_v3.db");
        ProjectStore store=new ProjectStore(c);ProjectStore.Project p=fixture(store,false);
        try(ActivityController<MainActivity> controller=Robolectric.buildActivity(MainActivity.class).setup()){
            apply(controller.get(),p,"Duplicate");ProjectStore.Project result=store.get(p.id);
            assertEquals(2,result.clips.size());assertEquals(1000,result.clips.get(1).startMs);
            assertEquals(2000,result.outputDurationMs());
            assertEquals(1,store.undo(p.id,result.revision).clips.size());
        }
    }
}
