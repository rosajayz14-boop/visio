package com.jason.webtv;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Vérifie s'il existe une version plus récente de l'app publiée en Release
 * GitHub, et télécharge l'APK. L'installation elle-même est déclenchée par
 * MainActivity (boîte de dialogue + intent d'installation).
 *
 * Tout se fait sur un thread de fond ; aucune dépendance externe.
 */
public final class Updater {

    private static final String LATEST_API =
            "https://api.github.com/repos/rosajayz14-boop/visio/releases/latest";

    /** Infos sur la dernière Release. */
    public static final class Release {
        public final String tag;     // ex: "v2.8"
        public final String apkUrl;  // lien direct de app-debug.apk
        Release(String tag, String apkUrl) { this.tag = tag; this.apkUrl = apkUrl; }
    }

    private Updater() { }

    /** Récupère la dernière Release (ou null si erreur / pas d'APK). Thread de fond. */
    public static Release fetchLatest() {
        HttpURLConnection c = null;
        try {
            URL url = new URL(LATEST_API);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("Accept", "application/vnd.github+json");
            c.setRequestProperty("User-Agent", "WebTV-Updater");
            c.setConnectTimeout(12000);
            c.setReadTimeout(12000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() != 200) return null;

            StringBuilder sb = new StringBuilder();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(c.getInputStream()))) {
                String line;
                while ((line = br.readLine()) != null) sb.append(line);
            }
            JSONObject o = new JSONObject(sb.toString());
            String tag = o.optString("tag_name", "");
            String apkUrl = null;
            JSONArray assets = o.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) continue;
                    String name = a.optString("name", "");
                    if (name.endsWith(".apk")) {
                        apkUrl = a.optString("browser_download_url", null);
                        break;
                    }
                }
            }
            if (tag.isEmpty() || apkUrl == null) return null;
            return new Release(tag, apkUrl);
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /**
     * Compare une version distante ("v2.8" ou "2.8") à la version locale.
     * Retourne true si la distante est strictement plus récente.
     */
    public static boolean isNewer(String remoteTag, String localName) {
        int[] r = parse(remoteTag);
        int[] l = parse(localName);
        int n = Math.max(r.length, l.length);
        for (int i = 0; i < n; i++) {
            int rv = i < r.length ? r[i] : 0;
            int lv = i < l.length ? l[i] : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    private static int[] parse(String v) {
        if (v == null) return new int[]{0};
        String s = v.trim().toLowerCase();
        if (s.startsWith("v")) s = s.substring(1);
        String[] parts = s.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            int val = 0;
            try {
                StringBuilder d = new StringBuilder();
                for (int k = 0; k < parts[i].length(); k++) {
                    char ch = parts[i].charAt(k);
                    if (ch >= '0' && ch <= '9') d.append(ch); else break;
                }
                if (d.length() > 0) val = Integer.parseInt(d.toString());
            } catch (Exception ignored) { }
            out[i] = val;
        }
        return out;
    }

    /** Télécharge l'APK vers un fichier local. Retourne le fichier ou null. Thread de fond. */
    public static File download(Context ctx, String apkUrl) {
        HttpURLConnection c = null;
        try {
            File dir = ctx.getExternalFilesDir(null);
            if (dir == null) dir = ctx.getCacheDir();
            File out = new File(dir, "update.apk");
            if (out.exists()) out.delete();

            URL url = new URL(apkUrl);
            c = (HttpURLConnection) url.openConnection();
            c.setRequestProperty("User-Agent", "WebTV-Updater");
            c.setConnectTimeout(15000);
            c.setReadTimeout(30000);
            c.setInstanceFollowRedirects(true);
            if (c.getResponseCode() / 100 != 2) return null;

            try (InputStream in = c.getInputStream();
                 FileOutputStream fos = new FileOutputStream(out)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) != -1) fos.write(buf, 0, n);
            }
            return out;
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
