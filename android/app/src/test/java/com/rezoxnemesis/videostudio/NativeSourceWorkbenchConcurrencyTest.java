package com.rezoxnemesis.videostudio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.File;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeSourceWorkbenchConcurrencyTest {
    @Test public void slowOriginalReadAllowsProjectSaveAndRejectsChangedProjectBeforeReturningImage()throws Exception {
        exercise(false);
    }
    @Test public void slowOriginalReadAllowsProfileSaveAndRejectsChangedProfileBeforeReturningImage()throws Exception {
        exercise(true);
    }
    private static void exercise(boolean changeProfile)throws Exception {
        try(NativeSourceWorkbenchFixture f=new NativeSourceWorkbenchFixture(RuntimeEnvironment.getApplication())) {
            File original=new File(Uri.parse(f.art.from.uri).getPath());String authority="workbench-"+java.util.UUID.randomUUID();
            BlockingOriginal provider=new BlockingOriginal(original);ProviderInfo info=new ProviderInfo();info.authority=authority;
            provider.attachInfo(f.context,info);ShadowContentResolver.registerProviderInternal(authority,provider);
            f.art.from.uri="content://"+authority+"/original";f.projects.save(f.project);
            ExecutorService workers=Executors.newFixedThreadPool(2);Future<JSONObject> reader=null;Future<?> writer=null;
            boolean progressed=false;Throwable readerFailure=null;
            try {
                reader=workers.submit(()->changeProfile?NativeSourceWorkbenchFixture.render(f,f.preview()):NativeSourceWorkbenchFixture.inspect(f,f.inspection()));
                assertTrue("Provider must actually be reached before concurrent write",provider.opened.await(5,TimeUnit.SECONDS));
                writer=workers.submit(()->{
                    ProjectStore separate=new ProjectStore(f.context);
                    if(changeProfile) {
                        float[][] joints=SourceArtworkRasterFixture.copy(f.rig.joints);joints[0][0]+=.01f;
                        Rig2DProfile changed=new Rig2DProfile(f.rig.id,f.project.id,f.art.from.id,true,false,joints,
                                f.rig.outline,f.rig.swordTip,f.rig.swordHilt,f.rig.swordOutline,f.sourceSha);
                        new Rig2DProjectStore(f.context,separate).saveWithBinding(changed,f.project.updatedAt,f.rigSha);
                    }else {
                        ProjectStore.Project changed=separate.get(f.project.id);changed.name="Owner edit during slow native original read";separate.save(changed);
                    }
                    return null;
                });
                try {writer.get(2,TimeUnit.SECONDS);progressed=true;}catch(TimeoutException blocked){}
            }finally {
                provider.release.countDown();
                if(reader!=null)try {reader.get(10,TimeUnit.SECONDS);}catch(ExecutionException failure){readerFailure=failure.getCause();}
                if(writer!=null)writer.get(10,TimeUnit.SECONDS);workers.shutdownNow();
            }
            assertTrue("Native source copy/decode must not hold the project writer lock",progressed);
            assertNotNull("Intervening owner edit must reject publication of stale inspection/pose",readerFailure);
            assertTrue(readerFailure instanceof IllegalStateException);
            assertTrue(readerFailure.getMessage().contains(changeProfile?"SOURCE_RIG_CHANGED":"SOURCE_PROJECT_CHANGED"));
        }
    }
    private static final class BlockingOriginal extends ContentProvider {
        final File original;final CountDownLatch opened=new CountDownLatch(1),release=new CountDownLatch(1);final AtomicInteger calls=new AtomicInteger();
        BlockingOriginal(File original){this.original=original;}
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws java.io.FileNotFoundException {
            if(calls.getAndIncrement()==0) {
                opened.countDown();try {if(!release.await(8,TimeUnit.SECONDS))throw new java.io.FileNotFoundException("Timed out waiting for original fixture release");}
                catch(InterruptedException interrupted){Thread.currentThread().interrupt();throw new java.io.FileNotFoundException("Original fixture cancelled");}
            }
            return ParcelFileDescriptor.open(original,ParcelFileDescriptor.MODE_READ_ONLY);
        }
        @Override public boolean onCreate(){return true;}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){return null;}
        @Override public String getType(Uri uri){return"image/png";}
        @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
        @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    }
}
