package com.rezoxnemesis.videostudio;

import android.graphics.Bitmap;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowStatFs;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativeRiggedCombatComposerRetentionTest {
    @Test public void cancellationAfterRealFirstFrameKeepsPriorGraphAndMedia()throws Exception{
        android.content.Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);
        // Four GiB at the shadow's 4096-byte block size lets the real disk preflight run.
        ShadowStatFs.registerStats(context.getFilesDir().getAbsolutePath(),1_048_576,1_048_576,1_048_576);
        ProjectStore.Project source=store.create("Registered compatible source");
        File image=new File(context.getFilesDir(),"compatible-source-"+UUID.randomUUID()+".png");
        Bitmap pixels=Bitmap.createBitmap(320,560,Bitmap.Config.ARGB_8888);pixels.eraseColor(0xff435272);
        try(FileOutputStream out=new FileOutputStream(image)){assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,out));}finally{pixels.recycle();}
        ProjectStore.Asset input=new ProjectStore.Asset();input.id=UUID.randomUUID().toString();input.uri=Uri.fromFile(image).toString();input.mime="image/png";input.name=image.getName();source.assets.add(input);store.save(source);
        Rig2DProfile black=Rig2DProfile.create(source.id,input.id,true,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline());
        Rig2DProfile white=Rig2DProfile.create(source.id,input.id,false,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline());
        ProjectStore.Project destination=store.create("Prior output survives cancellation");destination.sourcePrompt="rigged2d: existing generation";
        File folder=new File(context.getFilesDir(),"creative_workspace/projects/"+destination.id+"/generated/skinned_2d");assertTrue(folder.mkdirs());
        File existing=new File(folder,"skin_0000.jpg");byte[] original="prior-durable-generation".getBytes(StandardCharsets.UTF_8);
        try(FileOutputStream out=new FileOutputStream(existing)){out.write(original);}
        ProjectStore.Asset previous=new ProjectStore.Asset();previous.id=UUID.randomUUID().toString();previous.uri=Uri.fromFile(existing).toString();previous.mime="image/jpeg";previous.name=existing.getName();destination.assets.add(previous);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id=UUID.randomUUID().toString();clip.assetId=previous.id;clip.outMs=42;destination.clips.add(clip);store.save(destination);
        String before=destination.toJson().toString();boolean[] reachedProgress={false};
        try{
            try{NativeRiggedCombatComposer.compose(context,store,source,destination,black,white,320,560,24,2,true,null,
                    (completed,total,seconds,impact)->{assertEquals(1,completed);reachedProgress[0]=true;throw new InterruptedException("Owner cancelled after an actual encoded frame");});
                fail("Cancellation must stop native generation");
            }catch(InterruptedException expected){assertTrue("Tripwire must run after real frame encoding, not an early geometry rejection",reachedProgress[0]);}
            assertEquals(before,destination.toJson().toString());assertEquals(before,store.get(destination.id).toJson().toString());
            assertArrayEquals(original,Files.readAllBytes(existing.toPath()));
            File[] remaining=folder.listFiles();assertNotNull(remaining);assertEquals("Only prior media remains; cancelled UUID generation was removed",1,remaining.length);
        }finally{image.delete();ShadowStatFs.unregisterStats(context.getFilesDir().getAbsolutePath());}
    }
    @Test public void rejectedSquareSourcePreservesExistingDestinationAndFrameFile()throws Exception{
        android.content.Context context=RuntimeEnvironment.getApplication();ProjectStore store=new ProjectStore(context);
        ProjectStore.Project source=store.create("Registered square source");
        File image=new File(context.getFilesDir(),"square-source-"+UUID.randomUUID()+".png");
        Bitmap pixels=Bitmap.createBitmap(320,320,Bitmap.Config.ARGB_8888);pixels.eraseColor(0xff435272);
        try(FileOutputStream out=new FileOutputStream(image)){assertTrue(pixels.compress(Bitmap.CompressFormat.PNG,100,out));}finally{pixels.recycle();}
        ProjectStore.Asset sourceAsset=new ProjectStore.Asset();sourceAsset.id=UUID.randomUUID().toString();sourceAsset.uri=Uri.fromFile(image).toString();
        sourceAsset.mime="image/png";sourceAsset.name=image.getName();source.assets.add(sourceAsset);store.save(source);
        Rig2DProfile black=Rig2DProfile.create(source.id,sourceAsset.id,true,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline());
        Rig2DProfile white=Rig2DProfile.create(source.id,sourceAsset.id,false,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline());
        ProjectStore.Project destination=store.create("Existing owner output");destination.sourcePrompt="rigged2d: existing generation";
        File folder=new File(context.getFilesDir(),"creative_workspace/projects/"+destination.id+"/generated/skinned_2d");assertTrue(folder.mkdirs());
        File existing=new File(folder,"skin_0000.jpg");byte[] original="previous-owner-frame-do-not-delete".getBytes(StandardCharsets.UTF_8);
        try(FileOutputStream out=new FileOutputStream(existing)){out.write(original);}
        ProjectStore.Asset previous=new ProjectStore.Asset();previous.id=UUID.randomUUID().toString();previous.uri=Uri.fromFile(existing).toString();previous.mime="image/jpeg";previous.name=existing.getName();destination.assets.add(previous);
        ProjectStore.Clip clip=new ProjectStore.Clip();clip.id=UUID.randomUUID().toString();clip.assetId=previous.id;clip.outMs=42;destination.clips.add(clip);
        destination.latestExportUri="content://media/existing-owner-output";store.save(destination);
        String before=destination.toJson().toString();
        try{
            try{NativeRiggedCombatComposer.compose(context,store,source,destination,black,white,320,560,24,2,true,null,null);fail("Unregistered square-to-portrait sources must be rejected");}
            catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("registration"));}
            assertEquals("Predictable render rejection must not change the in-memory destination",before,destination.toJson().toString());
            assertEquals("Predictable render rejection must not publish a replacement graph",before,store.get(destination.id).toJson().toString());
            assertTrue("Previous generated media must remain referenced and readable",existing.isFile());
            assertArrayEquals(original,Files.readAllBytes(existing.toPath()));
        }finally{image.delete();}
    }
}
