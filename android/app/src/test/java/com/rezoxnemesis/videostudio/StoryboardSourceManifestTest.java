package com.rezoxnemesis.videostudio;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.ParcelFileDescriptor;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowContentResolver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.junit.Assert.*;

/** Native-URI fixtures; these are not the unavailable private Test 1 originals. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class StoryboardSourceManifestTest {
    private final Context context=RuntimeEnvironment.getApplication();

    private static byte[] picture(int width,int height,int color,boolean jpeg){
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);
        try{
            bitmap.eraseColor(color);ByteArrayOutputStream output=new ByteArrayOutputStream();
            assertTrue(bitmap.compress(jpeg?Bitmap.CompressFormat.JPEG:Bitmap.CompressFormat.PNG,90,output));
            return output.toByteArray();
        }finally{bitmap.recycle();}
    }
    private ProjectStore.Project project(){
        ProjectStore.Project value=new ProjectStore.Project();value.id="source-"+UUID.randomUUID();value.name="Fixture storyboard";return value;
    }
    private File add(ProjectStore.Project project,String id,String name,byte[] bytes,String mime)throws Exception{
        File file=File.createTempFile("story-source-",mime.contains("jpeg")?".jpg":".png",context.getFilesDir());
        Files.write(file.toPath(),bytes);ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id=id;asset.name=name;asset.uri=Uri.fromFile(file).toString();asset.mime=mime;asset.sizeBytes=bytes.length;
        project.assets.add(asset);ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="clip-"+id;clip.assetId=id;clip.outMs=3000;project.clips.add(clip);
        return file;
    }
    private static String sha(byte[] bytes)throws Exception{
        StringBuilder result=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(bytes))result.append(String.format(Locale.US,"%02x",value&255));return result.toString();
    }

    @Test public void declaredThirteenAnchorsPreserveTwentyThreeOriginalRecordsOnRepeatCapture()throws Exception{
        ProjectStore.Project source=project();JSONArray groups=new JSONArray();Map<String,String> expected=new HashMap<>();
        for(int index=0;index<13;index++){
            byte[] png=picture(48,80,0xff000000|((index+1)*0x010203),false);String id="original-"+index;
            add(source,id,"frame.png",png,"image/png");expected.put(id,sha(png));
        }
        for(int index=0;index<10;index++)add(source,"variant-"+index,"frame_480.jpg",picture(48,80,0xff000000|((index+1)*0x010203),true),"image/jpeg");
        for(int index=0;index<13;index++){
            JSONArray ids=new JSONArray().put("original-"+index);if(index<10)ids.put("variant-"+index);
            groups.put(new JSONObject().put("representativeAssetId","original-"+index).put("assetIds",ids));
        }
        String original=source.toJson().toString();JSONObject selection=new JSONObject().put("anchorGroups",groups);
        JSONObject first=StoryboardSourceManifest.capture(context,source,selection),second=StoryboardSourceManifest.capture(context,source,selection);
        assertEquals(23,first.getJSONArray("library").length());assertEquals(13,first.getJSONArray("orderedAssetIds").length());
        assertEquals(first.getString("manifestSha256"),second.getString("manifestSha256"));
        for(int index=0;index<13;index++)assertEquals("original-"+index,first.getJSONArray("orderedAssetIds").getString(index));
        JSONArray library=first.getJSONArray("library");
        for(int index=0;index<library.length();index++){JSONObject entry=library.getJSONObject(index);if(expected.containsKey(entry.getString("assetId")))assertEquals(expected.get(entry.getString("assetId")),entry.getString("sourceSha256"));}
        assertEquals(original,source.toJson().toString());assertEquals(23,source.assets.size());assertEquals(23,source.clips.size());
        StoryboardSourceManifest.verifyOwnedSources(context,source,first);
        assertFalse(first.toString().contains("file://"));assertFalse(first.toString().contains(context.getFilesDir().getAbsolutePath()));
        assertEquals(13,StoryboardBatchPlanner.plan(source,new JSONObject(),first).assets.size());
    }

    @Test public void exactByteAliasesAreEvidenceRatherThanDeletionOfExplicitRepeatedBeats()throws Exception{
        ProjectStore.Project source=project();byte[] same=picture(32,64,0xff443322,false);
        add(source,"a","开始.png",same,"image/png");add(source,"b","renamed.png",same,"image/png");
        JSONObject manifest=StoryboardSourceManifest.capture(context,source,new JSONObject().put("assetIds",new JSONArray().put("b").put("a").put("b")));
        assertEquals("[\"b\",\"a\",\"b\"]",manifest.getJSONArray("orderedAssetIds").toString());
        assertEquals(2,manifest.getJSONArray("library").length());assertEquals(1,manifest.getJSONArray("exactByteAliases").length());
        assertEquals(3,StoryboardBatchPlanner.plan(source,new JSONObject(),manifest).assets.size());
        assertEquals(3,StoryboardBatchPlanner.plan(source,new JSONObject().put("assetIds",new JSONArray().put("b").put("a").put("b")),manifest).assets.size());
        assertThrows(IllegalArgumentException.class,()->StoryboardBatchPlanner.plan(source,new JSONObject().put("assetIds",new JSONArray().put("a").put("b")),manifest));
        assertThrows(IllegalArgumentException.class,()->StoryboardBatchPlanner.plan(source,new JSONObject().put("assetIds","a,b"),manifest));
    }

    @Test public void orientationAndAspectComeFromTheSameHashedEncodedSource()throws Exception{
        ProjectStore.Project source=project();File jpeg=add(source,"rotated","rotated.jpg",picture(32,64,0xff884433,true),"image/jpeg");
        ExifInterface exif=new ExifInterface(jpeg.getAbsolutePath());exif.setAttribute(ExifInterface.TAG_ORIENTATION,"6");exif.saveAttributes();
        add(source,"second","second.png",picture(64,32,0xff334488,false),"image/png");
        JSONObject entry=StoryboardSourceManifest.capture(context,source,new JSONObject()).getJSONArray("library").getJSONObject(0);
        assertEquals("rotated",entry.getString("assetId"));assertEquals(32,entry.getInt("codedWidth"));assertEquals(64,entry.getInt("codedHeight"));
        assertEquals(6,entry.getInt("exifOrientation"));assertEquals(64,entry.getInt("orientedWidth"));assertEquals(32,entry.getInt("orientedHeight"));
        assertEquals(sha(Files.readAllBytes(jpeg.toPath())),entry.getString("sourceSha256"));
    }

    @Test public void verificationRejectsChangedBytesAndChangedTimelineOrderWithoutTouchingOriginals()throws Exception{
        ProjectStore.Project source=project();File first=add(source,"a","same.png",picture(32,64,0xff111111,false),"image/png");
        add(source,"b","same.png",picture(32,64,0xff222222,false),"image/png");
        JSONObject manifest=StoryboardSourceManifest.capture(context,source,new JSONObject());
        java.util.Collections.swap(source.clips,0,1);
        assertThrows(IllegalArgumentException.class,()->StoryboardSourceManifest.verifyOwnedSources(context,source,manifest));
        java.util.Collections.swap(source.clips,0,1);byte[] replacement=picture(32,64,0xffabcdef,false);Files.write(first.toPath(),replacement);
        assertThrows(IllegalArgumentException.class,()->StoryboardSourceManifest.verifyOwnedSources(context,source,manifest));
        assertArrayEquals(replacement,Files.readAllBytes(first.toPath()));assertEquals(2,source.assets.size());
    }

    @Test public void registeredProviderStreamsCloseAndUnknownIdsCannotCauseArbitraryReads()throws Exception{
        ProjectStore.Project source=project();OwnedProvider provider=new OwnedProvider();String authority="story-test-"+UUID.randomUUID();
        ProviderInfo info=new ProviderInfo();info.authority=authority;info.name=OwnedProvider.class.getName();info.packageName=context.getPackageName();
        info.applicationInfo=context.getApplicationInfo();info.exported=false;info.grantUriPermissions=true;provider.attachInfo(context,info);
        ShadowContentResolver.registerProviderInternal(authority,provider);
        File a=add(source,"a","same.png",picture(32,64,0xff112233,false),"image/png"),b=add(source,"b","same.png",picture(32,64,0xff334455,false),"image/png");
        provider.files.put("/a",a);provider.files.put("/b",b);
        source.assets.get(0).uri="content://"+authority+"/a";source.assets.get(1).uri="content://"+authority+"/b";
        JSONObject manifest=StoryboardSourceManifest.capture(context,source,new JSONObject());assertEquals(2,provider.opened);assertEquals(2,provider.closed);
        assertThrows(IllegalArgumentException.class,()->StoryboardSourceManifest.capture(context,source,new JSONObject().put("assetIds",new JSONArray().put("unknown").put("a"))));
        assertEquals(2,provider.opened);provider.files.remove("/a");
        assertThrows(IllegalArgumentException.class,()->StoryboardSourceManifest.verifyOwnedSources(context,source,manifest));
        assertEquals(2,source.assets.size());assertTrue(a.isFile());
    }

    @Test public void cancellationAndEncodedByteCapsLeaveSourcesAndProbeDirectoryIntact()throws Exception{
        ProjectStore.Project source=project();File large=add(source,"a","large.png",picture(32,64,0xff445566,false),"image/png");
        add(source,"b","other.png",picture(32,64,0xff556677,false),"image/png");
        Thread.currentThread().interrupt();
        try{assertThrows(InterruptedException.class,()->StoryboardSourceManifest.capture(context,source,new JSONObject()));}
        finally{Thread.interrupted();}
        source.assets.get(0).sizeBytes=-1;try(RandomAccessFile output=new RandomAccessFile(large,"rw")){output.setLength(StoryboardSourceManifest.MAX_IMAGE_BYTES+1L);}
        assertThrows(IllegalArgumentException.class,()->StoryboardSourceManifest.capture(context,source,new JSONObject()));
        assertTrue(large.isFile());assertEquals(StoryboardSourceManifest.MAX_IMAGE_BYTES+1L,large.length());assertEquals(2,source.assets.size());
        File probes=new File(context.getCacheDir(),"storyboard_source_probes");File[] leftovers=probes.listFiles();assertTrue(leftovers==null||leftovers.length==0);
    }

    @Test public void verifiedCallbackUsesFrozenBytesEvenWhenRegisteredSourceChangesDuringDecode()throws Exception{
        ProjectStore.Project source=project();byte[] original=picture(32,64,0xff123456,false);
        File registered=add(source,"a","a.png",original,"image/png");
        add(source,"b","b.png",picture(32,64,0xff654321,false),"image/png");
        JSONObject manifest=StoryboardSourceManifest.capture(context,source,new JSONObject());File[] frozen=new File[1];
        String decodedHash=StoryboardSourceManifest.withVerifiedSource(context,source,manifest,"a",(file,entry)->{
            frozen[0]=file;assertNotEquals(registered.getCanonicalPath(),file.getCanonicalPath());
            assertEquals(sha(original),entry.getString("sourceSha256"));assertEquals(32,entry.getInt("orientedWidth"));
            Files.write(registered.toPath(),picture(32,64,0xffabcdef,false));
            assertArrayEquals(original,Files.readAllBytes(file.toPath()));return sha(Files.readAllBytes(file.toPath()));
        });
        assertEquals(sha(original),decodedHash);assertFalse(frozen[0].exists());
        assertThrows(IllegalArgumentException.class,()->StoryboardSourceManifest.withVerifiedSource(context,source,manifest,"a",(file,entry)->"unreachable"));
    }

    @Test public void staleExpectedRecordCannotReachCallbackAndCallbackFailureReleasesSnapshot()throws Exception{
        ProjectStore.Project source=project();add(source,"a","a.png",picture(32,64,0xff123456,false),"image/png");
        JSONObject entry=StoryboardSourceManifest.captureSource(context,source.assets.get(0));boolean[] invoked={false};
        JSONObject damaged=new JSONObject(entry.toString()).put("orientedWidth",64);
        assertThrows(IllegalArgumentException.class,()->StoryboardSourceManifest.withVerifiedSource(context,source.assets.get(0),damaged,(file,actual)->{invoked[0]=true;return null;}));
        assertFalse(invoked[0]);File[] frozen=new File[1];
        assertThrows(IOException.class,()->StoryboardSourceManifest.withVerifiedSource(context,source.assets.get(0),entry,(file,actual)->{frozen[0]=file;throw new IOException("Decoder failure");}));
        assertNotNull(frozen[0]);assertFalse(frozen[0].exists());
        File probes=new File(context.getCacheDir(),"storyboard_source_probes");assertEquals(0,probes.listFiles().length);
    }

    @Test public void firstTimeAuthoringSnapshotIsProjectOwnedAndReturnsHashOfDisplayedBytes()throws Exception{
        ProjectStore.Project source=project();byte[] original=picture(32,64,0xff778899,false);add(source,"only","same.png",original,"image/png");
        JSONObject entry=StoryboardSourceManifest.withOwnedSourceSnapshot(context,source,"only",(file,actual)->{
            assertEquals(sha(Files.readAllBytes(file.toPath())),actual.getString("sourceSha256"));return actual;
        });
        assertEquals(sha(original),entry.getString("sourceSha256"));assertEquals("only",entry.getString("assetId"));
        assertThrows(IllegalArgumentException.class,()->StoryboardSourceManifest.withOwnedSourceSnapshot(context,source,"unknown",(file,actual)->null));
        assertThrows(IllegalArgumentException.class,()->StoryboardSourceManifest.withVerifiedSource(context,source.assets.get(0),(String)null,(file,actual)->null));
        assertEquals(sha(original),StoryboardSourceManifest.withFrozenSource(context,source.assets.get(0),(file,actual)->actual.getString("sourceSha256")));
    }

    @Test public void cleanupFailureDiscardsCompletedOwnedResultThroughVerifiedWrappers()throws Exception{
        ProjectStore.Project source=project();add(source,"a","a.png",picture(32,64,0xff778899,false),"image/png");
        JSONObject entry=StoryboardSourceManifest.captureSource(context,source.assets.get(0));boolean[] closed={false};File[] frozen=new File[1];
        try{
            assertThrows(IllegalStateException.class,()->StoryboardSourceManifest.withVerifiedSource(context,source.assets.get(0),entry,(file,actual)->{
                frozen[0]=file;assertTrue(file.delete());assertTrue(file.mkdir());Files.write(new File(file,"cleanup-fault").toPath(),new byte[]{1});
                return (AutoCloseable)()->closed[0]=true;
            }));
            assertTrue("A completed native result must not leak when cleanup prevents delivery",closed[0]);
        }finally{
            if(frozen[0]!=null){new File(frozen[0],"cleanup-fault").delete();frozen[0].delete();}
        }
    }

    public static final class OwnedProvider extends ContentProvider {
        final Map<String,File> files=new HashMap<>();int opened,closed;
        @Override public boolean onCreate(){return true;}
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{
            File file=files.get(uri.getPath());if(file==null)throw new FileNotFoundException("Source grant unavailable");opened++;
            return new ParcelFileDescriptor(ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY)){
                @Override public void close()throws IOException{try{super.close();}finally{closed++;}}
            };
        }
        @Override public String getType(Uri uri){return "image/png";}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){return null;}
        @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
        @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
    }
}
