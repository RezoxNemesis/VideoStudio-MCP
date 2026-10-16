package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class SketchLiveFollowPolicyTest {
    @Test public void newestSketchInterruptsStaleAutomaticReplay(){
        assertTrue(SketchLiveFollowPolicy.shouldPreempt(
                "old_completed","old_session","currently_editing","new_session",
                "currently_editing",false,false));
    }
    @Test public void newestBatchInSameSketchInterruptsOlderStrokes(){
        assertTrue(SketchLiveFollowPolicy.shouldPreempt(
                "same_sketch","batch_2","same_sketch","batch_3",
                "same_sketch",false,false));
    }
    @Test public void repeatedSignalNeverRestartsSameSession(){
        assertFalse(SketchLiveFollowPolicy.shouldPreempt(
                "sketch","live","sketch","live","sketch",false,false));
    }
    @Test public void interruptedUnjournaledEditingDoesNotRestartUnrelatedStream(){
        assertFalse(SketchLiveFollowPolicy.shouldPreempt(
                "sketch","live","sketch","stale","other_sketch",false,false));
        assertFalse(SketchLiveFollowPolicy.shouldPreempt(
                "sketch","live","","","",false,false));
    }
    @Test public void explicitlyTappedNotificationRemainsPinned(){
        assertFalse(SketchLiveFollowPolicy.shouldPreempt(
                "owner_selected","selected_session","new_sketch","new_session",
                "new_sketch",true,false));
    }
    @Test public void selectedHistoricReplayIsNeverInterrupted(){
        assertFalse(SketchLiveFollowPolicy.shouldPreempt(
                "historic","session","new_sketch","new_session",
                "new_sketch",false,true));
    }
}
