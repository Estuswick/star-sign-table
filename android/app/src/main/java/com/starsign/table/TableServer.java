package com.starsign.table;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The GM's table, hosted on the GM's phone. No internet needed: players reach it over the
 * phone's hotspot (or any shared Wi-Fi).
 *
 * - Plain web requests get the app itself, so any phone's browser can open it.
 * - "/ws" is a WebSocket where each phone shares its deck's status (presence), the GM sends
 *   moments to everyone (start an encounter, wild magic), and browser players keep a copy of
 *   their decks on the GM's phone so they can pick up where they left off.
 *
 * Messages to the server are text: a type line, then its fields, one per line. Messages from
 * the server are JSON. The server never parses JSON; it stores and forwards what phones send.
 * Plain Java only, so it runs on Android and can be tested on a computer.
 */
public class TableServer {

    /** Where the app's files come from (the app's assets on Android). */
    public interface Files {
        byte[] read(String path) throws IOException;
    }

    private static final String WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HEAD = 16 * 1024;
    private static final int MAX_FRAME = 2 * 1024 * 1024;
    private static final int MAX_PRESENCE = 8 * 1024;
    private static final int MAX_SAVE = 1024 * 1024;
    private static final int MAX_CLIENTS = 40;
    private static final int MAX_SAVES = 60;

    private final Files files;
    private final File saveDir;
    private final Map<String, Client> clients = new ConcurrentHashMap<>();
    private final Map<String, String> saves = new ConcurrentHashMap<>();      // key -> JSON text
    private final Map<String, String> saveNames = new ConcurrentHashMap<>();  // key -> name as typed
    private ServerSocket server;
    private volatile boolean running;
    private int port;
    private int nextId = 1;

    public TableServer(Files files, File saveDir) {
        this.files = files;
        this.saveDir = saveDir;
        loadSaves();
    }

    public synchronized boolean isRunning() {
        return running;
    }

    public synchronized int getPort() {
        return port;
    }

    public synchronized int clientCount() {
        return clients.size();
    }

