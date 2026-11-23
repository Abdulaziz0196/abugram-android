package org.telegram.messenger;

import java.io.InputStream;

/**
 * The Xabarchi build number: the CI run that made this APK (written to assets/abugram_build.txt before the build).
 * The same number versions the Android app and the web app, shown as "13.2.2 (12)".
 */
public class AbugramVersion {
    private static int build = -1;

    public static synchronized int build() {
        if (build >= 0) return build;
        build = 0;
        try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("abugram_build.txt")) {
            byte[] b = new byte[16];
            int n = in.read(b);
            build = Integer.parseInt(new String(b, 0, Math.max(n, 0)).trim());
        } catch (Throwable ignore) {
        }
        return build;
    }

    public static String name() {
        return BuildVars.BUILD_VERSION_STRING + " (" + build() + ")";
    }
}
