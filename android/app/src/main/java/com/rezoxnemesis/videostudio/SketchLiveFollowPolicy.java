package com.rezoxnemesis.videostudio;

/**
 * Pure admission guard for prioritising the CURRENT privately authored drawing.
 * Notifications and explicitly requested historic replays stay pinned.
 * Switching never erases the saved operation journal.
 */
final class SketchLiveFollowPolicy {
    private SketchLiveFollowPolicy() {}

    static boolean shouldPreempt(String displayedSketch,String displayedSession,
            String signaledSketch,String signaledSession,String streamSketch,
            boolean explicitNotification,boolean historicalReplay){
        if(explicitNotification||historicalReplay||signaledSketch==null||signaledSketch.isEmpty())
            return false;
        if(!signaledSketch.equals(displayedSketch))return true;
        // A native edit may update the visible source without a session.
        // In that case the viewer should not restart the old journal.
        return signaledSketch.equals(streamSketch)
                && signaledSession!=null&&!signaledSession.isEmpty()
                && !signaledSession.equals(displayedSession);
    }
}
