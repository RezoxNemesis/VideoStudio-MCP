package com.rezoxnemesis.videostudio;
import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import org.json.*;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowContentResolver;
import java.io.*;
import java.util.UUID;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AssetProbeMimeFallbackTest {
    @Before public void androidBuiltinMimeRegistry(){
        // Robolectric starts its MIME registry empty; Android includes these mappings.
        org.robolectric.shadows.ShadowMimeTypeMap map=Shadows.shadowOf(android.webkit.MimeTypeMap.getSingleton());
        map.addExtensionMimeTypeMapping("jpg","image/jpeg");map.addExtensionMimeTypeMapping("jpeg","image/jpeg");map.addExtensionMimeTypeMapping("png","image/png");
    }
    @Test public void aJpegWithoutProviderMimeImportsAsAnOwnedSketchReference() throws Exception {
        Context c=RuntimeEnvironment.getApplication();File file=new File(c.getCacheDir(),"portrait.JPEG");Bitmap bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888);new Canvas(bitmap).drawColor(Color.MAGENTA);try(FileOutputStream out=new FileOutputStream(file)){assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG,90,out));}bitmap.recycle();
        ProjectStore projects=new ProjectStore(c);ProjectStore.Project project=projects.create("Owned reference");ProjectStore.Asset asset=projects.importUri(project,Uri.fromFile(file));assertEquals("image/jpeg",asset.mime);assertEquals("portrait.JPEG",asset.name);assertEquals(1,project.clips.size());
        String id=UUID.randomUUID().toString();SketchEditOps.execute(c,"sketch_create",new JSONObject().put("sketchId",id).put("operationId","new"));
        JSONObject result=SketchEditOps.execute(c,"sketch_import_reference",new JSONObject().put("sketchId",id).put("operationId","reference").put("expectedRevision",1).put("projectId",project.id).put("sourceProjectUpdatedAt",project.updatedAt).put("assetId",asset.id).put("layerId","layer_1").put("objectId","reference"));assertTrue(result.getBoolean("ok"));
        try(SketchProjectStore store=new SketchProjectStore(c)){JSONObject reference=SketchDocument.object(store.get(id),"reference").getJSONObject("reference");assertEquals(256,reference.getInt("width"));assertTrue(store.referenceFile(id,reference).isFile());}
    }
    @Test public void genericMimeUsesProviderDisplayNameWhileAuthoritativeMimeWins() throws Exception {
        Context c=RuntimeEnvironment.getApplication();Uri uri=Uri.parse("content://reference-metadata/documents/opaque-123");
        MetadataProvider provider=new MetadataProvider();ShadowContentResolver.registerProviderInternal("reference-metadata",provider);
        provider.mime="application/octet-stream";assertEquals("image/jpeg",AssetProbe.probe(c.getContentResolver(),uri).mime);
        provider.mime="image/png";assertEquals("image/png",AssetProbe.probe(c.getContentResolver(),uri).mime);
        provider.mime="application/octet-stream";provider.name="unknown-file.no-such-extension";assertEquals("application/octet-stream",AssetProbe.probe(c.getContentResolver(),uri).mime);
    }
    public static class MetadataProvider extends ContentProvider {
        String mime="application/octet-stream",name="Reference Face.JPG";
        @Override public boolean onCreate(){return true;}
        @Override public String getType(Uri uri){return mime;}
        @Override public Cursor query(Uri uri,String[] projection,String selection,String[] args,String order){MatrixCursor c=new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE});c.addRow(new Object[]{name,123L});return c;}
        @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException();}
        @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException();}
        @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException();}
        @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException{throw new FileNotFoundException("Metadata-only provider");}
    }
}
