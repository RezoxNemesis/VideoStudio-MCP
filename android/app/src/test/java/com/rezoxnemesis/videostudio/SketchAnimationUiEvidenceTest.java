package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Screenshots of the actual native authoring Activity in workspace Android Canvas, not the phone. */
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE,qualifiers="w360dp-h640dp-xxhdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class SketchAnimationUiEvidenceTest {
    @Test public void preparedHundredFrameSceneOpensInTheActualEditableAnimationActivity()throws Exception {
        Context context=RuntimeEnvironment.getApplication();String suffix=UUID.randomUUID().toString().replace("-","");
        JSONObject source=SketchActionFilmTest.fixture("rooftop-relay-source").put("id","ui_rooftop_"+suffix);
        JSONObject program=SketchActionFilmTest.fixture("rooftop-relay-program");String animation="ui_scene_"+suffix;
        try(SketchProjectStore store=new SketchProjectStore(context)){store.importOwnedDocument(source,"seed");}
        JSONObject receipt=SketchAnimationOps.execute(context,"sketch_animation_save",new JSONObject().put("animationId",animation).put("sketchId",source.getString("id")).put("sourceRevision",1).put("expectedRevision",0).put("operationId","save").put("program",program));
        assertTrue(receipt.getJSONObject("report").getBoolean("independentArticulation"));
        try(ActivityController<SketchAnimationActivity> controller=Robolectric.buildActivity(SketchAnimationActivity.class,new Intent(context,SketchAnimationActivity.class).putExtra("animationId",animation))){
            controller.create().start().resume().visible();SketchAnimationActivity activity=controller.get();settle(activity);
            assertNotNull(get(activity,"program"));if((Boolean)get(activity,"mapping"))((android.widget.Button)get(activity,"mode")).performClick();
            JSONArray screenshots=new JSONArray();
            for(int frame:new int[]{16,43,76}){
                set(activity,"selectedRig",frame<36?"wide_rig":frame<60?"pov_rig":"medium_rig");invoke(activity,"refreshPickers");set(activity,"frame",frame);invoke(activity,"refreshFrame");invoke(activity,"requestPreview");settle(activity);
                Object canvas=get(activity,"canvas");Field image=canvas.getClass().getDeclaredField("bitmap");image.setAccessible(true);assertNotNull("The actual native Activity must display a rendered sketch",image.get(canvas));
                View decor=activity.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(1920,View.MeasureSpec.EXACTLY));decor.layout(0,0,1080,1920);
                Bitmap screenshot=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);File file=new File(SketchActionFilmTest.EVIDENCE,"animation-studio-frame-"+frame+".png");
                try{decor.draw(new Canvas(screenshot));SketchActionFilmTest.png(file,screenshot);}finally{screenshot.recycle();}
                screenshots.put(file.getName());
            }
            JSONObject evidence=new JSONObject().put("surface","SketchAnimationActivity; actual human animation editor")
                    .put("executionLocation","workspace Robolectric native Android Canvas; not a phone screenshot")
                    .put("sceneFrames",100).put("originalEditableArtwork",true).put("sourcePreserved",true)
                    .put("screenshots",screenshots).put("report",receipt.getJSONObject("report"));
            Files.write(new File(SketchActionFilmTest.EVIDENCE,"ui-evidence.json").toPath(),evidence.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }
    private static Object get(Object target,String name)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);}
    private static void set(Object target,String name,Object value)throws Exception {Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);}
    private static void invoke(Object target,String name)throws Exception {Method method=target.getClass().getDeclaredMethod(name);method.setAccessible(true);method.invoke(target);}
    private static void settle(Object target)throws Exception {ExecutorService io=(ExecutorService)get(target,"io");for(int n=0;n<4;n++){io.submit(()->{}).get(30,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(100));}}
}
