package com.starsign.table;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

import javax.net.SocketFactory;

/**
 * A player's connection to the GM's table (TableServer) over Wi-Fi: a small WebSocket client.
 * Plain Java, so it can be tested on a computer. Messages arrive on a background thread.
 */
public class LanClient {

    public interface Listener {
        void onOpen();

        void onMessage(String text);

        void onClose(String why);
    }

    private final String host;
    private final int port;
    private final SocketFactory factory;
    private final Listener listener;
    private final SecureRandom random = new SecureRandom();
    private Socket socket;
    private OutputStream out;
    private volatile boolean closed;

    /** {@code factory} decides which network the connection uses (the Wi-Fi one on Android). */
    public LanClient(String host, int port, SocketFactory factory, Listener listener) {
        this.host = host;
        this.port = port;
        this.factory = factory != null ? factory : SocketFactory.getDefault();
        this.listener = listener;
    }

    public void connect() {
        Thread t = new Thread(this::run, "table-client");
        t.setDaemon(true);
        t.start();
    }

    private void run() {
        String why = "closed";
        try {
            Socket s = factory.createSocket();
            s.connect(new InetSocketAddress(host, port), 6000);
            s.setTcpNoDelay(true);
            s.setSoTimeout(70000);
            socket = s;
            out = s.getOutputStream();
            InputStream in = s.getInputStream();
            byte[] nonce = new byte[16];
            random.nextBytes(nonce);
            String key = TableServer.base64(nonce);
            String req = "GET /ws HTTP/1.1\r\nHost: " + host + ":" + port + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                    + "Sec-WebSocket-Key: " + key + "\r\nSec-WebSocket-Version: 13\r\n\r\n";
            synchronized (this) {
                out.write(req.getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            }
            String head = readHead(in);
            if (head == null || !head.startsWith("HTTP/1.1 101")) throw new IOException("This isn't a Star-Sign table.");
            if (closed) return;
            listener.onOpen();
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            while (!closed) {
                int b0 = in.read();
                if (b0 == -1) break;
                int b1 = in.read();
                if (b1 == -1) break;
                boolean fin = (b0 & 0x80) != 0;
                int op = b0 & 0x0F;
                long len = b1 & 0x7F;
                if (len == 126) len = (read(in) << 8) | read(in);
                else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | read(in);
                }
                if (len > 4L * 1024 * 1024) throw new IOException("message too large");
                byte[] mask = null;
                if ((b1 & 0x80) != 0) {
                    mask = new byte[4];
                    readFully(in, mask);
                }
                byte[] data = new byte[(int) len];
                readFully(in, data);
                if (mask != null) for (int i = 0; i < data.length; i++) data[i] ^= mask[i & 3];
                if (op == 0x8) break;
                if (op == 0x9) {
                    frame(0xA, data);
                    continue;
                }
                if (op == 0xA) continue;
                if (op == 0x1 || op == 0x0) {
                    msg.write(data);
                    if (fin) {
                        listener.onMessage(new String(msg.toByteArray(), StandardCharsets.UTF_8));
                        msg.reset();
                    }
                }
            }
        } catch (IOException e) {
            why = e.getMessage() != null ? e.getMessage() : "connection lost";
        } finally {
            closeQuietly();
            listener.onClose(why);
        }
    }

    public void send(String text) {
        try {
            frame(0x1, text.getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            closeQuietly();
        }
    }

    public void close() {
        closed = true;
        try {
            frame(0x8, new byte[0]);
        } catch (IOException ignored) {
        }
        closeQuietly();
    }

    private synchronized void frame(int op, byte[] data) throws IOException {
        if (out == null) throw new IOException("not connected");
        int len = data.length;
        ByteArrayOutputStream f = new ByteArrayOutputStream(len + 14);
        f.write(0x80 | op);
        if (len < 126) f.write(0x80 | len);
        else if (len < 65536) {
            f.write(0x80 | 126);
            f.write(len >> 8);
            f.write(len);
        } else {
            f.write(0x80 | 127);
            for (int i = 7; i >= 0; i--) f.write(i >= 4 ? 0 : (len >> (8 * i)));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        f.write(mask, 0, 4);
        for (int i = 0; i < len; i++) f.write(data[i] ^ mask[i & 3]);
        out.write(f.toByteArray());
        out.flush();
    }

    private void closeQuietly() {
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
    }

    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int state = 0, c;
        while ((c = in.read()) != -1) {
            b.write(c);
            if (b.size() > 16384) return null;
            state = (c == '\r' && (state == 0 || state == 2)) ? state + 1 : (c == '\n' && (state == 1 || state == 3)) ? state + 1 : 0;
            if (state == 4) return new String(b.toByteArray(), StandardCharsets.ISO_8859_1);
        }
        return null;
    }

    private static int read(InputStream in) throws IOException {
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
}
