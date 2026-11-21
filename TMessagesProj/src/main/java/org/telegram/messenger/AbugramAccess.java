package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

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
        prefs().edit().putBoolean("ghost", ghost).putBoolean("admin", admin).apply();
        AbugramConfig.ghostAllowed = ghost;
        AbugramConfig.isAdmin = admin;
    }

    private static void hello(long uid) throws Exception {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        String today = format.format(new Date());
        SharedPreferences p = ApplicationLoader.applicationContext.getSharedPreferences("abugram_hello", Activity.MODE_PRIVATE);
        if (today.equals(p.getString("day" + uid, ""))) {
            return;
        }
        request("POST", AbugramProxy.RELAY + "/hello", "{\"uid\":" + uid + ",\"platform\":\"android\"}");
        p.edit().putString("day" + uid, today).apply();
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
