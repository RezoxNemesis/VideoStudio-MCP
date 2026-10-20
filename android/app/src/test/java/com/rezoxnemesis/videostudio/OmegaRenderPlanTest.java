package com.rezoxnemesis.videostudio;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import org.junit.Test;
import static org.junit.Assert.*;

/** Pure-engine contract tests; separate from real Android codec/restart acceptance. */
public class OmegaRenderPlanTest {
    private static final String H = hex(10), B = hex(11), C = hex(12), D = hex(13);
    private static String hex(long n) {return String.format(java.util.Locale.ROOT, "%064x", n);}
    private static OmegaRenderPlan.Snapshot snapshot(int count, long revision, String source,
                                                      String profile, int fps) {
        List<String> inputs = new ArrayList<>();
        for (int n = 0; n < count; n++) inputs.add(hex(100 + n));
        return new OmegaRenderPlan.Snapshot("film", source, revision, profile, fps, inputs);
    }
    private static OmegaRenderPlan.Snapshot withChanged(OmegaRenderPlan.Snapshot original, int at) {
        List<String> inputs = new ArrayList<>(original.frameInputs);
        inputs.set(at, D);
        return new OmegaRenderPlan.Snapshot(original.sketchId, B,
                original.revision + 1, original.profileSha256, original.fps, inputs);
    }
    private static List<OmegaRenderPlan.FrameProof> proofs(OmegaRenderPlan.Snapshot snapshot, int count) {
        List<OmegaRenderPlan.FrameProof> result = new ArrayList<>();
        for (int i = 0; i < count; i++) result.add(OmegaRenderPlan.proof(snapshot, i, hex(500 + i), 600 + i));
        return result;
    }
    private static void illegal(Runnable action) {
        try {action.run(); fail("Untrusted input must be rejected");}
        catch (IllegalArgumentException expected) {}
    }
    @Test public void dirtyRangesMergeOverlapsAndAdjacentWithoutDirtyingOtherFrames() {
        List<OmegaRenderPlan.Range> ranges = OmegaRenderPlan.invalidated(100, Arrays.asList(
                new OmegaRenderPlan.Range(80,85),new OmegaRenderPlan.Range(7,12),
                new OmegaRenderPlan.Range(13,16),new OmegaRenderPlan.Range(10,14)));
        assertEquals(2,ranges.size());
        assertEquals(7,ranges.get(0).first);assertEquals(16,ranges.get(0).last);
        assertEquals(80,ranges.get(1).first);assertEquals(85,ranges.get(1).last);
        assertTrue(OmegaRenderPlan.invalidated(100,Collections.emptyList()).isEmpty());
        illegal(()->OmegaRenderPlan.invalidated(10,Arrays.asList(new OmegaRenderPlan.Range(9,12))));
        illegal(()->OmegaRenderPlan.invalidated(0,Collections.emptyList()));
        illegal(()->OmegaRenderPlan.invalidated(10,Arrays.asList((OmegaRenderPlan.Range)null)));
        illegal(()->new OmegaRenderPlan.Range(-1,0));
        illegal(()->new OmegaRenderPlan.Range(0,1200));
    }
    @Test public void deterministicScopedCacheAndFrameKeysRejectInvalidInputs() {
        String original = OmegaRenderPlan.cacheKey("film",H,1,24,4);
        assertEquals(64,original.length());
        assertEquals(original,OmegaRenderPlan.cacheKey("film",H,1,24,4));
        assertNotEquals(original,OmegaRenderPlan.cacheKey("film",H,2,24,4));
        assertNotEquals(original,OmegaRenderPlan.cacheKey("film",H,1,24,5));
        assertNotEquals(original,OmegaRenderPlan.cacheKey("film",B,1,24,4));
        assertNotEquals(original,OmegaRenderPlan.cacheKey("other",H,1,24,4));
        illegal(()->OmegaRenderPlan.cacheKey("../filmmaker",H,1,24,4));
        illegal(()->OmegaRenderPlan.cacheKey("film",H.toUpperCase(),1,24,4));
        illegal(()->OmegaRenderPlan.cacheKey("film",H,1,25,4));
        illegal(()->OmegaRenderPlan.cacheKey("film",H,1200,24,4));
        OmegaRenderPlan.Snapshot old = snapshot(9,4,H,B,24);
        OmegaRenderPlan.Snapshot later = snapshot(9,5,C,B,24);
        assertNotEquals(old.identity(),later.identity());
        assertEquals("Only unchanged frame inputs may have cross-revision raster keys",
                OmegaRenderPlan.rasterKey(old,2),OmegaRenderPlan.rasterKey(later,2));
        assertNotEquals(OmegaRenderPlan.rasterKey(old,2),OmegaRenderPlan.rasterKey(withChanged(old,2),2));
        assertNotEquals(OmegaRenderPlan.rasterKey(old,2),OmegaRenderPlan.rasterKey(snapshot(9,4,H,C,24),2));
    }
    @Test public void selectiveAcrossRevisionsRequiresExactFrameInputsAndFreshPngProofs() {
        OmegaRenderPlan.Snapshot old = snapshot(8,3,H,B,30), next=withChanged(old,3);
        List<OmegaRenderPlan.FrameProof> receipts = proofs(old,8);
        OmegaRenderPlan.Plan plan=OmegaRenderPlan.plan(next,old,receipts,receipts,
                Arrays.asList(new OmegaRenderPlan.Range(5,5)));
        assertEquals(2,plan.renderCount);assertEquals(6,plan.reuseCount);
        assertEquals(2,plan.toRender.size());
        assertEquals(3,plan.toRender.get(0).first);
        assertEquals(5,plan.toRender.get(1).first);
        assertEquals(next.identity(),plan.targetIdentity);
        assertEquals(3,plan.toReuse.size());
        try {plan.toRender.clear();fail("Decision must be immutable");}
        catch(UnsupportedOperationException expected) {}
    }
    @Test public void noDiskVerificationMeansNoReuseAndNoPriorMeansFullRender() {
        OmegaRenderPlan.Snapshot old=snapshot(12,1,H,B,24);
        List<OmegaRenderPlan.FrameProof> receipts=proofs(old,12);
        assertEquals(12,OmegaRenderPlan.plan(old,old,receipts,Collections.emptyList(),Collections.emptyList()).renderCount);
        assertEquals(12,OmegaRenderPlan.plan(old,null,null,null,Collections.emptyList()).renderCount);
        illegal(()->OmegaRenderPlan.plan(old,null,receipts,receipts,Collections.emptyList()));
        assertEquals(0,OmegaRenderPlan.plan(old,old,receipts,receipts,Collections.emptyList()).renderCount);
        assertEquals(12,OmegaRenderPlan.plan(snapshot(12,2,C,D,24),old,receipts,receipts,Collections.emptyList()).renderCount);
        assertEquals(12,OmegaRenderPlan.plan(snapshot(12,2,C,B,60),old,receipts,receipts,Collections.emptyList()).renderCount);
    }
    @Test public void corruptedButDeclaredCacheBytesFailClosedRatherThanReusing() {
        OmegaRenderPlan.Snapshot snap=snapshot(8,3,H,B,30);
        List<OmegaRenderPlan.FrameProof> receipts=proofs(snap,8);
        List<OmegaRenderPlan.FrameProof> disk=new ArrayList<>(receipts);
        disk.set(4,OmegaRenderPlan.proof(snap,4,D,604));
        try {OmegaRenderPlan.plan(snap,snap,receipts,disk,Collections.emptyList());fail("PNG replacement cannot be silently reused");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("MISMATCH"));}
        disk.clear();disk.addAll(receipts);disk.add(receipts.get(7));
        illegal(()->OmegaRenderPlan.plan(snap,snap,receipts,disk,Collections.emptyList()));
        disk.clear();disk.addAll(receipts);
        disk.set(2,new OmegaRenderPlan.FrameProof(2,snap.frameInputs.get(2),hex(502),602,999));
        illegal(()->OmegaRenderPlan.plan(snap,snap,receipts,disk,Collections.emptyList()));
    }
    @Test public void incompleteOrUnorderedProofsDoNotForgeACompletedPrefix() {
        OmegaRenderPlan.Snapshot s=snapshot(4,3,H,B,30);
        List<OmegaRenderPlan.FrameProof> p=proofs(s,3);
        Collections.swap(p,0,2);
        final List<OmegaRenderPlan.FrameProof> shuffled=p;
        illegal(()->OmegaRenderPlan.checkpoint(s,"operation_3",shuffled));
        p=proofs(s,3);
        List<OmegaRenderPlan.FrameProof> almost=new ArrayList<>(p);
        almost.remove(1);
        illegal(()->OmegaRenderPlan.checkpoint(s,"operation_3",almost));
        illegal(()->OmegaRenderPlan.proof(s,4,C,100));
        illegal(()->new OmegaRenderPlan.FrameProof(1,C,D,3,0));
    }
    @Test public void resumeSurvivesCleanRestartOnlyWithExactOperationSnapshotAndVerifiedPrefix() {
        OmegaRenderPlan.Snapshot s=snapshot(9,6,H,B,24);
        List<OmegaRenderPlan.FrameProof> prefix=proofs(s,4);
        OmegaRenderPlan.RecoveryTicket ticket=OmegaRenderPlan.checkpoint(s,"render_1",prefix);
        OmegaRenderPlan.RecoveryTicket restored=OmegaRenderPlan.RecoveryTicket.decode(ticket.encode());
        assertEquals(4,restored.nextFrame);
        assertEquals(ticket.encode(),restored.encode());
        assertTrue(OmegaRenderPlan.mayResumeVerified(restored,s,"render_1",prefix,prefix,false,true));
        assertFalse(OmegaRenderPlan.mayResumeVerified(restored,s,"render_1",prefix,prefix,true,true));
        assertFalse(OmegaRenderPlan.mayResumeVerified(restored,s,"render_1",prefix,prefix,false,false));
        assertFalse(OmegaRenderPlan.mayResumeVerified(restored,s,"render_2",prefix,prefix,false,true));
        assertFalse(OmegaRenderPlan.mayResumeVerified(restored,withChanged(s,4),"render_1",prefix,prefix,false,true));
        assertFalse(OmegaRenderPlan.mayResumeVerified(restored,s,"render_1",prefix,proofs(s,3),false,true));
        List<OmegaRenderPlan.FrameProof> replaced=new ArrayList<>(prefix);
        replaced.set(3,OmegaRenderPlan.proof(s,3,C,603));
        assertFalse(OmegaRenderPlan.mayResumeVerified(restored,s,"render_1",prefix,replaced,false,true));
        assertFalse(OmegaRenderPlan.mayResumeVerified(restored,s,"render_1",Collections.emptyList(),prefix,false,true));
    }
    @Test public void restartTicketCorruptionOrCompletedOutputCannotResume() {
        OmegaRenderPlan.Snapshot s=snapshot(4,3,H,B,30);
        List<OmegaRenderPlan.FrameProof> complete=proofs(s,4);
        OmegaRenderPlan.RecoveryTicket ticket=OmegaRenderPlan.checkpoint(s,"render_1",complete);
        assertFalse(OmegaRenderPlan.mayResumeVerified(ticket,s,"render_1",complete,complete,false,true));
        illegal(()->OmegaRenderPlan.RecoveryTicket.decode(ticket.encode().replace("RESTART_V2","RESTART_V1")));
        illegal(()->OmegaRenderPlan.RecoveryTicket.decode(ticket.encode()+"extra"));
        illegal(()->OmegaRenderPlan.RecoveryTicket.decode(ticket.encode().replace("\n4\n","\n04\n")));
        illegal(()->OmegaRenderPlan.RecoveryTicket.decode(null));
        OmegaRenderPlan.RecoveryTicket forged=OmegaRenderPlan.RecoveryTicket.decode(ticket.encode().replace(ticket.prefixSha256,D));
        assertFalse(OmegaRenderPlan.mayResumeVerified(forged,s,"render_1",complete,complete,false,true));
    }
    @Test public void stableNativeRationalFrameClockAtAllFiveAuditPositions() {
        for(int fps:new int[]{24,30,60}) {
            OmegaRenderPlan.Snapshot s=snapshot(1200,1,H,B,fps);
            for (int i:new int[]{0,299,599,899,1199}) {
                OmegaRenderPlan.FrameProof frame=OmegaRenderPlan.proof(s,i,C,350);
                assertEquals(NativeFrameClock.presentationTimeUs(i,fps),frame.presentationTimeUs);
            }
            assertEquals(1200,OmegaRenderPlan.plan(s,null,null,null,Collections.emptyList()).renderCount);
        }
        illegal(()->snapshot(1201,1,H,B,24));
        illegal(()->snapshot(1,0,H,B,24));
        illegal(()->snapshot(1,1,H,B,25));
        illegal(()->new OmegaRenderPlan.Snapshot("film",H,1,B,24,Arrays.asList("broken")));
    }
    @Test public void randomizedDirtyRunGenerationEqualsSlowReference() {
        Random rng = new Random(80082026L);
        for(int cycle=0;cycle<200;cycle++) {
            int length=1+rng.nextInt(1200);
            boolean[] desired=new boolean[length];List<OmegaRenderPlan.Range> edit=new ArrayList<>();
            int count=rng.nextInt(50);
            for(int i=0;i<count;i++) {
                int left=rng.nextInt(length),right=left+rng.nextInt(length-left);
                edit.add(new OmegaRenderPlan.Range(left,right));
                for(int x=left;x<=right;x++) desired[x]=true;
            }
            boolean[] actual=new boolean[length];
            for(OmegaRenderPlan.Range x:OmegaRenderPlan.invalidated(length,edit)) {
                for(int n=x.first;n<=x.last;n++) {
                    assertFalse("Ranges must not overlap",actual[n]);actual[n]=true;
                }
            }
            assertArrayEquals(desired,actual);
        }
    }
    @Test public void originalOwnerRecoveryHoldIsNeverOverriddenByPrototype() {
        OmegaRenderPlan.Checkpoint c=new OmegaRenderPlan.Checkpoint(H,6,30);
        assertFalse(OmegaRenderPlan.mayResume(c,H,6,100,true));
        assertTrue(OmegaRenderPlan.mayResume(c,H,6,100,false));
        assertFalse(OmegaRenderPlan.mayResume(c,H,7,100,false));
        assertFalse(OmegaRenderPlan.mayResume(null,H,6,100,false));
    }
}
