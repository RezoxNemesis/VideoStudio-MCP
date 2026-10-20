package com.rezoxnemesis.videostudio;

import java.util.Arrays;
import java.util.Collections;
import org.junit.Test;
import static org.junit.Assert.*;

public class OmegaMasterDependencyIndexTest {
    private static final String HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    private static OmegaMasterDependencyIndex.Link link(String sketch, String object, boolean locked, boolean override, long version) {
        return new OmegaMasterDependencyIndex.Link("rose", "film", sketch, object, HASH, version, 7, locked, override);
    }
    @Test public void crossSketchImpactIsDeterministicAndPreservesLocalOverrideMetadata() {
        OmegaMasterDependencyIndex.Preview p=OmegaMasterDependencyIndex.preview(
            Arrays.asList(link("shotB","arm",false,true,2),link("shotA","arm",false,false,2)),
            "rose",HASH,2);
        assertTrue(p.ready());
        assertEquals(2,p.affected.size());
        assertEquals("shotA",p.affected.get(0).sketchId);
        assertEquals(1,p.overrideCount);
        assertEquals(7,p.affected.get(0).sketchRevision);
    }
    @Test public void locksAndStaleVersionsFailClosedWithoutMutation() {
        OmegaMasterDependencyIndex.Preview p=OmegaMasterDependencyIndex.preview(
            Arrays.asList(link("shotA","rose",true,false,2),link("shotB","rose",false,false,1)),
            "rose",HASH,2);
        assertFalse(p.ready());
        assertEquals(2,p.blockers.size());
        try {p.requireReady();fail("Locked and stale must block edits");}
        catch(IllegalStateException expected){assertTrue(expected.getMessage().contains("MASTER_IMPACT_BLOCKED"));}
    }
    @Test public void emptyIndexAndDuplicatedIdentitiesAreRejected() {
        assertFalse(OmegaMasterDependencyIndex.preview(Collections.emptyList(),"rose",HASH,1).ready());
        OmegaMasterDependencyIndex.Link a=link("shotA","rose",false,false,1);
        try {OmegaMasterDependencyIndex.preview(Arrays.asList(a,a),"rose",HASH,1);fail();}
        catch(IllegalArgumentException expected){assertTrue(expected.getMessage().contains("Duplicate"));}
    }
    @Test public void sourceHashMustMatchExpected() {
        OmegaMasterDependencyIndex.Link a=link("shotA","rose",false,false,1);
        String other="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb";
        assertFalse(OmegaMasterDependencyIndex.preview(Arrays.asList(a),"rose",other,1).ready());
    }
}