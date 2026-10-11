package com.rezoxnemesis.videostudio;

/**
 * Fail-closed update rules: this is only a preflight, never a way around
 * Android's package signing enforcement.
 */
public final class UpdateCompatibility {
    private UpdateCompatibility() {}

    public static String rejection(String installedPackage, String installedSigner,
                                   long installedVersionCode,
                                   String candidatePackage, String candidateSigner,
                                   long candidateVersionCode) {
        if (installedPackage == null || candidatePackage == null
                || !installedPackage.equals(candidatePackage)) {
            return "Different Android package ID: install alongside; cannot replace the existing app.";
        }
        if (installedSigner == null || candidateSigner == null
                || installedSigner.trim().isEmpty() || candidateSigner.trim().isEmpty()
                || !installedSigner.equalsIgnoreCase(candidateSigner)) {
            return "Different Android signing certificate. Do not uninstall: migrate projects"
                    + " into the separately signed VideoStudio Next application.";
        }
        if (candidateVersionCode <= installedVersionCode) {
            return "Not a newer APK. An update requires a larger Android versionCode.";
        }
        return "";
    }

    public static boolean canReplace(String installedPackage, String installedSigner,
                                     long installedVersionCode,
                                     String candidatePackage, String candidateSigner,
                                     long candidateVersionCode) {
        return rejection(installedPackage, installedSigner, installedVersionCode,
                candidatePackage, candidateSigner, candidateVersionCode).isEmpty();
    }
}
