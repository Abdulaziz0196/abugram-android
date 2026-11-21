package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Outline;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AbugramProxy;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.ui.ActionBar.Theme;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Abugram's own ad card, shown at the top of a chat (private chats, groups, channels and bots) for as long as its
 * campaign runs. The relay (/ads) decides which ads are running; each showing is a view (the account is sent so the
 * relay can count unique viewers, keeping only a hash), a tap is counted through /go/&lt;id&gt; and opens the link.
 */
public class AbugramAdCard extends FrameLayout {

    /** The height the chat reserves for the card, in dp (the card and its margins). */
    public static final int RESERVED_DP = 84;
    public static final int CARD_DP = 76;

    private static final long CACHE_MS = 60_000;
    private static String cachedJson;
    private static long cachedAt;

    private final TextView labelView;
    private final TextView titleView;
    private final TextView textView;
    private final ImageView imageView;
    private final Runnable onVisibilityChanged;
    private long adId;
    private String adUrl;

    public AbugramAdCard(Context context, Runnable onVisibilityChanged) {
        super(context);
        this.onVisibilityChanged = onVisibilityChanged;
        setVisibility(GONE);

        setBackground(Theme.createRoundRectDrawable(dp(14), Theme.getColor(Theme.key_windowBackgroundWhite)));
        setElevation(dp(2));
        setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(14));
            }
        });

        imageView = new ImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
        imageView.setVisibility(GONE);
        imageView.setClipToOutline(true);
        imageView.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(10));
            }
        });
        addView(imageView, LayoutHelper.createFrame(48, 48, Gravity.LEFT | Gravity.CENTER_VERTICAL, 12, 0, 0, 0));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_VERTICAL);

        labelView = new TextView(context);
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12);
        labelView.setTypeface(AndroidUtilities.bold());
        labelView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText));
        labelView.setText("Reklama");
        column.addView(labelView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        textView = new TextView(context);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        textView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        textView.setMaxLines(2);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        column.addView(textView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        addView(column, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL, 12, 6, 12, 6));
        // the text starts after the picture when there is one
        imageView.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int left = imageView.getVisibility() == VISIBLE ? dp(12 + 48 + 12) : dp(12);
            if (column.getPaddingLeft() != left - dp(12)) {
                column.setPadding(left - dp(12), 0, 0, 0);
            }
        });

        setOnClickListener(v -> {
            if (TextUtils.isEmpty(adUrl)) {
                return;
            }
            final long id = adId;
            Utilities.globalQueue.postRunnable(() -> {
                try {
                    HttpURLConnection c = (HttpURLConnection) new URL(AbugramProxy.RELAY + "/go/" + id).openConnection();
                    c.setInstanceFollowRedirects(false);
                    c.setConnectTimeout(10000);
                    c.setReadTimeout(10000);
                    c.getResponseCode();
                    c.disconnect();
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            });
            Browser.openUrl(getContext(), adUrl);
        });
    }

    /** Loads the running ads and shows one, counting a view. Call when the chat opens. */
    public void load() {
        Utilities.globalQueue.postRunnable(() -> {
            try {
                String json = fetchAds();
                JSONArray ads = new JSONObject(json).optJSONArray("ads");
                if (ads == null || ads.length() == 0) {
                    AndroidUtilities.runOnUIThread(() -> setShown(false));
                    return;
                }
                JSONObject ad = ads.getJSONObject((int) (Math.random() * ads.length()));
                long id = ad.optLong("id");
                String title = ad.optString("title");
                String text = ad.optString("text");
                String image = ad.optString("image");
                String url = ad.optString("url");
                Bitmap bitmap = null;
                if (image.startsWith("https://")) {
                    bitmap = fetchBitmap(image);
                }
                final Bitmap bm = bitmap;
                AndroidUtilities.runOnUIThread(() -> {
                    adId = id;
                    adUrl = url;
                    titleView.setText(title);
                    textView.setText(text);
                    if (bm != null) {
                        imageView.setImageBitmap(bm);
                        imageView.setVisibility(VISIBLE);
                    } else {
                        imageView.setVisibility(GONE);
                    }
                    setShown(true);
                });
                countView(id);
            } catch (Throwable e) {
                FileLog.e(e);
            }
        });
    }

    private boolean adShown;

    /** Whether there is an ad to show; inside the chat's top panel the panel itself shows and hides the view. */
    public boolean isAdShown() {
        return adShown;
    }

    private void setShown(boolean shown) {
        if (adShown != shown) {
            adShown = shown;
            if (!(getParent() instanceof AnimatedLinearLayout)) {
                setVisibility(shown ? VISIBLE : GONE);
            }
            if (onVisibilityChanged != null) {
                onVisibilityChanged.run();
            }
        }
    }

    private static synchronized String fetchAds() throws Exception {
        long now = System.currentTimeMillis();
        if (cachedJson != null && now - cachedAt < CACHE_MS) {
            return cachedJson;
        }
        String json = request("GET", AbugramProxy.RELAY + "/ads", null);
        cachedJson = json;
        cachedAt = now;
        return json;
    }

    private static void countView(long id) {
        try {
            long uid = UserConfig.getInstance(UserConfig.selectedAccount).getClientUserId();
            request("POST", AbugramProxy.RELAY + "/ads/seen", "{\"ids\":[" + id + "],\"uid\":" + uid + "}");
        } catch (Throwable e) {
            FileLog.e(e);
        }
    }

    private static Bitmap fetchBitmap(String address) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(address).openConnection();
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) {
                return null;
            }
            try (InputStream in = c.getInputStream()) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 2;
                return BitmapFactory.decodeStream(in, null, options);
            }
        } catch (Throwable e) {
            return null;
        } finally {
            if (c != null) {
                c.disconnect();
            }
        }
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
