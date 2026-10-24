package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import android.util.AtomicFile;
import java.io.*;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.RealObject;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Two real controllers/shared AtomicFile descriptors. The paused native write models noninterruptible file I/O. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows={SelectedVideoManualRequestRaceTest.PausedAtomicWrite.class,SelectedDocumentVideoImportTest.VideoMetadata.class})
@LooperMode(LooperMode.Mode.PAUSED)
public class SelectedVideoManualRequestRaceTest {
    @Implements(AtomicFile.class)
    public static class PausedAtomicWrite {
        @RealObject AtomicFile real;
        static volatile File target;static final AtomicBoolean once=new AtomicBoolean();
        static CountDownLatch oldOpened,releaseOld;static volatile Thread oldThread;
        @Implementation protected FileOutputStream startWrite()throws IOException{
            FileOutputStream stream=Shadow.directlyOn(real,AtomicFile.class,"startWrite");
            if(target!=null&&target.equals(real.getBaseFile())&&once.compareAndSet(false,true)){
                oldThread=Thread.currentThread();oldOpened.countDown();awaitUninterruptibly(releaseOld);
            }
            return stream;
        }
    }

    @Test public void cancelledOlderSaveCannotReplaceTheNewerSelectedDocumentUsedByExactRetry()throws Exception{
        Context context=RuntimeEnvironment.getApplication();Activity activity=Robolectric.buildActivity(Activity.class).setup().get();ProjectStore store=new ProjectStore(context);ProjectStore.Project project=store.create("Selected video retry pointer race");
        File first=new File(context.getCacheDir(),"old_selected_"+UUID.randomUUID()+".mp4"),second=new File(context.getCacheDir(),"new_selected_"+UUID.randomUUID()+".mp4");byte[] oldBytes=new byte[8192],newBytes=new byte[8192];java.util.Arrays.fill(oldBytes,(byte)17);java.util.Arrays.fill(newBytes,(byte)73);Files.write(first.toPath(),oldBytes);Files.write(second.toPath(),newBytes);
        GateProvider provider=new GateProvider(first,second);String authority="selected-pointer-"+UUID.randomUUID();ProviderInfo info=new ProviderInfo();info.authority=authority;info.name=GateProvider.class.getName();info.packageName=context.getPackageName();info.applicationInfo=context.getApplicationInfo();info.exported=false;info.grantUriPermissions=true;provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal(authority,provider);
        Uri oldUri=Uri.parse("content://"+authority+"/old"),newUri=Uri.parse("content://"+authority+"/new");context.getContentResolver().takePersistableUriPermission(oldUri,Intent.FLAG_GRANT_READ_URI_PERMISSION);context.getContentResolver().takePersistableUriPermission(newUri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
        File saved=new File(context.getFilesDir(),"selected_video_manual/"+project.id+".json");PausedAtomicWrite.target=saved;PausedAtomicWrite.once.set(false);PausedAtomicWrite.oldOpened=new CountDownLatch(1);PausedAtomicWrite.releaseOld=new CountDownLatch(1);PausedAtomicWrite.oldThread=null;
        SelectedVideoImportDialog older=null,newer=null,retry=null;AtomicReference<JSONObject> completed=new AtomicReference<>();String newOperation="new_"+UUID.randomUUID();
        try{
            older=SelectedVideoImportDialog.picked(activity,project.id,project.updatedAt,oldUri,"old_"+UUID.randomUUID(),()->true,result->{},closed->{});
            assertTrue("Pause the older controller after it owns an actual AtomicFile output descriptor",PausedAtomicWrite.oldOpened.await(5,TimeUnit.SECONDS));older.close();
            newer=SelectedVideoImportDialog.picked(activity,project.id,project.updatedAt,newUri,newOperation,()->true,result->{},closed->{});
            boolean advanced=provider.newHashEntered.await(750,TimeUnit.MILLISECONDS);
            if(advanced){JSONObject current=read(saved);assertEquals("The newer accepted save is bound to its chosen operation",newOperation,current.getString("operationId"));assertEquals(newUri.toString(),current.getString("uri"));}
            PausedAtomicWrite.releaseOld.countDown();PausedAtomicWrite.oldThread.join(5000);assertFalse("Cancelled old writer must eventually exit",PausedAtomicWrite.oldThread.isAlive());
            assertTrue("The newer reservation/save must complete after the prior writer releases",provider.newHashEntered.await(5,TimeUnit.SECONDS));
            newer.close();provider.releaseNewHash.countDown();if(provider.newThread!=null){provider.newThread.join(5000);assertFalse("Cancelled newer hash worker must eventually exit",provider.newThread.isAlive());}
            retry=SelectedVideoImportDialog.retry(activity,project.id,()->true,completed::set,closed->{});long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);
            do{ShadowLooper.idleMainLooper();if(!busy(retry))break;Thread.sleep(5);}while(System.nanoTime()<deadline);
            assertFalse("Retry must reach an explicit success or clean rejection",busy(retry));
            assertEquals("Exact retry must never read the old selected document after the newer save was accepted",0,provider.oldReads);
            if(completed.get()!=null){assertEquals(newOperation,completed.get().getString("operationId"));assertEquals(SketchStoryAudio.sha256(second),completed.get().getString("sha256"));}
            else{JSONObject kept=read(saved);assertEquals("A clean rejected retry must retain the newer selected operation",newOperation,kept.getString("operationId"));assertEquals(newUri.toString(),kept.getString("uri"));}
            assertArrayEquals(oldBytes,Files.readAllBytes(first.toPath()));assertArrayEquals(newBytes,Files.readAllBytes(second.toPath()));assertTrue(store.get(project.id).clips.isEmpty());assertTrue(store.get(project.id).studioLayers.isEmpty());
        }finally{
            PausedAtomicWrite.releaseOld.countDown();provider.releaseNewHash.countDown();if(older!=null)older.close();if(newer!=null)newer.close();if(retry!=null)retry.close();if(PausedAtomicWrite.oldThread!=null)PausedAtomicWrite.oldThread.join(5000);if(provider.newThread!=null)provider.newThread.join(5000);
            PausedAtomicWrite.target=null;store.close();activity.finish();assertTrue(first.delete());assertTrue(second.delete());
        }
    }

