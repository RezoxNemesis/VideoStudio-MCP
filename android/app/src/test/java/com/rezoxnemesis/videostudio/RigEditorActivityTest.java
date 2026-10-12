package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Synthetic phone fixtures; these do not certify the private Test 1 artwork. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class RigEditorActivityTest {
    @Test public void reopeningSavedActorUpdatesItsJointWithoutCreatingAnotherProfile()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ProjectStore projects=new ProjectStore(context);
        ProjectStore.Project project=projects.create("Synthetic owner annotation edit");
        ProjectStore.Asset image=new ProjectStore.Asset();image.id=UUID.randomUUID().toString();image.name="synthetic-annotation.png";image.mime="image/png";
        File source=new File(context.getFilesDir(),"annotation-"+UUID.randomUUID()+".png");Bitmap original=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);original.eraseColor(Color.rgb(25,35,48));
        try(FileOutputStream stream=new FileOutputStream(source)){assertTrue(original.compress(Bitmap.CompressFormat.PNG,100,stream));}finally{original.recycle();}
        image.uri=Uri.fromFile(source).toString();project.assets.add(image);projects.save(project);
        Rig2DProjectStore rigs=new Rig2DProjectStore(context,projects);
        Rig2DProfile saved=Rig2DProfile.create(project.id,image.id,true,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline(),new float[]{.82f,.35f});rigs.save(saved);
        Intent intent=new Intent(context,RigEditorActivity.class).putExtra("projectId",project.id).putExtra("assetId",image.id).putExtra("rigId",saved.id);
        ActivityController<RigEditorActivity> controller=Robolectric.buildActivity(RigEditorActivity.class,intent).create().start().resume().visible();
        try{
            RigEditorActivity activity=controller.get();awaitOwnerWorker(activity);Field canvasField=RigEditorActivity.class.getDeclaredField("drawing");canvasField.setAccessible(true);View drawing=(View)canvasField.get(activity);
            drawing.layout(0,0,1000,1000);Bitmap screen=Bitmap.createBitmap(1000,1000,Bitmap.Config.ARGB_8888);try{drawing.draw(new Canvas(screen));}finally{screen.recycle();}
            touch(drawing,MotionEvent.ACTION_DOWN,420,570,10);touch(drawing,MotionEvent.ACTION_MOVE,490,580,20);touch(drawing,MotionEvent.ACTION_UP,500,590,30);
            Button save=findButton(activity.getWindow().getDecorView(),"Save Actor Rig");assertNotNull("Existing actor-save owner action must stay reachable",save);save.performClick();awaitOwnerWorker(activity);
            Rig2DProfile edited=rigs.load(project.id,saved.id);
            assertEquals("Dragging a saved joint must update that exact existing source profile",.50f,edited.joints[Rig2DProfile.PELVIS][0],.001f);
            assertEquals(.59f,edited.joints[Rig2DProfile.PELVIS][1],.001f);
            assertEquals("Updating an annotation must not create an ambiguous second actor",1,rigs.list(project.id).size());
            assertEquals(image.id,edited.sourceAssetId);assertArrayEquals(saved.swordTip,edited.swordTip,.00001f);
        }finally{controller.pause().stop().destroy();}
    }
    private static void awaitOwnerWorker(RigEditorActivity activity)throws Exception{Field field=RigEditorActivity.class.getDeclaredField("ownerWorker");field.setAccessible(true);((ExecutorService)field.get(activity)).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    private static void touch(View view,int action,float x,float y,long at){MotionEvent event=MotionEvent.obtain(1,at,action,x,y,0);try{view.onTouchEvent(event);}finally{event.recycle();}}
    private static Button findButton(View view,String title){if(view instanceof Button&&title.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int index=0;index<group.getChildCount();index++){Button found=findButton(group.getChildAt(index),title);if(found!=null)return found;}}return null;}
}
