package com.rezoxnemesis.videostudio;
import android.view.*;
import android.widget.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import java.lang.reflect.Method;
import static org.junit.Assert.*;
@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class MainStudioLayoutTest {
    @Test public void savedProjectCardsMeasureVisibleTitlesInsteadOfZeroWidthBlankRows() throws Exception {
        MainActivity activity=Robolectric.buildActivity(MainActivity.class).get();ProjectStore.Project project=new ProjectStore.Project();project.name="Visible saved drawing";project.id="owned";
        Method method=MainActivity.class.getDeclaredMethod("projectRow",ProjectStore.Project.class);method.setAccessible(true);View row=(View)method.invoke(activity,project);row.measure(View.MeasureSpec.makeMeasureSpec(1080,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(2200,View.MeasureSpec.AT_MOST));row.layout(0,0,1080,row.getMeasuredHeight());TextView title=find(row,project.name);assertNotNull(title);assertTrue("Project title must have drawable width",title.getMeasuredWidth()>0);assertTrue("One project must not consume the entire screen",row.getMeasuredHeight()<800);
    }
    private static TextView find(View v,String text){if(v instanceof TextView&&text.contentEquals(((TextView)v).getText()))return (TextView)v;if(v instanceof ViewGroup){ViewGroup group=(ViewGroup)v;for(int n=0;n<group.getChildCount();n++){TextView found=find(group.getChildAt(n),text);if(found!=null)return found;}}return null;}
}
