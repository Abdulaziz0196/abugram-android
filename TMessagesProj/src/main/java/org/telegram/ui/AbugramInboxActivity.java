package org.telegram.ui;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AbugramInbox;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.Date;
import java.util.List;

/** "Xabarchi uz" service chat: messages sent from the admin panel, shown like a conversation. */
public class AbugramInboxActivity extends BaseFragment {

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(org.telegram.messenger.R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Xabarchi uz");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) finishFragment();
            }
        });

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundWhite));
        fragmentView = root;

        ScrollView scroll = new ScrollView(context);
        LinearLayout col = new LinearLayout(context);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(10), AndroidUtilities.dp(16));
        scroll.addView(col, new FrameLayout.LayoutParams(-1, -2));
        root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));

        List<AbugramInbox.Msg> list = AbugramInbox.list();
        int text = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        int bubbleColor = getThemedColor(Theme.key_chat_inBubble);
        if (list.isEmpty()) {
            TextView t = new TextView(context);
            t.setText("Hozircha xabar yo'q");
            t.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            t.setGravity(Gravity.CENTER);
            t.setPadding(0, AndroidUtilities.dp(60), 0, 0);
            col.addView(t);
        }
        for (AbugramInbox.Msg m : list) {
            LinearLayout b = new LinearLayout(context);
            b.setOrientation(LinearLayout.VERTICAL);
            b.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(8), AndroidUtilities.dp(12), AndroidUtilities.dp(8));
            GradientDrawable bg = new GradientDrawable();
            bg.setColor(bubbleColor);
            bg.setCornerRadius(AndroidUtilities.dp(14));
            bg.setStroke(AndroidUtilities.dp(1), Color.argb(40, 128, 128, 128));
            b.setBackground(bg);
            if (m.title != null && !m.title.isEmpty()) {
                TextView title = new TextView(context);
                title.setText(m.title);
                title.setTextColor(getThemedColor(Theme.key_chat_messageLinkIn));
                title.setTypeface(Typeface.DEFAULT_BOLD);
                title.setTextSize(15);
                b.addView(title);
            }
            TextView body = new TextView(context);
            body.setText(m.body);
            body.setTextColor(text);
            body.setTextSize(16);
            body.setLinksClickable(true);
            android.text.util.Linkify.addLinks(body, android.text.util.Linkify.WEB_URLS);
            b.addView(body);
            if (m.url != null && !m.url.isEmpty()) {
                TextView btn = new TextView(context);
                btn.setText(m.btn != null && !m.btn.isEmpty() ? m.btn : "Ochish");
                btn.setTextColor(Color.WHITE);
                btn.setTypeface(Typeface.DEFAULT_BOLD);
                btn.setGravity(Gravity.CENTER);
                btn.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8), AndroidUtilities.dp(14), AndroidUtilities.dp(8));
                GradientDrawable bb = new GradientDrawable();
                bb.setColor(getThemedColor(Theme.key_featuredStickers_addButton));
                bb.setCornerRadius(AndroidUtilities.dp(10));
                btn.setBackground(bb);
                final String url = m.url;
                btn.setOnClickListener(v -> Browser.openUrl(getParentActivity(), url));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
                lp.topMargin = AndroidUtilities.dp(8);
                b.addView(btn, lp);
            }
            TextView time = new TextView(context);
            long ms = m.created < 100000000000L ? m.created * 1000L : m.created;
            time.setText(DateFormat.format("dd.MM HH:mm", new Date(ms)));
            time.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
            time.setTextSize(11);
            time.setGravity(Gravity.END);
            b.addView(time, new LinearLayout.LayoutParams(-1, -2));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-2, -2);
            lp.topMargin = AndroidUtilities.dp(6);
            lp.rightMargin = AndroidUtilities.dp(40);
            col.addView(b, lp);
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
        AbugramInbox.markRead();
        return fragmentView;
    }
}
