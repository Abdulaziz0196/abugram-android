package org.telegram.messenger;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.os.Build;
import android.app.PendingIntent;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.LaunchActivity;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Xabarchi updates itself, like a game does: on start the app looks for a newer build, shows its size, downloads it with a
 * progress, and hands it to Android's installer without leaving the app. The first time Android asks once to allow installs
 * from Xabarchi; the system always confirms an update that is not made by the store.
 */
public class AbugramUpdater {

    private static final String RELEASE = "https://api.github.com/repos/Abdulaziz0196/abugram-android/releases/latest";
    private static final String ACTION = "org.telegram.abugram.INSTALL_RESULT";
    private static boolean shown;
    private static volatile boolean busy;

    /** Looks for a newer build (off the main thread); asks the user when there is one. */
    public static void check(boolean forced) {
        if (shown || busy) return;
        try {
            JSONObject release = new JSONObject(get(RELEASE));
            String tag = release.optString("tag_name", "");
            int build = parseBuild(tag);
            if (build <= AbugramVersion.build()) return;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) return;
            for (int i = 0; i < assets.length(); i++) {
                JSONObject a = assets.getJSONObject(i);
                if (!a.optString("name").toLowerCase(Locale.US).endsWith(".apk")) continue;
                SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences("abugram_update", Activity.MODE_PRIVATE);
                if (!forced && p.getInt("skipped", 0) == build) return;
                shown = true;
                final String name = release.optString("name", "Xabarchi uz");
                prompt(build, name, a.optString("browser_download_url"), a.optLong("size"), a.optString("digest", ""), forced);
                return;
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static int parseBuild(String tag) {
        int dash = tag.lastIndexOf('-');
        try {
            return Integer.parseInt(tag.substring(dash + 1).trim());
        } catch (Throwable e) {
            return 0;
        }
    }

    private static void prompt(int build, String name, String url, long size, String digest, boolean forced) {
        AndroidUtilities.runOnUIThread(() -> {
            LaunchActivity activity = LaunchActivity.instance;
            if (activity == null) {
                shown = false;
                return;
            }
            AlertDialog.Builder b = new AlertDialog.Builder(activity);
            b.setTitle("Yangi versiya chiqdi");
            b.setMessage(name + "\nHajmi: " + megabytes(size) + "\n\nIlova ichidan yangilanadi, brauzerga chiqmaysiz.");
            b.setPositiveButton("Yangilash", (d, w) -> start(activity, build, url, size, digest));
            if (!forced) {
                b.setNegativeButton("Keyinroq", (d, w) -> activity.getSharedPreferences("abugram_update", Activity.MODE_PRIVATE).edit().putInt("skipped", build).apply());
            }
            AlertDialog dialog = b.create();
            if (forced) {
                dialog.setCanCancel(false);
                dialog.setCanceledOnTouchOutside(false);
            }
            dialog.show();
        });
    }

    private static String megabytes(long bytes) {
        return String.format(Locale.US, "%.1f MB", bytes / 1048576.0);
    }

    private static void start(Activity activity, int build, String url, long size, String digest) {
        busy = true;
        final AlertDialog progress = new AlertDialog.Builder(activity).setTitle("Yuklanmoqda").setMessage("0 / " + megabytes(size)).create();
        progress.setCanCancel(false);
        progress.setCanceledOnTouchOutside(false);
        progress.show();
        Utilities.globalQueue.postRunnable(() -> {
            try {
                File dir = new File(ApplicationLoader.applicationContext.getCacheDir(), "updates");
                if (!dir.exists()) dir.mkdirs();
                File[] old = dir.listFiles();
                if (old != null) for (File f : old) f.delete();
                File apk = new File(dir, "xabarchi-" + build + ".apk");
                download(url, apk, size, (done, total) -> AndroidUtilities.runOnUIThread(() -> progress.setMessage(megabytes(done) + " / " + megabytes(total))));
                if (size > 0 && apk.length() != size) throw new Exception("size " + apk.length() + " != " + size);
                if (digest.startsWith("sha256:") && !digest.substring(7).equalsIgnoreCase(sha256(apk))) throw new Exception("checksum");
                AndroidUtilities.runOnUIThread(() -> progress.setMessage("O'rnatilmoqda…"));
                install(apk);
            } catch (Throwable e) {
                FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    progress.dismiss();
                    toast("Yangilab bo'lmadi. Keyinroq qayta urinib ko'ring.");
                });
                shown = false;
            } finally {
                busy = false;
            }
        });
    }

    private interface Progress {
        void on(long done, long total);
    }

    private static void download(String address, File out, long expected, Progress progress) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(20000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            long total = c.getContentLengthLong() > 0 ? c.getContentLengthLong() : expected;
            try (InputStream in = c.getInputStream(); OutputStream o = new FileOutputStream(out)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0, lastReport = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    o.write(buf, 0, n);
                    done += n;
                    if (done - lastReport > 512 * 1024) {
                        lastReport = done;
                        progress.on(done, total);
                    }
                }
                progress.on(done, total);
            }
        } finally {
            c.disconnect();
        }
    }

    private static String sha256(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        StringBuilder sb = new StringBuilder();
        for (byte x : md.digest()) sb.append(String.format("%02x", x));
        return sb.toString();
    }

    private static void install(File apk) throws Exception {
        final Context context = ApplicationLoader.applicationContext;
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        PackageInstaller.SessionParams params = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
        if (Build.VERSION.SDK_INT >= 31) {
            // later updates made by this app need no extra tap where Android allows it
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
        }
        int id = installer.createSession(params);
        try (PackageInstaller.Session session = installer.openSession(id)) {
            try (InputStream in = new FileInputStream(apk); OutputStream out = session.openWrite("xabarchi.apk", 0, apk.length())) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                session.fsync(out);
            }
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    int status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1);
                    if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
                        Intent confirm = intent.getParcelableExtra(Intent.EXTRA_INTENT);
                        if (confirm != null) {
                            confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            ctx.startActivity(confirm);
                        }
                        return;
                    }
                    try {
                        ctx.unregisterReceiver(this);
                    } catch (Throwable ignore) {
                    }
                    if (status != PackageInstaller.STATUS_SUCCESS) {
                        shown = false;
                        toast("O'rnatilmadi (" + intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) + ")");
                    }
                }
            };
            IntentFilter filter = new IntentFilter(ACTION);
            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else {
                context.registerReceiver(receiver, filter);
            }
            Intent intent = new Intent(ACTION).setPackage(context.getPackageName());
            int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0);
            PendingIntent pending = PendingIntent.getBroadcast(context, id, intent, flags);
            session.commit(pending.getIntentSender());
        }
    }

    private static void toast(String text) {
        AndroidUtilities.runOnUIThread(() -> android.widget.Toast.makeText(ApplicationLoader.applicationContext, text, android.widget.Toast.LENGTH_LONG).show());
    }

    private static String get(String address) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "Xabarchi-Android");
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            try (InputStream in = c.getInputStream()) {
                java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
