package com.rezoxnemesis.videostudio;

import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import java.lang.reflect.Field;
import static org.junit.Assert.*;

/** A cold launcher must not create a decoder, audio track or Media3 player. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=33,manifest=Config.NONE)
public final class NativeColdStartSmokeTest {
    @Test public void launchHomeWithoutEagerExoPlayerOrAudioDecoders()throws Exception{
        try(ActivityController<MainActivity> controller=Robolectric
                .buildActivity(MainActivity.class).create().start().resume().visible()){
            MainActivity activity=controller.get();
            assertNotNull("The user should see a working home screen",activity.findViewById(android.R.id.content));
            assertNull("Media3 ExoPlayer should not be allocated during the splash",field(activity,"livePlayer"));
            assertNull("Audio waveform decoder should not start until editing",field(activity,"studioAudioWaveforms"));
            assertNull("AudioTrack preview should not start until editing",field(activity,"studioAudioPreview"));
            assertNotNull("Project persistence remains available",field(activity,"store"));
        }
    }

    private static Object field(Object owner,String name)throws Exception{
        Field f=owner.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(owner);
    }
}
