package com.rezoxnemesis.videostudio;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class OwnerIdentityPolicyTest {
    @Test public void firstInstallMayCreateOneOwnerSecret() {
        assertEquals(
                OwnerIdentityPolicy.Decision.CREATE_INITIAL_SECRET,
                OwnerIdentityPolicy.decide(false, false)
        );
    }

    @Test public void existingEncryptedIdentityMustBeReusedWhenDecryptable() {
        assertEquals(
                OwnerIdentityPolicy.Decision.USE_DECRYPTED_SECRET,
                OwnerIdentityPolicy.decide(true, true)
        );
    }

    @Test public void decryptFailureRequiresRecoveryInsteadOfCreatingSplitIdentity() {
        assertEquals(
                OwnerIdentityPolicy.Decision.RECOVERY_REQUIRED,
                OwnerIdentityPolicy.decide(true, false)
        );
    }
}
