package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33, manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeChunkedImportTest {
    private static final String MIME = "image/png";
    private static final String NAME = "01_Action_Frame.png";

    private byte[] picture() {
        Bitmap bitmap = Bitmap.createBitmap(64, 96, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xffee33aa);
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        bitmap.recycle();
        return stream.toByteArray();
    }

    private String hash(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for(byte b : digest) result.append(String.format(java.util.Locale.US, "%02x",b & 255));
        return result.toString();
    }

    @Test public void chunkTransferVerifiesIntegrityAndResumesExactly() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String projectId = UUID.randomUUID().toString(), id=UUID.randomUUID().toString();
        byte[] bytes = picture();
        int mid = Math.min(80, bytes.length / 2);
        String sha = hash(bytes);

        NativeChunkedImport transfer = new NativeChunkedImport(context.getFilesDir());
        byte[] first = Arrays.copyOfRange(bytes, 0, mid);
        JSONObject initial = transfer.append(projectId,id,NAME,MIME,bytes.length,sha,0,
                Base64.getEncoder().encodeToString(first));
        assertEquals(mid, initial.getLong("bytesReceived"));

        // Reconnect/retry with exactly the same chunk must not duplicate bytes.
        NativeChunkedImport resumed = new NativeChunkedImport(context.getFilesDir());
        JSONObject repeated = resumed.append(projectId,id,NAME,MIME,bytes.length,sha,0,
                Base64.getEncoder().encodeToString(first));
        assertEquals(mid, repeated.getLong("bytesReceived"));

        byte[] rest = Arrays.copyOfRange(bytes, mid, bytes.length);
        JSONObject all = resumed.append(projectId,id,NAME,MIME,bytes.length,sha,mid,
                Base64.getEncoder().encodeToString(rest));
        assertTrue(all.getBoolean("completedBytes"));

        NativeChunkedImport.Result file = resumed.finalizeImage(projectId,id,NAME,MIME,
                bytes.length,sha,new File(context.getFilesDir(),"verified-frames"));
        assertEquals(64,file.width); assertEquals(96,file.height);
        assertTrue(file.file.isFile());
        assertEquals(bytes.length,file.file.length());
        assertEquals(sha,hash(java.nio.file.Files.readAllBytes(file.file.toPath())));
    }

    @Test public void wrongHashFailsWithoutPublishingIncompleteAsset() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String projectId = UUID.randomUUID().toString(), id=UUID.randomUUID().toString();
        byte[] bytes = picture(), corrupted = bytes.clone();
        String expected = hash(bytes);
        corrupted[corrupted.length-1] ^= 1;
        NativeChunkedImport importer = new NativeChunkedImport(context.getFilesDir());
        importer.append(projectId,id,NAME,MIME,bytes.length,expected,0,
                Base64.getEncoder().encodeToString(corrupted));
        File target = new File(context.getFilesDir(),"verified-failed");
        try {
            importer.finalizeImage(projectId,id,NAME,MIME,bytes.length,expected,target);
            fail("Corrupted image unexpectedly committed");
        } catch (IllegalArgumentException expectedError) {
            assertTrue(expectedError.getMessage().contains("SHA-256"));
        }
    }

    @Test public void refusesPathTraversalAndChunkGaps() throws Exception {
        String id=UUID.randomUUID().toString(), project=UUID.randomUUID().toString();
        byte[] bytes=picture(), chunk=Arrays.copyOfRange(bytes,0,40);
        NativeChunkedImport importer=new NativeChunkedImport(RuntimeEnvironment.getApplication().getFilesDir());
        try {
            importer.append(project,id,"../private.png",MIME,bytes.length,hash(bytes),0,
                    Base64.getEncoder().encodeToString(chunk));
            fail("Filename traversal accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("filename"));
        }
        try {
            importer.append(project,id,NAME,MIME,bytes.length,hash(bytes),42,
                    Base64.getEncoder().encodeToString(chunk));
            fail("Chunk gap accepted");
        } catch (IllegalArgumentException expected) {
            assertTrue(expected.getMessage().contains("offset"));
        }
    }
}
