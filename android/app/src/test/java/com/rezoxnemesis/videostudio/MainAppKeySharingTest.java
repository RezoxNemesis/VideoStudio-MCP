package com.rezoxnemesis.videostudio;

import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=33,manifest=Config.NONE)
public class MainAppKeySharingTest {
    private static final String KEY="vsapp1.synthetic_only_012345678901234567890123456789";
    @Test public void shareUsesOneAppKeyAndNoOwnerUrl()throws Exception{
        MainActivity activity=activity(KEY);invoke(activity,"shareAppKey");
        Intent chooser=Shadows.shadowOf(activity).getNextStartedActivity();
        assertEquals(Intent.ACTION_CHOOSER,chooser.getAction());
        Intent share=chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND,share.getAction());assertEquals("text/plain",share.getType());
        assertEquals(KEY,share.getStringExtra(Intent.EXTRA_TEXT));
    }
    @Test public void unavailableIdentityIsNotSharedOrCopied()throws Exception{
        MainActivity activity=activity("");invoke(activity,"shareAppKey");invoke(activity,"copyAppKey");
        assertNull(Shadows.shadowOf(activity).getNextStartedActivity());
        assertFalse(((ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE)).hasPrimaryClip());
    }
    @Test public void explicitCopyMarksClipboardSensitive()throws Exception{
        MainActivity activity=activity(KEY);invoke(activity,"copyAppKey");
        ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertEquals(KEY,clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertTrue(clipboard.getPrimaryClipDescription().getExtras().getBoolean(android.content.ClipDescription.EXTRA_IS_SENSITIVE));
    }
    private MainActivity activity(String key)throws Exception{
        MainActivity a=Robolectric.buildActivity(MainActivity.class).get();
        AppProtocol p=new AppProtocol(a,null);
        Field owner=AppProtocol.class.getDeclaredField("ownerKey");owner.setAccessible(true);owner.set(p,key.isEmpty()?"":key.substring(7));
        Field recovery=AppProtocol.class.getDeclaredField("identityRecoveryRequired");recovery.setAccessible(true);recovery.setBoolean(p,key.isEmpty());
        Field f=MainActivity.class.getDeclaredField("protocol");f.setAccessible(true);f.set(a,p);return a;
    }
    private void invoke(MainActivity a,String name)throws Exception{Method m=MainActivity.class.getDeclaredMethod(name);m.setAccessible(true);m.invoke(a);}
}