    public static final class GateProvider extends ContentProvider {
        final File oldFile,newFile;final CountDownLatch newHashEntered=new CountDownLatch(1),releaseNewHash=new CountDownLatch(1);final AtomicBoolean blockNew=new AtomicBoolean(true);volatile int oldReads;volatile Thread newThread;
        GateProvider(File oldFile,File newFile){this.oldFile=oldFile;this.newFile=newFile;}
        @Override public boolean onCreate(){return true;}
        @Override public String getType(Uri uri){file(uri);return "video/mp4";}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String order){File file=file(uri);MatrixCursor row=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});row.addRow(new Object[]{uri.getLastPathSegment()+".mp4",file.length()});return row;}
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
            File file=file(uri);if(!"r".equals(mode))throw new FileNotFoundException("Read only");if(file==oldFile)oldReads++;
            else if(blockNew.compareAndSet(true,false)){newThread=Thread.currentThread();newHashEntered.countDown();awaitUninterruptibly(releaseNewHash);}
            return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);
        }
        private File file(Uri uri){if("/old".equals(uri.getPath()))return oldFile;if("/new".equals(uri.getPath()))return newFile;throw new AssertionError("No unrelated document reads: "+uri);}
        @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
        @Override public int delete(Uri uri,String selection,String[] arguments){throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,ContentValues values,String selection,String[] arguments){throw new UnsupportedOperationException();}
    }
    private static void awaitUninterruptibly(CountDownLatch release){boolean interrupted=false;while(true)try{if(release.await(15,TimeUnit.SECONDS))break;throw new AssertionError("Fixture release timed out");}catch(InterruptedException ignored){interrupted=true;}if(interrupted)Thread.currentThread().interrupt();}
    private static boolean busy(SelectedVideoImportDialog dialog)throws Exception{java.lang.reflect.Field field=SelectedVideoImportDialog.class.getDeclaredField("busy");field.setAccessible(true);return field.getBoolean(dialog);}
    private static JSONObject read(File file)throws Exception{try(InputStream input=new AtomicFile(file).openRead();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] bytes=new byte[4096];for(int n;(n=input.read(bytes))!=-1;)out.write(bytes,0,n);return new JSONObject(out.toString("UTF-8"));}}
}
