package com.rezoxnemesis.videostudio;

import android.content.Context;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class NativeStartupRecentProjectsTest {
    @Test public void homeLoadsOnlyMostRecentProjectsWhileTheRestStayInSQLite()throws Exception{
        Context context=RuntimeEnvironment.getApplication();
        ProjectStore store=new ProjectStore(context);
        Set<String> archived=new HashSet<>();
        for(int i=0;i<13;i++){
            ProjectStore.Project item=store.create("Cold startup archived_"+UUID.randomUUID());
            archived.add(item.id);
        }
        List<ProjectStore.Project> recent=store.listRecent(8);
        assertEquals(8,recent.size());
        assertEquals("Latest project remains available",store.active().id,recent.get(0).id);
        for(ProjectStore.Project item:recent)assertTrue(archived.contains(item.id));
        List<ProjectStore.Project> all=store.list();
        for(String id:archived)assertTrue("Archived project must not be deleted",all.stream().anyMatch(x->id.equals(x.id)));
        for(int limit:new int[]{0,33}){
            try{store.listRecent(limit);fail();}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("limit"));}
        }
    }
}
