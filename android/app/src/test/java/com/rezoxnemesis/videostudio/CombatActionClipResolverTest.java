package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public class CombatActionClipResolverTest {
    private final String[] named={"Sword_Idle","SprintRun","Jump","SlashAttack",
            "ParryBlock","Counter_Strike","KnockbackFall","LandingRecover"};

    @Test public void directorsSelectActualImportedMotionNames(){
        for(CombatActionPlan.Beat beat:CombatActionPlan.Beat.values()){
            CombatActionClipResolver.Choice black=
                    CombatActionClipResolver.resolve(named,beat,true);
            CombatActionClipResolver.Choice white=
                    CombatActionClipResolver.resolve(named,beat,false);
            assertTrue("Missing black motion "+beat,black.semanticMatch);
            assertTrue("Missing white motion "+beat,white.semanticMatch);
            assertTrue(black.index>=0&&black.index<named.length);
            assertTrue(white.index>=0&&white.index<named.length);
        }
        assertEquals("ParryBlock",
                CombatActionClipResolver.resolve(named,CombatActionPlan.Beat.CONTACT,false).matchedName);
    }

    @Test public void neverInventAnUnavailableAnimation(){
        String[] clips={"A_Statue_Idle"};
        CombatActionClipResolver.Choice choice=CombatActionClipResolver.resolve(
                clips,CombatActionPlan.Beat.LEAP,true);
        assertFalse(choice.semanticMatch);
        assertEquals(0,choice.index);
        assertEquals("A_Statue_Idle",choice.matchedName);
    }

    @Test(expected=IllegalArgumentException.class)
    public void deniesMissingGlbClipList(){
        CombatActionClipResolver.resolve(new String[0],CombatActionPlan.Beat.STRIKE,true);
    }
}
