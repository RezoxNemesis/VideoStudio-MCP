package com.rezoxnemesis.videostudio;

/** Pure policy for owner-credential loading. Never rotate an existing identity silently. */
public final class OwnerIdentityPolicy {
    public enum Decision {
        USE_DECRYPTED_SECRET,
        CREATE_INITIAL_SECRET,
        RECOVERY_REQUIRED
    }

    private OwnerIdentityPolicy() {}

    public static Decision decide(boolean encryptedSecretPresent, boolean decryptSucceeded) {
        if (!encryptedSecretPresent) return Decision.CREATE_INITIAL_SECRET;
        return decryptSucceeded ? Decision.USE_DECRYPTED_SECRET : Decision.RECOVERY_REQUIRED;
    }
}
