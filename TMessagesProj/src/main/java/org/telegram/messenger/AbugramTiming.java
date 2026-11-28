package org.telegram.messenger;

import android.os.SystemClock;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Abugram: how long a sent message took to reach Telegram, shown next to its time (for media: voice, video, photos, files).
 * The clock starts when the sending message first appears in the chat and stops when the server confirms it.
 */
public class AbugramTiming {
    private static final int KEEP = 300;
    private static final ConcurrentHashMap<String, Long> started = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Long> took = new ConcurrentHashMap<>();

    private static String key(long dialogId, int id) {
        return dialogId + ":" + id;
    }

    /** A message that is still being sent (its id is temporary, negative) was shown. */
    public static void seen(long dialogId, int id) {
        if (id >= 0) return;
        String k = key(dialogId, id);
        if (!started.containsKey(k)) {
            if (started.size() > KEEP) started.clear();
            started.put(k, SystemClock.elapsedRealtime());
        }
    }

    /** The server confirmed the message: it now has its real id. */
    public static void sent(long dialogId, int oldId, int newId) {
        Long s = started.remove(key(dialogId, oldId));
        if (s == null) return;
        if (took.size() > KEEP) took.clear();
        took.put(key(dialogId, newId), SystemClock.elapsedRealtime() - s);
    }

    /** Milliseconds the message took, or 0 when unknown. */
    public static long get(long dialogId, int id) {
        Long t = took.get(key(dialogId, id));
        return t == null ? 0 : t;
    }

    public static String format(long millis) {
        if (millis < 1000) return millis + " ms";
        return String.format(java.util.Locale.US, "%.1f s", millis / 1000f);
    }

    private static final ConcurrentHashMap<String, long[]> transfers = new ConcurrentHashMap<>();

    /**
     * How long a running upload or download of a large file still takes, from its speed so far, as " · 12 s" (or null while
     * there is too little to tell). The key names the message; the measure starts at the first call.
     */
    public static String eta(String key, long loaded, long total) {
        long now = SystemClock.elapsedRealtime();
        long[] t = transfers.get(key);
        if (t == null || loaded < t[1]) {
            if (transfers.size() > KEEP) transfers.clear();
            transfers.put(key, new long[]{now, loaded});
            return null;
        }
        if (loaded >= total) {
            transfers.remove(key);
            return null;
        }
        long elapsed = now - t[0];
        long done = loaded - t[1];
        if (elapsed < 1500 || done <= 0) return null;
        long remaining = (long) ((total - loaded) / (done / (double) elapsed)); // milliseconds
        long sec = Math.max(1, remaining / 1000);
        if (sec < 60) return " · " + sec + " s";
        if (sec < 3600) return " · " + (sec / 60) + " min " + (sec % 60) + " s";
        return " · " + (sec / 3600) + " soat " + ((sec % 3600) / 60) + " min";
    }
}
