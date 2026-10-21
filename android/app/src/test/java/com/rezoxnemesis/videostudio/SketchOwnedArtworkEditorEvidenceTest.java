package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.*;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import java.io.*;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.Assert.*;

/** Captures the real native editor; private artwork remains outside the repository. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE,qualifiers="w720dp-h1440dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public final class SketchOwnedArtworkEditorEvidenceTest {
    @Test public void restoredOwnedArtAppearsInTheRealEditorAndFrameNavigationKeepsIt()throws Exception{
        String path=System.getenv("VIDEOSTUDIO_OWNED_EDITOR_MANIFEST");
        Assume.assumeTrue("Explicit private editor evidence manifest",path!=null);
        JSONObject manifest=new JSONObject(new String(Files.readAllBytes(new File(path).toPath()),StandardCharsets.UTF_8));
        File directory=new File(manifest.getString("output"));assertTrue(directory.mkdirs()||directory.isDirectory());
        Context context=RuntimeEnvironment.getApplication();File archive=new File(manifest.getString("sourceArchive"));
        ExecutorService importer=Executors.newSingleThreadExecutor();String id;
        try{id=importer.submit(()->SketchPaintArchive.restoreFile(context,archive,SketchProjectStore.fileHash(archive),
                "editor_"+UUID.randomUUID().toString().replace("-",""),"Owned native editor review").getString("sketchId")).get(60,TimeUnit.SECONDS);}
        finally{importer.shutdownNow();}
        JSONObject before=load(context,id);String beforeHash=SketchDocument.sha256(SketchDocument.canonical(before).getBytes(StandardCharsets.UTF_8));
        JSONArray captures=new JSONArray();
        try(ActivityController<SketchStudioActivity> controller=Robolectric.buildActivity(SketchStudioActivity.class,
                new Intent(context,SketchStudioActivity.class).putExtra("sketchId",id)).create().start().resume().visible()){
            SketchStudioActivity studio=controller.get();waitIo(studio);
            SketchCanvasView canvas=(SketchCanvasView)field(studio,"canvas");View root=studio.getWindow().getDecorView();
            JSONArray frames=manifest.getJSONArray("frames");
            for(int i=0;i<frames.length();i++){
                int frame=frames.getInt(i);assertTrue(frame>=0&&frame<before.getInt("frameCount"));
                if(canvas.getFrame()!=frame){
                    Button go=button(root,"Go");assertNotNull("Ordinary frame navigation is visible",go);go.performClick();
                    AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();assertNotNull(dialog);
                    EditText number=(EditText)description(dialog.getWindow().getDecorView(),"Frame or page number");assertNotNull(number);
                    number.setText(String.valueOf(frame+1));dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();for(int drain=0;drain<6&&canvas.getFrame()!=frame;drain++)waitIo(studio);
                }
                assertEquals(frame,canvas.getFrame());
                try{
                    for(int attempt=0;attempt<8;attempt++){
                        Bitmap interim=capture(root);interim.recycle();
                        ((ExecutorService)field(canvas,"rasterWorker")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();
                        assertFalse("Native editor preview failed: "+field(canvas,"previewError"),canvas.previewFailed());
                        if(((Integer)field(canvas,"lastRasterFrame"))==frame)break;
                    }
                    assertEquals("Actual native viewport raster must be ready",frame,field(canvas,"lastRasterFrame"));
                    Bitmap screenshot=capture(root);
                    try{
                        JSONObject sample=new JSONObject();
                        if(manifest.has("inkProbe")){
                            int ink=probe(screenshot,canvas,before,manifest.getJSONArray("inkProbe"));
                            int paper=probe(screenshot,canvas,before,manifest.getJSONArray("backgroundProbe"));
                            assertTrue("Owned ink must appear over the native canvas",luminance(paper)-luminance(ink)>100);
                            sample.put("inkPixel",Integer.toHexString(ink)).put("backgroundPixel",Integer.toHexString(paper));
                        }
                        File png=new File(directory,String.format(java.util.Locale.ROOT,"editor-frame-%04d.png",frame));
                        save(screenshot,png);captures.put(new JSONObject().put("frameIndex",frame).put("file",png.getName())
                                .put("sha256",SketchProjectStore.fileHash(png)).put("width",720).put("height",1440)
                                .put("pixelSamples",sample));
                    }finally{screenshot.recycle();}
                }catch(Throwable failure){Bitmap shot=capture(root);try{save(shot,new File(directory,"editor-failure.png"));}finally{shot.recycle();}throw failure;}
            }
        }
        JSONObject after=load(context,id);String afterHash=SketchDocument.sha256(SketchDocument.canonical(after).getBytes(StandardCharsets.UTF_8));
        assertEquals("Opening and navigating must not edit source artwork",beforeHash,afterHash);
        JSONObject report=new JSONObject().put("technicalStatus","PASS").put("visualStatus","UNREVIEWED")
                .put("appVersion",AppProtocol.APP_VERSION).put("renderer","Actual SketchStudioActivity and SketchCanvasView / Android native Canvas under Robolectric")
                .put("phoneExecution",false).put("installedApkExecution",false).put("androidCodecExecution",false)
                .put("sourceProof",manifest.getJSONObject("sourceProof")).put("sourceArchiveSha256",SketchProjectStore.fileHash(archive))
                .put("sourceDocumentSha256Before",beforeHash).put("sourceDocumentSha256After",afterHash)
                .put("sourceModified",false).put("captures",captures);
        Files.write(new File(directory,"editor-evidence.json").toPath(),report.toString(2).getBytes(StandardCharsets.UTF_8));
    }
    private static JSONObject load(Context context,String id)throws Exception{try(SketchProjectStore store=new SketchProjectStore(context)){return store.get(id);}}
    private static void waitIo(SketchStudioActivity studio)throws Exception{((ExecutorService)field(studio,"io")).submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    private static Object field(Object target,String name)throws Exception{Field f=target.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(target);}
    private static Bitmap capture(View root){root.measure(View.MeasureSpec.makeMeasureSpec(720,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1440,View.MeasureSpec.EXACTLY));root.layout(0,0,720,1440);Bitmap bitmap=Bitmap.createBitmap(720,1440,Bitmap.Config.ARGB_8888);root.draw(new Canvas(bitmap));return bitmap;}
    private static void save(Bitmap image,File file)throws Exception{try(FileOutputStream out=new FileOutputStream(file)){assertTrue(image.compress(Bitmap.CompressFormat.PNG,100,out));}}
    private static int probe(Bitmap screenshot,SketchCanvasView canvas,JSONObject document,JSONArray point)throws Exception{
        int[] location=new int[2];canvas.getLocationInWindow(location);float scale=(Float)field(canvas,"viewScale");
        int x=Math.round(location[0]+(Float)field(canvas,"left")+scale*(float)point.getDouble(0)*document.getInt("width"));
        int y=Math.round(location[1]+(Float)field(canvas,"top")+scale*(float)point.getDouble(1)*document.getInt("height"));
        assertTrue("Probe must lie inside the captured native window",x>=0&&y>=0&&x<screenshot.getWidth()&&y<screenshot.getHeight());return screenshot.getPixel(x,y);
    }
    private static int luminance(int color){return (Color.red(color)+Color.green(color)+Color.blue(color))/3;}
    private static Button button(View view,String label){if(view instanceof Button&&label.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=button(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
    private static View description(View view,String label){if(label.equals(view.getContentDescription()))return view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=description(group.getChildAt(i),label);if(found!=null)return found;}}return null;}
}
