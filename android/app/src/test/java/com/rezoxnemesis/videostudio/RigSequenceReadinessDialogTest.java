package com.rezoxnemesis.videostudio;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class RigSequenceReadinessDialogTest {
    @Test public void repeatedExplicitSourcesRemainSeparateAndNextMissingOpensExactActorWithoutSaving()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Phone exact order");
        ProjectStore.Asset a=image("source-a"),b=image("source-b");project.assets.add(a);project.assets.add(b);projects.save(project);
        ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).create().start().resume().visible();
        String[] opened={null,null};
        Constructor<RigSequenceReadinessDialog> constructor=RigSequenceReadinessDialog.class.getDeclaredConstructor(Activity.class,ProjectStore.class,String.class,JSONObject.class,RigSequenceReadinessDialog.MissingActor.class);constructor.setAccessible(true);
        JSONObject ordered=new JSONObject().put("assetIds",new JSONArray().put(b.id).put(a.id).put(b.id));JSONObject[] continuedOrder={null};
        RigSequenceReadinessDialog checklist=constructor.newInstance(controller.get(),projects,project.id,ordered,(RigSequenceReadinessDialog.MissingActor)(id,black,selection)->{opened[0]=id;opened[1]=black?"black":"white";continuedOrder[0]=selection;});
        Method open=RigSequenceReadinessDialog.class.getDeclaredMethod("open");open.setAccessible(true);open.invoke(checklist);
        AlertDialog dialog=(AlertDialog)field(checklist,"dialog");
        try{
            ((ExecutorService)field(checklist,"worker")).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();
            JSONObject result=(JSONObject)field(checklist,"result");assertNotNull(result);assertEquals(new JSONArray().put(b.id).put(a.id).put(b.id).toString(),result.getJSONArray("assetIds").toString());
            LinearLayout rows=(LinearLayout)field(checklist,"rows");assertEquals("Every ordered occurrence retains its own source/actor controls",6,rows.getChildCount());assertTrue(((TextView)rows.getChildAt(4)).getText().toString().startsWith("3 ·"));assertTrue(((TextView)rows.getChildAt(4)).getText().toString().contains(b.id));
            Button next=findButton(dialog.getWindow().getDecorView(),"Next missing actor");assertNotNull(next);next.performClick();assertEquals(b.id,opened[0]);assertEquals("black",opened[1]);
            assertEquals("Next actor navigation retains the exact selected repeated order",ordered.toString(),continuedOrder[0].toString());
            assertTrue("Readiness navigation cannot promote an incomplete profile",new Rig2DProjectStore(context,projects).list(project.id).isEmpty());
            assertEquals("Readiness performs no project write",project.updatedAt,projects.get(project.id).updatedAt);
        }finally{dialog.dismiss();controller.pause().stop().destroy();}
        assertTrue(((ExecutorService)field(checklist,"worker")).isShutdown());
    }
    private static ProjectStore.Asset image(String name){ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=UUID.randomUUID().toString();asset.name=name;asset.mime="image/png";asset.uri="file:///private/"+name+".png";return asset;}
    private static Object field(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static Button findButton(View view,String title){if(view instanceof Button&&title.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=findButton(group.getChildAt(i),title);if(found!=null)return found;}}return null;}
}
