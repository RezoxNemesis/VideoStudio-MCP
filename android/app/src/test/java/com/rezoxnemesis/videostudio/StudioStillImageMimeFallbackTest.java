package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import androidx.media3.common.MediaItem;
import androidx.media3.transformer.Composition;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Method;
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
 * Preserve old suffix fallback for wildcard/unknown image catalog metadata.
 * Real private JPEG fixtures; composition/MIME classification only, no GPU,
 * playback/export or claim about the unavailable owner's phone project.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class StudioStillImageMimeFallbackTest {
    private final Context context=RuntimeEnvironment.getApplication();
    private final List<File> privateFixtures=new ArrayList<>();

    @After public void removeOnlyThisTestsPrivateFixtures(){
        for(File file:privateFixtures)if(file.exists())assertTrue(file.delete());
    }

    @Test public void wildcardImageMetadataRetainsTheExistingJpegSuffixFallback()throws Exception{
        assertSuffixFallback("image/*");
    }

    @Test public void unknownImageSubtypeRetainsTheExistingJpegSuffixFallback()throws Exception{
        assertSuffixFallback("image/x-unknown");
    }

    private void assertSuffixFallback(String catalogMime)throws Exception{
        File file=File.createTempFile("still-mime-fallback-",".jpg",context.getFilesDir());
        privateFixtures.add(file);
        Bitmap bitmap=Bitmap.createBitmap(24,32,Bitmap.Config.ARGB_8888);
        try{
            bitmap.eraseColor(0xff42617d);
            try(FileOutputStream output=new FileOutputStream(file)){
                assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,92,output));
            }
        }finally{bitmap.recycle();}
        BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;
        BitmapFactory.decodeFile(file.getAbsolutePath(),bounds);
        assertEquals(24,bounds.outWidth);assertEquals(32,bounds.outHeight);
        assertEquals("The suffixed source really contains JPEG pixels","image/jpeg",bounds.outMimeType);
        byte[] bytes=Files.readAllBytes(file.toPath());
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id="owned-still";
        asset.name="Imported reference.jpg";asset.uri=Uri.fromFile(file).toString();asset.mime=catalogMime;
        asset.durationMs=0;asset.sizeBytes=file.length();asset.seekable=true;asset.persistedReadAccess=true;
        ProjectStore.Project project=new ProjectStore.Project();project.id=UUID.randomUUID().toString();
        project.name="Preserved JPEG inference";project.assets.add(asset);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id="original-image";clip.assetId=asset.id;
        clip.inMs=0;clip.outMs=3000;project.clips.add(clip);
        String original=SketchDocument.canonical(project.toJson());
        Composition result=new NativeRenderEngine(context).buildPreviewComposition(project,"9:16","720p");
        MediaItem media=result.sequences.get(0).editedMediaItems.get(0).mediaItem;
        assertEquals(asset.uri,media.localConfiguration.uri.toString());
        assertEquals(3000,media.localConfiguration.imageDurationMs);
        // Positive legacy control on the SAME real URI. Unsupported catalog MIME
        // must not suppress this valid inference by becoming explicit MIME.
        assertEquals("image/jpeg",pinnedImageMime(media.buildUpon().setMimeType(null).build()));
        assertNull("Only supported known image MIME should become explicit",media.localConfiguration.mimeType);
        assertEquals("image/jpeg",pinnedImageMime(media));
        assertEquals(original,SketchDocument.canonical(project.toJson()));
        assertArrayEquals(bytes,Files.readAllBytes(file.toPath()));
    }

    /** Explicit PINNED PRIVATE-SDK classifier probe, Media3 1.11.1 only. */
    private String pinnedImageMime(MediaItem media)throws Exception{
        Class<?> type=Class.forName("androidx.media3.transformer.TransformerUtil");
        Method method=type.getDeclaredMethod("getImageMimeType",Context.class,MediaItem.class);
        method.setAccessible(true);
        return (String)method.invoke(null,context,media);
    }
}
