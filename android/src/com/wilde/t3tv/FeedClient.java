package com.wilde.t3tv;

import android.content.Context;
import org.json.JSONObject;
import org.json.JSONException;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import javax.net.ssl.*;

final class FeedClient {
    static JSONObject readFile(File file) throws Exception {
        try (InputStream in = new FileInputStream(file)) { return new JSONObject(read(in)); }
    }

    static String read(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = in.read(buffer)) != -1) {
            out.write(buffer, 0, count);
            if (out.size() > 4 * 1024 * 1024) throw new IOException("Response too large");
        }
        return out.toString("UTF-8");
    }

    static JSONObject fetch(Context context) throws Exception {
        return request(context, "/v1/dashboard", null);
    }

    static JSONObject request(Context context, String route, JSONObject body) throws Exception {
        JSONObject config = readFile(new File(context.getFilesDir(), "connection.json"));
        URL url = new URL(config.getString("url") + route);
        if (!url.getProtocol().equals("https")) throw new IOException("HTTPS is required");
        KeyStore trust = KeyStore.getInstance(KeyStore.getDefaultType());
        trust.load(null);
        byte[] pem = config.getString("certificate").getBytes(StandardCharsets.US_ASCII);
        trust.setCertificateEntry("bridge", CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(pem)));
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(trust);
        SSLContext ssl = SSLContext.getInstance("TLS");
        ssl.init(null, factory.getTrustManagers(), null);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();
        connection.setSSLSocketFactory(ssl.getSocketFactory());
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(body == null ? 5000 : 90000);
        connection.setRequestProperty("Authorization", "Bearer " + config.getString("token"));
        try {
            if (body != null) {
                byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST");
                connection.setRequestProperty("Content-Type", "application/json");
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setDoOutput(true);
                try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
            }
            int status = connection.getResponseCode();
            if (status != 200) {
                String message = "Bridge returned HTTP " + status;
                InputStream error = connection.getErrorStream();
                if (error != null) try (InputStream in = error) { message = new JSONObject(read(in)).optString("error", message); } catch (JSONException ignored) {}
                throw new IOException(message);
            }
            try (InputStream in = connection.getInputStream()) { return new JSONObject(read(in)); }
        } finally { connection.disconnect(); }
    }
}
