package com.rezoxnemesis.videostudio;

import android.net.Uri;
import androidx.media3.muxer.MuxerException;
import java.io.File;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Admission guards only. Actual held-FD writing/trim must execute on Android. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class GaplessAudioMuxerTest {
    @Test public void replacedOutputAfterFinalizationCannotReceiveOriginalSuccessProof()throws Exception{
        File path=new File(RuntimeEnvironment.getApplication().getFilesDir(),"mux-swap-"+UUID.randomUUID());Files.write(path.toPath(),new byte[]{1,2,3});org.json.JSONObject held;try(var input=new java.io.FileInputStream(path)){held=PlayableMediaVerifier.bytesProof(input,()->false);}
        File replacement=new File(path.getParentFile(),"replacement-"+UUID.randomUUID());Files.write(replacement.toPath(),new byte[]{4,5,6});Files.move(replacement.toPath(),path.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);org.json.JSONObject reopened;try(var input=new java.io.FileInputStream(path)){reopened=PlayableMediaVerifier.bytesProof(input,()->false);}
        assertThrows(java.io.IOException.class,()->GaplessAudioMuxer.bindVerifiedBytes(held,reopened));assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(path.toPath()));
    }
    @Test public void byteBindingRequiresBothChecksumAndSizeFromFinalizedWriter()throws Exception{
        var proof=new org.json.JSONObject().put("sha256","a".repeat(64)).put("sizeBytes",123);assertSame(proof,GaplessAudioMuxer.bindVerifiedBytes(proof,proof));assertThrows(java.io.IOException.class,()->GaplessAudioMuxer.bindVerifiedBytes(null,proof));assertThrows(java.io.IOException.class,()->GaplessAudioMuxer.bindVerifiedBytes(proof,new org.json.JSONObject(proof.toString()).put("sizeBytes",124)));
    }
    @Test public void freshMuxFactoryNeverTruncatesOwnerOrUnrelatedExistingFiles()throws Exception{
        File original=new File(RuntimeEnvironment.getApplication().getFilesDir(),"mux-source-"+UUID.randomUUID());Files.write(original.toPath(),new byte[]{1,2,3});var graph=new ProjectStore.Project();var asset=new ProjectStore.Asset();asset.uri=Uri.fromFile(original).toString();graph.assets.add(asset);var factory=new GaplessAudioMuxer.Factory(graph,new AtomicReference<>(),()->false);
        assertThrows(MuxerException.class,()->factory.create(original.getAbsolutePath()));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(original.toPath()));File other=new File(original.getParentFile(),"mux-existing-"+UUID.randomUUID());Files.write(other.toPath(),new byte[]{4,5,6});assertThrows(MuxerException.class,()->factory.create(other.getAbsolutePath()));assertArrayEquals(new byte[]{4,5,6},Files.readAllBytes(other.toPath()));
    }
    @Test public void ownerSymlinkAliasIsRejectedBeforeAnyWriterAndLowRatePaddingStaysBounded()throws Exception{
        File original=new File(RuntimeEnvironment.getApplication().getFilesDir(),"mux-original-"+UUID.randomUUID());Files.write(original.toPath(),new byte[]{1,2,3});File alias=new File(original.getParentFile(),"mux-alias-"+UUID.randomUUID());Files.createSymbolicLink(alias.toPath(),original.toPath());var graph=new ProjectStore.Project();var asset=new ProjectStore.Asset();asset.uri=Uri.fromFile(original).toString();graph.assets.add(asset);var factory=new GaplessAudioMuxer.Factory(graph,new AtomicReference<>(),()->false);assertThrows(MuxerException.class,()->factory.create(alias.getAbsolutePath()));assertTrue(Files.isSymbolicLink(alias.toPath()));assertArrayEquals(new byte[]{1,2,3},Files.readAllBytes(original.toPath()));assertTrue(GaplessAudioMuxer.maxPaddingUs(8000)<2_000_000);assertThrows(IllegalArgumentException.class,()->GaplessAudioMuxer.maxPaddingUs(0));
    }
}
