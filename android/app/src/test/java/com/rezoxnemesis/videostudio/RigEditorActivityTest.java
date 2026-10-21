package com.rezoxnemesis.videostudio;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
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
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowAlertDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** Synthetic phone fixtures; these do not certify the private Test 1 artwork. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public class RigEditorActivityTest {
    @Test public void precisionNudgesUseOriginalPixelsAndCommitOneUndoableEdit()throws Exception{
        Fixture fixture=fixture();ActivityController<RigEditorActivity> controller=Robolectric.buildActivity(RigEditorActivity.class,fixture.intent).create().start().resume().visible();
        try{
            RigEditorActivity activity=controller.get();awaitOwnerWorker(activity);String before=draft(activity);
            Button fit=findButton(activity.getWindow().getDecorView(),"Fit");assertNotNull(fit);for(android.view.ViewParent parent=fit.getParent();parent!=null;parent=parent.getParent())assertFalse("Fit must remain visible without scrolling the tool strip",parent instanceof HorizontalScrollView);
            findButton(activity.getWindow().getDecorView(),"Coordinates").performClick();AlertDialog dialog=ShadowAlertDialog.getLatestAlertDialog();
            EditText u=dialog.getWindow().getDecorView().findViewWithTag("rig-coordinate-u"),v=dialog.getWindow().getDecorView().findViewWithTag("rig-coordinate-v");assertNotNull("Normalized U must have an accessible named field",u);assertNotNull(v);
            findButton(dialog.getWindow().getDecorView(),"X +1 px").performClick();findButton(dialog.getWindow().getDecorView(),"Y −1 px").performClick();
            assertEquals(.43f,Float.parseFloat(u.getText().toString()),.00001f);assertEquals(.56f,Float.parseFloat(v.getText().toString()),.00001f);assertEquals("Dialog nudges are staged until Set point",before,draft(activity));
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();assertEquals(.43f,new org.json.JSONObject(draft(activity)).getJSONArray("joints").getJSONArray(0).getDouble(0),.00001f);
            assertEquals(1,((java.util.ArrayDeque<?>)field(activity,"undo")).size());findButton(activity.getWindow().getDecorView(),"Undo").performClick();assertEquals(before,draft(activity));
        }finally{controller.pause().stop().destroy();}
    }

    @Test public void malformedCoordinateDraftsAreRejectedBeforeAnyAcceptedStateChanges()throws Exception{
        Fixture fixture=fixture();ActivityController<RigEditorActivity> controller=Robolectric.buildActivity(RigEditorActivity.class,fixture.intent).create().start().resume().visible();
        try{
            RigEditorActivity activity=controller.get();awaitOwnerWorker(activity);String before=draft(activity);Method apply=RigEditorActivity.class.getDeclaredMethod("applyDraft",String.class);apply.setAccessible(true);
            for(String invalid:new String[]{"NaN","Infinity","1e300","-0.1","1.1"}){
                org.json.JSONObject corrupt=new org.json.JSONObject(before);corrupt.put("rigId","must-not-replace-accepted-rig");corrupt.put("black",false);corrupt.getJSONArray("outline").getJSONArray(1).put(0,invalid);
                try{apply.invoke(activity,corrupt.toString());fail("Coordinate "+invalid+" must be rejected");}catch(java.lang.reflect.InvocationTargetException expected){assertTrue(expected.getCause() instanceof IllegalArgumentException);}
                assertEquals("Rejected geometry must preserve all draft metadata and points",before,draft(activity));
            }
        }finally{controller.pause().stop().destroy();}
    }

    @Test public void nonfiniteTouchRollsBackActiveDragAndLeavesHistoryClean()throws Exception{
        Fixture fixture=fixture();ActivityController<RigEditorActivity> controller=Robolectric.buildActivity(RigEditorActivity.class,fixture.intent).create().start().resume().visible();
        try{
            RigEditorActivity activity=controller.get();awaitOwnerWorker(activity);String before=draft(activity);View drawing=(View)field(activity,"drawing");drawing.layout(0,0,1000,1000);
            touch(drawing,MotionEvent.ACTION_DOWN,420,570,10);touch(drawing,MotionEvent.ACTION_MOVE,490,580,20);touch(drawing,MotionEvent.ACTION_MOVE,Float.NaN,580,25);touch(drawing,MotionEvent.ACTION_UP,500,590,30);
            assertEquals(before,draft(activity));assertTrue(((java.util.ArrayDeque<?>)field(activity,"undo")).isEmpty());assertFalse((Boolean)field(activity,"dirty"));
            drawing.layout(0,0,0,0);touch(drawing,MotionEvent.ACTION_DOWN,0,0,40);touch(drawing,MotionEvent.ACTION_UP,0,0,50);assertEquals(before,draft(activity));
        }finally{controller.pause().stop().destroy();}
    }

    @Test public void recreationBeforeOriginalLoadKeepsRequestedSavedProfileReachable()throws Exception{
        Fixture fixture=fixture();ActivityController<RigEditorActivity> first=Robolectric.buildActivity(RigEditorActivity.class,fixture.intent).create();Bundle state=new Bundle();first.saveInstanceState(state);
        assertFalse("A not-yet-accepted source cannot persist an empty draft over the requested rig",state.containsKey("draft"));first.destroy();
        ActivityController<RigEditorActivity> reopened=Robolectric.buildActivity(RigEditorActivity.class,fixture.intent).create(state).start().resume().visible();
        try{awaitOwnerWorker(reopened.get());assertEquals(fixture.profile.id,field(reopened.get(),"rigId"));assertEquals(fixture.projects.get(fixture.project.id).updatedAt,((Long)field(reopened.get(),"expectedSourceUpdatedAt")).longValue());}finally{reopened.pause().stop().destroy();}
    }

    @Test public void recreationDuringAcceptedSaveRetriesOnlyIdenticalPublishedGeometry()throws Exception{
        Fixture fixture=fixture();ActivityController<RigEditorActivity> first=Robolectric.buildActivity(RigEditorActivity.class,fixture.intent).create().start().resume().visible();RigEditorActivity activity=first.get();awaitOwnerWorker(activity);
        View drawing=(View)field(activity,"drawing");drawing.layout(0,0,1000,1000);touch(drawing,MotionEvent.ACTION_DOWN,420,570,10);touch(drawing,MotionEvent.ACTION_UP,500,590,30);
        Field saving=RigEditorActivity.class.getDeclaredField("saving");saving.setAccessible(true);saving.setBoolean(activity,true);Bundle state=new Bundle();first.saveInstanceState(state);
        org.json.JSONObject frozen=new org.json.JSONObject(state.getString("draft"));if(frozen.getJSONArray("swordOutline").length()==0)frozen.remove("swordOutline");Rig2DProfile accepted=NativeRigAuthoring.parse(fixture.project.id,fixture.asset.id,frozen);
        fixture.rigs.saveWithBinding(accepted,state.getLong("sourceRevision"),state.getString("rigSha"));String published=fixture.rigs.captureBinding(fixture.project.id,accepted.id).getString("sha256");saving.setBoolean(activity,false);first.pause().stop().destroy();
        ActivityController<RigEditorActivity> reopened=Robolectric.buildActivity(RigEditorActivity.class,fixture.intent).create(state).start().resume().visible();
        try{awaitOwnerWorker(reopened.get());findButton(reopened.get().getWindow().getDecorView(),"Save Actor Rig").performClick();awaitOwnerWorker(reopened.get());assertEquals(published,field(reopened.get(),"expectedRigSha256"));assertFalse((Boolean)field(reopened.get(),"dirty"));assertEquals(1,fixture.rigs.list(fixture.project.id).size());}finally{reopened.pause().stop().destroy();}
    }

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
            Button diagnostic=findButton(activity.getWindow().getDecorView(),"Pose diagnostic");assertNotNull("Saved-source diagnostic must stay reachable beside annotation tools",diagnostic);
            assertNotNull("Ordered preparation must stay reachable",findButton(activity.getWindow().getDecorView(),"Sequence readiness"));
            assertNotNull("Existing contact authoring must stay reachable",findButton(activity.getWindow().getDecorView(),"Contacts"));
            diagnostic.performClick();assertNull("Unsaved source-rest edits cannot be silently tested as a saved profile",shadowOf(activity).getNextStartedActivity());
            Button save=findButton(activity.getWindow().getDecorView(),"Save Actor Rig");assertNotNull("Existing actor-save owner action must stay reachable",save);save.performClick();awaitOwnerWorker(activity);
            Rig2DProfile edited=rigs.load(project.id,saved.id);
            assertEquals("Dragging a saved joint must update that exact existing source profile",.50f,edited.joints[Rig2DProfile.PELVIS][0],.001f);
            assertEquals(.59f,edited.joints[Rig2DProfile.PELVIS][1],.001f);
            assertEquals("Updating an annotation must not create an ambiguous second actor",1,rigs.list(project.id).size());
            assertEquals(image.id,edited.sourceAssetId);assertArrayEquals(saved.swordTip,edited.swordTip,.00001f);
            ProjectStore.Asset missing=new ProjectStore.Asset();missing.id=UUID.randomUUID().toString();missing.name="unreadable-original.png";missing.mime="image/png";missing.uri="file:///unavailable/"+missing.id+".png";project.assets.add(missing);projects.save(project);
            Field bitmapField=RigEditorActivity.class.getDeclaredField("picture");bitmapField.setAccessible(true);Bitmap retained=(Bitmap)bitmapField.get(activity);
            Method navigate=RigEditorActivity.class.getDeclaredMethod("openMissingActor",String.class,boolean.class,org.json.JSONObject.class);navigate.setAccessible(true);navigate.invoke(activity,missing.id,false,new org.json.JSONObject());awaitOwnerWorker(activity);
            Field actor=RigEditorActivity.class.getDeclaredField("black");actor.setAccessible(true);assertTrue("A failed next-source read must preserve the old actor identity",actor.getBoolean(activity));
            Field selected=RigEditorActivity.class.getDeclaredField("selected");selected.setAccessible(true);assertEquals(image.id,((ProjectStore.Asset)selected.get(activity)).id);
            assertSame("Failed navigation retains the displayed original and its annotation draft",retained,bitmapField.get(activity));assertFalse(retained.isRecycled());
        }finally{controller.pause().stop().destroy();}
    }
    @Test public void headMaskCanBeDrawnUndoneSavedAndReopened()throws Exception{
        Fixture fixture=fixture();ActivityController<RigEditorActivity> first=Robolectric.buildActivity(RigEditorActivity.class,fixture.intent).create().start().resume().visible();
        Bundle state=new Bundle();
        try{
            RigEditorActivity activity=first.get();awaitOwnerWorker(activity);
            Button head=findButton(activity.getWindow().getDecorView(),"Head mask");assertNotNull("Head isolation must be reachable in the native owner editor",head);head.performClick();
            View drawing=(View)field(activity,"drawing");drawing.layout(0,0,1000,1000);
            int[][] vertices={{300,120},{550,120},{550,280},{300,280}};long clock=10;
            for(int[] point:vertices){touch(drawing,MotionEvent.ACTION_DOWN,point[0],point[1],clock++);touch(drawing,MotionEvent.ACTION_UP,point[0],point[1],clock++);}
            assertEquals(4,new org.json.JSONObject(draft(activity)).getJSONArray("headOutline").length());
            findButton(activity.getWindow().getDecorView(),"Undo").performClick();assertEquals(3,new org.json.JSONObject(draft(activity)).getJSONArray("headOutline").length());
            findButton(activity.getWindow().getDecorView(),"Redo").performClick();
            findButton(activity.getWindow().getDecorView(),"Save Actor Rig").performClick();awaitOwnerWorker(activity);
            assertEquals(4,fixture.rigs.load(fixture.project.id,fixture.profile.id).toJson().getJSONArray("headOutline").length());
            first.saveInstanceState(state);
        }finally{first.pause().stop().destroy();}
        ActivityController<RigEditorActivity> reopened=Robolectric.buildActivity(RigEditorActivity.class,fixture.intent).create(state).start().resume().visible();
        try{awaitOwnerWorker(reopened.get());assertEquals(4,new org.json.JSONObject(draft(reopened.get())).getJSONArray("headOutline").length());}
        finally{reopened.pause().stop().destroy();}
    }
    private static void awaitOwnerWorker(RigEditorActivity activity)throws Exception{Field field=RigEditorActivity.class.getDeclaredField("ownerWorker");field.setAccessible(true);((ExecutorService)field.get(activity)).submit(()->{}).get(10,TimeUnit.SECONDS);ShadowLooper.idleMainLooper();}
    private static Object field(Object activity,String name)throws Exception{Field field=RigEditorActivity.class.getDeclaredField(name);field.setAccessible(true);return field.get(activity);}
    private static String draft(RigEditorActivity activity)throws Exception{Method method=RigEditorActivity.class.getDeclaredMethod("draft");method.setAccessible(true);return(String)method.invoke(activity);}
    private static final class Fixture {ProjectStore projects;ProjectStore.Project project;ProjectStore.Asset asset;Rig2DProjectStore rigs;Rig2DProfile profile;Intent intent;}
    private static Fixture fixture()throws Exception{
        Context context=RuntimeEnvironment.getApplication();Fixture fixture=new Fixture();fixture.projects=new ProjectStore(context);fixture.project=fixture.projects.create("Phone lifecycle source");fixture.asset=new ProjectStore.Asset();fixture.asset.id=UUID.randomUUID().toString();fixture.asset.name="lifecycle.png";fixture.asset.mime="image/png";
        File source=new File(context.getFilesDir(),"phone-lifecycle-"+UUID.randomUUID()+".png");Bitmap original=Bitmap.createBitmap(100,100,Bitmap.Config.ARGB_8888);original.eraseColor(Color.rgb(25,35,48));try(FileOutputStream output=new FileOutputStream(source)){assertTrue(original.compress(Bitmap.CompressFormat.PNG,100,output));}finally{original.recycle();}
        fixture.asset.uri=Uri.fromFile(source).toString();fixture.project.assets.add(fixture.asset);fixture.projects.save(fixture.project);fixture.rigs=new Rig2DProjectStore(context,fixture.projects);fixture.profile=Rig2DProfile.create(fixture.project.id,fixture.asset.id,true,false,Rig2DProfileTest.joints(),Rig2DProfileTest.outline(),new float[]{.82f,.35f});fixture.rigs.save(fixture.profile);
        fixture.intent=new Intent(context,RigEditorActivity.class).putExtra("projectId",fixture.project.id).putExtra("assetId",fixture.asset.id).putExtra("rigId",fixture.profile.id);return fixture;
    }
    private static void touch(View view,int action,float x,float y,long at){MotionEvent event=MotionEvent.obtain(1,at,action,x,y,0);try{view.onTouchEvent(event);}finally{event.recycle();}}
    private static Button findButton(View view,String title){if(view instanceof Button&&title.contentEquals(((Button)view).getText()))return(Button)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int index=0;index<group.getChildCount();index++){Button found=findButton(group.getChildAt(index),title);if(found!=null)return found;}}return null;}
}
