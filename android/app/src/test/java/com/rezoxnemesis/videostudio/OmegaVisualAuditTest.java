package com.rezoxnemesis.videostudio;

import java.util.Arrays;
import org.junit.Test;
import static org.junit.Assert.*;

public class OmegaVisualAuditTest {
    @Test public void separatesLocalCharacterMotionFromBackgroundChanges(){
        int[] a=new int[64],b=new int[64];
        b[3+3*8]=0x00ffffff;b[4+3*8]=0x00ffffff;
        OmegaVisualAudit.Rect region=new OmegaVisualAudit.Rect(3,3,5,5,8,8);
        OmegaVisualAudit.Evidence evidence=OmegaVisualAudit.measure(a,b,8,8,region,100);
        assertEquals(4,evidence.subjectPixels);assertEquals(2,evidence.subjectChanged);
        assertEquals(.5,evidence.subjectChangeRatio(),1e-10);
        assertEquals(0,evidence.backgroundChanged);
        assertFalse(evidence.backgroundDominated());
    }
    @Test public void flagsBackgroundDominatedMotionWithoutClaimingActing(){
        int[] a=new int[100],b=new int[100];
        Arrays.fill(b,0x00ffffff);
        for(int y=4;y<6;y++)for(int x=4;x<6;x++)b[x+y*10]=0;
        OmegaVisualAudit.Evidence evidence=OmegaVisualAudit.measure(
            a,b,10,10,new OmegaVisualAudit.Rect(4,4,6,6,10,10),100);
        assertTrue(evidence.staticSubject());assertTrue(evidence.backgroundDominated());
        assertEquals(96,evidence.backgroundChanged);
    }
    @Test public void identicalFramesHaveNoVerifiedMotion(){
        int[] blank=new int[16];
        OmegaVisualAudit.Evidence e=OmegaVisualAudit.measure(
            blank,blank,4,4,new OmegaVisualAudit.Rect(1,1,3,3,4,4),10);
        assertEquals(0,e.subjectChanged);assertEquals(0,e.backgroundChanged);
        try{OmegaVisualAudit.measure(blank,blank,4,4,new OmegaVisualAudit.Rect(1,1,3,3,4,4),800);fail();}
        catch(IllegalArgumentException expected){}
    }
}