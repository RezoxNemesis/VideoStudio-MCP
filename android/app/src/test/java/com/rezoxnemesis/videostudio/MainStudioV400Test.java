package com.rezoxnemesis.videostudio;

import android.content.res.Configuration;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE) @GraphicsMode(GraphicsMode.Mode.NATIVE)
public class MainStudioV400Test {
    @Test public void directoryKeepsNativeStudiosReachableAndLaunchesSketch() throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        View directory=directory(activity, "");
        for(String label:new String[]{"Sketch Studio","Cinematic 3D Studio","Open Pipeline","Rig Builder","3D Model Viewer"})
            assertNotNull("Missing studio: "+label,find(directory,label));
        find(directory,"Sketch Studio").performClick();
        assertEquals(SketchStudioActivity.class.getName(),Shadows.shadowOf(activity).getNextStartedActivity().getComponent().getClassName());
        View filtered=directory(activity,"sketch");
        assertNotNull(find(filtered,"Sketch Studio"));
        assertNull(find(filtered,"Cinematic 3D Studio"));
    }
    @Test public void narrowLargeFontDirectoryHasUnclippedTouchTargets() throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        Configuration configuration=new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale=1.6f;
        activity.getResources().updateConfiguration(configuration,activity.getResources().getDisplayMetrics());
        View directory=directory(activity,"");
        int width=(int)(280*activity.getResources().getDisplayMetrics().density);
        directory.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        directory.layout(0,0,width,directory.getMeasuredHeight());
        verifyButtons(directory,48*activity.getResources().getDisplayMetrics().density);
    }
    @Test public void liveSearchFiltersNativeStudiosWithoutAnExtraDialog() throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        java.lang.reflect.Field content=MainActivity.class.getDeclaredField("content");content.setAccessible(true);
        FrameLayout frame=new FrameLayout(activity);content.set(activity,frame);
        Method show=MainActivity.class.getDeclaredMethod("showTools");show.setAccessible(true);show.invoke(activity);
        EditText search=findInput(frame);assertNotNull(search);
        search.setText("cinematic");assertNotNull(find(frame,"Cinematic 3D Studio"));assertNull(find(frame,"Sketch Studio"));
        search.setText("sketch");assertNotNull(find(frame,"Sketch Studio"));assertNull(find(frame,"Cinematic 3D Studio"));
    }
    @Test public void shortFixedActionRowsGrowInsteadOfClippingAtLargeFont() throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        Configuration configuration=new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale=1.6f;configuration.screenWidthDp=320;
        activity.getResources().updateConfiguration(configuration,activity.getResources().getDisplayMetrics());
        LinearLayout outer=new LinearLayout(activity);outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row=new LinearLayout(activity);outer.addView(row,new LinearLayout.LayoutParams(-1,StudioUi.dp(activity,42)));
        for(String label:new String[]{"Play edited timeline","View the latest render","All project renders"}){
            Button button=new Button(activity);button.setText(label);StudioUi.styleButton(button,false);
            row.addView(button,new LinearLayout.LayoutParams(0,StudioUi.dp(activity,38),1));
        }
        StudioUi.prepareTouchLayout(outer);
        int width=StudioUi.dp(activity,280);
        outer.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        outer.layout(0,0,width,outer.getMeasuredHeight());
        assertEquals(LinearLayout.VERTICAL,row.getOrientation());verifyButtons(outer,StudioUi.dp(activity,48));
        assertTrue(row.getChildAt(2).getBottom()<=outer.getHeight());
    }
    @Test public void mixedTrackRowGrowsSmallDeleteTargetAndLongLabel() {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        Configuration configuration=new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale=1.6f;configuration.screenWidthDp=320;
        activity.getResources().updateConfiguration(configuration,activity.getResources().getDisplayMetrics());
        LinearLayout outer=new LinearLayout(activity);outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row=new LinearLayout(activity);
        outer.addView(row,new LinearLayout.LayoutParams(-1,StudioUi.dp(activity,42)));
        TextView label=new TextView(activity);label.setText("A long caption track title that must remain readable at a larger system font");label.setTextSize(18);
        row.addView(label,new LinearLayout.LayoutParams(0,-2,1));
        Button delete=new Button(activity);delete.setText("×");StudioUi.styleButton(delete,false);
        row.addView(delete,new LinearLayout.LayoutParams(StudioUi.dp(activity,42),StudioUi.dp(activity,42)));
        StudioUi.prepareTouchLayout(outer);
        int width=StudioUi.dp(activity,280);
        outer.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        outer.layout(0,0,width,outer.getMeasuredHeight());
        assertEquals("Mixed rows retain their label beside the action",LinearLayout.HORIZONTAL,row.getOrientation());
        assertTrue("Delete target must have 48dp actual width",delete.getWidth()>=StudioUi.dp(activity,48));
        verifyButtons(outer,StudioUi.dp(activity,48));
        assertTrue("Native font measurement must wrap the long label",label.getLayout().getLineCount()>1);
        assertTrue("Growing label must fit its row",label.getHeight()<=row.getHeight());
        assertTrue("Label text must remain unclipped",label.getLayout().getHeight()<=label.getHeight()-label.getCompoundPaddingTop()-label.getCompoundPaddingBottom());
        assertTrue("Action must fit beside the label",delete.getRight()<=width);
        assertEquals("Weighted label width preserved",0,label.getLayoutParams().width);
    }
    @Test public void widthNormalizationPreservesWeightedAndWrapButtons() {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();
        Button weighted=new Button(activity);StudioUi.styleButton(weighted,false);
        weighted.setLayoutParams(new LinearLayout.LayoutParams(0,42,1));StudioUi.prepareTouchLayout(weighted);
        assertEquals(0,weighted.getLayoutParams().width);
        assertEquals(1f,((LinearLayout.LayoutParams)weighted.getLayoutParams()).weight,0f);
        Button wrapping=new Button(activity);StudioUi.styleButton(wrapping,false);
        wrapping.setLayoutParams(new LinearLayout.LayoutParams(-2,42));StudioUi.prepareTouchLayout(wrapping);
        assertEquals(-2,wrapping.getLayoutParams().width);
    }
    private static EditText findInput(View view){if(view instanceof EditText)return (EditText)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){EditText found=findInput(group.getChildAt(i));if(found!=null)return found;}}return null;}
    private View directory(MainActivity activity,String query)throws Exception{
        Method method=MainActivity.class.getDeclaredMethod("buildStudioDirectory",String.class);method.setAccessible(true);return (View)method.invoke(activity,query);
    }
    private static void verifyButtons(View view,float minimum){
        if(view instanceof Button){Button button=(Button)view;assertTrue(button.getText().toString(),button.getHeight()>=minimum);assertNotNull(button.getLayout());assertTrue("Text clipped: "+button.getText(),button.getLayout().getHeight()<=button.getHeight()-button.getCompoundPaddingTop()-button.getCompoundPaddingBottom());}
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)verifyButtons(group.getChildAt(i),minimum);}
    }
    private static TextView find(View view,String text){if(view instanceof TextView&&text.contentEquals(((TextView)view).getText()))return (TextView)view;if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){TextView found=find(group.getChildAt(i),text);if(found!=null)return found;}}return null;}
}
