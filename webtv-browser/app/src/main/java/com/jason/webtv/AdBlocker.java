package com.jason.webtv;

import android.content.Context;
import android.net.Uri;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.HashSet;
import java.util.Set;

/**
 * Bloqueur de publicités simple et rapide, basé sur une liste de domaines
 * (fichier assets/adblock_hosts.txt). Pour chaque requête réseau du navigateur,
 * on vérifie si le domaine — ou un de ses domaines parents — figure dans la liste.
 * Si oui, la requête est annulée (réponse vide), ce qui supprime la pub/traceur.
 */
public final class AdBlocker {

    // Volatile : construit sur un thread de fond, lu sur le thread réseau de la WebView.
    private static volatile Set<String> BLOCKED = null;
    private static volatile boolean loading = false;

    private AdBlocker() { }

    /** Charge la liste depuis les assets (une seule fois). À appeler sur un thread de fond. */
    public static synchronized void init(Context ctx) {
        if (BLOCKED != null || loading) return;
        loading = true;
        Set<String> set = new HashSet<>();
        try (InputStream is = ctx.getAssets().open("adblock_hosts.txt");
             BufferedReader br = new BufferedReader(new InputStreamReader(is))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#' || line.charAt(0) == '!') continue;

                String host = line;
                // Supporte le format "hosts" : "0.0.0.0 domaine.com"
                if (line.startsWith("0.0.0.0") || line.startsWith("127.0.0.1")) {
                    int sp = line.indexOf(' ');
                    if (sp < 0) sp = line.indexOf('\t');
                    if (sp > 0) host = line.substring(sp + 1).trim();
                }
                int sp2 = host.indexOf(' ');
                if (sp2 > 0) host = host.substring(0, sp2);
                host = host.trim().toLowerCase();

                if (!host.isEmpty() && !host.equals("localhost") && host.indexOf('.') > 0) {
                    set.add(host);
                }
            }
        } catch (Exception ignored) {
            // Pas de liste -> pas de blocage, l'app fonctionne quand même.
        }
        BLOCKED = set;
    }

    /** true si l'URL correspond à un domaine (ou sous-domaine) à bloquer. */
    public static boolean isAd(String url) {
        Set<String> b = BLOCKED;
        if (b == null || b.isEmpty() || url == null) return false;

        String host;
        try {
            host = Uri.parse(url).getHost();
        } catch (Exception e) {
            return false;
        }
        if (host == null) return false;
        host = host.toLowerCase();

        if (b.contains(host)) return true;
        // Vérifie les domaines parents : sub.ads.example.com -> ads.example.com -> example.com
        int dot = host.indexOf('.');
        while (dot >= 0 && dot < host.length() - 1) {
            if (b.contains(host.substring(dot + 1))) return true;
            dot = host.indexOf('.', dot + 1);
        }
        return false;
    }
}
