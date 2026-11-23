package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.app.Activity;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AbugramConfig;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;

/**
 * Xabarchi's opening: the logo pops in, the name and the address rise under it, a short chime plays, then it fades into the app.
 * Once per start of the app; a tap skips it. Settings → Xabarchi switches it off.
 */
public class AbugramSplash {
    private static boolean shown;

    public static void show(Activity activity, FrameLayout root) {
        if (shown || root == null) return;
        shown = true;
        AbugramConfig.load();
        if (!AbugramConfig.splash) return;
        try {
            build(activity, root);
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static void build(Activity activity, FrameLayout root) {
        final Context context = activity;
        final FrameLayout overlay = new FrameLayout(context);
        overlay.setBackgroundColor(0xFF0F0F17);
        overlay.setClickable(true);

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER);
        overlay.addView(column, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));

        FrameLayout logo = new FrameLayout(context);
        GradientDrawable gradient = new GradientDrawable(GradientDrawable.Orientation.BL_TR, new int[]{0xFF6A5CFF, 0xFF00D5FF});
        gradient.setCornerRadius(dp(34));
        logo.setBackground(gradient);
        ImageView mark = new ImageView(context);
        mark.setImageResource(R.drawable.abugram_icon_foreground);
        mark.setScaleType(ImageView.ScaleType.FIT_XY);
        logo.addView(mark, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        column.addView(logo, new LinearLayout.LayoutParams(dp(132), dp(132)));

        TextView name = new TextView(context);
        name.setText("Xabarchi");
        name.setTextColor(0xFFFFFFFF);
        name.setTextSize(32);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        name.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        nameParams.topMargin = dp(22);
        column.addView(name, nameParams);

        TextView address = new TextView(context);
        address.setText("xabarchi.uz");
        address.setTextColor(0xFF00D5FF);
        address.setTextSize(16);
        address.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams addressParams = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        addressParams.topMargin = dp(4);
        column.addView(address, addressParams);

        root.addView(overlay, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        logo.setAlpha(0f);
        logo.setScaleX(0.45f);
        logo.setScaleY(0.45f);
        name.setAlpha(0f);
        name.setTranslationY(dp(22));
        address.setAlpha(0f);
        address.setTranslationY(dp(22));

        logo.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(520).setInterpolator(new OvershootInterpolator(1.6f)).start();
        name.animate().alpha(1f).translationY(0).setStartDelay(330).setDuration(380).start();
        address.animate().alpha(1f).translationY(0).setStartDelay(500).setDuration(380).start();

        final Runnable[] closer = new Runnable[1];
        closer[0] = () -> {
            if (overlay.getParent() == null) return;
            overlay.setClickable(false);
            overlay.animate().alpha(0f).setDuration(320).withEndAction(() -> {
                if (overlay.getParent() instanceof ViewGroup) ((ViewGroup) overlay.getParent()).removeView(overlay);
            }).start();
        };
        overlay.setOnClickListener(v -> {
            AndroidUtilities.cancelRunOnUIThread(closer[0]);
            closer[0].run();
        });
        AndroidUtilities.runOnUIThread(closer[0], 1650);

        playChime(context);
    }

    private static void playChime(Context context) {
        if (!AbugramConfig.splashSound) return;
        try {
            AudioManager audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            // never over a silent or vibrating phone
            if (audio == null || audio.getRingerMode() != AudioManager.RINGER_MODE_NORMAL) return;
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build();
            MediaPlayer player = MediaPlayer.create(context, R.raw.xabarchi_start, attributes, AudioManager.AUDIO_SESSION_ID_GENERATE);
            if (player == null) return;
            player.setVolume(0.7f, 0.7f);
            player.setOnCompletionListener(MediaPlayer::release);
            player.start();
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }
}
