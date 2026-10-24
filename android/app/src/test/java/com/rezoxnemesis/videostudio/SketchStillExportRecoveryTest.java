package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.util.UUID;
import static org.junit.Assert.*;

/** Actual SQLite rollback, missing filesystem receipt and frozen source dependency recovery. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class SketchStillExportRecoveryTest {
    private final Context context=RuntimeEnvironment.getApplication();

    @Test public void failedCommitWitnessRollsBackProjectButKeepsPublishedPngForFrozenRetry()throws Exception {
        Fixture f=new Fixture(false);ProjectStore projects=new ProjectStore(context);SQLiteDatabase db=database(projects);
        String trigger="fail_still_"+UUID.randomUUID().toString().replace("-","");
        db.execSQL("CREATE TRIGGER "+trigger+" BEFORE INSERT ON meta WHEN NEW.key LIKE 'sketch_still_commit/%' BEGIN SELECT RAISE(ABORT,'Injected still commit failure'); END");
        try{assertThrows(Exception.class,f::prepare);}finally{db.execSQL("DROP TRIGGER "+trigger);}
        assertNull("The project insert must roll back with its durable witness",projects.get(f.outputId()));
        assertEquals(0,witnesses(db));File png=f.png();assertTrue("Published content is not deleted after writer failure",png.isFile());
        String retained=SketchProjectStore.fileHash(png);f.recolor();String human=f.document().toString();
        SketchNativeExport.Prepared retry=f.prepare();assertEquals(retained,SketchProjectStore.fileHash(retry.file));
        assertEquals(1,retry.project.assets.size());assertEquals(1,witnesses(db));assertEquals(human,f.document().toString());
        Bitmap image=BitmapFactory.decodeFile(png.getPath());try{assertEquals(0xff112233,image.getPixel(80,80));}finally{image.recycle();}
    }

    @Test public void missingCompletionFileUsesAtomicWitnessAndNeverResurrectsRemovedHumanContent()throws Exception {
        for(boolean removeProject:new boolean[]{false,true}){
            Fixture f=new Fixture(false);ProjectStore projects=new ProjectStore(context);int before=witnesses(database(projects));SketchNativeExport.Prepared first=f.prepare();
            Files.delete(new File(f.generation(),"completed.json").toPath());
            ProjectStore.Project human=projects.get(first.project.id);human.name="Reviewed by me";projects.save(human);
            SketchNativeExport.Prepared replay=f.prepare();assertTrue(replay.metadata.getBoolean("replayed"));assertEquals(human.updatedAt,replay.project.updatedAt);assertEquals(human.name,replay.project.name);
            Files.delete(new File(f.generation(),"completed.json").toPath());
            if(removeProject)projects.delete(first.project.id);else{human=projects.get(first.project.id);human.assets.clear();projects.save(human);}
            assertThrows("A DB commit survives loss of its filesystem receipt",Exception.class,f::prepare);
            if(removeProject)assertNull(projects.get(first.project.id));else assertTrue(projects.get(first.project.id).assets.isEmpty());
            assertTrue(first.file.isFile());assertEquals(before+1,witnesses(database(projects)));
        }
    }

    @Test public void deterministicOutputIdentityCannotReplaceAnExistingUnrelatedProject()throws Exception {
        Fixture f=new Fixture(false);ProjectStore projects=new ProjectStore(context);ProjectStore.Project human=projects.create("My existing work");
        human.id=f.outputId();human.name="Do not replace this project";projects.save(human);String before=projects.get(human.id).toJson().toString();
        assertThrows(Exception.class,f::prepare);assertEquals(before,projects.get(human.id).toJson().toString());assertEquals(0,witnesses(database(projects)));
    }

    @Test public void frozenReferenceSurvivesOriginalRemovalButCorruptionIsNeverSilentlyRepaired()throws Exception {
        Fixture f=new Fixture(true);JSONObject reference=SketchDocument.object(f.document(),"reference").getJSONObject("reference");
        assertThrows(InterruptedException.class,()->SketchNativeExport.prepare(context,f.args(),(done,total)->{if(f.png().isFile())throw new InterruptedException("Before DB commit");}));
        File original;try(SketchProjectStore sketches=new SketchProjectStore(context)){original=sketches.referenceFile(f.id,reference);}
        Files.delete(original.toPath());SketchNativeExport.Prepared output=f.prepare();
        Bitmap image=BitmapFactory.decodeFile(output.file.getPath());try{assertEquals(0xff445566,image.getPixel(128,128));}finally{image.recycle();}
        File frozen=new File(f.generation(),"assets/references/"+reference.getString("file"));assertTrue(frozen.isFile());
        Files.write(frozen.toPath(),new byte[]{7,8,9});String outputHash=SketchProjectStore.fileHash(output.file);
        assertThrows(Exception.class,f::prepare);assertArrayEquals(new byte[]{7,8,9},Files.readAllBytes(frozen.toPath()));assertEquals(outputHash,SketchProjectStore.fileHash(output.file));
    }

    private final class Fixture {
        final String id=UUID.randomUUID().toString(),operation=UUID.randomUUID().toString();long revision;
        Fixture(boolean reference)throws Exception {
            SketchEditOps.execute(context,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","create").put("width",256).put("height",256).put("frameCount",1).put("background","#00000000"));
            if(reference){
                File source=new File(context.getFilesDir(),UUID.randomUUID()+".png");Bitmap b=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);b.eraseColor(0xff445566);
                try(FileOutputStream out=new FileOutputStream(source)){assertTrue(b.compress(Bitmap.CompressFormat.PNG,100,out));}finally{b.recycle();}
                ProjectStore projects=new ProjectStore(context);ProjectStore.Project p=projects.create("Owned reference source");ProjectStore.Asset a=new ProjectStore.Asset();a.id="source";a.mime="image/png";a.uri=Uri.fromFile(source).toString();a.name="source.png";p.assets.add(a);projects.save(p);
                SketchEditOps.execute(context,"sketch_import_reference",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","reference").put("projectId",p.id).put("sourceProjectUpdatedAt",p.updatedAt).put("assetId",a.id).put("layerId","layer_1").put("objectId","reference").put("guideVisible",true)
                        .put("bounds",new JSONObject().put("x",0).put("y",0).put("width",1).put("height",1)));
            }else SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",1).put("operationId","ink").put("operations",new JSONArray().put(new JSONObject().put("type","add_object").put("layerId","layer_1").put("object",new JSONObject().put("id","ink").put("kind","rect").put("frame",0).put("exposure",1).put("fill",true).put("color","#FF112233").put("bounds",new JSONObject().put("x",.1).put("y",.1).put("width",.5).put("height",.5))))));
            revision=document().getLong("revision");
        }
        JSONObject args()throws Exception{return new JSONObject().put("sketchId",id).put("expectedRevision",revision).put("operationId",operation).put("format","png").put("frame",0);}
        JSONObject document()throws Exception{try(SketchProjectStore sketches=new SketchProjectStore(context)){return sketches.get(id);}}
        SketchNativeExport.Prepared prepare()throws Exception{return SketchNativeExport.prepare(context,args());}
        File generation(){return new File(context.getFilesDir(),"sketch_studio/"+id+"/exports/"+operation);}
        File png(){return new File(generation(),"frame_0000.png");}
        String outputId(){return UUID.nameUUIDFromBytes(("sketch-output/"+id+"/"+operation).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();}
        void recolor()throws Exception{SketchEditOps.execute(context,"sketch_edit",new JSONObject().put("sketchId",id).put("expectedRevision",document().getLong("revision")).put("operationId","human_recolor").put("operations",new JSONArray().put(new JSONObject().put("type","update_object").put("objectId","ink").put("patch",new JSONObject().put("color","#FFAA0011")))));}
    }
    private static SQLiteDatabase database(ProjectStore store)throws Exception{Field f=ProjectStore.class.getDeclaredField("db");f.setAccessible(true);return (SQLiteDatabase)f.get(store);}
    private static int witnesses(SQLiteDatabase db){try(Cursor c=db.rawQuery("SELECT count(*) FROM meta WHERE key LIKE 'sketch_still_commit/%'",null)){assertTrue(c.moveToFirst());return c.getInt(0);}}
}
