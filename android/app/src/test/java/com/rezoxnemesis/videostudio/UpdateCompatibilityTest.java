package com.rezoxnemesis.videostudio;

import org.junit.Test;
import static org.junit.Assert.*;

public final class UpdateCompatibilityTest {
    private static final String ID="com.rezoxnemesis.videostudio.next";
    private static final String CERT="7e0187470356616be2a45bc7f41445894085185db110edfdc0cdfd232942a6dd";

    @Test public void nextPatchFromSameIdentityInstallsInPlace() {
        assertTrue(UpdateCompatibility.canReplace(ID,CERT,363,ID,CERT,364));
        assertTrue(UpdateCompatibility.canReplace(ID,CERT.toUpperCase(),363,ID,CERT,364));
    }

    @Test public void oldUnrelatedSigningIdentityNeverReplacesExistingApp() {
        assertFalse(UpdateCompatibility.canReplace(ID,
            "92021f4fca3c69902f51d10db933c6e14247ebbfe5bb53193ed9111b36ca9b7c",
            363,ID,CERT,364));
        assertTrue(UpdateCompatibility.rejection(ID,"oldcert",363,ID,CERT,364)
            .contains("Do not uninstall"));
    }

    @Test public void sideBySideHasDistinctApplicationId() {
        assertFalse(UpdateCompatibility.canReplace("com.rezoxnemesis.videostudio",
                CERT,360,ID,CERT,363));
    }

    @Test public void sameOrOlderVersionCodeIsRejected() {
        assertFalse(UpdateCompatibility.canReplace(ID,CERT,363,ID,CERT,363));
        assertFalse(UpdateCompatibility.canReplace(ID,CERT,363,ID,CERT,362));
    }

    @Test public void unknownSignatureFailsClosed() {
        assertFalse(UpdateCompatibility.canReplace(ID,"",362,ID,CERT,363));
        assertFalse(UpdateCompatibility.canReplace(ID,CERT,362,ID,"",363));
    }
}
