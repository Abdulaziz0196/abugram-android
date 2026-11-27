package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Abugram: asks the Abugram relay what this account may use (ghost mode, the admin page) and tells it, once a day,
 * that the account is an Abugram user, for the user counts shown to advertisers. The relay keeps only a hash of the
 * Telegram ID. Without the relay (offline, blocked) the answer from the last time is used; the default is nothing.
 */
public class AbugramAccess {

    private static final int RETRY_SECONDS = 30;
    private static final int MAX_RETRIES = 20;
    private static final long REFRESH_MS = 6L * 3600 * 1000;
    private static boolean started;

    public static synchronized void start() {
        if (started) {
            return;
        }
        started = true;
        Utilities.globalQueue.postRunnable(() -> run(0), 5000);
    }

    private static void run(int attempt) {
        long uid = 0;
        for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
            UserConfig config = UserConfig.getInstance(a);
            if (config.isClientActivated()) {
                uid = config.getClientUserId();
                break;
            }
        }
        if (uid <= 0) {
            // not logged in yet: look again shortly, a few times, then wait for the next start
            if (attempt < MAX_RETRIES) {
                Utilities.globalQueue.postRunnable(() -> run(attempt + 1), RETRY_SECONDS * 1000L);
            }
            return;
        }
        try {
            fetchAccess(uid);
            hello(uid);
            checkGate(uid);
            AbugramUpdater.check(false);
        } catch (Throwable e) {
            FileLog.e(e);
        }
        Utilities.globalQueue.postRunnable(() -> run(0), REFRESH_MS);
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("abugram_access", Activity.MODE_PRIVATE);
    }

    private static void fetchAccess(long uid) throws Exception {
        String body = request("GET", AbugramProxy.RELAY + "/access?uid=" + uid, null);
        JSONObject json = new JSONObject(body);
        boolean ghost = json.optBoolean("ghost", false);
        boolean admin = json.optBoolean("admin", false);
        boolean pro = json.optBoolean("pro", false);
        prefs().edit().putBoolean("ghost", ghost).putBoolean("admin", admin).putBoolean("pro", pro).apply();
        AbugramConfig.ghostAllowed = ghost;
        AbugramConfig.isAdmin = admin;
    }

    private static void hello(long uid) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String today = format.format(new Date());
        SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences("abugram_hello", Activity.MODE_PRIVATE);
        String version = AbugramVersion.name();
        if (today.equals(p.getString("day" + uid, "")) && version.equals(p.getString("ver" + uid, ""))) {
            return;
        }
        request("POST", AbugramProxy.RELAY + "/hello", "{\"uid\":" + uid + ",\"platform\":\"android\",\"version\":\"" + version + "\"}");
        p.edit().putString("day" + uid, today).putString("ver" + uid, version).apply();
    }

    private static boolean gateShown;

    /**
     * What the Abugram admin set in the relay's /config: a minimum build (an update is required) or paid mode (accounts
     * without a paid period, and not admin, must pay). Both are off by default: nothing is shown while the app is free.
     */
    private static void checkGate(long uid) throws Exception {
        JSONObject c = new JSONObject(request("GET", AbugramProxy.RELAY + "/config", null));
        JSONArray offList = c.optJSONArray("off");
        if (offList != null) {
            java.util.ArrayList<String> off = new java.util.ArrayList<>();
            for (int i = 0; i < offList.length(); i++) {
                off.add(offList.optString(i));
            }
            AbugramConfig.setFeaturesOff(off);
        }
        JSONArray relayList = c.optJSONArray("relays");
        if (relayList != null) {
            java.util.ArrayList<String> relays = new java.util.ArrayList<>();
            for (int i = 0; i < relayList.length() && i < 10; i++) {
                relays.add(relayList.optString(i));
            }
            AbugramProxy.setRelays(relays);
        }
        int minBuild = c.optInt("minBuild", 0);
        boolean paid = c.optBoolean("paid", false);
        boolean pro = prefs().getBoolean("pro", false) || AbugramConfig.isAdmin;
        boolean update = minBuild > 0 && AbugramVersion.build() < minBuild;
        boolean pay = paid && !pro;
        if (gateShown || !(update || pay)) {
            return;
        }
        final String title = c.optString(update ? "updateTitle" : "payTitle", update ? "Yangilash kerak" : "Obuna kerak");
        final String message = c.optString(update ? "updateMessage" : "payMessage", "");
        if (update) {
            AbugramUpdater.check(true);
            return;
        }
        gateShown = true;
        gatePaid = false;
        final String payUrl = PAY_PAGE + uid;
        showGate(title, message, payUrl);
        watchGate(uid, 0);
    }

    private static final String PAY_PAGE = "https://abdulaziz0196.github.io/tashlanov.ru/abugram/pay.html?uid=";
    private static volatile boolean gatePaid;
    private static volatile org.telegram.ui.ActionBar.AlertDialog gateDialog;

    /** The payment notice: it opens the pay page, and comes back if it is closed before the account has paid. */
    private static void showGate(String title, String message, String url) {
        AndroidUtilities.runOnUIThread(() -> {
            if (gatePaid) return;
            org.telegram.ui.LaunchActivity activity = org.telegram.ui.LaunchActivity.instance;
            if (activity == null) {
                AndroidUtilities.runOnUIThread(() -> showGate(title, message, url), 3000);
                return;
            }
            org.telegram.ui.ActionBar.AlertDialog.Builder b = new org.telegram.ui.ActionBar.AlertDialog.Builder(activity);
            b.setTitle(title);
            b.setMessage(message);
            b.setPositiveButton("To'lash", (d, w) -> org.telegram.messenger.browser.Browser.openUrl(activity, url));
            org.telegram.ui.ActionBar.AlertDialog dialog = b.create();
            dialog.setCanCancel(false);
            dialog.setCanceledOnTouchOutside(false);
            dialog.setOnDismissListener(d -> {
                if (!gatePaid) {
                    AndroidUtilities.runOnUIThread(() -> showGate(title, message, url), 800);
                }
            });
            gateDialog = dialog;
            dialog.show();
        });
    }

    /** While the notice is up, looks every 5 seconds whether the account has paid (or the admin switched paid mode off). */
    private static void watchGate(long uid, int round) {
        Utilities.globalQueue.postRunnable(() -> {
            if (gatePaid || round > 720) return;
            try {
                fetchAccess(uid);
                JSONObject c = new JSONObject(request("GET", AbugramProxy.RELAY + "/config", null));
                boolean pro = prefs().getBoolean("pro", false) || AbugramConfig.isAdmin;
                if (pro || !c.optBoolean("paid", false)) {
                    gatePaid = true;
                    gateShown = false;
                    AndroidUtilities.runOnUIThread(() -> {
                        org.telegram.ui.ActionBar.AlertDialog dialog = gateDialog;
                        if (dialog != null) dialog.dismiss();
                    });
                    return;
                }
            } catch (Throwable e) {
                FileLog.e(e);
            }
            watchGate(uid, round + 1);
        }, 5000);
    }

    private static String request(String method, String address, String json) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(address).openConnection();
        try {
            c.setRequestMethod(method);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            if (json != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/json");
                byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
                c.setFixedLengthStreamingMode(bytes.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(bytes);
                }
            }
            if (c.getResponseCode() != 200) {
                throw new Exception("relay answered " + c.getResponseCode());
            }
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return out.toString("UTF-8");
            }
        } finally {
            c.disconnect();
        }
    }
}
