package com.jason.webtv;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Stockage simple et persistant des sites favoris (nom + URL) dans les
 * SharedPreferences, sérialisés en JSON. Aucune dépendance externe.
 *
 * Chaque favori est un couple {@code title} / {@code url}. Les doublons d'URL
 * sont évités (un nouvel ajout de la même URL met à jour le titre).
 */
public final class Favorites {

    /** Un favori = un titre affiché + une URL à ouvrir. */
    public static final class Item {
        public final String title;
        public final String url;
        public Item(String title, String url) {
            this.title = title;
            this.url = url;
        }
    }

    private static final String PREFS = "webtv_prefs";
    private static final String KEY = "favorites";

    private Favorites() { }

    private static SharedPreferences prefs(Context c) {
        return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Retourne la liste des favoris (jamais null), dans l'ordre d'ajout. */
    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<>();
        String raw = prefs(c).getString(KEY, "[]");
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o == null) continue;
                String url = o.optString("url", "").trim();
                if (TextUtils.isEmpty(url)) continue;
                String title = o.optString("title", "").trim();
                if (TextUtils.isEmpty(title)) title = url;
                out.add(new Item(title, url));
            }
        } catch (Exception ignored) { }
        return out;
    }

    /** Les favoris sérialisés en JSON, pour la page d'accueil (pont JavaScript). */
    public static String toJson(Context c) {
        JSONArray arr = new JSONArray();
        for (Item it : list(c)) {
            try {
                JSONObject o = new JSONObject();
                o.put("title", it.title);
                o.put("url", it.url);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        return arr.toString();
    }

    public static boolean contains(Context c, String url) {
        if (TextUtils.isEmpty(url)) return false;
        for (Item it : list(c)) {
            if (it.url.equals(url)) return true;
        }
        return false;
    }

    /**
     * Ajoute (ou met à jour) un favori. Retourne false si l'URL est vide ou
     * n'est pas une vraie page web (about:, la page d'accueil interne…).
     */
    public static boolean add(Context c, String title, String url) {
        if (TextUtils.isEmpty(url)) return false;
        url = url.trim();
        if (!(url.startsWith("http://") || url.startsWith("https://"))) return false;
        if (TextUtils.isEmpty(title)) title = url;

        List<Item> items = list(c);
        List<Item> kept = new ArrayList<>();
        for (Item it : items) {
            if (!it.url.equals(url)) kept.add(it);
        }
        kept.add(new Item(title.trim(), url));
        save(c, kept);
        return true;
    }

    /** Supprime le favori correspondant à cette URL. */
    public static void remove(Context c, String url) {
        if (TextUtils.isEmpty(url)) return;
        List<Item> items = list(c);
        List<Item> kept = new ArrayList<>();
        for (Item it : items) {
            if (!it.url.equals(url)) kept.add(it);
        }
        save(c, kept);
    }

    private static void save(Context c, List<Item> items) {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            try {
                JSONObject o = new JSONObject();
                o.put("title", it.title);
                o.put("url", it.url);
                arr.put(o);
            } catch (Exception ignored) { }
        }
        prefs(c).edit().putString(KEY, arr.toString()).apply();
    }
}
