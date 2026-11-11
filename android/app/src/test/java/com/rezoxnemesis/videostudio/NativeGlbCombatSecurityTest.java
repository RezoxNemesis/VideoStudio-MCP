package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class NativeGlbCombatSecurityTest {
    private static ProjectStore.Asset registerGlb(Context c,ProjectStore.Project project,
                                                  File file) throws Exception {
        ProjectStore.Asset model=new ProjectStore.Asset();
        model.id=UUID.randomUUID().toString();
        model.uri=Uri.fromFile(file).toString();
        model.mime="model/gltf-binary";
        model.name="fighter.glb";
        project.assets.add(model);
        return model;
    }
    private static File glb(File dir) throws Exception {
        if(!dir.exists()&&!dir.mkdirs())throw new IllegalStateException("Test folder");
        File model=new File(dir,"actor_"+UUID.randomUUID()+".glb");
        ByteBuffer raw=ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN);
        raw.putInt(0x46546c67).putInt(2).putInt(20);
        try(FileOutputStream out=new FileOutputStream(model)){out.write(raw.array());}
        return model;
    }

    @Test public void projectPrivateActorPassesExplicitSourceGate() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        ProjectStore store=new ProjectStore(c);
        ProjectStore.Project source=store.create("Two user-imported GLB fighters");
        File dir=new File(c.getFilesDir(),"creative_workspace/projects/"+source.id+"/models");
        File file=glb(dir);
        ProjectStore.Asset asset=registerGlb(c,source,file);
        assertEquals(file.getCanonicalPath(),
                NativeGlbCombatComposer.glbFile(c,source,asset).getCanonicalPath());
    }

    @Test public void galleryAndOtherProvidersAreNotAccepted() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        ProjectStore.Project source=new ProjectStore(c).create("no broad media access");
        ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id=UUID.randomUUID().toString();
        asset.mime="model/gltf-binary";
        asset.uri="content://media/external/file/123";
        source.assets.add(asset);
        try{
            NativeGlbCombatComposer.glbFile(c,source,asset);
            fail("Any arbitrary Gallery or provider URI must be denied");
        }catch(IllegalArgumentException expected){
            assertTrue(expected.getMessage().contains("private VideoStudio"));
        }
    }

    @Test public void registeredButExternalPrivateFileIsStillRejected() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        ProjectStore.Project source=new ProjectStore(c).create("external path policy");
        File file=glb(new File(c.getCacheDir(),"not_video_studio_model"));
        ProjectStore.Asset asset=registerGlb(c,source,file);
        try{
            NativeGlbCombatComposer.glbFile(c,source,asset);
            fail("Other files must not be loaded by a project-scoped GLB renderer");
        }catch(IllegalArgumentException expected){
            assertTrue(expected.getMessage().contains("private project"));
        }
    }

    @Test(expected=IllegalArgumentException.class)
    public void refusesModelNotRegisteredInSourceProject() throws Exception {
        Context c=RuntimeEnvironment.getApplication();
        ProjectStore.Project source=new ProjectStore(c).create("unregistered model");
        File file=glb(new File(c.getFilesDir(),
                "creative_workspace/projects/"+source.id+"/models"));
        ProjectStore.Asset asset=new ProjectStore.Asset();
        asset.id=UUID.randomUUID().toString();
        asset.uri=Uri.fromFile(file).toString();
        asset.mime="model/gltf-binary";
        NativeGlbCombatComposer.glbFile(c,source,asset);
    }
}