    /** Starts on the first free port from {@code preferred}; returns the port. */
    public synchronized int start(int preferred) throws IOException {
        if (running) return port;
        IOException last = null;
        for (int p = preferred; p < preferred + 10; p++) {
            try {
                ServerSocket s = new ServerSocket();
                s.setReuseAddress(true);
                s.bind(new InetSocketAddress(p));
                server = s;
                port = p;
                running = true;
                Thread accept = new Thread(this::acceptLoop, "table-accept");
                accept.setDaemon(true);
                accept.start();
                Thread ping = new Thread(this::pingLoop, "table-ping");
                ping.setDaemon(true);
                ping.start();
                return port;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last != null ? last : new IOException("No free port");
    }

    public synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (IOException ignored) {
        }
        for (Client c : clients.values()) c.close();
        clients.clear();
    }

    // ---------------------------------------------------------------- connections

    private void acceptLoop() {
        while (running) {
            try {
                final Socket s = server.accept();
                Thread t = new Thread(() -> handle(s), "table-conn");
                t.setDaemon(true);
                t.start();
            } catch (IOException e) {
                if (!running) return;
            }
        }
    }

    private void pingLoop() {
        while (running) {
            try {
                Thread.sleep(20000);
            } catch (InterruptedException e) {
                return;
            }
            for (Client c : clients.values()) {
                try {
                    c.sendFrame(0x9, new byte[0]);
                } catch (IOException e) {
                    c.close();
                }
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(15000);
            s.setTcpNoDelay(true);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String head = readHead(in);
            if (head == null) {
                s.close();
                return;
            }
            String[] lines = head.split("\r\n");
            String[] req = lines[0].split(" ");
            if (req.length < 2) {
                s.close();
                return;
            }
            String method = req[0], target = req[1];
            String upgrade = header(lines, "upgrade"), key = header(lines, "sec-websocket-key");
            String path = target.split("\\?")[0];
            if ("/ws".equals(path) && upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket") && key != null) {
                webSocket(s, in, out, key.trim());
            } else if ("GET".equals(method) || "HEAD".equals(method)) {
                serve(out, path, "HEAD".equals(method));
                s.close();
            } else {
                respond(out, 405, "text/plain", "Method not allowed".getBytes(StandardCharsets.UTF_8), false);
                s.close();
            }
        } catch (IOException e) {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0, c;
        while ((c = in.read()) != -1) {
            b.write(c);
            if (b.size() > MAX_HEAD) return null;
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    private static String header(String[] lines, String name) {
        for (int i = 1; i < lines.length; i++) {
            int k = lines[i].indexOf(':');
            if (k > 0 && lines[i].substring(0, k).trim().toLowerCase(Locale.ROOT).equals(name)) return lines[i].substring(k + 1).trim();
        }
        return null;
    }

    // ---------------------------------------------------------------- the app's files

    private void serve(OutputStream out, String path, boolean headOnly) throws IOException {
        String p = path;
        try {
            p = java.net.URLDecoder.decode(path, "UTF-8");
        } catch (IllegalArgumentException ignored) {
        }
        while (p.startsWith("/")) p = p.substring(1);
        if (p.isEmpty()) p = "index.html";
        if (p.contains("..") || p.contains("\\") || p.contains("\0")) {
            respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), headOnly);
            return;
        }
        byte[] body;
        try {
            body = files.read(p);
        } catch (IOException e) {
            body = null;
        }
        if (body == null) {
            respond(out, 404, "text/plain", "Not found".getBytes(StandardCharsets.UTF_8), headOnly);
            return;
        }
        if (p.equals("index.html")) {
            // The same app, told it was opened from the GM's phone.
            String html = new String(body, StandardCharsets.UTF_8)
                    .replace("window.SST_HOST='android'", "window.SST_HOST='lan'")
                    .replace("window.SST_HOST='web'", "window.SST_HOST='lan'");
            body = html.getBytes(StandardCharsets.UTF_8);
        }
        respond(out, 200, contentType(p), body, headOnly);
    }

    private static String contentType(String p) {
        String l = p.toLowerCase(Locale.ROOT);
        if (l.endsWith(".html")) return "text/html; charset=utf-8";
        if (l.endsWith(".css")) return "text/css; charset=utf-8";
        if (l.endsWith(".js")) return "text/javascript; charset=utf-8";
        if (l.endsWith(".json") || l.endsWith(".webmanifest")) return "application/json";
        if (l.endsWith(".png")) return "image/png";
        if (l.endsWith(".svg")) return "image/svg+xml";
        if (l.endsWith(".woff2")) return "font/woff2";
        if (l.endsWith(".woff")) return "font/woff";
        return "application/octet-stream";
    }

    private static void respond(OutputStream out, int code, String type, byte[] body, boolean headOnly) throws IOException {
        String reason = code == 200 ? "OK" : code == 404 ? "Not Found" : "Method Not Allowed";
        String head = "HTTP/1.1 " + code + " " + reason + "\r\n"
                + "Content-Type: " + type + "\r\n"
                + "Content-Length: " + body.length + "\r\n"
                + "Cache-Control: no-cache\r\n"
                + "Connection: close\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.ISO_8859_1));
        if (!headOnly) out.write(body);
        out.flush();
    }

    // ---------------------------------------------------------------- the table (WebSocket)

    private void webSocket(Socket s, InputStream in, OutputStream out, String key) throws IOException {
        if (clients.size() >= MAX_CLIENTS) {
            respond(out, 404, "text/plain", "Table full".getBytes(StandardCharsets.UTF_8), false);
            s.close();
            return;
        }
        String accept;
        try {
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            accept = base64(sha1.digest((key + WS_GUID).getBytes(StandardCharsets.ISO_8859_1)));
        } catch (Exception e) {
            throw new IOException(e);
        }
        out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + accept + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
        s.setSoTimeout(65000);
        String id;
        synchronized (this) {
            id = "c" + (nextId++);
        }
        Client c = new Client(id, s, out, s.getInetAddress() != null && s.getInetAddress().isLoopbackAddress());
        clients.put(id, c);
        try {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (running) {
                int b0 = in.read();
                if (b0 == -1) break;
                int b1 = in.read();
                if (b1 == -1) break;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                boolean masked = (b1 & 0x80) != 0;
                long len = b1 & 0x7F;
                if (len == 126) len = ((readByte(in) << 8) | readByte(in));
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | readByte(in);
                }
                if (len > MAX_FRAME) break;
                byte[] mask = new byte[4];
                if (masked) readFully(in, mask);
                byte[] data = new byte[(int) len];
                readFully(in, data);
                if (masked) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                if (op == 0x8) {
                    try {
                        c.sendFrame(0x8, new byte[0]);
                    } catch (IOException ignored) {
                    }
                    break;
                } else if (op == 0x9) {
                    c.sendFrame(0xA, data);
                } else if (op == 0xA) {
                    // pong: the phone is still there
                } else if (op == 0x1 || op == 0x0) {
                    msg.write(data);
                    if (msg.size() > MAX_FRAME) break;
                    if (fin) {
                        onMessage(c, new String(msg.toByteArray(), StandardCharsets.UTF_8));
                        msg.reset();
                    }
                }
            }
        } catch (SocketTimeoutException e) {
            // the phone went quiet: treat it as gone
        } catch (IOException e) {
            // connection dropped
        } finally {
            clients.remove(id);
            c.close();
            broadcastPeers();
        }
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b == -1) throw new IOException("closed");
        return b;
    }

    private static void readFully(InputStream in, byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n == -1) throw new IOException("closed");
            off += n;
        }
    }

    private void onMessage(Client c, String text) {
        int nl = text.indexOf('\n');
        String type = nl < 0 ? text : text.substring(0, nl);
        String rest = nl < 0 ? "" : text.substring(nl + 1);
        try {
            switch (type) {
                case "hello": {
                    // Only the GM's own app (on this phone) may take the GM's seat.
                    c.role = ("gm".equals(rest.trim()) && c.loopback) ? "gm" : "player";
                    c.send("{\"t\":\"welcome\",\"id\":" + q(c.id) + ",\"role\":" + q(c.role) + "}");
                    c.send(savesJson());
                    broadcastPeers();
                    break;
                }
                case "presence": {
                    if (rest.length() > MAX_PRESENCE) return;
                    c.presence = rest;
                    c.at = System.currentTimeMillis();
                    broadcastPeers();
                    break;
                }
                case "emit": {
                    int k = rest.indexOf('\n');
                    String topic = k < 0 ? rest : rest.substring(0, k);
                    String data = k < 0 ? "null" : rest.substring(k + 1);
                    if (!"gm".equals(c.role)) {
                        c.send("{\"t\":\"error\",\"code\":\"not_permitted\"}");
                        return;
                    }
                    if (!topic.matches("[a-z]{1,16}") || data.length() > MAX_PRESENCE) return;
                    broadcast("{\"t\":\"emit\",\"topic\":" + q(topic) + ",\"data\":" + q(data) + ",\"from\":" + q(c.id) + "}");
                    break;
                }
                case "save": {
                    int k = rest.indexOf('\n');
                    if (k < 0) return;
                    String name = rest.substring(0, k).trim(), data = rest.substring(k + 1);
                    String key = key(name);
                    if (key.isEmpty() || name.length() > 40 || data.length() > MAX_SAVE) return;
                    if (!saves.containsKey(key) && saves.size() >= MAX_SAVES) return;
                    saves.put(key, data);
                    saveNames.put(key, name);
                    persist(key, name, data);
                    break;
                }
                case "load": {
                    String key = key(rest.trim());
                    String data = saves.get(key);
                    if (data != null) c.send("{\"t\":\"state\",\"name\":" + q(saveNames.get(key)) + ",\"data\":" + q(data) + "}");
                    break;
                }
                case "forget": {
                    String key = key(rest.trim());
                    if (!"gm".equals(c.role) || key.isEmpty()) return;
                    saves.remove(key);
                    saveNames.remove(key);
                    if (saveDir != null) new File(saveDir, key + ".json").delete();
                    broadcast(savesJson());
                    break;
                }
                case "ping":
                    c.send("{\"t\":\"pong\"}");
                    break;
                default:
                    break;
            }
        } catch (IOException e) {
            c.close();
        }
    }

    private void broadcastPeers() {
        StringBuilder sb = new StringBuilder("{\"t\":\"peers\",\"peers\":[");
        boolean first = true;
        List<Client> list = new ArrayList<>(clients.values());
        Collections.sort(list, (a, b) -> a.id.compareTo(b.id));
        for (Client c : list) {
            if (c.role == null) continue;
            if (!first) sb.append(',');
            first = false;
            sb.append("{\"peer\":").append(q(c.id)).append(",\"role\":").append(q(c.role))
              .append(",\"presence\":").append(q(c.presence == null ? "{}" : c.presence))
              .append(",\"at\":").append(c.at).append('}');
        }
        sb.append("]}");
        broadcast(sb.toString());
    }

    private void broadcast(String json) {
        for (Client c : clients.values()) {
            if (c.role == null) continue;
            try {
                c.send(json);
            } catch (IOException e) {
                c.close();
            }
        }
    }

    // ---------------------------------------------------------------- saved decks for browser players

    private String savesJson() {
        StringBuilder sb = new StringBuilder("{\"t\":\"saves\",\"names\":[");
        List<String> names = new ArrayList<>(saveNames.values());
        Collections.sort(names, String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < names.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(q(names.get(i)));
        }
        return sb.append("]}").toString();
    }

    /** A file-safe key for a character name: letters and digits, lowercased. */
    static String key(String name) {
        StringBuilder sb = new StringBuilder();
        for (char ch : name.toLowerCase(Locale.ROOT).toCharArray()) {
            if ((ch >= 'a' && ch <= 'z') || (ch >= '0' && ch <= '9')) sb.append(ch);
            if (sb.length() >= 40) break;
        }
        return sb.toString();
    }

    private void persist(String key, String name, String data) {
        if (saveDir == null) return;
        try {
            if (!saveDir.exists() && !saveDir.mkdirs()) return;
            try (FileOutputStream f = new FileOutputStream(new File(saveDir, key + ".json"))) {
                f.write((name + "\n" + data).getBytes(StandardCharsets.UTF_8));
            }
        } catch (IOException ignored) {
        }
    }

    private void loadSaves() {
        if (saveDir == null || !saveDir.isDirectory()) return;
        File[] list = saveDir.listFiles();
        if (list == null) return;
        for (File f : list) {
            if (!f.getName().endsWith(".json") || f.length() > MAX_SAVE + 100) continue;
            try (FileInputStream in = new FileInputStream(f)) {
                byte[] b = new byte[(int) f.length()];
                readFully(in, b);
                String s = new String(b, StandardCharsets.UTF_8);
                int k = s.indexOf('\n');
                if (k <= 0) continue;
                String name = s.substring(0, k), key = key(name);
                if (key.isEmpty()) continue;
                saves.put(key, s.substring(k + 1));
                saveNames.put(key, name);
            } catch (IOException ignored) {
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    /** This phone's addresses on local networks, hotspot first, as the GM should read them out. */
    public static List<String> addresses() {
        List<String> hot = new ArrayList<>(), other = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> it = NetworkInterface.getNetworkInterfaces();
            while (it != null && it.hasMoreElements()) {
                NetworkInterface ni = it.nextElement();
                try {
                    if (!ni.isUp() || ni.isLoopback()) continue;
                } catch (Exception e) {
                    continue;
                }
                String n = ni.getName() == null ? "" : ni.getName().toLowerCase(Locale.ROOT);
                if (n.startsWith("rmnet") || n.startsWith("ccmni") || n.startsWith("dummy") || n.startsWith("tun") || n.startsWith("ipsec") || n.startsWith("p2p")) continue;
                Enumeration<InetAddress> as = ni.getInetAddresses();
                while (as.hasMoreElements()) {
                    InetAddress a = as.nextElement();
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress() || a.isLinkLocalAddress()) continue;
                    String ip = a.getHostAddress();
                    boolean local = a.isSiteLocalAddress();
                    boolean likelyHotspot = n.startsWith("ap") || n.startsWith("swlan") || n.startsWith("softap") || n.startsWith("wlan1") || n.startsWith("wlan2") || ip.endsWith(".1");
                    if (!local) continue;
                    (likelyHotspot ? hot : other).add(ip);
                }
            }
        } catch (Exception ignored) {
        }
        hot.addAll(other);
        return hot;
    }

    /** JSON string literal. */
    public static String q(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 16).append('"');
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"': sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (ch < 0x20 || ch == 0x2028 || ch == 0x2029) sb.append(String.format(Locale.ROOT, "\\u%04x", (int) ch));
                    else sb.append(ch);
            }
        }
        return sb.append('"').toString();
    }

    private static final char[] B64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/".toCharArray();

    static String base64(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < d.length; i += 3) {
            int b = (d[i] & 0xFF) << 16 | (i + 1 < d.length ? (d[i + 1] & 0xFF) << 8 : 0) | (i + 2 < d.length ? (d[i + 2] & 0xFF) : 0);
            sb.append(B64[(b >> 18) & 63]).append(B64[(b >> 12) & 63]);
            sb.append(i + 1 < d.length ? B64[(b >> 6) & 63] : '=');
            sb.append(i + 2 < d.length ? B64[b & 63] : '=');
        }
        return sb.toString();
    }

    /** One phone at the table. */
    private static final class Client {
        final String id;
        final Socket socket;
        final OutputStream out;
        final boolean loopback;
        volatile String role;
        volatile String presence;
        volatile long at;

        Client(String id, Socket socket, OutputStream out, boolean loopback) {
            this.id = id;
            this.socket = socket;
            this.out = out;
            this.loopback = loopback;
        }

        void send(String text) throws IOException {
            sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
        }

        synchronized void sendFrame(int op, byte[] data) throws IOException {
            int len = data.length;
            if (len < 126) {
                out.write(new byte[]{(byte) (0x80 | op), (byte) len});
            } else if (len < 65536) {
                out.write(new byte[]{(byte) (0x80 | op), 126, (byte) (len >> 8), (byte) len});
            } else {
                out.write(new byte[]{(byte) (0x80 | op), 127, 0, 0, 0, 0, (byte) (len >> 24), (byte) (len >> 16), (byte) (len >> 8), (byte) len});
            }
            out.write(data);
            out.flush();
        }

        void close() {
            try {
                socket.close();
            } catch (IOException ignored) {
            }
        }
    }
}
