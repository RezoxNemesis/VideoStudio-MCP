package com.rezoxnemesis.videostudio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowContentResolver;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import static org.junit.Assert.*;

/** Selected-document byte/guard/publication behavior; synthetic metadata never proves video decoding. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,shadows=SelectedDocumentVideoImportTest.VideoMetadata.class)
public class SelectedDocumentVideoImportTest {
    @Implements(MediaMetadataRetriever.class)
    public static class VideoMetadata extends ShadowMediaMetadataRetriever {
        static volatile boolean queriedOnMain;
        @Implementation @Override protected void setDataSource(Context context,Uri uri){}
        @Implementation @Override protected void setDataSource(String path){}
        @Implementation @Override protected String extractMetadata(int key){
            if(Looper.myLooper()==Looper.getMainLooper())queriedOnMain=true;
            if(key==MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)return "960";
            if(key==MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)return "540";
            if(key==MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)return "90";
            if(key==MediaMetadataRetriever.METADATA_KEY_DURATION)return "6000";
            if(key==MediaMetadataRetriever.METADATA_KEY_MIMETYPE)return "video/mp4";
            if(key==MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)return "yes";
            return null;
        }
        @Implementation protected void release(){}
    }

    @Test public void explicitInfoHashesOnlyTheRegisteredGrantedDocumentWithoutWritingAProject()throws Exception{
        try(Fixture f=new Fixture()){
            String before=f.sourceJson();JSONObject info=f.inspect();
            assertTrue(info.getBoolean("ok"));assertTrue(info.getBoolean("readOnly"));assertFalse(info.getBoolean("appOwned"));assertTrue(info.getBoolean("selectedDocument"));
            assertEquals(f.sha,info.getString("sha256"));assertEquals(f.bytes.length,info.getLong("bytes"));assertEquals(f.source.id,info.getString("projectId"));assertEquals(f.asset.id,info.getString("assetId"));
            assertEquals(before,f.sourceJson());assertEquals(1,f.provider.paths.size());assertTrue(f.provider.paths.contains("/chosen"));assertEquals(f.provider.opened,f.provider.closed);assertFalse(f.provider.queriedOnMain);
        }
    }

    @Test public void sameProjectCopyPublishesOneOwnedSourceAssetWithoutChangingItsTimelineOrLinkedSource()throws Exception{
        try(Fixture f=new Fixture()){
            ProjectStore.Clip existing=new ProjectStore.Clip();existing.id="existing_base";existing.assetId=f.asset.id;existing.outMs=2000;f.source.clips.add(existing);
            StudioLayer title=new StudioLayer();title.text="Keep this title";title.durationMs=1000;f.source.studioLayers.add(title);f.store.save(f.source);
            f.sameProject();String clips=canonical(f.source.toJson().getJSONArray("clips")),layers=canonical(StudioLayerContract.toJson(f.source.studioLayers)),linked=canonical(f.asset.toJson());
            JSONObject result=f.execute();ProjectStore.Project saved=f.store.get(f.source.id);ProjectStore.Asset owned=saved.asset(result.getString("assetId"));
            assertTrue(result.getBoolean("independentCopy"));assertTrue(result.getBoolean("selectedDocument"));assertFalse(result.getBoolean("appendedToTimeline"));assertEquals(2,saved.assets.size());
            assertEquals(clips,canonical(saved.toJson().getJSONArray("clips")));assertEquals(layers,canonical(StudioLayerContract.toJson(saved.studioLayers)));assertEquals(linked,canonical(saved.asset(f.asset.id).toJson()));
            assertFalse(owned.generated);assertEquals("source",owned.role);assertEquals("",owned.providerAuthority);assertTrue(owned.seekable);assertEquals(6000,owned.durationMs);assertEquals(f.bytes.length,owned.sizeBytes);
            File copied=ProjectAssetImport.sourceFile(f.context,owned);assertArrayEquals(f.bytes,Files.readAllBytes(copied.toPath()));assertNotEquals(f.file.getCanonicalPath(),copied.getCanonicalPath());assertArrayEquals(f.bytes,Files.readAllBytes(f.file.toPath()));assertFalse(VideoMetadata.queriedOnMain);
        }
    }

    @Test public void exactReplayUsesTheOwnedWitnessAfterSourceRemovalAndPreservesLaterEdits()throws Exception{
        try(Fixture f=new Fixture()){
            String sourceBefore=f.sourceJson();JSONObject first=f.execute();String id=first.getString("assetId");ProjectStore.Project edited=f.store.get(f.destination.id);edited.name="Human kept this project";f.store.save(edited);long revision=edited.updatedAt;
            f.context.getContentResolver().releasePersistableUriPermission(f.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);f.provider.available=false;f.store.delete(f.source.id);
            JSONObject replay=f.execute();assertTrue(replay.getBoolean("replayed"));assertEquals(id,replay.getString("assetId"));assertEquals(1,f.store.get(f.destination.id).assets.size());assertEquals(revision,f.store.get(f.destination.id).updatedAt);assertEquals("Human kept this project",f.store.get(f.destination.id).name);
            assertTrue(sourceBefore.contains(f.asset.id));assertArrayEquals(f.bytes,Files.readAllBytes(f.file.toPath()));
        }
    }

    @Test public void strictModeRejectsCoercionsAppendUrisAndInconsistentSameProjectRevisions()throws Exception{
        try(Fixture f=new Fixture()){
            assertTrue(ProjectAssetImport.validate(f.request).getBoolean("selectedDocument"));
            for(Object value:new Object[]{false,"true",1,JSONObject.NULL}){JSONObject bad=new JSONObject(f.request.toString()).put("selectedDocument",value);assertThrows(String.valueOf(value),Exception.class,()->ProjectAssetImport.validate(bad));}
            assertThrows(Exception.class,()->ProjectAssetImport.validate(new JSONObject(f.request.toString()).put("uri",f.uri.toString())));
            assertThrows(Exception.class,()->ProjectAssetImport.validate(new JSONObject(f.request.toString()).put("appendToTimeline",true)));
            f.sameProject();assertTrue(ProjectAssetImport.validate(f.request).getBoolean("selectedDocument"));
            assertThrows(Exception.class,()->ProjectAssetImport.validate(new JSONObject(f.request.toString()).put("sourceProjectUpdatedAt",f.source.updatedAt+1)));
            assertEquals(0,f.provider.opened);
        }
    }

    @Test public void missingExactReadGrantUnregisteredSourceAndOversizeMetadataCannotReadOrPublish()throws Exception{
        for(String invalid:new String[]{"grant","unregistered","size","audio"})try(Fixture f=new Fixture()){
            assertTrue(ProjectAssetImport.validate(f.request).getBoolean("selectedDocument"));
            if("grant".equals(invalid))f.context.getContentResolver().releasePersistableUriPermission(f.uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            if("unregistered".equals(invalid))f.request.put("assetId","not-the-selected-asset");
            if("size".equals(invalid)||"audio".equals(invalid)){
                ProjectStore.Project changed=f.store.get(f.source.id);ProjectStore.Asset asset=changed.asset(f.asset.id);if("size".equals(invalid)){asset.sizeBytes=ProjectAssetImport.MAX_BYTES+1;f.provider.reportedSize=asset.sizeBytes;}else asset.mime="audio/mp4";
                f.store.save(changed);f.request.put("sourceProjectUpdatedAt",changed.updatedAt);
            }
            String before=f.destinationJson();assertThrows(invalid,Exception.class,()->f.execute());assertEquals(before,f.destinationJson());assertEquals(0,f.provider.opened);
        }
    }

    @Test public void interruptedCopyPublishesNothingAndExactRetryCopiesOneAssetWithoutAppending()throws Exception{
        try(Fixture f=new Fixture()){
            String before=f.destinationJson();AtomicBoolean entered=new AtomicBoolean();
            assertThrows(Exception.class,()->f.execute((copied,total)->{if(copied>0&&entered.compareAndSet(false,true))Thread.currentThread().interrupt();}));
            assertTrue("The real bounded copy must reach its progress callback",entered.get());assertEquals(before,f.destinationJson());
            JSONObject first=f.execute(),again=f.execute();assertEquals(first.getString("assetId"),again.getString("assetId"));assertTrue(again.getBoolean("replayed"));assertEquals(1,f.store.get(f.destination.id).assets.size());assertEquals(0,f.store.get(f.destination.id).clips.size());assertEquals(f.provider.opened,f.provider.closed);
        }
    }

    @Test public void sourceAndDestinationEditsDuringCopyRejectPublicationAndPreserveHumanChanges()throws Exception{
        for(String target:new String[]{"source","destination"})try(Fixture f=new Fixture()){
            AtomicBoolean changed=new AtomicBoolean();
            assertThrows(Exception.class,()->f.execute((copied,total)->{if(copied>0&&changed.compareAndSet(false,true)){ProjectStore.Project human=f.store.get("source".equals(target)?f.source.id:f.destination.id);human.name="Human edit during copy";f.store.save(human);}}));
            assertTrue(changed.get());assertEquals(0,f.store.get(f.destination.id).assets.size());assertEquals(0,f.store.get(f.destination.id).clips.size());assertEquals("Human edit during copy",f.store.get("source".equals(target)?f.source.id:f.destination.id).name);assertArrayEquals(f.bytes,Files.readAllBytes(f.file.toPath()));
        }
    }

    @Test public void changedProviderBytesAndChangedOperationMeaningAreNeverSilentlyAccepted()throws Exception{
        try(Fixture f=new Fixture()){
            AtomicBoolean changed=new AtomicBoolean();assertThrows(Exception.class,()->f.execute((copied,total)->{if(copied>0&&changed.compareAndSet(false,true))try(java.io.RandomAccessFile source=new java.io.RandomAccessFile(f.file,"rw")){source.seek(0);source.write(99);}}));
            assertTrue(changed.get());assertEquals(0,f.store.get(f.destination.id).assets.size());assertEquals(99,Files.readAllBytes(f.file.toPath())[0]&255);
        }
        try(Fixture f=new Fixture()){
            JSONObject first=f.execute();String before=f.destinationJson();f.request.put("expectedSha256","0".repeat(64));assertThrows(Exception.class,()->f.execute());assertEquals(before,f.destinationJson());assertNotNull(f.store.get(f.destination.id).asset(first.getString("assetId")));
        }
    }

    static final class Fixture implements AutoCloseable {
        final Context context=RuntimeEnvironment.getApplication();final ProjectStore store=new ProjectStore(context);
        final ProjectStore.Project source=store.create("Selected VIDEO document"),destination=store.create("Owned VIDEO destination");final ProjectStore.Asset asset=new ProjectStore.Asset();
        final byte[] bytes=new byte[192*1024];final File file;final Uri uri;final SelectedProvider provider;final String sha;final JSONObject request;
        Fixture()throws Exception{
            VideoMetadata.queriedOnMain=false;for(int i=0;i<bytes.length;i++)bytes[i]=(byte)(i*31+17);
            file=new File(context.getCacheDir(),"selected_video_unit_"+UUID.randomUUID()+".mp4");Files.write(file.toPath(),bytes);provider=new SelectedProvider(file);String authority="selected-video-"+UUID.randomUUID();
            ProviderInfo info=new ProviderInfo();info.authority=authority;info.name=SelectedProvider.class.getName();info.packageName=context.getPackageName();info.applicationInfo=context.getApplicationInfo();info.exported=false;info.grantUriPermissions=true;provider.attachInfo(context,info);ShadowContentResolver.registerProviderInternal(authority,provider);
            uri=Uri.parse("content://"+authority+"/chosen");context.getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);
            asset.id="selected_"+UUID.randomUUID();asset.name="Selected source.mp4";asset.mime="video/mp4";asset.uri=uri.toString();asset.durationMs=6000;asset.sizeBytes=bytes.length;asset.seekable=true;asset.persistedReadAccess=true;asset.providerAuthority=authority;asset.role="source";source.assets.add(asset);store.save(source);
            sha=SketchStoryAudio.sha256(file);request=new JSONObject().put("selectedDocument",true).put("projectId",destination.id).put("expectedUpdatedAt",destination.updatedAt).put("sourceProjectId",source.id).put("sourceProjectUpdatedAt",source.updatedAt).put("assetId",asset.id).put("expectedSha256",sha).put("operationId","selected_copy_"+UUID.randomUUID());
            assertTrue("Fixture must hold the exact persisted read grant",context.getContentResolver().getPersistedUriPermissions().stream().anyMatch(permission->permission.isReadPermission()&&uri.equals(permission.getUri())));
        }
        void sameProject()throws Exception{request.put("projectId",source.id).put("expectedUpdatedAt",source.updatedAt).put("sourceProjectUpdatedAt",source.updatedAt);}
        JSONObject inspect()throws Exception{return worker(()->ProjectAssetImport.inspect(context,new JSONObject().put("projectId",source.id).put("expectedUpdatedAt",source.updatedAt).put("assetId",asset.id).put("selectedDocument",true)));}
        JSONObject execute()throws Exception{return execute((copied,total)->{});}
        JSONObject execute(ProjectAssetImport.Progress progress)throws Exception{return worker(()->ProjectAssetImport.execute(context,request,progress));}
        String sourceJson()throws Exception{return canonical(store.get(source.id).toJson());}
        String destinationJson()throws Exception{return canonical(store.get(destination.id).toJson());}
        @Override public void close()throws Exception{try{context.getContentResolver().releasePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}store.close();assertTrue(file.delete());}
    }

    public static final class SelectedProvider extends ContentProvider {
        final File file;long reportedSize;boolean available=true;int opened,closed;boolean queriedOnMain;final java.util.Set<String> paths=new java.util.HashSet<>();
        SelectedProvider(File file){this.file=file;reportedSize=file.length();}
        @Override public boolean onCreate(){return true;}
        @Override public String getType(Uri uri){selected(uri);return "video/mp4";}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] arguments,String order){selected(uri);if(Looper.myLooper()==Looper.getMainLooper())queriedOnMain=true;MatrixCursor cursor=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});cursor.addRow(new Object[]{"Selected source.mp4",reportedSize});return cursor;}
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
            selected(uri);if(!available)throw new FileNotFoundException("Selected document unavailable");if(!"r".equals(mode))throw new FileNotFoundException("Read only");opened++;
            return new ParcelFileDescriptor(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)){
                private boolean counted;
                @Override public synchronized void close()throws IOException{try{super.close();}finally{if(!counted){counted=true;closed++;}}}
            };
        }
        private void selected(Uri uri){if(!"/chosen".equals(uri.getPath()))throw new AssertionError("No Gallery or unrelated document reads: "+uri);paths.add(uri.getPath());}
        @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
        @Override public int delete(Uri uri,String selection,String[] arguments){throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,ContentValues values,String selection,String[] arguments){throw new UnsupportedOperationException();}
    }
    static String canonical(Object value)throws Exception{return SketchDocument.canonical(value);}
    static <T>T worker(Callable<T> work)throws Exception{
        ExecutorService executor=Executors.newSingleThreadExecutor();try{return executor.submit(work).get(15,TimeUnit.SECONDS);}catch(ExecutionException error){if(error.getCause() instanceof Exception)throw (Exception)error.getCause();throw error;}finally{executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
}
