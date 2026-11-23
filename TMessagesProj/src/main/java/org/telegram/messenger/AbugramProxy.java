package org.telegram.messenger;

import android.app.Activity;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import android.text.TextUtils;

import org.telegram.tgnet.ConnectionsManager;
import org.telegram.utils.proxy.ProxySettings;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;

/**
 * Abugram: built-in MTProxy so the app connects where Telegram is blocked, without a VPN.
 *
 * On every start the proxy list is downloaded from the Abugram repository (two mirrors, then the list
 * compiled into the app). The proxies are added to the normal proxy list, proxy rotation is switched on,
 * and every proxy is tested with a real MTProto handshake through it; the first that answers becomes the
 * active one. Telegram's ProxyRotationController then keeps switching to a working proxy whenever the
 * current one stops connecting. Proxies the user added are never touched, and once the user turns proxies
 * off Abugram leaves them off.
 */
public class AbugramProxy {

    private static final String[] LIST_URLS = {
            "https://raw.githubusercontent.com/Abdulaziz0196/Telegram-iOS/master/abugram/proxies.txt",
            "https://cdn.jsdelivr.net/gh/Abdulaziz0196/Telegram-iOS@master/abugram/proxies.txt",
            "https://fastly.jsdelivr.net/gh/Abdulaziz0196/Telegram-iOS@master/abugram/proxies.txt"
    };
    private static final int MAX_PROXIES = 40;
    /** The Abugram relay (Cloudflare Worker) that the local bridge sends Telegram's traffic through as HTTPS. */
    public static final String RELAY = "https://winter-cake-2d11.tashlanow.workers.dev";
    private static final int BRIDGE_PORT = 19050;
    private static AbugramRelayBridge bridge;
    private static int bridgePort;
    private static final String PREFS = "abugram_proxy";
    private static final String KEY_MANAGED = "managed";
    private static final String KEY_INITIALIZED = "initialized";

    // Filled in from abugram/proxies.txt when the APK is built; used when no mirror is reachable.
    private static final String FALLBACK_LIST =
            "https://t.me/proxy?server=172.65.172.190&port=8443&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=a1.anotelinopalk.co.uk&port=8880&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=noh.anotelinopalk.co.uk&port=8880&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=jhndoes.co.uk&port=8443&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=opss.anotelinopalk.co.uk&port=8880&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=irogallery.golgoli1.co.uk&port=2096&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=a4.anotelinopalk.co.uk&port=8880&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=silver.ciaude.co.uk&port=2096&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=name.golgoli2.co.uk&port=2096&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=white.graduat.co.uk&port=8443&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=craft.malavanann.co.uk&port=2083&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=ir.closcloud.co.uk&port=8443&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=we.antony-gordon.co.uk&port=8443&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=haf.anotelinopalk.co.uk&port=8880&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=silnet.varfootball.co.uk&port=2053&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=star.telbet.lol&port=8443&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=char.anotelinopalk.co.uk&port=8880&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=se.anotelinopalk.co.uk&port=8880&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=fruit.golgoli2.co.uk&port=2096&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=a5.anotelinopalk.co.uk&port=8880&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=name.namenewok.info&port=8443&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=ir.mediaad.co.uk&port=8880&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=iro.varfootball2.co.uk&port=2053&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=yek.anotelinopalk.co.uk&port=8880&secret=eeNEgYdJvXrFGRMCIMJdCQ\n" +
            "https://t.me/proxy?server=great.cipservice.co.uk&port=2096&secret=eeNEgYdJvXrFGRMCIMJdCQ\n";

    private static boolean started;
    private static boolean chosen;

    /** Whether Telegram currently connects through the Abugram relay bridge. */
    public static boolean viaRelay() {
        return SharedConfig.currentProxy != null && SharedConfig.isProxyEnabled()
                && "127.0.0.1".equals(SharedConfig.currentProxy.settings.getAddress());
    }

    public static void start() {
        if (started) {
            return;
        }
        started = true;
        Utilities.globalQueue.postRunnable(() -> {
            try {
                bridge = new AbugramRelayBridge(RELAY, FileLog::d);
                bridgePort = bridge.start(BRIDGE_PORT);
            } catch (Throwable e) {
                FileLog.e(e);
                bridge = null;
                bridgePort = 0;
            }
            loadAndApply();
        });
    }

