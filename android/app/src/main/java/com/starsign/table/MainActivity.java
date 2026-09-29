package com.starsign.table;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.RouteInfo;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;

import androidx.webkit.WebViewAssetLoader;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.List;
import java.util.Locale;

import javax.net.SocketFactory;

/**
 * Star-Sign Table: the whole app runs inside this one screen, from files packed in the app.
 * No internet needed. The GM's phone can also host a table that other phones join over its
 * hotspot (TableServer); players with this app connect with LanClient.
 */
public class MainActivity extends Activity {
    private static final String HOST = "appassets.androidplatform.net";
    private static final String START = "https://" + HOST + "/assets/index.html";
    private static final int TABLE_PORT = 8765;

    private WebView web;
    private TableServer server;
    private LanClient client;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Keep the page clear of the status bar and navigation bar.
        FrameLayout root = new FrameLayout(this);
        root.setFitsSystemWindows(true);
        root.setBackgroundColor(Color.parseColor("#161C3F"));
        web = new WebView(this);
        web.setBackgroundColor(Color.parseColor("#0E1227"));
        root.addView(web, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        setContentView(root);

        WebSettings ws = web.getSettings();
        ws.setJavaScriptEnabled(true);
        ws.setDomStorageEnabled(true);
        ws.setDatabaseEnabled(true);
        ws.setAllowFileAccess(false);
        ws.setAllowContentAccess(false);
        ws.setTextZoom(100);

        // Serve the app's files from inside the app, at a stable secure address, so saved decks stay put.
        final WebViewAssetLoader loader = new WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", new WebViewAssetLoader.AssetsPathHandler(this))
                .build();
        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                return loader.shouldInterceptRequest(request.getUrl());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                Uri uri = request.getUrl();
                if (HOST.equals(uri.getHost())) return false;
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, uri));
                } catch (ActivityNotFoundException ignored) {
                }
                return true;
            }
        });
        web.addJavascriptInterface(new Bridge(), "StarSignNative");

        if (savedInstanceState != null) web.restoreState(savedInstanceState);
        else web.loadUrl(START);
    }

    /** What the page can ask of the phone. Every method runs off the main thread. */
    private class Bridge {
        /** Keep the screen on during a session (Settings in the app, and always while hosting). */
        @JavascriptInterface
        public void keepAwake(final boolean on) {
            runOnUiThread(() -> {
                if (on) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        }

        /** Start hosting the GM's table. Returns {"ok":true,"port":8765,"addresses":[...]}. */
        @JavascriptInterface
        public String startServer() {
            try {
                synchronized (MainActivity.this) {
                    if (server == null) server = new TableServer(MainActivity.this::readAsset, new File(getFilesDir(), "table-saves"));
                    int port = server.isRunning() ? server.getPort() : server.start(TABLE_PORT);
                    return "{\"ok\":true,\"port\":" + port + ",\"addresses\":" + jsonList(TableServer.addresses()) + "}";
                }
            } catch (Exception e) {
                return "{\"ok\":false,\"error\":" + TableServer.q(String.valueOf(e.getMessage())) + "}";
            }
        }

        @JavascriptInterface
        public String serverInfo() {
            synchronized (MainActivity.this) {
                boolean on = server != null && server.isRunning();
                return "{\"ok\":" + on + ",\"port\":" + (on ? server.getPort() : 0) + ",\"players\":" + (on ? server.clientCount() : 0)
                        + ",\"addresses\":" + jsonList(TableServer.addresses()) + "}";
            }
        }

        @JavascriptInterface
        public void stopServer() {
            synchronized (MainActivity.this) {
                if (server != null) server.stop();
            }
        }

        /** A player's guess at the GM's address: on the GM's hotspot, the GM's phone is the Wi-Fi gateway. */
        @JavascriptInterface
        public String findGm() {
            String gw = wifiGateway();
            return "{\"gateway\":" + (gw == null ? "null" : TableServer.q(gw)) + ",\"wifi\":" + (wifiNetwork() != null) + "}";
        }

        /** Connect this phone to a table. Events come back through window.__lanRecv(kind, text). */
        @JavascriptInterface
        public void lanConnect(String host, int port) {
            synchronized (MainActivity.this) {
                if (client != null) client.close();
                // Go over Wi-Fi even when the phone would rather use mobile data (the hotspot has no internet).
                Network wifi = isLoopback(host) ? null : wifiNetwork();
                SocketFactory factory = wifi != null ? wifi.getSocketFactory() : null;
                final LanClient[] self = new LanClient[1];
                self[0] = new LanClient(host, port, factory, new LanClient.Listener() {
                    @Override
                    public void onOpen() {
                        if (client == self[0]) toPage("open", "");
                    }

                    @Override
                    public void onMessage(String text) {
                        if (client == self[0]) toPage("msg", text);
                    }

                    @Override
                    public void onClose(String why) {
                        if (client == self[0]) toPage("close", why);
                    }
                });
                client = self[0];
                client.connect();
            }
        }

        @JavascriptInterface
        public void lanSend(String text) {
            LanClient c;
            synchronized (MainActivity.this) {
                c = client;
            }
            if (c != null) c.send(text);
        }

        @JavascriptInterface
        public void lanClose() {
            synchronized (MainActivity.this) {
                if (client != null) client.close();
                client = null;
            }
        }
    }

    private void toPage(String kind, String text) {
        final String js = "window.__lanRecv&&window.__lanRecv(" + TableServer.q(kind) + "," + TableServer.q(text) + ")";
        runOnUiThread(() -> {
            if (web != null) web.evaluateJavascript(js, null);
        });
    }

    private byte[] readAsset(String path) throws IOException {
        try (InputStream in = getAssets().open(path)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) != -1) b.write(buf, 0, n);
            return b.toByteArray();
        }
    }

    private Network wifiNetwork() {
        ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return null;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities c = cm.getNetworkCapabilities(n);
            if (c != null && c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return n;
        }
        return null;
    }

    @SuppressWarnings("deprecation")
    private String wifiGateway() {
        try {
            ConnectivityManager cm = (ConnectivityManager) getSystemService(Context.CONNECTIVITY_SERVICE);
            Network n = wifiNetwork();
            if (cm != null && n != null) {
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp != null) {
                    List<RouteInfo> routes = lp.getRoutes();
                    for (RouteInfo r : routes) {
                        InetAddress g = r.getGateway();
                        if (r.isDefaultRoute() && g instanceof Inet4Address && !g.isAnyLocalAddress()) return g.getHostAddress();
                    }
                }
            }
            WifiManager wm = (WifiManager) getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wm != null && wm.getDhcpInfo() != null && wm.getDhcpInfo().gateway != 0) {
                int ip = wm.getDhcpInfo().gateway;
                return String.format(Locale.ROOT, "%d.%d.%d.%d", ip & 0xff, (ip >> 8) & 0xff, (ip >> 16) & 0xff, (ip >> 24) & 0xff);
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean isLoopback(String host) {
        return "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host);
    }

    private static String jsonList(List<String> items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append(TableServer.q(items.get(i)));
        }
        return sb.append(']').toString();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        web.saveState(outState);
    }

    @Override
    protected void onPause() {
        web.onPause();
        super.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        web.onResume();
    }

    @Override
    protected void onDestroy() {
        synchronized (this) {
            if (client != null) client.close();
            if (server != null) server.stop();
        }
        if (web != null) web.destroy();
        super.onDestroy();
    }

    @Override
    public void onBackPressed() {
        // Back closes an open settings panel first, then leaves the app.
        web.evaluateJavascript(
                "(function(){var s=document.getElementById('sheet-root');if(s&&s.innerHTML){s.innerHTML='';return 'closed';}return 'none';})()",
                result -> {
                    if (!"\"closed\"".equals(result)) MainActivity.super.onBackPressed();
                });
    }
}
