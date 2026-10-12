package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.reflect.InvocationTargetException;
import java.util.UUID;

/** Owned synthetic originals for workbench tests; never authentic Test 1 anatomy. */
final class NativeSourceWorkbenchFixture implements AutoCloseable {
    final Context context;
    final ProjectStore projects;
    final ProjectStore.Project project;
    final SourceArtworkRasterFixture art;
    final Rig2DProjectStore rigs;
    final Rig2DProfile rig;
    final String sourceSha,rigSha;

    NativeSourceWorkbenchFixture(Context context)throws Exception {
        this.context=context;projects=new ProjectStore(context);
        project=projects.create("Synthetic original workbench");
        art=new SourceArtworkRasterFixture(context,false);
        project.assets.add(art.from);projects.save(project);
        sourceSha=StoryboardSourceManifest.captureSource(context,art.from).getString("sourceSha256");
        Rig2DProfile source=art.blackFrom;
        rig=new Rig2DProfile(UUID.randomUUID().toString(),project.id,art.from.id,source.black,false,
                source.joints,source.outline,source.swordTip,source.swordHilt,source.swordOutline,sourceSha);
        rigs=new Rig2DProjectStore(context,projects);
        rigSha=rigs.saveWithBinding(rig,project.updatedAt,"").getString("sha256");
    }
    JSONObject inspection()throws Exception {
        return new JSONObject().put("projectId",project.id).put("assetId",art.from.id)
                .put("expectedSourceSha256",sourceSha).put("maxDimension",448);
    }
    JSONObject preview()throws Exception {
        return new JSONObject().put("projectId",project.id).put("rigId",rig.id)
                .put("expectedRigSha256",rigSha).put("expectedSourceSha256",sourceSha).put("maxDimension",448);
    }
    static JSONObject invoke(String helper,String method,Context context,ProjectStore projects,JSONObject request)throws Exception {
        Class<?> type;
        try {type=Class.forName("com.rezoxnemesis.videostudio."+helper);}
        catch(ClassNotFoundException missing) {throw new AssertionError("Missing native original workbench helper: "+helper,missing);}
        try {return (JSONObject)type.getMethod(method,Context.class,ProjectStore.class,JSONObject.class)
                .invoke(null,context,projects,request);}
        catch(InvocationTargetException failure) {
            Throwable cause=failure.getCause();
            if(cause instanceof Exception)throw(Exception)cause;
            if(cause instanceof Error)throw(Error)cause;
            throw failure;
        }
    }
    static JSONObject inspect(NativeSourceWorkbenchFixture f,JSONObject request)throws Exception {
        return invoke("NativeSourceArtworkInspector","inspect",f.context,f.projects,request);
    }
    static JSONObject render(NativeSourceWorkbenchFixture f,JSONObject request)throws Exception {
        return invoke("NativeSourceRigPreview","render",f.context,f.projects,request);
    }
    static Bitmap image(JSONObject response,String key)throws Exception {
        byte[] bytes=Base64.decode(response.getJSONObject(key).getString("base64"),Base64.DEFAULT);
        Bitmap decoded=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
        org.junit.Assert.assertNotNull("Image result must contain a decodable native raster",decoded);
        return decoded;
    }
    static JSONArray pairs(float[][] points)throws Exception {
        JSONArray array=new JSONArray();for(float[] point:points)array.put(new JSONArray().put(point[0]).put(point[1]));return array;
    }
    static void evidence(String name,Bitmap image)throws Exception {
        java.io.File directory=new java.io.File("build/source-rig-workbench");
        if(!directory.isDirectory()&&!directory.mkdirs())throw new IllegalStateException("Cannot create native workbench raster evidence");
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(directory,name+".png"))) {
            org.junit.Assert.assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,output));
        }
    }
    @Override public void close(){art.close();projects.delete(project.id);}
}
