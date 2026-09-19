package myau.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import javax.net.ssl.HttpsURLConnection;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * Looks up the newest release of a GitHub repository and downloads its jar.
 * <p>
 * Everything here is deliberately narrow: only HTTPS, only {@code api.github.com} and
 * {@code *.githubusercontent.com}, only a {@code .jar} asset, and the download is verified to be a
 * real zip before it is ever allowed near the mods folder.
 */
public final class UpdateChecker {
    private static final String USER_AGENT = "Myau-Updater";
    private static final int TIMEOUT_MS = 10_000;
    /** A client jar is tens of megabytes; anything wildly bigger is not what we asked for. */
    private static final long MAX_DOWNLOAD_BYTES = 256L * 1024L * 1024L;

    private UpdateChecker() {
    }

    /** One release: its tag and the jar asset to download. */
    public static final class Release {
        public final String tag;
        public final String name;
        public final String downloadUrl;
        public final long size;

        Release(String tag, String name, String downloadUrl, long size) {
            this.tag = tag;
            this.name = name;
            this.downloadUrl = downloadUrl;
            this.size = size;
        }
    }

    private static void reject(String url) throws IOException {
        throw new IOException("Refusing non-GitHub URL: " + url);
    }

    private static HttpsURLConnection open(String url, String accept) throws IOException {
        URL parsed = new URL(url);
        if (!"https".equalsIgnoreCase(parsed.getProtocol())) {
            reject(url);
        }
        String host = parsed.getHost().toLowerCase(java.util.Locale.ROOT);
        if (!host.equals("api.github.com") && !host.equals("github.com")
                && !host.endsWith(".githubusercontent.com")) {
            reject(url);
        }
        HttpsURLConnection connection = (HttpsURLConnection) parsed.openConnection();
        connection.setRequestProperty("User-Agent", USER_AGENT);
        connection.setRequestProperty("Accept", accept);
        connection.setConnectTimeout(TIMEOUT_MS);
        connection.setReadTimeout(TIMEOUT_MS);
        connection.setInstanceFollowRedirects(true);
        return connection;
    }

    /**
     * @return the latest release of {@code owner/repo}, or null when the repository has no
     *         release carrying a jar.
     */
    public static Release fetchLatest(String owner, String repo) throws IOException {
        String url = String.format("https://api.github.com/repos/%s/%s/releases/latest",
                owner.trim(), repo.trim());
        HttpsURLConnection connection = open(url, "application/vnd.github+json");
        try {
            int status = connection.getResponseCode();
            if (status == 404) {
                return null; // no releases yet
            }
            if (status != 200) {
                throw new IOException("GitHub returned HTTP " + status);
            }
            JsonObject json;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                JsonElement parsed = new JsonParser().parse(reader);
                if (parsed == null || !parsed.isJsonObject()) {
                    throw new IOException("Unexpected response from GitHub");
                }
                json = parsed.getAsJsonObject();
            }
            if (json.has("draft") && json.get("draft").getAsBoolean()) {
                return null;
            }
            String tag = json.has("tag_name") ? json.get("tag_name").getAsString() : null;
            if (tag == null || tag.isEmpty()) {
                return null;
            }
            if (!json.has("assets") || !json.get("assets").isJsonArray()) {
                return null;
            }
            JsonArray assets = json.getAsJsonArray("assets");
            for (JsonElement element : assets) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject asset = element.getAsJsonObject();
                String name = asset.has("name") ? asset.get("name").getAsString() : "";
                if (!name.toLowerCase(java.util.Locale.ROOT).endsWith(".jar")) {
                    continue;
                }
                String download = asset.has("browser_download_url") ? asset.get("browser_download_url").getAsString() : null;
                if (download == null) {
                    continue;
                }
                long size = asset.has("size") ? asset.get("size").getAsLong() : -1L;
                return new Release(tag, name, download, size);
            }
            return null;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * Downloads the release jar to {@code destination}. The file is only kept if the whole body
     * arrived and starts with the zip magic bytes, so a truncated or error-page download can
     * never be installed.
     */
    public static void download(Release release, File destination) throws IOException {
        HttpsURLConnection connection = open(release.downloadUrl, "application/octet-stream");
        File partial = new File(destination.getAbsolutePath() + ".part");
        try {
            if (connection.getResponseCode() != 200) {
                throw new IOException("Download returned HTTP " + connection.getResponseCode());
            }
            File parent = partial.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            long written = 0L;
            try (InputStream input = connection.getInputStream();
                 OutputStream output = new FileOutputStream(partial)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) != -1) {
                    written += read;
                    if (written > MAX_DOWNLOAD_BYTES) {
                        throw new IOException("Download exceeded the size limit");
                    }
                    output.write(buffer, 0, read);
                }
            }
            if (release.size > 0L && written != release.size) {
                throw new IOException("Download was " + written + " bytes, expected " + release.size);
            }
            if (!isZip(partial)) {
                throw new IOException("Downloaded file is not a jar");
            }
            if (destination.exists() && !destination.delete()) {
                throw new IOException("Could not replace " + destination.getName());
            }
            if (!partial.renameTo(destination)) {
                throw new IOException("Could not finish writing " + destination.getName());
            }
        } finally {
            connection.disconnect();
            if (partial.exists()) {
                partial.delete();
            }
        }
    }

    private static boolean isZip(File file) {
        try (InputStream input = new java.io.FileInputStream(file)) {
            byte[] magic = new byte[2];
            return input.read(magic) == 2 && magic[0] == 'P' && magic[1] == 'K';
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Compares release tags like {@code v2.2}, {@code 2.1+4} or {@code b3-26.3}: the numbers in
     * each are compared in order, and anything the numbers can't separate falls back to "different
     * text means newer", which is the safe answer for a tag the author just published.
     */
    public static boolean isNewer(String latestTag, String current) {
        if (latestTag == null || latestTag.isEmpty()) {
            return false;
        }
        if (current == null || current.isEmpty() || "dev".equalsIgnoreCase(current)) {
            return false; // a dev build never auto-updates itself
        }
        int[] latestNumbers = numbers(latestTag);
        int[] currentNumbers = numbers(current);
        int length = Math.max(latestNumbers.length, currentNumbers.length);
        for (int i = 0; i < length; i++) {
            int a = i < latestNumbers.length ? latestNumbers[i] : 0;
            int b = i < currentNumbers.length ? currentNumbers[i] : 0;
            if (a != b) {
                return a > b;
            }
        }
        return false; // same numbers: same build
    }

    private static int[] numbers(String text) {
        String[] parts = text.replaceAll("^[vV]", "").split("[^0-9]+");
        int count = 0;
        int[] values = new int[parts.length];
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            try {
                values[count++] = Integer.parseInt(part);
            } catch (NumberFormatException ignored) {
                // A segment too large to be a version number; treat it as zero.
                values[count++] = 0;
            }
        }
        int[] trimmed = new int[count];
        System.arraycopy(values, 0, trimmed, 0, count);
        return trimmed;
    }
}
