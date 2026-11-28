package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Messages from the Xabarchi admin (sent from the admin panel to everybody or to chosen accounts): fetched from the relay
 * (/inbox), kept on the phone, shown under the "Xabarchi uz" card at the top of the chat list.
 */
public class AbugramInbox {

    public static class Msg {
        public long id;
        public String title, body, url, btn;
        public long created;
    }

    public static final long DIALOG_ID = 8000000001L;

    private static final String PREFS = "abugram_inbox";
    private static final int KEEP = 100;
    private static final ArrayList<Msg> messages = new ArrayList<>();
    private static boolean loaded;
    private static long readUpTo;
    private static volatile Runnable listener;
    private static boolean polling;

    public static void setListener(Runnable l) {
        listener = l;
    }

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
    }

    private static synchronized void load() {
        if (loaded) return;
        loaded = true;
        try {
            SharedPreferences p = prefs();
            readUpTo = p.getLong("read", 0);
            JSONArray a = new JSONArray(p.getString("list", "[]"));
            for (int i = 0; i < a.length(); i++) {
                messages.add(parse(a.getJSONObject(i)));
            }
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static Msg parse(JSONObject o) {
        Msg m = new Msg();
        m.id = o.optLong("id");
        m.title = o.optString("title");
        m.body = o.optString("body");
        m.url = o.optString("url");
        m.btn = o.optString("btn");
        m.created = o.optLong("created");
        return m;
    }

    public static synchronized List<Msg> list() {
        load();
        return new ArrayList<>(messages);
    }

    public static synchronized int unread() {
        load();
        int n = 0;
        for (Msg m : messages) if (m.id > readUpTo) n++;
        return n;
    }

    public static synchronized void markRead() {
        load();
        long max = readUpTo;
        for (Msg m : messages) max = Math.max(max, m.id);
        if (max != readUpTo) {
            readUpTo = max;
            prefs().edit().putLong("read", readUpTo).apply();
        }
        notifyChanged();
    }

    private static void notifyChanged() {
        Runnable l = listener;
        AndroidUtilities.runOnUIThread(() -> {
            try {
                MessagesController mc = MessagesController.getInstance(UserConfig.selectedAccount);
                mc.sortDialogs(null);
                NotificationCenter.getInstance(UserConfig.selectedAccount).postNotificationName(NotificationCenter.dialogsNeedReload);
            } catch (Throwable e) {
                FileLog.e(e);
            }
            if (l != null) l.run();
        });
    }

    /** Asks the relay for messages newer than the last one known. */
    public static void refresh(long uid) {
        if (uid <= 0 || !AbugramConfig.featureOn("inbox")) return;
        try {
            long after = 0;
            synchronized (AbugramInbox.class) {
                load();
                for (Msg m : messages) after = Math.max(after, m.id);
            }
            JSONObject json = new JSONObject(AbugramAccess.request("GET", AbugramProxy.RELAY + "/inbox?uid=" + uid + "&after=" + after, null));
            JSONArray a = json.optJSONArray("messages");
            if (a == null || a.length() == 0) return;
            synchronized (AbugramInbox.class) {
                for (int i = 0; i < a.length(); i++) {
                    Msg m = parse(a.getJSONObject(i));
                    boolean have = false;
                    for (Msg x : messages) if (x.id == m.id) have = true;
                    if (!have) messages.add(m);
                }
                while (messages.size() > KEEP) messages.remove(0);
                JSONArray out = new JSONArray();
                for (Msg m : messages) {
                    JSONObject o = new JSONObject();
                    o.put("id", m.id).put("title", m.title).put("body", m.body).put("url", m.url).put("btn", m.btn).put("created", m.created);
                    out.put(o);
                }
                prefs().edit().putString("list", out.toString()).apply();
            }
            notifyChanged();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    /** Looks for new messages every 5 minutes while the app runs. */
    public static synchronized void startPolling() {
        if (polling) return;
        polling = true;
        Utilities.globalQueue.postRunnable(AbugramInbox::pollTick, 8000);
    }

    private static void pollTick() {
        refresh(AbugramAccess.currentUid);
        Utilities.globalQueue.postRunnable(AbugramInbox::pollTick, 5 * 60 * 1000);
    }
}
