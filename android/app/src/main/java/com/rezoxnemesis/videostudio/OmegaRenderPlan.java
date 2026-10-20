package com.rezoxnemesis.videostudio;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * OMEGA Phase 8 deterministic non-destructive render invalidation planner.
 * Has no side effects, starts no jobs and never changes NativeStartupGuard.
 */
final class OmegaRenderPlan {
    private OmegaRenderPlan() {}
    static final class Range {
        final int first,last;
        Range(int first,int last) {
            if(first<0||first>last||last>=1200)
                throw new IllegalArgumentException("Dirty frame bounds invalid");
            this.first=first;this.last=last;
        }
    }
    static final class Checkpoint {
        final String sourceSha256;
        final long projectRevision;
        final int nextFrame;
        Checkpoint(String sourceSha256,long projectRevision,int nextFrame) {
            this.sourceSha256=sha(sourceSha256);
            if(projectRevision<1||nextFrame<0||nextFrame>1200)
                throw new IllegalArgumentException("Invalid checkpoint");
            this.projectRevision=projectRevision;this.nextFrame=nextFrame;
        }
    }
    static List<Range> invalidated(int frameCount,List<Range> edits) {
        if(frameCount<1||frameCount>1200||edits==null||edits.size()>4096)
            throw new IllegalArgumentException("Render invalidation budget exceeded");
        boolean[] dirty=new boolean[frameCount];
        for(Range r:edits) {
            if(r==null||r.last>=frameCount)throw new IllegalArgumentException("Invalid dirty range");
            for(int i=r.first;i<=r.last;i++)dirty[i]=true;
        }
        List<Range> result=new ArrayList<>();
        int start=-1;
        for(int i=0;i<=frameCount;i++) {
            if(i<frameCount&&dirty[i]) {if(start<0)start=i;}
            else if(start>=0) {result.add(new Range(start,i-1));start=-1;}
        }
        return Collections.unmodifiableList(result);
    }
    static String cacheKey(String projectId,String contentSha256,int frame,int fps,long projectRevision){
        if(projectId==null||!projectId.matches("[A-Za-z0-9_-]{1,100}")||
           frame<0||frame>=1200||(fps!=24&&fps!=30&&fps!=60)||projectRevision<1)
            throw new IllegalArgumentException("Invalid render input");
        String source=sha(contentSha256);
        // Explicitly delimited metadata, no defaults inferred from state.
        String key="OMEGA_FRAME_V1\n"+projectId+"\n"+source+"\n"+
            frame+"\n"+fps+"\n"+projectRevision+"\n";
        try{
            byte[] digest=MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex=new StringBuilder(64);
            for(byte b:digest)hex.append(String.format("%02x",b&255));
            return hex.toString();
        }catch(Exception e){throw new IllegalStateException("Missing SHA-256",e);}
    }
    static boolean mayResume(Checkpoint prior,String expectedSha256,long expectedRevision,
                             int totalFrames,boolean ownerRecoveryHeld){
        sha(expectedSha256);
        if(totalFrames<1||totalFrames>1200||expectedRevision<1)
            throw new IllegalArgumentException("Invalid resume target");
        if(ownerRecoveryHeld||prior==null)return false;
        return prior.sourceSha256.equals(expectedSha256)
            &&prior.projectRevision==expectedRevision&&prior.nextFrame<=totalFrames;
    }
    private static String sha(String value){
        if(value==null||!value.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Expected lowercase SHA-256");
        return value;
    }
}