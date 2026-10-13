package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import static org.junit.Assert.*;

/** Synthetic native source fixtures, not private Test 1 or device cinematic approval. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class StoryboardSequenceAuditorTest {
    @Test public void matchingOwnedManifestRemainsAuditableWithoutChangingSources()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);
        ProjectStore.Project source=store.create("Matching owned audit source");
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(context,false)){
            source.assets.add(fixture.from);source.assets.add(fixture.to);
            for(ProjectStore.Asset asset:source.assets){ProjectStore.Clip clip=new ProjectStore.Clip();clip.id=UUID.randomUUID().toString();clip.assetId=asset.id;clip.outMs=2000;source.clips.add(clip);}
            store.save(source);String before=source.toJson().toString();JSONObject manifest=StoryboardSourceManifest.capture(context,source,new JSONObject());
            StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,new JSONObject(),manifest);
            JSONObject report=StoryboardSequenceAuditor.inspect(context,source,plan,0,null);
            assertEquals(1,report.getInt("pairsInspected"));assertEquals(before,source.toJson().toString());
            StoryboardSourceManifest.verifyOwnedSources(context,source,manifest);
        }
    }

    @Test public void acceptedManifestCannotAuditReplacementBytesAtTheSameRegisteredUri()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);
        ProjectStore.Project source=store.create("Owned audit source");
        try(SourceArtworkRasterFixture fixture=new SourceArtworkRasterFixture(context,false,0xff6a3520)){
            source.assets.add(fixture.from);source.assets.add(fixture.to);
            for(ProjectStore.Asset asset:source.assets){ProjectStore.Clip clip=new ProjectStore.Clip();clip.id=UUID.randomUUID().toString();clip.assetId=asset.id;clip.outMs=2000;source.clips.add(clip);}
            store.save(source);JSONObject manifest=StoryboardSourceManifest.capture(context,source,new JSONObject());
            StoryboardBatchPlanner.Plan plan=StoryboardBatchPlanner.plan(source,new JSONObject(),manifest);
            File first=new File(Uri.parse(fixture.from.uri).getPath()),replacement=new File(Uri.parse(fixture.to.uri).getPath());
            byte[] replacementBytes=Files.readAllBytes(replacement.toPath());Files.copy(replacement.toPath(),first.toPath(),StandardCopyOption.REPLACE_EXISTING);
            assertThrows(IllegalArgumentException.class,()->StoryboardSequenceAuditor.inspect(context,source,plan,0,null));
            assertArrayEquals(replacementBytes,Files.readAllBytes(first.toPath()));assertEquals(2,source.assets.size());assertEquals(2,source.clips.size());
        }
    }
}
