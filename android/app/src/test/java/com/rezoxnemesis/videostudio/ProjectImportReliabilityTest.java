package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class ProjectImportReliabilityTest {
    @Test public void completedImportPreservesNewerHumanEditsAndDuplicateDeliveryDoesNotAppendTwice(){
        Context context=RuntimeEnvironment.getApplication();ProjectStore first=new ProjectStore(context),second=new ProjectStore(context);
        ProjectStore.Project stale=first.create("Import started");ProjectStore.Project edited=second.get(stale.id);edited.name="Human edit while downloading";second.save(edited);
        Uri file=Uri.parse("file:///private/import.mp4");ProjectStore.Asset imported=first.registerImportedAsset(stale.id,file,"import.mp4","video/mp4",2500,500);
        ProjectStore.Asset replay=second.registerImportedAsset(stale.id,file,"import.mp4","video/mp4",2500,500);
        ProjectStore.Project actual=first.get(stale.id);assertEquals("Human edit while downloading",actual.name);assertEquals(imported.id,replay.id);assertEquals(1,actual.assets.size());assertEquals(1,actual.clips.size());assertEquals(2500,actual.clips.get(0).outMs);
    }
    @Test public void simultaneousProjectImportsMergeWithoutLosingEitherAsset()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ProjectStore first=new ProjectStore(context),second=new ProjectStore(context);String id=first.create("Parallel imports").id;
        java.util.concurrent.ExecutorService workers=java.util.concurrent.Executors.newFixedThreadPool(2);
        try{java.util.concurrent.Future<?> a=workers.submit(()->first.registerImportedAsset(id,Uri.parse("file:///private/first.png"),"first.png","image/png",0,50));java.util.concurrent.Future<?> b=workers.submit(()->second.registerImportedAsset(id,Uri.parse("file:///private/second.png"),"second.png","image/png",0,60));a.get(10,java.util.concurrent.TimeUnit.SECONDS);b.get(10,java.util.concurrent.TimeUnit.SECONDS);assertEquals(2,first.get(id).assets.size());assertEquals(2,first.get(id).clips.size());}finally{workers.shutdownNow();}
    }
    @Test public void deletedProjectCannotBeResurrectedByLateImport(){
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());String id=store.create("Delete during transfer").id;store.delete(id);
        try{store.registerImportedAsset(id,Uri.parse("file:///private/late.mp4"),"late.mp4","video/mp4",1000,50);fail();}catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("no longer exists"));}assertNull(store.get(id));
    }
    @Test public void largeProjectMetadataCanBeReadAndListedBeyondTheCursorWindow(){
        ProjectStore store=new ProjectStore(RuntimeEnvironment.getApplication());ProjectStore.Project project=store.create("Large metadata");
        String suffix="x".repeat(1024);for(int i=0;i<2300;i++){ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="large_"+i;asset.name="Asset "+i+suffix;asset.uri="file:///private/"+i+".png";asset.mime="image/png";project.assets.add(asset);}store.save(project);
        assertTrue(project.toJson().toString().length()>2*1024*1024);ProjectStore.Project read=store.get(project.id);assertNotNull(read);assertEquals(2300,read.assets.size());assertEquals("large_2299",read.assets.get(2299).id);assertTrue(store.list().stream().anyMatch(p->p.id.equals(project.id)&&p.assets.size()==2300));
    }
}
