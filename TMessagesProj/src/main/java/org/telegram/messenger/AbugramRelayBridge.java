package org.telegram.messenger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Abugram: a local SOCKS5 proxy that carries Telegram's connections as plain HTTPS requests through the Abugram
 * relay (a Cloudflare Worker that forwards /&lt;host&gt;/apiw1 to https://&lt;host&gt;.web.telegram.org/apiw1).
 *
 * Networks in Russia block Telegram's TCP and starve WebSockets, but let plain HTTPS through. The app connects to
 * this proxy as to any SOCKS5 proxy; for each connection the bridge reads the obfuscated2 handshake (whose keys are
 * in the handshake itself), takes every MTProto packet out of its transport framing (abridged, intermediate or
 * padded intermediate) and POSTs it to the relay, as Telegram's web clients do, and frames the answer back. The
 * MTProto messages themselves stay encrypted end to end between the app and Telegram: neither the bridge nor the
 * relay can read them.
 *
 * Plain Java (no Android classes), so it can be tested on a desktop JVM.
 */
public class AbugramRelayBridge {

    public interface Log {
        void log(String message);
    }

    private static final String[] HOSTS = {"pluto", "venus", "aurora", "vesta", "flora"};
    private static final int TAG_ABRIDGED = 0xefefefef;
    private static final int TAG_INTERMEDIATE = 0xeeeeeeee;
    private static final int TAG_PADDED = 0xdddddddd;

    /** One finished request through the relay, for the diagnostics list in Settings → Abugram. */
    public static class Stat {
        public final long time;
        public final int dc;
        public final int bytesOut;
        public final int bytesIn;
        public final long millis;
        public final String result;

        Stat(int dc, int bytesOut, int bytesIn, long millis, String result) {
            this.time = System.currentTimeMillis();
            this.dc = dc;
            this.bytesOut = bytesOut;
            this.bytesIn = bytesIn;
            this.millis = millis;
            this.result = result;
        }
    }

    /** Big packets (file parts) go to the relay a few at a time: more only queue behind each other on the phone's uplink and time out. */
    private static final Semaphore BIG_POSTS = new Semaphore(4);
    private static final int BIG_PACKET = 64 * 1024;

    private static final int KEEP_STATS = 40;
    private static final java.util.ArrayDeque<Stat> STATS = new java.util.ArrayDeque<>();

    private static void record(Stat stat) {
        synchronized (STATS) {
            STATS.addLast(stat);
            while (STATS.size() > KEEP_STATS) {
                STATS.removeFirst();
            }
        }
    }

    /** The latest requests, newest first. */
    public static java.util.List<Stat> getStats() {
        synchronized (STATS) {
            java.util.ArrayList<Stat> list = new java.util.ArrayList<>(STATS);
            java.util.Collections.reverse(list);
            return list;
        }
    }

    /** One relay server: the app can use several and moves to another when one stops answering. */
    private static final class Relay {
        final String base;
        volatile int fails;
        volatile long failedAt;
        volatile double ewma; // milliseconds of a small request, smoothed
        Relay(String base, double ewma) {
            this.base = base;
            this.ewma = ewma;
        }
    }

    private final String relayBase;
    private volatile Relay[] relays;

    /**
     * The relay servers to use, the first ones preferred: those from the admin's list, then the built-in one as the last
     * resort. A server that is already known keeps what the app has learned about it.
     */
    public synchronized void setRelays(java.util.List<String> bases) {
        java.util.ArrayList<Relay> list = new java.util.ArrayList<>();
        Relay[] old = relays;
        for (String b : bases) {
            String base = b == null ? "" : b.trim().replaceAll("/+$", "");
            if (!base.startsWith("https://") || base.length() < 12) continue;
            Relay existing = null;
            for (Relay r : old) if (r.base.equals(base)) existing = r;
            boolean dup = false;
            for (Relay r : list) if (r.base.equals(base)) dup = true;
            if (!dup) list.add(existing != null ? existing : new Relay(base, 200));
        }
        boolean hasBuiltIn = false;
        for (Relay r : list) if (r.base.equals(relayBase)) hasBuiltIn = true;
        if (!hasBuiltIn) {
            Relay builtIn = null;
            for (Relay r : old) if (r.base.equals(relayBase)) builtIn = r;
            list.add(builtIn != null ? builtIn : new Relay(relayBase, 300));
        }
        relays = list.toArray(new Relay[0]);
        log("relays: " + list.size());
    }

    /** All relays are given another chance (after a stretch of failures). */
    public void resetHealth() {
        for (Relay r : relays) {
            r.fails = 0;
        }
    }

    /** Whether any relay answers at all (any HTTP answer counts; used to come back to the relay after a fallback). */
    public boolean probe() {
        for (Relay r : relays) {
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(r.base + "/pluto/apiw1").openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(6000);
                c.setReadTimeout(8000);
                c.setRequestProperty("X-Abugram-Build", String.valueOf(AbugramVersion.build()));
                c.setFixedLengthStreamingMode(1);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(0);
                }
                int code = c.getResponseCode();
                if (code > 0 && code != 426) {
                    r.fails = 0;
                    return true;
                }
            } catch (Throwable ignore) {
            } finally {
                if (c != null) c.disconnect();
            }
        }
        return false;
    }

    /** The best relay now, other than the one to avoid; null when there is no other. */
    private Relay pick(Relay avoid) {
        Relay best = null;
        double bestScore = Double.MAX_VALUE;
        long now = System.currentTimeMillis();
        for (Relay r : relays) {
            if (r == avoid) continue;
            double score = r.ewma + (r.fails > 0 && now - r.failedAt < 60000 ? 4000.0 * r.fails : 0);
            if (score < bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    private final Log log;
    private ServerSocket server;
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "AbugramRelayBridge");
        t.setDaemon(true);
        return t;
    });

    /** @param relayBase e.g. "https://winter-cake-2d11.tashlanow.workers.dev" */
    public AbugramRelayBridge(String relayBase, Log log) {
        this.relayBase = relayBase.replaceAll("/+$", "");
        this.relays = new Relay[]{new Relay(this.relayBase, 300)};
        this.log = log;
    }

    /** Starts listening on 127.0.0.1; returns the port. */
    public synchronized int start(int preferredPort) throws IOException {
        if (server != null) {
            return server.getLocalPort();
        }
        // The default of 5 connections per host holds back the many parallel parts of a file upload.
        System.setProperty("http.maxConnections", "24");
        ServerSocket s = new ServerSocket();
        s.setReuseAddress(true);
        try {
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), preferredPort));
        } catch (IOException e) {
            s.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0));
        }
        server = s;
        pool.execute(this::acceptLoop);
        return s.getLocalPort();
    }

    public synchronized int getPort() {
        return server != null ? server.getLocalPort() : 0;
    }

    private void acceptLoop() {
        while (true) {
            Socket client;
            try {
                client = server.accept();
            } catch (IOException e) {
                log("accept failed: " + e);
                return;
            }
            pool.execute(() -> handle(client));
        }
    }

    private void log(String message) {
        if (log != null) {
            log.log("AbugramRelayBridge: " + message);
        }
    }

    // ---- SOCKS5 ----

    private static void readFully(InputStream in, byte[] buf, int off, int len) throws IOException {
        while (len > 0) {
            int n = in.read(buf, off, len);
            if (n < 0) throw new IOException("closed");
            off += n;
            len -= n;
        }
    }

    private static byte[] read(InputStream in, int len) throws IOException {
        byte[] b = new byte[len];
        readFully(in, b, 0, len);
        return b;
    }

    private void handle(Socket client) {
        try {
            client.setTcpNoDelay(true);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();

            // greeting: VER NMETHODS METHODS...
            byte[] head = read(in, 2);
            if (head[0] != 5) throw new IOException("not socks5");
            read(in, head[1] & 0xff);
            out.write(new byte[]{5, 0}); // no authentication
            out.flush();

            // request: VER CMD RSV ATYP ADDR PORT
            byte[] req = read(in, 4);
            if (req[1] != 1) { // CONNECT only
                out.write(new byte[]{5, 7, 0, 1, 0, 0, 0, 0, 0, 0});
                throw new IOException("unsupported command " + req[1]);
            }
            String address;
            int atyp = req[3];
            if (atyp == 1) {
                address = InetAddress.getByAddress(read(in, 4)).getHostAddress();
            } else if (atyp == 4) {
                address = InetAddress.getByAddress(read(in, 16)).getHostAddress();
            } else if (atyp == 3) {
                address = new String(read(in, in.read() & 0xff), "UTF-8");
            } else {
                throw new IOException("bad address type");
            }
            read(in, 2); // port
            out.write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0}); // succeeded
            out.flush();

            new Session(client, in, out, address).run();
        } catch (Throwable e) {
            log("connection ended: " + e);
        } finally {
            try {
                client.close();
            } catch (IOException ignore) {
            }
        }
    }

    // ---- one Telegram connection ----

    private class Session {
        private final Socket socket;
        private final InputStream in;
        private final OutputStream out;
        private final String address;
        private Cipher decrypt; // client -> us
        private Cipher encrypt; // us -> client
        private int tag;
        private String url;
        private int dcOfSession;
        private volatile boolean closed;

        Session(Socket socket, InputStream in, OutputStream out, String address) {
            this.socket = socket;
            this.in = in;
            this.out = out;
            this.address = address;
        }

        void run() throws Exception {
            byte[] init = read(in, 64);
            byte[] keyIv = new byte[48];
            System.arraycopy(init, 8, keyIv, 0, 48);
            byte[] reversed = new byte[48];
            for (int i = 0; i < 48; i++) reversed[i] = keyIv[47 - i];

            decrypt = Cipher.getInstance("AES/CTR/NoPadding");
            decrypt.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyIv, 0, 32, "AES"), new IvParameterSpec(keyIv, 32, 16));
            encrypt = Cipher.getInstance("AES/CTR/NoPadding");
            encrypt.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(reversed, 0, 32, "AES"), new IvParameterSpec(reversed, 32, 16));

            byte[] plainInit = decrypt.update(init);
            tag = le32(plainInit, 56);
            if (tag != TAG_ABRIDGED && tag != TAG_INTERMEDIATE && tag != TAG_PADDED) {
                throw new IOException("unknown transport " + Integer.toHexString(tag));
            }
            // The handshake names its datacenter only when it carries a proxy secret, which a SOCKS5 connection has not
            // (the field is random then): the address the app asked for tells it.
            int dc = dcFromAddress(address);
            if (dc < 1 || dc > 5) {
                short dcField = (short) ((plainInit[60] & 0xff) | ((plainInit[61] & 0xff) << 8));
                dc = Math.abs(dcField);
            }
            if (dc < 1 || dc > 5) {
                throw new IOException("unknown datacenter for " + address);
            }
            dcOfSession = dc;
            url = "/" + HOSTS[dc - 1] + "/apiw1"; // the relay's address is put in front of it for every request
            log("connection to DC" + dc + " via " + address);

            while (!closed) {
                byte[] packet = readPacket();
                if (packet == null) break;
                byte[] message = stripPadding(packet);
                pool.execute(() -> post(message));
            }
        }

        private byte[] readPacket() throws IOException {
            int length;
            if (tag == TAG_ABRIDGED) {
                byte[] b = decrypt.update(read(in, 1));
                int first = b[0] & 0x7f;
                if (first == 0x7f) {
                    byte[] l = decrypt.update(read(in, 3));
                    length = ((l[0] & 0xff) | ((l[1] & 0xff) << 8) | ((l[2] & 0xff) << 16)) * 4;
                } else {
                    length = first * 4;
                }
            } else {
                byte[] l = decrypt.update(read(in, 4));
                length = le32(l, 0) & 0x7fffffff; // the top bit asks for a quick ack, which HTTP has not
            }
            if (length <= 0 || length > 16 * 1024 * 1024) throw new IOException("bad packet length " + length);
            return decrypt.update(read(in, length));
        }

        /** Padded intermediate adds 0-15 random bytes; the MTProto message's own layout tells its length. */
        private byte[] stripPadding(byte[] packet) {
            if (tag != TAG_PADDED || packet.length < 24) return packet;
            int exact;
            boolean unencrypted = true;
            for (int i = 0; i < 8; i++) if (packet[i] != 0) unencrypted = false;
            if (unencrypted && packet.length >= 20) {
                exact = 20 + le32(packet, 16);
            } else {
                exact = packet.length - ((packet.length - 24) % 16);
            }
            if (exact <= 0 || exact > packet.length) return packet;
            byte[] m = new byte[exact];
            System.arraycopy(packet, 0, m, 0, exact);
            return m;
        }

        private void post(byte[] message) {
            final boolean big = message.length > BIG_PACKET;
            if (big) {
                try {
                    BIG_POSTS.acquire();
                } catch (InterruptedException e) {
                    close();
                    return;
                }
            }
            try {
                postNow(message);
            } finally {
                if (big) BIG_POSTS.release();
            }
        }

        private static final int DONE = 0;
        private static final int RETRY = 1;
        private static final int GIVE_UP = 2;

        /** A request goes to the best relay; if that one fails it is sent once more through another. */
        private void postNow(byte[] message) {
            Relay first = pick(null);
            if (first == null) {
                close();
                return;
            }
            int outcome = attempt(message, first);
            if (outcome == RETRY && !closed) {
                Relay second = pick(first);
                if (second != null) {
                    outcome = attempt(message, second);
                }
            }
            if (outcome != DONE) {
                close();
            }
        }

        private int attempt(byte[] message, Relay relay) {
            HttpURLConnection c = null;
            final long started = System.currentTimeMillis();
            boolean reuse = false; // a fully read answer leaves the HTTPS connection open for the next request: no new handshake
            int received = 0;
            int outcome = RETRY;
            String result = "xato";
            try {
                c = (HttpURLConnection) new URL(relay.base + url).openConnection();
                c.setRequestMethod("POST");
                c.setDoOutput(true);
                c.setConnectTimeout(12000);
                c.setReadTimeout(45000);
                c.setRequestProperty("Content-Type", "application/octet-stream");
                c.setRequestProperty("X-Abugram-Build", String.valueOf(AbugramVersion.build())); // lets the relay ask old builds to update
                c.setFixedLengthStreamingMode(message.length);
                try (OutputStream o = c.getOutputStream()) {
                    o.write(message);
                }
                int status = c.getResponseCode();
                result = "HTTP " + status;
                if (status == 200) {
                    byte[] body = readAll(c.getInputStream());
                    received = body.length;
                    result = "ok";
                    reuse = true;
                    if (body.length > 0) send(body);
                    outcome = DONE;
                } else if (status == 404) {
                    // Telegram answers an unknown auth key with HTTP 404, and TCP clients expect the code -404
                    byte[] code = new byte[4];
                    int v = -404;
                    code[0] = (byte) v; code[1] = (byte) (v >> 8); code[2] = (byte) (v >> 16); code[3] = (byte) (v >> 24);
                    send(code);
                    outcome = DONE;
                } else if (status == 426) {
                    log("relay asks for an update");
                    outcome = GIVE_UP; // the same answer from every relay
                } else {
                    log("relay " + relay.base + " answered " + status);
                    outcome = RETRY; // a relay that errors, is overloaded or blocked: another may answer
                }
            } catch (Throwable e) {
                log("request failed: " + e);
                result = e instanceof java.net.SocketTimeoutException ? "vaqt tugadi" : "tarmoq xatosi";
                outcome = RETRY;
            } finally {
                if (c != null && !reuse) c.disconnect();
                long took = System.currentTimeMillis() - started;
                if (outcome == DONE) {
                    relay.fails = 0;
                    if (message.length < 16384) relay.ewma = relay.ewma * 0.8 + took * 0.2;
                } else if (outcome == RETRY) {
                    relay.fails = Math.min(relay.fails + 1, 5);
                    relay.failedAt = System.currentTimeMillis();
                }
                record(new Stat(dcOfSession, message.length, received, took, result));
            }
            return outcome;
        }

        private synchronized void send(byte[] payload) throws IOException {
            if (closed) return;
            byte[] header;
            if (tag == TAG_ABRIDGED) {
                int words = payload.length / 4;
                header = words < 0x7f ? new byte[]{(byte) words}
                        : new byte[]{0x7f, (byte) words, (byte) (words >> 8), (byte) (words >> 16)};
            } else {
                int n = payload.length;
                header = new byte[]{(byte) n, (byte) (n >> 8), (byte) (n >> 16), (byte) (n >> 24)};
            }
            out.write(encrypt.update(header));
            out.write(encrypt.update(payload));
            out.flush();
        }

        private void close() {
            closed = true;
            try {
                socket.close();
            } catch (IOException ignore) {
            }
        }
    }

    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        in.close();
        return b.toByteArray();
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xff) | ((b[off + 1] & 0xff) << 8) | ((b[off + 2] & 0xff) << 16) | ((b[off + 3] & 0xff) << 24);
    }

    /** Telegram's production datacenter addresses, for a handshake that does not name its datacenter. */
    static int dcFromAddress(String address) {
        if (address == null) return 0;
        String a = address.toLowerCase();
        if (a.startsWith("149.154.175.")) {
            int last = parseLast(a);
            return last >= 100 ? 3 : 1;
        }
        if (a.startsWith("149.154.167.")) {
            int last = parseLast(a);
            return last >= 90 && last < 100 ? 4 : 2;
        }
        if (a.startsWith("149.154.166.") || a.startsWith("149.154.164.") || a.startsWith("149.154.165.")) return 4;
        if (a.startsWith("91.108.56.") || a.startsWith("91.108.57.")) return 5;
        if (a.startsWith("91.108.4.") || a.startsWith("91.108.8.")) return 4;
        if (a.startsWith("91.108.12.")) return 3;
        if (a.startsWith("2001:b28:f23d:f001")) return 1;
        if (a.startsWith("2001:67c:4e8:f002")) return 2;
        if (a.startsWith("2001:b28:f23d:f003")) return 3;
        if (a.startsWith("2001:67c:4e8:f004")) return 4;
        if (a.startsWith("2001:b28:f23f:f005")) return 5;
        return 0;
    }

    private static int parseLast(String ipv4) {
        try {
            return Integer.parseInt(ipv4.substring(ipv4.lastIndexOf('.') + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
