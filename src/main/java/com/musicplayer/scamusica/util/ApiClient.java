package com.musicplayer.scamusica.util;

import javax.net.ssl.HttpsURLConnection;
import javax.crypto.CipherOutputStream;
import java.net.HttpURLConnection;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class ApiClient {

    private static final int TIMEOUT = 15000;
    private static final int BUFFER_SIZE = 8192;

    public interface ProgressCallback {
        void onProgress(long bytesRead, long contentLength);
    }

    public static String get(String urlString, Map<String, String> headers) throws Exception {
        System.out.println("[ApiClient][GET] URL = " + urlString);
        HttpsURLConnection connection = createConnection(urlString, "GET", headers);
        return getResponse(connection);
    }

    public static String post(String urlString, String jsonBody, Map<String, String> headers) throws Exception {
        System.out.println("[ApiClient][POST] URL = " + urlString);
        System.out.println("[ApiClient][POST] Body = " + jsonBody);
        HttpsURLConnection connection = createConnection(urlString, "POST", headers);
        writeBody(connection, jsonBody);
        return getResponse(connection);
    }

    public static String put(String urlString, String jsonBody, Map<String, String> headers) throws Exception {
        System.out.println("[ApiClient][PUT] URL = " + urlString);
        System.out.println("[ApiClient][PUT] Body = " + jsonBody);
        HttpsURLConnection connection = createConnection(urlString, "PUT", headers);
        writeBody(connection, jsonBody);
        return getResponse(connection);
    }

    public static String delete(String urlString, Map<String, String> headers) throws Exception {
        System.out.println("[ApiClient][DELETE] URL = " + urlString);
        HttpsURLConnection connection = createConnection(urlString, "DELETE", headers);
        return getResponse(connection);
    }

    private static HttpsURLConnection createConnection(String urlString,
                                                       String method,
                                                       Map<String, String> headers) throws Exception {
        URL url = new URL(urlString);
        HttpsURLConnection connection = (HttpsURLConnection) url.openConnection();

        connection.setReadTimeout(TIMEOUT);
        connection.setConnectTimeout(TIMEOUT);
        connection.setRequestMethod(method);
        connection.setDoInput(true);

        if ("POST".equals(method) || "PUT".equals(method)) {
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json");
        }
        
        connection.setRequestProperty("Connection", "close");

        if (headers != null) {
            for (String key : headers.keySet()) {
                connection.setRequestProperty(key, headers.get(key));
            }
        }

        System.out.println("[ApiClient] Method = " + method);
        System.out.println("[ApiClient] Headers = " + headers);

        return connection;
    }


    private static String getResponse(HttpsURLConnection connection) throws Exception {
        int status = connection.getResponseCode();
        System.out.println("[ApiClient] HTTP Status = " + status);

        try (InputStream is = (status < HttpsURLConnection.HTTP_BAD_REQUEST)
                ? connection.getInputStream()
                : connection.getErrorStream()) {

            if (is == null) {
                System.out.println("[ApiClient] Response stream is NULL");
                connection.disconnect();
                return "";
            }

            StringBuilder response = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                String line;
                while ((line = br.readLine()) != null) {
                    response.append(line);
                }
            }

            connection.disconnect();

            System.out.println("[ApiClient] Raw Response = " + response);
            return response.toString();
        } catch (Exception e) {
            connection.disconnect();
            throw e;
        }
    }

    public static boolean downloadToFile(String urlString,
                                         Map<String, String> headers,
                                         File outputFile,
                                         ProgressCallback progressCallback) throws Exception {

        System.out.println("[ApiClient][DOWNLOAD] URL = " + urlString);
        System.out.println("[ApiClient][DOWNLOAD] Output = " + outputFile.getAbsolutePath());

        HttpsURLConnection connection = createConnection(urlString, "GET", headers);
        connection.setDoInput(true);
        connection.setConnectTimeout(TIMEOUT);
        connection.setReadTimeout(TIMEOUT);

        int status = connection.getResponseCode();
        System.out.println("[ApiClient][DOWNLOAD] HTTP Status = " + status);

        if (status >= HttpsURLConnection.HTTP_BAD_REQUEST) {
            try (InputStream err = connection.getErrorStream()) {
                if (err != null) {
                    try (BufferedReader br = new BufferedReader(new InputStreamReader(err))) {
                        StringBuilder sb = new StringBuilder();
                        String line;
                        while ((line = br.readLine()) != null) sb.append(line);
                        System.out.println("[ApiClient][DOWNLOAD] Error Response = " + sb);
                    }
                }
            } catch (Exception ignored) {}
            connection.disconnect();
            return false;
        }

        long contentLength = connection.getContentLengthLong();
        System.out.println("[ApiClient][DOWNLOAD] Content-Length = " + contentLength);

        InputStream is = connection.getInputStream();

        try (BufferedInputStream in = new BufferedInputStream(is);
             FileOutputStream fos = new FileOutputStream(outputFile);
             BufferedOutputStream bout = new BufferedOutputStream(fos, BUFFER_SIZE)) {

            byte[] buffer = new byte[BUFFER_SIZE];
            int bytesRead;
            long totalRead = 0L;

            while ((bytesRead = in.read(buffer)) != -1) {
                bout.write(buffer, 0, bytesRead);
                totalRead += bytesRead;

                if (progressCallback != null) {
                    try {
                        progressCallback.onProgress(totalRead, contentLength);
                    } catch (Exception ignored) {
                    }
                }
            }
            bout.flush();
        } finally {
            connection.disconnect();
        }

        System.out.println("[ApiClient][DOWNLOAD] Completed successfully");
        return true;
    }

    private static final int DOWNLOAD_TIMEOUT = 30000;

    public static boolean downloadEncrypted(String urlStr,
                                            Map<String, String> headers,
                                            File outFile,
                                            ProgressCallback callback) {

        HttpURLConnection connection = null;
        String currentUrlStr = urlStr;
        int redirectCount = 0;
        final int MAX_REDIRECTS = 5;

        try {
            while (redirectCount < MAX_REDIRECTS) {
                URL url = new URL(currentUrlStr);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setRequestProperty("Connection", "close");
                connection.setConnectTimeout(DOWNLOAD_TIMEOUT);
                connection.setReadTimeout(DOWNLOAD_TIMEOUT);

                connection.setInstanceFollowRedirects(false);

                if (headers != null) {
                    URL originalUrl = new URL(urlStr);
                    boolean isSameHost = url.getHost().equalsIgnoreCase(originalUrl.getHost());
                    for (Map.Entry<String, String> entry : headers.entrySet()) {
                        if (!isSameHost && "Authorization".equalsIgnoreCase(entry.getKey())) {
                            continue;
                        }
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }

                connection.connect();

                int responseCode = connection.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_MOVED_PERM
                        || responseCode == HttpURLConnection.HTTP_MOVED_TEMP
                        || responseCode == HttpURLConnection.HTTP_SEE_OTHER
                        || responseCode == 307
                        || responseCode == 308) {

                    String location = connection.getHeaderField("Location");
                    connection.disconnect();

                    if (location == null || location.trim().isEmpty()) {
                        System.err.println("[ApiClient][DOWNLOAD] Redirected with empty Location header");
                        return false;
                    }

                    if (location.startsWith("/")) {
                        URL base = new URL(currentUrlStr);
                        location = base.getProtocol() + "://" + base.getHost() + (base.getPort() != -1 ? ":" + base.getPort() : "") + location;
                    }

                    System.out.println("[ApiClient][DOWNLOAD] Following redirect (" + responseCode + ") -> " + location);
                    currentUrlStr = location;
                    redirectCount++;
                    continue;
                }

                if (responseCode < 200 || responseCode >= 300) {
                    System.err.println("[ApiClient][DOWNLOAD] HTTP error " + responseCode + " for URL: " + currentUrlStr);
                    return false;
                }

                long contentLength = connection.getContentLengthLong();

                try (InputStream in = connection.getInputStream();
                     FileOutputStream fos = new FileOutputStream(outFile);
                     CipherOutputStream cos = CryptoUtil.encrypt(fos)) {

                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    long total = 0;

                    while ((bytesRead = in.read(buffer)) != -1) {
                        cos.write(buffer, 0, bytesRead);
                        cos.flush();
                        total += bytesRead;

                        if (callback != null) {
                            callback.onProgress(total, contentLength);
                        }
                    }

                    if (contentLength > 0 && total < contentLength) {
                        System.out.println("[ApiClient][DOWNLOAD] Incomplete download: " 
                            + total + "/" + contentLength + " bytes");
                        return false;
                    }
                }

                return true;
            }

            System.err.println("[ApiClient][DOWNLOAD] Too many redirects for: " + urlStr);
            return false;

        } catch (Exception e) {
            System.err.println("[ApiClient][DOWNLOAD] Exception downloading " + urlStr + ": " + e.getMessage());
            e.printStackTrace();
            return false;

        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static void writeBody(HttpsURLConnection connection, String jsonBody) throws IOException {
        if (jsonBody != null && !jsonBody.isEmpty()) {
            System.out.println("[ApiClient] Writing request body");
            try (OutputStream os = connection.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }
        } else {
            System.out.println("[ApiClient] No request body to write");
        }
    }

}
