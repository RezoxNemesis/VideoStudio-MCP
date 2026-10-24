package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.EditedMediaItemSequence;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;

/**
 * Shared-composition MIME admission, using real private JPEG/PNG fixtures.
 *
 * These tests do not establish the cause of the owner's unavailable phone
 * project, decode MP3, run a GPU or verify export/playback. Media3 preview can
 * sniff an opaque JPEG. Its separate ImageAssetLoader export admission also
 * needs the already-known image MIME when the owned filename is opaque.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class StudioStillImageMimeAdmissionTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final List<File> privateFixtures=new ArrayList<>();

    @After public void removeOnlyThisTestsPrivateFixtures(){
        for(File file:privateFixtures)if(file.exists())assertTrue(file.delete());
    }

    @Test public void knownOwnedJpegMimeIsRetainedInTheActualSharedComposition()throws Exception{
        ProjectStore.Project project=still("image/jpeg",".bin");
        ProjectStore.Asset asset=project.assets.get(0);
        EditedMediaItem item=base(composition(project));
        assertEquals(asset.uri,item.mediaItem.localConfiguration.uri.toString());
        assertEquals(3000,item.mediaItem.localConfiguration.imageDurationMs);
        assertEquals(3_000_000,item.getPresentationDurationUs());
        assertEquals("image/jpeg",item.mediaItem.localConfiguration.mimeType);
    }

    @Test public void pinnedExportClassifierAcceptsTheOpaqueJpegBuiltByTheApp()throws Exception{
        ProjectStore.Project project=still("image/jpeg",".bin");
        MediaItem actual=base(composition(project)).mediaItem;
        // Positive and negative controls use the SAME real JPEG URI. This probe
        // classifies MIME only; it does not pretend to execute ImageAssetLoader.
        assertNull(pinnedImageMime(actual.buildUpon().setMimeType(null).build()));
        assertEquals("image/jpeg",pinnedImageMime(
                actual.buildUpon().setMimeType("image/jpeg").build()));
        assertEquals("image/jpeg",pinnedImageMime(actual));
    }

    @Test public void knownOwnedPngMimeIsRetainedWithoutReplacingItsNativeClock()throws Exception{
        ProjectStore.Project project=still("image/png",".bin");
        EditedMediaItem item=base(composition(project));
        assertTrue(item.removeAudio);assertFalse(item.removeVideo);
        assertEquals(30,item.frameRate);
        assertEquals(3_000_000,item.durationUs);
        assertEquals(3_000_000,item.getPresentationDurationUs());
        assertEquals("image/png",item.mediaItem.localConfiguration.mimeType);
    }

    @Test public void stillWithIndependentMusicUsesKnownImageMimeAndPreservesLegacyAudio()throws Exception{
        ProjectStore.Project project=still("image/jpeg",".bin");
        ProjectStore.Asset audio=wav();project.assets.add(audio);
        StudioLayer music=new StudioLayer();music.id="existing-music";
        music.type=StudioLayer.AUDIO;music.assetId=audio.id;
        music.startMs=500;music.durationMs=1000;music.sourceInMs=0;music.gain=.5f;
        project.studioLayers.add(music);
        Composition result=composition(project);
        assertEquals(2,result.sequences.size());
        EditedMediaItem audioItem=source(result,audio.uri);
        assertTrue(audioItem.removeVideo);assertFalse(audioItem.removeAudio);
        assertNull("This image repair must preserve existing AUDIO MIME omission",
                audioItem.mediaItem.localConfiguration.mimeType);
        assertEquals(C.TIME_UNSET,audioItem.mediaItem.localConfiguration.imageDurationMs);
        assertEquals(1_000_000,audioItem.getPresentationDurationUs());
        assertEquals(500_000,result.sequences.get(1).editedMediaItems.get(0).getPresentationDurationUs());
        assertEquals("image/jpeg",base(result).mediaItem.localConfiguration.mimeType);
    }

    @Test public void missingEmptyAndGenericCatalogMimeKeepTheirExistingOmittedMimeAndTiming()throws Exception{
        for(String catalogMime:new String[]{null,"","application/octet-stream"}){
            ProjectStore.Project project=still("image/jpeg",".bin");
            project.assets.get(0).mime=catalogMime;
            EditedMediaItem item=base(composition(project));
            // Catalog omission is intentionally preserved. This is a composition
            // compatibility check, not a claim these unknown records can play.
            assertNull(item.mediaItem.localConfiguration.mimeType);
            assertEquals(C.TIME_UNSET,item.mediaItem.localConfiguration.imageDurationMs);
            assertEquals(C.RATE_UNSET_INT,item.frameRate);
        }
    }

    @Test public void ordinarySuffixedJpegStillPassesItsExistingExportClassification()throws Exception{
        ProjectStore.Project project=still("image/jpeg",".jpg");
        MediaItem actual=base(composition(project)).mediaItem;
        // This control already works by suffix on the old source. It prevents
        // this regression from being described as all JPEG previews failing.
        assertEquals("image/jpeg",pinnedImageMime(actual.buildUpon().setMimeType(null).build()));
        assertEquals("image/jpeg",pinnedImageMime(actual));
    }

    @Test public void buildingKeepsTheOwnedImageBytesAndEditableProjectIdentity()throws Exception{
        ProjectStore.Project project=still("image/jpeg",".bin");
        File file=new File(Uri.parse(project.assets.get(0).uri).getPath());
        byte[] before=Files.readAllBytes(file.toPath());
        String recipe=SketchDocument.canonical(project.toJson());
        String hash=SketchProjectStore.fileHash(file);
        composition(project);
        assertEquals(recipe,SketchDocument.canonical(project.toJson()));
        assertArrayEquals(before,Files.readAllBytes(file.toPath()));
        assertEquals(hash,SketchProjectStore.fileHash(file));
    }

    private Composition composition(ProjectStore.Project project)throws Exception{
        return new NativeRenderEngine(context).buildPreviewComposition(project,"9:16","720p");
    }

    private static EditedMediaItem base(Composition composition){
        return composition.sequences.get(0).editedMediaItems.get(0);
    }

    private static EditedMediaItem source(Composition composition,String uri){
        for(EditedMediaItemSequence sequence:composition.sequences)
            for(EditedMediaItem item:sequence.editedMediaItems)
                if(item.mediaItem.localConfiguration!=null
                        &&uri.equals(item.mediaItem.localConfiguration.uri.toString()))return item;
        throw new AssertionError("Owned source is absent from the actual composition");
    }

    /**
     * Explicit PINNED PRIVATE-SDK probe: Media3 1.11.1 TransformerUtil's
     * package-private getImageMimeType(Context,MediaItem). All application
     * admission above goes through public NativeRenderEngine/Composition APIs.
     */
    private String pinnedImageMime(MediaItem item)throws Exception{
        Class<?> type=Class.forName("androidx.media3.transformer.TransformerUtil");
        Method method=type.getDeclaredMethod("getImageMimeType",Context.class,MediaItem.class);
        method.setAccessible(true);
        return (String)method.invoke(null,context,item);
    }

    private ProjectStore.Project still(String mime,String suffix)throws Exception{
        File file=privateFile(suffix);
        Bitmap bitmap=Bitmap.createBitmap(32,48,Bitmap.Config.ARGB_8888);
        try{
            bitmap.eraseColor(0xff7d4a32);
            try(FileOutputStream output=new FileOutputStream(file)){
                assertTrue(bitmap.compress("image/jpeg".equals(mime)
                        ?Bitmap.CompressFormat.JPEG:Bitmap.CompressFormat.PNG,92,output));
            }
        }finally{bitmap.recycle();}
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
        assertEquals(32,bounds.outWidth);assertEquals(48,bounds.outHeight);
        assertEquals("The owned fixture contains the declared image format",mime,bounds.outMimeType);
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned-still";
        asset.name="Already imported reference"+("image/jpeg".equals(mime)?".jpg":".png");
        asset.uri=Uri.fromFile(file).toString();asset.mime=mime;asset.durationMs=0;
        asset.sizeBytes=file.length();asset.seekable=true;asset.persistedReadAccess=true;
        ProjectStore.Project project=new ProjectStore.Project();project.id=UUID.randomUUID().toString();
        project.name="Opaque owned still MIME admission";project.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="original-still";
        clip.assetId=asset.id;clip.inMs=0;clip.outMs=3000;project.clips.add(clip);
        return project;
    }

    /** Valid one-second PCM WAV fixture; no MP3/voice or audible-output claim. */
    private ProjectStore.Asset wav()throws Exception{
        int pcmBytes=8000*2;
        ByteBuffer data=ByteBuffer.allocate(44+pcmBytes).order(ByteOrder.LITTLE_ENDIAN);
        data.put(new byte[]{'R','I','F','F'}).putInt(36+pcmBytes).put(new byte[]{'W','A','V','E'});
        data.put(new byte[]{'f','m','t',' '}).putInt(16).putShort((short)1).putShort((short)1);
        data.putInt(8000).putInt(16000).putShort((short)2).putShort((short)16);
        data.put(new byte[]{'d','a','t','a'}).putInt(pcmBytes);
        File file=privateFile(".wav");Files.write(file.toPath(),data.array());
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned-music";asset.name="Fixture music.wav";
        asset.uri=Uri.fromFile(file).toString();asset.mime="audio/wav";asset.durationMs=1000;
        asset.sizeBytes=file.length();asset.seekable=true;asset.persistedReadAccess=true;
        return asset;
    }

    private File privateFile(String suffix)throws Exception{
        File file=File.createTempFile("still-mime-admission-",suffix,context.getFilesDir());
        privateFixtures.add(file);return file;
    }
}
