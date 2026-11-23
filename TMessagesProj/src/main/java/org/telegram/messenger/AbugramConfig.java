package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;

import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.tgnet.tl.TL_stories;

/**
 * Abugram feature switches. Everything is off by default except hiding sponsored messages, and is changed
 * from Settings → Abugram. The privacy switches work by holding back the matching requests on this
 * device only (see {@link #filterRequest}); nothing is sent to anyone on the user's behalf.
 */
public class AbugramConfig {

    public static boolean ghostRead;
    public static boolean ghostTyping;
    public static boolean ghostOnline;
    public static boolean ghostStories;
    public static boolean keepDeleted;
    /** Telegram's API terms ask clients to show its sponsored messages, so they are never held back. */
    public static boolean hideSponsored = false;
    /** Set from the Abugram relay (/access): whether the Abugram admin allowed this account ghost mode / the admin page. */
    public static boolean ghostAllowed;
    public static boolean isAdmin;
    public static boolean hideStories;
    public static boolean showIds;
    public static boolean showSeconds = true;
    public static boolean confirmCalls = true;

    private static boolean loaded;

    private static SharedPreferences prefs() {
        return ApplicationLoader.applicationContext.getSharedPreferences("abugram_config", Activity.MODE_PRIVATE);
    }

    public static synchronized void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        SharedPreferences p = prefs();
        ghostRead = p.getBoolean("ghostRead", false);
        ghostTyping = p.getBoolean("ghostTyping", false);
        ghostOnline = p.getBoolean("ghostOnline", false);
        ghostStories = p.getBoolean("ghostStories", false);
        keepDeleted = p.getBoolean("keepDeleted", false);
        hideSponsored = false;
        SharedPreferences a = ApplicationLoader.applicationContext.getSharedPreferences("abugram_access", Activity.MODE_PRIVATE);
        ghostAllowed = a.getBoolean("ghost", false);
        isAdmin = a.getBoolean("admin", false);
        hideStories = p.getBoolean("hideStories", false);
        showIds = p.getBoolean("showIds", false);
        showSeconds = p.getBoolean("showSeconds", true);
        confirmCalls = p.getBoolean("confirmCalls", true);
    }

    public static synchronized void save() {
        prefs().edit()
                .putBoolean("ghostRead", ghostRead)
                .putBoolean("ghostTyping", ghostTyping)
                .putBoolean("ghostOnline", ghostOnline)
                .putBoolean("ghostStories", ghostStories)
                .putBoolean("keepDeleted", keepDeleted)
                .putBoolean("hideStories", hideStories)
                .putBoolean("showIds", showIds)
                .putBoolean("showSeconds", showSeconds)
                .putBoolean("confirmCalls", confirmCalls)
                .apply();
    }

    /**
     * Called for every request before it is sent. Returns true when the request must not be sent at all.
     * May adjust the request in place (going online is turned into staying offline).
     */
    /** Keeping deleted messages is one of the extra features: only for accounts the Abugram admin allowed. */
    public static boolean keepDeletedActive() {
        return keepDeleted && ghostAllowed;
    }

    public static boolean filterRequest(TLObject object) {
        load();
        boolean ghost = ghostAllowed;
        if (ghost && ghostRead && (object instanceof TLRPC.TL_messages_readHistory
                || object instanceof TLRPC.TL_channels_readHistory
                || object instanceof TLRPC.TL_messages_readDiscussion
                || object instanceof TLRPC.TL_messages_readMessageContents
                || object instanceof TLRPC.TL_channels_readMessageContents)) {
            return true;
        }
        if (ghost && ghostTyping && object instanceof TLRPC.TL_messages_setTyping) {
            return true;
        }
        if (ghost && ghostOnline && object instanceof TL_account.updateStatus) {
            ((TL_account.updateStatus) object).offline = true;
            return false;
        }
        if (ghost && ghostStories && (object instanceof TL_stories.TL_stories_readStories
                || object instanceof TL_stories.TL_stories_incrementStoryViews)) {
            return true;
        }
        return false;
    }
}