    private static void loadAndApply() {
        String text = null;
        for (String url : LIST_URLS) {
            text = download(url);
            if (text != null && !parse(text).isEmpty()) {
                break;
            }
            text = null;
        }
        ArrayList<ProxySettings> servers = parse(text != null ? text : FALLBACK_LIST);
        if (servers.isEmpty()) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> apply(servers));
    }

    private static String download(String address) {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(address).openConnection();
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);
            connection.setUseCaches(false);
            if (connection.getResponseCode() != 200) {
                return null;
            }
            InputStream stream = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1 && out.size() < 512 * 1024) {
                out.write(buffer, 0, read);
            }
            stream.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable e) {
            FileLog.e(e);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static ArrayList<ProxySettings> parse(String text) {
        ArrayList<ProxySettings> result = new ArrayList<>();
        if (TextUtils.isEmpty(text)) {
            return result;
        }
        Set<String> seen = new HashSet<>();
        for (String rawLine : text.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            try {
                ProxySettings settings = ProxySettings.fromUri(Uri.parse(line));
                if (settings != null && settings.isValid() && seen.add(key(settings))) {
                    result.add(settings);
                }
            } catch (Throwable ignore) {
            }
            if (result.size() >= MAX_PROXIES) {
                break;
            }
        }
        return result;
    }

    private static String key(ProxySettings settings) {
        return settings.getAddress() + ":" + settings.getPort();
    }

    private static void apply(ArrayList<ProxySettings> servers) {
        SharedConfig.loadProxyList();
        SharedPreferences prefs = ApplicationLoader.applicationContext.getSharedPreferences(PREFS, Activity.MODE_PRIVATE);
        Set<String> previouslyManaged = new HashSet<>(prefs.getStringSet(KEY_MANAGED, new HashSet<>()));
        boolean initialized = prefs.getBoolean(KEY_INITIALIZED, false);
        Set<String> newKeys = new HashSet<>();
        for (ProxySettings settings : servers) {
            newKeys.add(key(settings));
        }

        // Drop proxies Abugram added earlier that left the list; keep the user's own and the active one.
        for (SharedConfig.ProxyInfo info : new ArrayList<>(SharedConfig.proxyList)) {
            String infoKey = key(info.settings);
            if (previouslyManaged.contains(infoKey) && !newKeys.contains(infoKey) && info != SharedConfig.currentProxy) {
                SharedConfig.deleteProxy(info);
            }
        }
        SharedConfig.ProxyInfo bridgeInfo = null;
        if (bridgePort > 0) {
            ProxySettings bridgeSettings = ProxySettings.builder()
                    .setType(ProxySettings.Type.SOCKS5)
                    .setAddress("127.0.0.1")
                    .setPort(bridgePort)
                    .setUser("")
                    .setPassword("")
                    .build();
            newKeys.add(key(bridgeSettings));
            bridgeInfo = SharedConfig.addProxy(new SharedConfig.ProxyInfo(bridgeSettings));
        }
        ArrayList<SharedConfig.ProxyInfo> managed = new ArrayList<>();
        for (ProxySettings settings : servers) {
            managed.add(SharedConfig.addProxy(new SharedConfig.ProxyInfo(settings)));
        }
        prefs.edit().putStringSet(KEY_MANAGED, newKeys).apply();

        SharedPreferences main = MessagesController.getGlobalMainSettings();
        boolean enabled = main.getBoolean("proxy_enabled", false);
        SharedConfig.ProxyInfo current = SharedConfig.currentProxy;
        boolean currentIsUsers = current != null && !newKeys.contains(key(current.settings)) && !previouslyManaged.contains(key(current.settings));
        if ((initialized && !enabled) || (enabled && currentIsUsers)) {
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
            return;
        }

        if (bridgeInfo != null) {
            // The relay bridge carries everything as HTTPS through the Abugram relay; the MTProxy list stays in the
            // proxy settings for picking by hand. Rotation would move away from the bridge, so it is off.
            SharedConfig.proxyRotationEnabled = false;
            SharedConfig.saveConfig();
            select(bridgeInfo);
            prefs.edit().putBoolean(KEY_INITIALIZED, true).apply();
            return;
        }

        // Rotation switches away from a proxy that stops connecting after 5 seconds.
        SharedConfig.proxyRotationEnabled = true;
        SharedConfig.proxyRotationTimeout = 0;
        SharedConfig.saveConfig();

        if (!enabled || current == null) {
            // Start connecting through a proxy right away; the checks below move to the fastest one.
            select(managed.get(0));
        }
        prefs.edit().putBoolean(KEY_INITIALIZED, true).apply();

        chosen = false;
        int account = UserConfig.selectedAccount;
        for (SharedConfig.ProxyInfo info : managed) {
            if (info.checking) {
                continue;
            }
            info.checking = true;
            ConnectionsManager.getInstance(account).checkProxy(info.settings, time -> AndroidUtilities.runOnUIThread(() -> {
                info.availableCheckTime = SystemClock.elapsedRealtime();
                info.checking = false;
                if (time == -1) {
                    info.available = false;
                    info.ping = 0;
                } else {
                    info.available = true;
                    info.ping = time;
                    SharedConfig.ProxyInfo active = SharedConfig.currentProxy;
                    boolean activeWorks = active != null && active.available && active != info;
                    if (!chosen && !activeWorks) {
                        chosen = true;
                        select(info);
                    }
                }
                NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxyCheckDone, info);
            }));
        }
    }

    private static void select(SharedConfig.ProxyInfo info) {
        SharedPreferences.Editor editor = MessagesController.getGlobalMainSettings().edit();
        info.settings.toSharedPreferences(editor);
        editor.putBoolean("proxy_enabled", true);
        editor.commit();
        SharedConfig.currentProxy = info;
        SharedConfig.saveProxyList();
        ConnectionsManager.setProxySettings(true, info.settings);
        NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
    }
}
