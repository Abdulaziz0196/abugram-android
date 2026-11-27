package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.text.util.Linkify;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AbugramConfig;
import org.telegram.messenger.AbugramInbox;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ChatObject;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.TLObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.DialogsActivity;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Two cards at the top of the chat list: "Xabarchi uz" (the admin's messages, with an unread count) and the recommended
 * channel (a Join button until the account is in it, Open after). Both sit in the list's top panel, so the list starts below.
 */
public class AbugramHomeCards {
    private final DialogsActivity fragment;
    private final Context context;
    private final int account;
    private final DialogsActivityTopPanelLayout panel;
    private FrameLayout inboxRow, promoRow;
    private TextView inboxSub, inboxBadge, promoTitle, promoSub, promoButton;
    private boolean resolving;

    public static AbugramHomeCards attach(DialogsActivity fragment, Context context, int account, DialogsActivityTopPanelLayout panel) {
        AbugramHomeCards cards = new AbugramHomeCards(fragment, context, account, panel);
        cards.build();
        return cards;
    }

    private AbugramHomeCards(DialogsActivity fragment, Context context, int account, DialogsActivityTopPanelLayout panel) {
        this.fragment = fragment;
        this.context = context;
        this.account = account;
        this.panel = panel;
    }

    private FrameLayout row(String title, TextView[] titleOut, TextView[] subOut) {
        FrameLayout row = new FrameLayout(context);
        FrameLayout logo = new FrameLayout(context);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.BL_TR, new int[]{0xFF6A5CFF, 0xFF00D5FF});
        gradient.setCornerRadius(dp(12));
        logo.setBackground(gradient);
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.abugram_icon_foreground);
        mark.setScaleType(ImageView.ScaleType.FIT_XY);
        logo.addView(mark, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        row.addView(logo, LayoutHelper.createFrame(44, 44, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        LinearLayout texts = new LinearLayout(context);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView t = new TextView(context);
        t.setText(title);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        t.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        TextView s = new TextView(context);
        s.setTextSize(14);
        s.setSingleLine(true);
        s.setEllipsize(TextUtils.TruncateAt.END);
        s.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        texts.addView(t);
        texts.addView(s);
        row.addView(texts, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL, 68, 0, 96, 0));
        titleOut[0] = t;
        subOut[0] = s;
        return row;
    }

    private TextView pill(String text, int color) {
        TextView p = new TextView(context);
        p.setText(text);
        p.setTextSize(13);
        p.setTypeface(Typeface.DEFAULT_BOLD);
        p.setTextColor(0xFFFFFFFF);
        p.setGravity(Gravity.CENTER);
        p.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(dp(16));
        p.setBackground(bg);
        return p;
    }

    private void build() {
        // the admin's messages
        TextView[] t = new TextView[1], s = new TextView[1];
        inboxRow = row("Xabarchi uz", t, s);
        inboxSub = s[0];
        inboxBadge = pill("0", 0xFF6A5CFF);
        inboxRow.addView(inboxBadge, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        inboxRow.setOnClickListener(v -> openInbox());
        panel.addView(inboxRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));
        panel.setPriority(inboxRow, 60);
        panel.setDebugName(inboxRow, "abugram inbox");

        // the recommended channel
        promoRow = row("", t, s);
        promoTitle = t[0];
        promoSub = s[0];
        promoButton = pill("Qo'shilish", 0xFF6A5CFF);
        promoRow.addView(promoButton, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL, 0, 0, 12, 0));
        promoRow.setOnClickListener(v -> MessagesController.getInstance(account).openByUserName(AbugramConfig.promoUser, fragment, 1));
        panel.addView(promoRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 60));
        panel.setPriority(promoRow, 61);
        panel.setDebugName(promoRow, "abugram promo");

        AbugramInbox.setListener(this::update);
        update();
    }

    /** Fills the cards from what is known now; looks up the channel once if it is not known yet. */
    public void update() {
        boolean inboxOn = AbugramConfig.featureOn("inbox");
        List<AbugramInbox.Msg> list = AbugramInbox.list();
        int unread = AbugramInbox.unread();
        if (list.isEmpty()) {
            inboxSub.setText("Yangiliklar va xabarlar");
        } else {
            AbugramInbox.Msg last = list.get(list.size() - 1);
            String preview = (last.title.isEmpty() ? last.body : last.title + ": " + last.body).replace('\n', ' ');
            inboxSub.setText(preview);
        }
        inboxBadge.setVisibility(unread > 0 ? View.VISIBLE : View.GONE);
        inboxBadge.setText(String.valueOf(unread));
        panel.setViewVisible(inboxRow, inboxOn, true);

        boolean promoOn = AbugramConfig.featureOn("promo") && !AbugramConfig.promoUser.isEmpty();
        promoTitle.setText(AbugramConfig.promoTitle);
        promoSub.setText(AbugramConfig.promoText);
        if (promoOn) {
            TLObject object = MessagesController.getInstance(account).getUserOrChat(AbugramConfig.promoUser);
            if (object instanceof TLRPC.Chat) {
                boolean joined = ChatObject.isInChat((TLRPC.Chat) object);
                promoButton.setText(joined ? "Ochish" : "Qo'shilish");
            } else {
                promoButton.setText("Qo'shilish");
                resolve();
            }
        }
        panel.setViewVisible(promoRow, promoOn, true);
    }

    private void resolve() {
        if (resolving) return;
        resolving = true;
        try {
            TLRPC.TL_contacts_resolveUsername req = new TLRPC.TL_contacts_resolveUsername();
            req.username = AbugramConfig.promoUser;
            ConnectionsManager.getInstance(account).sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                resolving = false;
                if (response instanceof TLRPC.TL_contacts_resolvedPeer) {
                    TLRPC.TL_contacts_resolvedPeer res = (TLRPC.TL_contacts_resolvedPeer) response;
                    MessagesController.getInstance(account).putUsers(res.users, false);
                    MessagesController.getInstance(account).putChats(res.chats, false);
                    update();
                }
            }));
        } catch (Throwable e) {
            resolving = false;
            FileLog.e(e);
        }
    }

    private void openInbox() {
        List<AbugramInbox.Msg> list = AbugramInbox.list();
        ScrollView scroll = new ScrollView(context);
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(dp(20), dp(4), dp(20), dp(4));
        scroll.addView(col);
        if (list.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("Hozircha xabar yo'q.");
            empty.setTextSize(15);
            empty.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
            col.addView(empty);
        }
        SimpleDateFormat format = new SimpleDateFormat("dd.MM HH:mm", Locale.getDefault());
        AlertDialog[] dialogRef = new AlertDialog[1];
        for (AbugramInbox.Msg m : list) {
            LinearLayout bubble = new LinearLayout(context);
            bubble.setOrientation(LinearLayout.VERTICAL);
            bubble.setPadding(dp(12), dp(10), dp(12), dp(10));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(Theme.getColor(Theme.key_chat_inBubble));
            bg.setCornerRadius(dp(14));
            bubble.setBackground(bg);
            if (!m.title.isEmpty()) {
                TextView title = new TextView(context);
                title.setText(m.title);
                title.setTextSize(15);
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
                bubble.addView(title);
            }
            TextView body = new TextView(context);
            body.setText(m.body);
            body.setTextSize(15);
            body.setAutoLinkMask(Linkify.WEB_URLS);
            body.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            bubble.addView(body);
            TextView time = new TextView(context);
            time.setText(format.format(new Date(m.created)));
            time.setTextSize(11);
            time.setTextColor(Theme.getColor(Theme.key_dialogTextGray2));
            time.setGravity(Gravity.RIGHT);
            bubble.addView(time);
            if (!m.url.isEmpty()) {
                TextView button = pill(m.btn.isEmpty() ? "Ochish" : m.btn, 0xFF6A5CFF);
                button.setOnClickListener(v -> {
                    if (dialogRef[0] != null) dialogRef[0].dismiss();
                    Browser.openUrl(context, m.url);
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(8);
                bubble.addView(button, lp);
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            col.addView(bubble, lp);
        }
        AlertDialog.Builder b = new AlertDialog.Builder(context);
        b.setTitle("Xabarchi uz");
        b.setView(scroll);
        b.setPositiveButton("Yopish", (d, w) -> {});
        AlertDialog dialog = b.create();
        dialogRef[0] = dialog;
        fragment.showDialog(dialog);
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        AbugramInbox.markRead();
    }
}
