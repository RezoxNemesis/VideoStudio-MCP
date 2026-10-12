package com.rezoxnemesis.videostudio;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.net.Uri;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
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
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

/** Native synthetic source diagnostics verify isolation, rollback and bitmap ownership only. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RigPoseDiagnosticActivityTest {
    @Test public void containedStageMapsHandlesBackToOriginalUvAndRejectsPaddingTouches()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Phone coprime original mapping");
        File source=new File(context.getFilesDir(),"phone-contained-"+UUID.randomUUID()+".png");writeSource(source,Color.rgb(70,90,110),1000,701);
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=UUID.randomUUID().toString();asset.name="coprime-original.png";asset.mime="image/png";asset.uri=Uri.fromFile(source).toString();project.assets.add(asset);projects.save(project);
        Rig2DProjectStore rigs=new Rig2DProjectStore(context,projects);Rig2DProfile rig=new Rig2DProfile(UUID.randomUUID().toString(),project.id,asset.id,true,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline(),new float[]{.82f,.35f},new float[]{.64f,.54f},new float[][]{{.63f,.53f},{.81f,.34f},{.83f,.36f},{.65f,.55f}},StoryboardSourceManifest.captureSource(context,asset).getString("sourceSha256"));rigs.save(rig);String rigSha=rigs.captureBinding(project.id,rig.id).getString("sha256");
        Intent intent=new Intent(context,RigPoseDiagnosticActivity.class).putExtra("projectId",project.id).putExtra("rigId",rig.id).putExtra("expectedRigSha256",rigSha);ActivityController<RigPoseDiagnosticActivity> controller=Robolectric.buildActivity(RigPoseDiagnosticActivity.class,intent).create().start().resume().visible();
        try{
            RigPoseDiagnosticActivity activity=controller.get();await(activity);assertNotNull(field(activity,"picture"));View drawing=(View)field(activity,"drawing");drawing.layout(0,0,1000,1000);
            Object mapping=field(activity,"mapping");float offsetX=(Float)field(mapping,"offsetX"),offsetY=(Float)field(mapping,"offsetY");assertTrue("Coprime original aspect must retain its explicit containment mapping",offsetX>0||offsetY>0);
            Method x=drawing.getClass().getDeclaredMethod("x",float.class),y=drawing.getClass().getDeclaredMethod("y",float.class),geometry=drawing.getClass().getDeclaredMethod("geometry");x.setAccessible(true);y.setAccessible(true);geometry.setAccessible(true);geometry.invoke(drawing);RectF stage=(RectF)field(drawing,"stage");
            float paddingX=offsetX>0?stage.left+stage.width()*offsetX/2:stage.centerX(),paddingY=offsetY>0?stage.top+stage.height()*offsetY/2:stage.centerY();MotionEvent padding=MotionEvent.obtain(1,10,MotionEvent.ACTION_DOWN,paddingX,paddingY,0);try{assertFalse("Native diagnostic padding is not an original source coordinate",drawing.onTouchEvent(padding));}finally{padding.recycle();}
            touch(drawing,MotionEvent.ACTION_DOWN,(Float)x.invoke(drawing,.42f),(Float)y.invoke(drawing,.57f),20);touch(drawing,MotionEvent.ACTION_UP,(Float)x.invoke(drawing,.44f),(Float)y.invoke(drawing,.59f),30);await(activity);
            RigPoseDiagnosticState pose=(RigPoseDiagnosticState)field(activity,"pose");assertArrayEquals(new float[]{.44f,.59f},pose.targetJoints()[0],.00002f);assertEquals(rigSha,rigs.captureBinding(project.id,rig.id).getString("sha256"));
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void draggingAndScrubbingTestPoseNeverWriteSourceMarksAndRejectedSourceRetainsImage()throws Exception{
        Context context=RuntimeEnvironment.getApplication();ProjectStore projects=new ProjectStore(context);ProjectStore.Project project=projects.create("Phone masked-source diagnostic");
        File source=new File(context.getFilesDir(),"phone-pose-"+UUID.randomUUID()+".png");writeSource(source,Color.rgb(70,90,110));
        ProjectStore.Asset asset=new ProjectStore.Asset();asset.id=UUID.randomUUID().toString();asset.name="pose-original.png";asset.mime="image/png";asset.uri=Uri.fromFile(source).toString();project.assets.add(asset);projects.save(project);
        Rig2DProjectStore rigs=new Rig2DProjectStore(context,projects);String sourceSha=StoryboardSourceManifest.captureSource(context,asset).getString("sourceSha256");
        Rig2DProfile rig=new Rig2DProfile(UUID.randomUUID().toString(),project.id,asset.id,true,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline(),new float[]{.82f,.35f},new float[]{.64f,.54f},new float[][]{{.63f,.53f},{.81f,.34f},{.83f,.36f},{.65f,.55f}},sourceSha);
        rigs.save(rig);String rigSha=rigs.captureBinding(project.id,rig.id).getString("sha256");
        Intent intent=new Intent(context,RigPoseDiagnosticActivity.class).putExtra("projectId",project.id).putExtra("rigId",rig.id).putExtra("expectedRigSha256",rigSha);
        ActivityController<RigPoseDiagnosticActivity> controller=Robolectric.buildActivity(RigPoseDiagnosticActivity.class,intent).create().start().resume().visible();
        RigPoseDiagnosticActivity activity=controller.get();Bitmap lastImage=null;
        try{
            await(activity);View drawing=(View)field(activity,"drawing");drawing.layout(0,0,1000,1000);
            lastImage=(Bitmap)field(activity,"picture");assertNotNull("Native masked-source diagnostic must produce an actual image",lastImage);
            Bitmap sourceRestImage=lastImage.copy(Bitmap.Config.ARGB_8888,false);
            dragOriginal(drawing,.42f,.57f,.45f,.59f,10);await(activity);
            try{assertFalse("Dragging a body handle must deform the actual native source mesh, beyond changing phone overlays",sourceRestImage.sameAs((Bitmap)field(activity,"picture")));}finally{sourceRestImage.recycle();}
            RigPoseDiagnosticState pose=(RigPoseDiagnosticState)field(activity,"pose");assertEquals(.45f,pose.targetJoints()[0][0],.00001f);
            float[] beforeCancel=pose.targetJoints()[0];float[] handle=canvasPoint(drawing,.45f,.59f),cancelled=canvasPoint(drawing,.48f,.61f);assertTrue("Cancel regression must actually start on the visible handle",touch(drawing,MotionEvent.ACTION_DOWN,handle[0],handle[1],31));touch(drawing,MotionEvent.ACTION_MOVE,cancelled[0],cancelled[1],32);touch(drawing,MotionEvent.ACTION_CANCEL,cancelled[0],cancelled[1],33);assertArrayEquals("Cancelled gestures must restore the separate test pose",beforeCancel,pose.targetJoints()[0],.00001f);
            SeekBar scrub=(SeekBar)field(activity,"scrub");scrub.setProgress(0);assertArrayEquals(rig.joints[0],pose.targetJoints()[0],.00001f);scrub.setProgress(100);
            assertEquals("Diagnostic must never change saved profile proof",rigSha,rigs.captureBinding(project.id,rig.id).getString("sha256"));assertArrayEquals(rig.joints[0],rigs.load(project.id,rig.id).joints[0],.00001f);
            lastImage=(Bitmap)field(activity,"picture");float[] accepted=pose.targetJoints()[0];
            writeSource(source,Color.rgb(130,70,40));
            assertNotEquals("The rejection fixture must actually change the original encoded bytes",sourceSha,StoryboardSourceManifest.captureSource(context,asset).getString("sourceSha256"));
            long beforeRequest=(Long)field(activity,"generation");dragOriginal(drawing,.45f,.59f,.48f,.60f,40);
            assertArrayEquals("Changed-source regression must request a different test pose before native rejection",new float[]{.48f,.60f},pose.targetJoints()[0],.00002f);assertTrue("Completing the gesture must queue an actual native diagnostic request",(Long)field(activity,"generation")>beforeRequest+1);await(activity);
            assertSame("A rejected changed source must retain the last valid diagnostic image",lastImage,field(activity,"picture"));assertFalse(lastImage.isRecycled());assertArrayEquals(accepted,pose.targetJoints()[0],.00001f);
            String rejection=((TextView)field(activity,"status")).getText().toString();assertTrue("Changed source must visibly reject the pose and report retained evidence; status: "+rejection,rejection.startsWith("Pose rejected:")&&rejection.contains("Last valid pose and image retained"));
            assertEquals(rigSha,rigs.captureBinding(project.id,rig.id).getString("sha256"));
            assertNotNull(findButton(activity.getWindow().getDecorView(),"Cancel preview"));
        }finally{controller.pause().stop().destroy();}
        assertNotNull(lastImage);assertTrue("The activity owns and releases its last image",lastImage.isRecycled());
    }
    private static void writeSource(File source,int color)throws Exception{writeSource(source,color,100,100);}
    private static void writeSource(File source,int color,int width,int height)throws Exception{Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888);bitmap.eraseColor(color);Paint paint=new Paint();paint.setColor(Color.rgb(220,220,235));paint.setStrokeWidth(3);new Canvas(bitmap).drawLine(width*.64f,height*.54f,width*.82f,height*.35f,paint);try(FileOutputStream output=new FileOutputStream(source)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}finally{bitmap.recycle();}}
    private static Object field(Object owner,String name)throws Exception{Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);}
    private static void await(RigPoseDiagnosticActivity activity)throws Exception{ExecutorService worker=(ExecutorService)field(activity,"previewWorker");for(int i=0;i<3;i++){worker.submit(()->{}).get(15,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}}
    private static float[] canvasPoint(View view,float u,float v)throws Exception{Method geometry=view.getClass().getDeclaredMethod("geometry"),x=view.getClass().getDeclaredMethod("x",float.class),y=view.getClass().getDeclaredMethod("y",float.class);geometry.setAccessible(true);x.setAccessible(true);y.setAccessible(true);geometry.invoke(view);return new float[]{(Float)x.invoke(view,u),(Float)y.invoke(view,v)};}
    private static void dragOriginal(View view,float fromU,float fromV,float toU,float toV,long at)throws Exception{float[] from=canvasPoint(view,fromU,fromV),to=canvasPoint(view,toU,toV);assertTrue("Gesture must reach the current visible original-UV handle after any window relayout",touch(view,MotionEvent.ACTION_DOWN,from[0],from[1],at));assertTrue(touch(view,MotionEvent.ACTION_MOVE,to[0],to[1],at+5));assertTrue(touch(view,MotionEvent.ACTION_UP,to[0],to[1],at+10));}
    private static boolean touch(View view,int action,float x,float y,long at){MotionEvent event=MotionEvent.obtain(1,at,action,x,y,0);try{return view.onTouchEvent(event);}finally{event.recycle();}}
    private static Button findButton(View view,String title){if(view instanceof Button&&title.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){Button found=findButton(group.getChildAt(i),title);if(found!=null)return found;}}return null;}
}
