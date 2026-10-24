package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import static org.junit.Assert.*;

/** Public still-export behavior with a trusted Context ancestor alias, without private reflection. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchStillExportOwnedRootAliasTest {
    private final Context base=RuntimeEnvironment.getApplication();

    @Test public void trustedContextAncestorAliasAllowsNativeCaptureVerificationAndExactReplay()throws Exception {
        Fixture f=new Fixture();
        Path container=Files.createTempDirectory(base.getFilesDir().toPath(),"trusted_still_context_");
        Path realUser=Files.createDirectory(container.resolve("real-user"));
        Path realFiles=Files.createDirectory(realUser.resolve("files"));
        Path userAlias=Files.createSymbolicLink(container.resolve("user-zero-alias"),realUser);
        File aliasedFiles=userAlias.resolve("files").toFile();
        assertTrue(Files.isSymbolicLink(userAlias));
        assertFalse("The trusted files directory is not itself a symlink",Files.isSymbolicLink(aliasedFiles.toPath()));
        assertEquals(realFiles.toFile().getCanonicalFile(),aliasedFiles.getCanonicalFile());
        Context aliased=new FilesContext(base,aliasedFiles);
        String sourceBefore=f.document();
        ProjectStore projects=new ProjectStore(base);
        ProjectStore.Project keep=projects.create("Keep active project for trusted-root alias study");
        String activeBefore=projects.get(keep.id).toJson().toString();

        SketchStillExport.admit(aliased,f.args());
        SketchNativeExport.Prepared first=SketchNativeExport.prepare(aliased,f.args());
        assertTrue(first.completed);
        assertEquals(1,first.project.assets.size());
        assertTrue(first.project.clips.isEmpty());
        assertEquals("image/png",first.project.assets.get(0).mime);
        assertEquals(256,first.metadata.getInt("width"));
        assertEquals(256,first.metadata.getInt("height"));
        assertTrue(first.file.getCanonicalPath().startsWith(realFiles.toFile().getCanonicalPath()+File.separator));
        Bitmap image=BitmapFactory.decodeFile(first.file.getPath());
        assertNotNull(image);
        try {
            assertEquals("The native alpha ink survives an owned-root alias",0x80ff0000,image.getPixel(128,128));
            assertEquals("Transparent canvas remains transparent",0,image.getPixel(0,0));
        }finally{image.recycle();}
        String outputHash=SketchProjectStore.fileHash(first.file);
        JSONObject verified=SketchStillExport.verify(aliased,first.metadata);
        assertEquals(outputHash,verified.getString("sha256"));
        SketchNativeExport.Prepared replay=SketchNativeExport.prepare(aliased,f.args());
        assertTrue(replay.metadata.getBoolean("replayed"));
        assertEquals(first.project.id,replay.project.id);
        assertEquals(outputHash,SketchProjectStore.fileHash(replay.file));
        assertEquals(sourceBefore,f.document());
        assertEquals(keep.id,projects.active().id);
        assertEquals(activeBefore,projects.get(keep.id).toJson().toString());
    }

    @Test public void untrustedGenerationSymlinkInsideTheOwnedRootStillRejectsWithoutChangingItsTarget()throws Exception {
        Fixture f=new Fixture();
        File canonicalFiles=base.getFilesDir().getCanonicalFile();
        Context canonical=new FilesContext(base,canonicalFiles);
        // Prove the exact public request is valid before the only invalid state is introduced.
        SketchStillExport.admit(canonical,f.args());
        Path target=Files.createTempDirectory(canonicalFiles.toPath(),"untrusted_still_target_");
        Path marker=target.resolve("keep.txt");byte[] sentinel="Keep this unrelated owned content".getBytes(StandardCharsets.UTF_8);
        Files.write(marker,sentinel);
        Path exports=canonicalFiles.toPath().resolve("sketch_studio").resolve(f.id).resolve("exports");
        Files.createDirectories(exports);
        Path generation=Files.createSymbolicLink(exports.resolve(f.operation),target);
        assertTrue(Files.isSymbolicLink(generation));
        assertTrue("Canonical containment alone must not admit an untrusted descendant link",generation.toFile().getCanonicalPath().startsWith(canonicalFiles.getPath()+File.separator));
        ProjectStore projects=new ProjectStore(base);int before=projects.list().size();String sourceBefore=f.document();
        assertThrows(Exception.class,()->SketchStillExport.admit(canonical,f.args()));
        assertThrows(Exception.class,()->SketchNativeExport.prepare(canonical,f.args()));
        assertEquals(before,projects.list().size());
        assertNull(projects.get(SketchStillExport.outputProjectId(f.id,f.operation)));
        assertTrue(Files.isSymbolicLink(generation));
        assertArrayEquals(sentinel,Files.readAllBytes(marker));
        try(var entries=Files.list(target)){assertEquals("Rejected publication writes no target files",1,entries.count());}
        assertEquals(sourceBefore,f.document());
    }

    private static final class FilesContext extends ContextWrapper {
        private final File files;
        FilesContext(Context base,File files){super(base);this.files=files;}
        @Override public File getFilesDir(){return files;}
    }

    private final class Fixture {
        final String id=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();
        final long revision;
        Fixture()throws Exception {
            SketchEditOps.execute(base,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create")
                    .put("width",256).put("height",256).put("frameCount",1).put("background","#00000000"));
            JSONObject mark=new JSONObject().put("id","alpha_ink").put("kind","rect").put("frame",0).put("exposure",1)
                    .put("color","#80FF0000").put("fill",true).put("bounds",new JSONObject().put("x",.25).put("y",.25).put("width",.5).put("height",.5));
            SketchEditOps.execute(base,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","ink")
                    .put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",mark))));
            try(SketchProjectStore sketches=new SketchProjectStore(base)){revision=sketches.get(id).getLong("revision");}
        }
        JSONObject args()throws Exception{return new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId",operation).put("format","png").put("frame",0);}
        String document()throws Exception{try(SketchProjectStore sketches=new SketchProjectStore(base)){return SketchDocument.canonical(sketches.get(id));}}
    }
}
