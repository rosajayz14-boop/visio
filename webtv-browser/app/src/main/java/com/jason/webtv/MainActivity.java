package com.jason.webtv;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Bundle;
import android.os.Message;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.util.Collections;
import java.util.List;

/**
 * Navigateur web plein écran pour Android TV / Fire TV / Google TV Streamer 4K.
 *  - Bloqueur de pub intégré (voir AdBlocker)
 *  - Mode curseur : la télécommande déplace un pointeur et clique dans la page
 *  - Lecture vidéo HTML5 plein écran
 *  - Barre d'adresse appelée par la touche MENU de la télécommande
 */
public class MainActivity extends AppCompatActivity {

    private static final String HOME_URL = "file:///android_asset/home.html";
    private static final String DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Safari/537.36";
    // UA mobile : certains lecteurs vidéo ne proposent leur player HTML5 qu'en mobile.
    private static final String MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) "
            + "Chrome/124.0.0.0 Mobile Safari/537.36";

    private FrameLayout root;
    private WebView web;
    private ImageView cursor;
    private ProgressBar progress;
    private View bar;
    private EditText url;
    private Button btnCursor, btnAd, btnAddFav, btnFav, btnUA, btnPopup;

    private LinearLayout favOverlay;
    private LinearLayout favList;
    private TextView favEmpty;

    private FrameLayout popupContainer;
    private WebView popupWeb;

    private volatile boolean adblockEnabled = true;
    // Popups bloqués par défaut : indispensable pour les sites de streaming qui
    // ouvrent une pub au clic sur « Play ». Une fois le popup bloqué, la vidéo
    // se lance en place. Activable si un site met vraiment son lecteur en popup.
    private boolean allowPopups = false;
    // 0 = Auto (UA système, meilleur pour Cloudflare/connexions), 1 = PC, 2 = Mobile
    private int uaMode = 0;
    private String autoUa = null;
    private boolean cursorMode = true;
    private boolean cursorInit = false;
    private float cursorX, cursorY;
    private final int baseStep = 28;
    private long lastBack = 0L;
    private long centerDownAt = 0L;
    private static final long LONG_PRESS_MS = 450L;

    // Réglages mémorisés entre les lancements
    private static final String PREFS = "webtv_prefs";
    private static final String K_UA = "ua_mode";
    private static final String K_POPUPS = "popups";
    private static final String K_ADBLOCK = "adblock";
    private static final String K_CURSOR = "cursor";

    // Le curseur se cache tout seul après un moment sans bouger (il ne reste
    // pas planté au milieu de la vidéo) et réapparaît au premier appui.
    private static final long CURSOR_HIDE_MS = 3000L;
    private final Runnable hideCursorRunnable = () -> {
        if (cursorMode && cursor.getVisibility() == View.VISIBLE) {
            cursor.setVisibility(View.INVISIBLE);
        }
    };

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;
    // WebView qui a demandé le plein écran : c'est à elle qu'on transmet les
    // touches (◄ ► OK…) pour que le lecteur du site les reçoive.
    private WebView fullscreenWeb;

    // Zoom du texte (lisibilité TV), mémorisé
    private static final String K_TEXTZOOM = "text_zoom";
    private static final int[] TEXT_ZOOMS = {75, 100, 125, 150, 175};
    private int textZoom = 100;

    // Mise à jour : version ignorée par l'utilisateur, et mise à jour en attente
    // d'autorisation d'installation (reprise dans onResume).
    private static final String K_SKIP_TAG = "skip_tag";
    private Updater.Release pendingUpdate;

    // Vrai uniquement quand la page d'accueil interne est affichée : le pont
    // AndroidFav (lecture des favoris) n'est exposé qu'à elle.
    private volatile boolean onHomePage = false;

    // Erreurs SSL : un seul message par courte période (évite le spam de toasts)
    private long lastSslToast = 0L;

    // ---- Contrôle vidéo universel (voir section « Contrôle vidéo ») ----
    // Un script injecté dans CHAQUE frame (y compris les lecteurs d'un autre
    // domaine) signale la présence d'une vidéo et exécute nos commandes.
    private TextView osd;
    private final Object videoLock = new Object();
    private String videoCmd = null;      // commande en attente : "seek:10", "toggle"…
    private long videoCmdAt = 0L;
    private volatile long videoSeenAt = 0L;      // dernier signalement d'une vidéo
    private volatile boolean videoPlaying = false;
    private volatile int pauseGen = 0;           // incrémenté = toutes les frames se mettent en pause
    private final Runnable osdHideRunnable = () -> { if (osd != null) osd.setVisibility(View.GONE); };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        root = findViewById(R.id.root);
        web = findViewById(R.id.web);
        cursor = findViewById(R.id.cursor);
        progress = findViewById(R.id.progress);
        bar = findViewById(R.id.bar);
        url = findViewById(R.id.url);
        btnCursor = findViewById(R.id.btnCursor);
        btnAd = findViewById(R.id.btnAd);
        btnAddFav = findViewById(R.id.btnAddFav);
        btnFav = findViewById(R.id.btnFav);
        btnUA = findViewById(R.id.btnUA);
        btnPopup = findViewById(R.id.btnPopup);
        favOverlay = findViewById(R.id.favOverlay);
        favList = findViewById(R.id.favList);
        favEmpty = findViewById(R.id.favEmpty);
        popupContainer = findViewById(R.id.popupContainer);
        osd = findViewById(R.id.osd);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Nettoie un éventuel APK de mise à jour déjà installé.
        try {
            File dir = getExternalFilesDir(null);
            File old = new File(dir != null ? dir : getCacheDir(), "update.apk");
            if (old.exists()) old.delete();
        } catch (Exception ignored) { }

        // Charge la liste anti-pub en arrière-plan pour ne pas bloquer le démarrage.
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() { AdBlocker.init(app); }
        }).start();

        loadSettings();
        configureWebView();
        setupButtons();
        updateToggleLabels();

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        boolean restored = savedInstanceState != null
                && web.restoreState(savedInstanceState) != null;

        if (restored) {
            // Android avait tué l'app en arrière-plan : on reprend la page et
            // l'historique là où ils étaient.
            bar.setVisibility(View.GONE);
            web.requestFocus();
            root.post(this::ensureCursorVisible);
        } else {
            web.loadUrl(HOME_URL);
            if (Favorites.list(getApplicationContext()).isEmpty()) {
                // Premier lancement : barre d'adresse prête à recevoir une URL.
                bar.setVisibility(View.VISIBLE);
                cursor.setVisibility(View.GONE);
                url.requestFocus();
            } else {
                // Des favoris existent : on arrive directement sur la grille, curseur
                // prêt, sans clavier qui s'ouvre tout seul.
                bar.setVisibility(View.GONE);
                web.requestFocus();
                root.post(this::ensureCursorVisible);
            }
        }

        checkForUpdate();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        if (web != null) web.saveState(outState);
    }

    // ---------------------------------------------------------------- Réglages

    private void loadSettings() {
        android.content.SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        uaMode = p.getInt(K_UA, 0);
        if (uaMode < 0 || uaMode > 2) uaMode = 0;
        allowPopups = p.getBoolean(K_POPUPS, false);
        adblockEnabled = p.getBoolean(K_ADBLOCK, true);
        cursorMode = p.getBoolean(K_CURSOR, true);
        textZoom = p.getInt(K_TEXTZOOM, 100);
        if (textZoom < 50 || textZoom > 300) textZoom = 100;
    }

    private void saveSettings() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putInt(K_UA, uaMode)
                .putBoolean(K_POPUPS, allowPopups)
                .putBoolean(K_ADBLOCK, adblockEnabled)
                .putBoolean(K_CURSOR, cursorMode)
                .putInt(K_TEXTZOOM, textZoom)
                .apply();
    }

    /** Applique le zoom texte à toutes les WebView ouvertes. */
    private void applyTextZoom() {
        if (web != null) web.getSettings().setTextZoom(textZoom);
        if (popupWeb != null) popupWeb.getSettings().setTextZoom(textZoom);
    }

    // ---------------------------------------------------------- Mise à jour auto

    /** Vérifie en arrière-plan s'il existe une version plus récente sur GitHub. */
    private void checkForUpdate() {
        final String skipped = getSharedPreferences(PREFS, MODE_PRIVATE).getString(K_SKIP_TAG, "");
        new Thread(() -> {
            final Updater.Release rel = Updater.fetchLatest();
            if (rel == null) return;
            if (rel.tag.equals(skipped)) return; // version ignorée par l'utilisateur
            String local;
            try {
                PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
                local = pi.versionName;
            } catch (Exception e) {
                local = "0";
            }
            if (!Updater.isNewer(rel.tag, local)) return;
            runOnUiThread(() -> promptUpdate(rel));
        }).start();
    }

    private void promptUpdate(final Updater.Release rel) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this)
                .setTitle("Mise à jour disponible")
                .setMessage("Une nouvelle version (" + rel.tag + ") est disponible.\n"
                        + "Voulez-vous l'installer maintenant ?")
                .setPositiveButton("Installer", (d, w) -> startUpdateDownload(rel))
                .setNegativeButton("Plus tard", null)
                .setNeutralButton("Ignorer cette version", (d, w) ->
                        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                                .putString(K_SKIP_TAG, rel.tag).apply())
                .show();
    }

    private void startUpdateDownload(final Updater.Release rel) {
        // Sur Android 8+, l'app doit être autorisée à installer des applis.
        // On mémorise la mise à jour : dès que l'autorisation est donnée et
        // qu'on revient dans l'app, le téléchargement reprend tout seul.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !getPackageManager().canRequestPackageInstalls()) {
            pendingUpdate = rel;
            Toast.makeText(this,
                    "Autorise WebTV à installer des applications : la mise à jour reprendra au retour",
                    Toast.LENGTH_LONG).show();
            try {
                startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignored) { }
            return;
        }
        pendingUpdate = null;

        Toast.makeText(this, "Téléchargement de la mise à jour…", Toast.LENGTH_SHORT).show();
        final Context app = getApplicationContext();
        new Thread(() -> {
            final File apk = Updater.download(app, rel.apkUrl);
            runOnUiThread(() -> {
                if (apk == null) {
                    Toast.makeText(this, "Échec du téléchargement de la mise à jour",
                            Toast.LENGTH_SHORT).show();
                } else {
                    installApk(apk);
                }
            });
        }).start();
    }

    private void installApk(File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(
                    this, getPackageName() + ".fileprovider", apk);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(uri, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "Impossible de lancer l'installation", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------------------------------------------------------------- WebView

    private void configureWebView() {
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        // Multi-fenêtres activé : on récupère les popups (lecteurs qui s'ouvrent
        // dans un nouvel onglet) pour les charger dans la fenêtre principale.
        s.setSupportMultipleWindows(true);
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);

        // UA « Auto » : on part du vrai UA de la WebView et on enlève les marqueurs
        // « wv » et « Version/4.0 » qui trahissent une WebView. Résultat : un UA
        // Chrome Android cohérent, qui passe beaucoup mieux les protections
        // Cloudflare et les pages de connexion.
        autoUa = s.getUserAgentString()
                .replace("; wv", "")
                .replaceAll("Version/\\d+\\.\\d+\\s*", "")
                .replaceAll("\\s+", " ")
                .trim();
        s.setUserAgentString(currentUa());
        s.setTextZoom(textZoom);

        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        // Pont pour que la page d'accueil (home.html) affiche les favoris.
        web.addJavascriptInterface(new FavBridge(), "AndroidFav");
        // Pont + script de contrôle vidéo, dans toutes les frames.
        web.addJavascriptInterface(new VideoBridge(), "AndroidVideo");
        installVideoScript(web);

        web.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
                if (adblockEnabled && request != null && request.getUrl() != null
                        && AdBlocker.isAd(request.getUrl().toString())) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(view, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return handleOverride(request != null && request.getUrl() != null
                        ? request.getUrl().toString() : null);
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String u) {
                return handleOverride(u);
            }

            @Override
            public void onPageStarted(WebView view, String u, Bitmap favicon) {
                if (u != null) url.setText(u);
                onHomePage = u != null && u.startsWith(HOME_URL);
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                if (u != null) url.setText(u);
                onHomePage = u != null && u.startsWith(HOME_URL);
                // Secours si l'injection « document start » n'est pas disponible :
                // au moins la frame principale reçoit le script (garde anti-doublon).
                view.evaluateJavascript(VIDEO_SCRIPT, null);
            }

            @Override
            public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error) {
                handler.cancel(); // on ne contourne pas la sécurité, mais on l'explique
                sslToast(error != null ? error.getUrl() : null);
            }

            @Override
            public void onReceivedError(WebView view, WebResourceRequest request,
                                        WebResourceError error) {
                if (request != null && request.isForMainFrame()) {
                    Toast.makeText(MainActivity.this,
                            "Impossible de charger la page" + (error != null
                                    ? " (" + error.getDescription() + ")" : ""),
                            Toast.LENGTH_SHORT).show();
                }
            }
        });

        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView view, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }

            // Popups / target="_blank" / window.open : on capture l'URL cible via
            // une WebView temporaire et on la charge dans la fenêtre principale.
            // Indispensable pour de nombreux sites de streaming.
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                // Popups bloqués : on n'ouvre rien -> la vidéo se joue en place
                // sur les sites de streaming à pub.
                if (!allowPopups) {
                    // …mais un VRAI lien « nouvel onglet » s'ouvre dans la même page,
                    // sinon le clic ne ferait rien du tout.
                    openAnchorInSameWindow(view, web);
                    return false;
                }
                // Popups autorisés : on ouvre le popup dans une fenêtre superposée
                // séparée. La page d'origine reste intacte dessous ; Retour ferme
                // le popup et y revient exactement.
                openPopupWindow(resultMsg);
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                if (window == popupWeb) closePopup();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showFullscreen(web, view, callback);
            }

            @Override
            public void onHideCustomView() {
                hideFullscreen();
            }
        });
    }

    /** Garde le http/https dans l'app, ignore les schémas externes (intent://, market://…). */
    private boolean handleOverride(String u) {
        if (u == null) return false;
        // Seuls http(s) et about: sont navigables depuis une page. Les pages
        // internes (file://) ne sont chargées que par l'app elle-même (loadUrl).
        return !(u.startsWith("http://") || u.startsWith("https://")
                || u.startsWith("about:"));
    }

    private void navigateTo(String input) {
        if (input == null) return;
        String u = input.trim();
        if (u.isEmpty()) return;
        if (u.startsWith("http://") || u.startsWith("https://")
                || u.startsWith("file:") || u.startsWith("about:")) {
            web.loadUrl(u);
        } else if (!u.contains(" ") && u.contains(".")) {
            web.loadUrl("https://" + u);
        } else {
            web.loadUrl("https://www.google.com/search?q=" + android.net.Uri.encode(u));
        }
    }

    /** Met en pause les vidéos/audios de la page courante (le son ne continue
     *  pas quand on quitte la vidéo en reculant). */
    private static final String PAUSE_JS =
            "(function(){function c(d){try{var m=d.querySelectorAll('video,audio');"
            + "for(var i=0;i<m.length;i++){try{m[i].pause();}catch(e){}}"
            + "var f=d.querySelectorAll('iframe');for(var j=0;j<f.length;j++){"
            + "try{if(f[j].contentDocument)c(f[j].contentDocument);}catch(e){}}}catch(e){}}"
            + "c(document);})();";

    private void pauseMedia(WebView w) {
        if (w == null) return;
        pauseGen++; // les frames (même d'un autre domaine) se mettent en pause
        try {
            w.evaluateJavascript(PAUSE_JS, null);
        } catch (Exception ignored) { }
    }

    /** Met en pause PUIS exécute l'action (ex. goBack) : plus de course entre le
     *  JavaScript asynchrone et la navigation. */
    private void pauseThen(WebView w, Runnable then) {
        if (w == null) { then.run(); return; }
        pauseGen++;
        try {
            w.evaluateJavascript(PAUSE_JS, v -> then.run());
        } catch (Exception e) {
            then.run();
        }
    }

    /** Popups bloqués : si le clic était un VRAI lien (target=_blank), on l'ouvre
     *  dans la fenêtre indiquée au lieu de ne rien faire. */
    private void openAnchorInSameWindow(WebView from, WebView into) {
        try {
            WebView.HitTestResult r = from.getHitTestResult();
            if (r != null && r.getType() == WebView.HitTestResult.SRC_ANCHOR_TYPE) {
                String u = r.getExtra();
                if (u != null && (u.startsWith("http://") || u.startsWith("https://"))
                        && into != null) {
                    into.loadUrl(u);
                }
            }
        } catch (Exception ignored) { }
    }

    /** User-Agent courant selon le mode choisi (Auto / PC / Mobile). */
    private String currentUa() {
        switch (uaMode) {
            case 1: return DESKTOP_UA;
            case 2: return MOBILE_UA;
            default: return (autoUa != null && !autoUa.isEmpty()) ? autoUa : MOBILE_UA;
        }
    }

    private String uaLabel() {
        switch (uaMode) {
            case 1: return "Vue: PC";
            case 2: return "Vue: Mobile";
            default: return "Vue: Auto";
        }
    }

    // ------------------------------------------------------------- Popups (fenêtre superposée)

    private boolean popupVisible() {
        return popupContainer != null && popupContainer.getVisibility() == View.VISIBLE;
    }

    /** WebView qui reçoit les actions du curseur : le popup s'il est ouvert, sinon la principale. */
    private WebView activeWeb() {
        return (popupVisible() && popupWeb != null) ? popupWeb : web;
    }

    /** Ouvre un popup dans une fenêtre superposée (la page d'origine reste dessous). */
    private void openPopupWindow(Message resultMsg) {
        closePopup(); // une seule fenêtre popup à la fois

        popupWeb = new WebView(this);
        configurePopupWeb(popupWeb);
        popupContainer.addView(popupWeb, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        popupContainer.setVisibility(View.VISIBLE);
        if (bar.getVisibility() == View.VISIBLE) hideBar();
        if (cursorMode) { cursor.setVisibility(View.VISIBLE); ensureCursorVisible(); }

        WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
        transport.setWebView(popupWeb);
        resultMsg.sendToTarget();
    }

    /** Ferme le popup et revient à la page d'origine, intacte. */
    private void closePopup() {
        if (popupWeb != null) {
            popupContainer.removeView(popupWeb);
            try {
                popupWeb.stopLoading();
                popupWeb.loadUrl("about:blank");
                popupWeb.destroy();
            } catch (Exception ignored) { }
            popupWeb = null;
        }
        popupContainer.setVisibility(View.GONE);
        web.requestFocus();
    }

    private void configurePopupWeb(final WebView pw) {
        WebSettings s = pw.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setMediaPlaybackRequiresUserGesture(false);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setSupportZoom(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setJavaScriptCanOpenWindowsAutomatically(true);
        s.setSupportMultipleWindows(true);
        s.setUserAgentString(currentUa());
        s.setTextZoom(textZoom);
        pw.setBackgroundColor(Color.BLACK);
        pw.setFocusable(true);
        pw.setFocusableInTouchMode(true);

        CookieManager.getInstance().setAcceptThirdPartyCookies(pw, true);

        // Contrôle vidéo aussi dans la fenêtre popup (lecteurs en nouvel onglet).
        pw.addJavascriptInterface(new VideoBridge(), "AndroidVideo");
        installVideoScript(pw);

        pw.setWebViewClient(new WebViewClient() {
            @Override
            public WebResourceResponse shouldInterceptRequest(WebView v, WebResourceRequest request) {
                if (adblockEnabled && request != null && request.getUrl() != null
                        && AdBlocker.isAd(request.getUrl().toString())) {
                    return new WebResourceResponse("text/plain", "utf-8",
                            new ByteArrayInputStream(new byte[0]));
                }
                return super.shouldInterceptRequest(v, request);
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                return handleOverride(request != null && request.getUrl() != null
                        ? request.getUrl().toString() : null);
            }

            @SuppressWarnings("deprecation")
            @Override
            public boolean shouldOverrideUrlLoading(WebView v, String u) {
                return handleOverride(u);
            }

            @Override
            public void onPageFinished(WebView v, String u) {
                v.evaluateJavascript(VIDEO_SCRIPT, null);
            }

            @Override
            public void onReceivedSslError(WebView v, SslErrorHandler handler, SslError error) {
                handler.cancel();
                sslToast(error != null ? error.getUrl() : null);
            }
        });

        pw.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onProgressChanged(WebView v, int newProgress) {
                progress.setProgress(newProgress);
                progress.setVisibility(newProgress < 100 ? View.VISIBLE : View.GONE);
            }

            // Un popup à l'intérieur du popup : on navigue dans le même popup
            // plutôt que d'empiler les fenêtres.
            @Override
            public boolean onCreateWindow(WebView v, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                if (!allowPopups) {
                    openAnchorInSameWindow(v, popupWeb);
                    return false;
                }
                final WebView temp = new WebView(MainActivity.this);
                temp.getSettings().setUserAgentString(currentUa());
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView vv, WebResourceRequest request) {
                        String u = (request != null && request.getUrl() != null)
                                ? request.getUrl().toString() : null;
                        if (u != null && popupWeb != null) popupWeb.loadUrl(u);
                        temp.post(temp::destroy);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(temp);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onCloseWindow(WebView window) {
                closePopup();
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showFullscreen(pw, view, callback);
            }

            @Override
            public void onHideCustomView() {
                hideFullscreen();
            }
        });
    }

    // ------------------------------------------------------------- Boutons UI

    private void setupButtons() {
        ((Button) findViewById(R.id.btnBack)).setOnClickListener(v -> {
            if (web.canGoBack()) pauseThen(web, () -> { if (web != null) web.goBack(); });
        });
        ((Button) findViewById(R.id.btnFwd)).setOnClickListener(v -> {
            if (web.canGoForward()) web.goForward();
        });
        ((Button) findViewById(R.id.btnReload)).setOnClickListener(v -> web.reload());
        ((Button) findViewById(R.id.btnHome)).setOnClickListener(v -> web.loadUrl(HOME_URL));
        ((Button) findViewById(R.id.btnExit)).setOnClickListener(v -> finish());

        // Zoom du TEXTE (lisibilité des sites « PC » sur une TV) : fiable même
        // sur les sites qui interdisent le zoom de page, et mémorisé.
        ((Button) findViewById(R.id.btnZoomOut)).setOnClickListener(v -> stepTextZoom(-1));
        ((Button) findViewById(R.id.btnZoomIn)).setOnClickListener(v -> stepTextZoom(+1));

        btnCursor.setOnClickListener(v -> {
            cursorMode = !cursorMode;
            updateToggleLabels();
            saveSettings();
            if (cursorMode && bar.getVisibility() != View.VISIBLE) {
                ensureCursorVisible();
            } else {
                cursor.setVisibility(View.GONE);
            }
        });

        btnAd.setOnClickListener(v -> {
            adblockEnabled = !adblockEnabled;
            updateToggleLabels();
            saveSettings();
            Toast.makeText(this,
                    adblockEnabled ? "Anti-pub activé" : "Anti-pub désactivé",
                    Toast.LENGTH_SHORT).show();
            web.reload();
        });

        btnAddFav.setOnClickListener(v -> addCurrentToFavorites());
        btnFav.setOnClickListener(v -> showFavorites());
        ((Button) findViewById(R.id.btnFavClose)).setOnClickListener(v -> hideFavorites());

        btnUA.setOnClickListener(v -> {
            uaMode = (uaMode + 1) % 3;   // Auto -> PC -> Mobile -> Auto
            web.getSettings().setUserAgentString(currentUa());
            updateToggleLabels();
            saveSettings();
            Toast.makeText(this, uaLabel(), Toast.LENGTH_SHORT).show();
            web.reload();
        });

        btnPopup.setOnClickListener(v -> {
            allowPopups = !allowPopups;
            updateToggleLabels();
            saveSettings();
            Toast.makeText(this,
                    allowPopups ? "Popups autorisés" : "Popups bloqués",
                    Toast.LENGTH_SHORT).show();
        });

        url.setOnEditorActionListener((v, actionId, event) -> {
            boolean go = actionId == EditorInfo.IME_ACTION_GO
                    || actionId == EditorInfo.IME_ACTION_DONE
                    || actionId == EditorInfo.IME_ACTION_SEARCH
                    || (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                        && event.getAction() == KeyEvent.ACTION_DOWN);
            if (go) {
                navigateTo(url.getText().toString());
                hideBar();
                return true;
            }
            return false;
        });
    }

    private void updateToggleLabels() {
        btnCursor.setText(cursorMode ? "Curseur: ON" : "Curseur: OFF");
        btnAd.setText(adblockEnabled ? "Anti-pub: ON" : "Anti-pub: OFF");
        btnUA.setText(uaLabel());
        btnPopup.setText(allowPopups ? "Popups: ON" : "Popups: OFF");
    }

    // ------------------------------------------------------- Barre d'adresse

    private void toggleBar() {
        if (bar.getVisibility() == View.VISIBLE) hideBar();
        else showBar();
    }

    private void showBar() {
        bar.setVisibility(View.VISIBLE);
        cursor.setVisibility(View.GONE);
        String current = web.getUrl();
        url.setText(current == null ? "" : current);
        url.requestFocus();
        url.selectAll();
        // Pas de clavier forcé : la barre sert souvent à cliquer Favoris/Popups/Vue.
        // Le clavier s'ouvre quand on appuie OK sur le champ d'adresse.
    }

    private void hideBar() {
        hideKeyboard();
        bar.setVisibility(View.GONE);
        web.requestFocus();
        if (cursorMode) {
            cursor.setVisibility(View.VISIBLE);
            ensureCursorVisible();
        }
    }

    // ------------------------------------------------------------- Favoris

    /** Ajoute la page courante aux favoris (nom modifiable via une boîte de dialogue). */
    private void addCurrentToFavorites() {
        final String current = web.getUrl();
        if (current == null
                || !(current.startsWith("http://") || current.startsWith("https://"))) {
            Toast.makeText(this, "Ouvrez d'abord un site web à ajouter", Toast.LENGTH_SHORT).show();
            return;
        }
        String pageTitle = web.getTitle();
        if (TextUtils.isEmpty(pageTitle)) pageTitle = current;

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(pageTitle);
        input.setSelectAllOnFocus(true);

        new AlertDialog.Builder(this)
                .setTitle("Ajouter aux favoris")
                .setMessage(current)
                .setView(input)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    String name = input.getText().toString().trim();
                    boolean ok = Favorites.add(getApplicationContext(), name, current);
                    Toast.makeText(this,
                            ok ? "Ajouté aux favoris" : "Impossible d'ajouter ce site",
                            Toast.LENGTH_SHORT).show();
                    refreshHomeIfShown();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    /** Affiche le panneau des favoris et construit la liste. */
    private void showFavorites() {
        if (bar.getVisibility() == View.VISIBLE) hideBar();
        cursor.setVisibility(View.GONE);
        // Empêche le focus D-pad de s'échapper vers la page derrière le panneau.
        web.setFocusable(false);
        web.setFocusableInTouchMode(false);
        buildFavoritesList();
        favOverlay.setVisibility(View.VISIBLE);

        // Donne le focus au premier élément (ou au bouton Fermer si vide).
        View first = favList.getChildCount() > 0 ? favList.getChildAt(0) : null;
        final View target = first != null ? first.findViewById(R.id.favOpen) : findViewById(R.id.btnFavClose);
        if (target != null) target.post(target::requestFocus);
    }

    private void hideFavorites() {
        favOverlay.setVisibility(View.GONE);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);
        web.requestFocus();
        if (cursorMode && bar.getVisibility() != View.VISIBLE) {
            cursor.setVisibility(View.VISIBLE);
            ensureCursorVisible();
        }
    }

    private boolean favoritesVisible() {
        return favOverlay != null && favOverlay.getVisibility() == View.VISIBLE;
    }

    private void buildFavoritesList() {
        favList.removeAllViews();
        List<Favorites.Item> items = Favorites.list(getApplicationContext());

        if (items.isEmpty()) {
            favEmpty.setVisibility(View.VISIBLE);
            return;
        }
        favEmpty.setVisibility(View.GONE);

        for (final Favorites.Item item : items) {
            LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            rowLp.bottomMargin = dp(8);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setLayoutParams(rowLp);

            Button open = new Button(this, null);
            open.setId(R.id.favOpen);
            open.setText(item.title + "\n" + item.url);
            open.setTextColor(Color.WHITE);
            open.setAllCaps(false);
            open.setBackgroundResource(R.drawable.btn_bg);
            open.setPadding(dp(18), dp(10), dp(18), dp(10));
            open.setFocusable(true);
            LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(
                    0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            openLp.rightMargin = dp(8);
            open.setLayoutParams(openLp);
            open.setOnClickListener(v -> {
                hideFavorites();
                navigateTo(item.url);
            });

            Button del = new Button(this, null);
            del.setText("Supprimer");
            del.setTextColor(Color.WHITE);
            del.setAllCaps(false);
            del.setBackgroundResource(R.drawable.btn_bg);
            del.setPadding(dp(16), dp(10), dp(16), dp(10));
            del.setFocusable(true);
            del.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.MATCH_PARENT));
            del.setOnClickListener(v -> {
                Favorites.remove(getApplicationContext(), item.url);
                buildFavoritesList();
                refreshHomeIfShown();
                View next = favList.getChildCount() > 0
                        ? favList.getChildAt(0).findViewById(R.id.favOpen)
                        : findViewById(R.id.btnFavClose);
                if (next != null) next.post(next::requestFocus);
            });

            row.addView(open);
            row.addView(del);
            favList.addView(row);
        }
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    private void refreshHomeIfShown() {
        String u = web.getUrl();
        if (u != null && u.startsWith("file:///android_asset/home.html")) {
            web.reload();
        }
    }

    /** Pont JavaScript : SEULE la page d'accueil interne peut lire les favoris.
     *  (L'objet est injecté dans toutes les pages ; sans ce garde-fou, n'importe
     *  quel site pourrait lire la liste de ce que tu regardes.) */
    private final class FavBridge {
        @JavascriptInterface
        public String list() {
            if (!onHomePage) return "[]";
            return Favorites.toJson(getApplicationContext());
        }
    }

    // ------------------------------------------------------------- Plein écran vidéo

    private void showFullscreen(WebView owner, View view,
                                WebChromeClient.CustomViewCallback cb) {
        if (customView != null) {
            cb.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = cb;
        fullscreenWeb = owner;
        bar.setVisibility(View.GONE);
        cursor.setVisibility(View.GONE);
        favOverlay.setVisibility(View.GONE);
        web.setVisibility(View.GONE);
        if (popupContainer != null) popupContainer.setVisibility(View.GONE);
        customView.setBackgroundColor(Color.BLACK);
        // Gravity.CENTER est indispensable : le lecteur dimensionne la surface
        // vidéo à son ratio et compte sur le parent pour la centrer. Sans ça,
        // elle se colle en haut à gauche avec une bande noire de l'autre côté.
        root.addView(customView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        customView.bringToFront();
        osd.bringToFront(); // l'indicateur (+10 s, pause…) reste visible par-dessus
        // On ne donne PAS le focus à la vue plein écran : sinon les touches
        // s'y perdent au lieu d'atteindre le lecteur (avance/recul cassé).
        enterImmersive();
    }

    private void hideFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        fullscreenWeb = null;
        osd.removeCallbacks(osdHideRunnable);
        osd.setVisibility(View.GONE);
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        // Restaure la page principale, et le popup par-dessus s'il était ouvert.
        web.setVisibility(View.VISIBLE);
        if (popupWeb != null && popupContainer != null) {
            popupContainer.setVisibility(View.VISIBLE);
        }
        exitImmersive();
        if (cursorMode && bar.getVisibility() != View.VISIBLE
                && !popupVisible()) {
            ensureCursorVisible();
        }
    }

    // Mode immersif via l'API moderne (WindowInsetsController) : fiable sur
    // Android 11+ (Google TV Streamer = Android 14), là où les anciens drapeaux
    // setSystemUiVisibility pouvaient laisser un décalage / une bande.
    private void enterImmersive() {
        try {
            WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(
                    getWindow(), getWindow().getDecorView());
            c.setSystemBarsBehavior(
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            c.hide(WindowInsetsCompat.Type.systemBars());
        } catch (Exception ignored) { }
    }

    private void exitImmersive() {
        try {
            WindowInsetsControllerCompat c = WindowCompat.getInsetsController(
                    getWindow(), getWindow().getDecorView());
            c.show(WindowInsetsCompat.Type.systemBars());
            WindowCompat.setDecorFitsSystemWindows(getWindow(), true);
        } catch (Exception ignored) { }
    }

    // ------------------------------------------------------------- Télécommande

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        final int code = e.getKeyCode();
        final boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        final boolean up = e.getAction() == KeyEvent.ACTION_UP;
        final boolean isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                || code == KeyEvent.KEYCODE_BUTTON_A;

        // Touches média physiques (Fire TV…) : si une vidéo est détectée, on la
        // pilote directement, quel que soit l'écran.
        if (videoAvailable()) {
            if (code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE || code == KeyEvent.KEYCODE_MEDIA_PLAY
                    || code == KeyEvent.KEYCODE_MEDIA_PAUSE) {
                if (up) togglePlayOsd();
                return true;
            }
            if (code == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) { if (down) seekOsd(30); return true; }
            if (code == KeyEvent.KEYCODE_MEDIA_REWIND)       { if (down) seekOsd(-30); return true; }
        }

        // Plein écran vidéo. Si notre script a détecté une vidéo (même dans un
        // lecteur d'un autre domaine) : ◄ ► = −/+10 s, OK = lecture/pause, avec
        // indicateur à l'écran. Sinon, les touches vont au lecteur du site tel
        // quel. Retour quitte toujours le plein écran.
        if (customView != null) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (up) hideFullscreen();
                return true;
            }
            if (videoAvailable()) {
                if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
                    if (down && e.getRepeatCount() % 3 == 0) seekOsd(-10);
                    return true;
                }
                if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                    if (down && e.getRepeatCount() % 3 == 0) seekOsd(10);
                    return true;
                }
                if (isCenter) {
                    if (up) togglePlayOsd();
                    return true;
                }
            }
            WebView target = fullscreenWeb != null ? fullscreenWeb : activeWeb();
            if (target != null && target.dispatchKeyEvent(e)) return true;
            return super.dispatchKeyEvent(e);
        }

        // Panneau des favoris ouvert : Retour ferme, MENU ignoré, le reste
        // laisse la navigation D-pad native entre les éléments.
        if (favoritesVisible()) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (up) hideFavorites();
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) return true;
            return super.dispatchKeyEvent(e);
        }

        // Popup ouvert : Retour recule dans le popup (en coupant le son) puis le
        // ferme (retour à la page d'origine) ; le curseur agit sur le popup.
        if (popupVisible()) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (up) {
                    if (popupWeb != null && popupWeb.canGoBack()) {
                        pauseThen(popupWeb, () -> { if (popupWeb != null) popupWeb.goBack(); });
                    } else {
                        closePopup();
                    }
                }
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) return true;
            if (cursorMode && handleCursorKey(e)) return true;
            return super.dispatchKeyEvent(e);
        }

        // Touche MENU (Fire TV) : afficher/cacher la barre d'adresse
        if (code == KeyEvent.KEYCODE_MENU) {
            if (up) toggleBar();
            return true;
        }

        // Touche RETOUR
        if (code == KeyEvent.KEYCODE_BACK) {
            if (up) handleBack();
            return true;
        }

        // Barre visible : navigation native entre les boutons / champ texte
        if (bar.getVisibility() == View.VISIBLE) {
            return super.dispatchKeyEvent(e);
        }

        // Appui LONG sur OK = barre d'outils, QUEL QUE SOIT le mode curseur
        // (sans ça, « Curseur: OFF » sur Google TV — pas de touche MENU —
        // rendait la barre inaccessible pour de bon). Appui court : clic au
        // curseur, ou appui transmis à la page si le curseur est désactivé.
        if (isCenter) {
            if (down) {
                if (e.getRepeatCount() == 0) centerDownAt = e.getEventTime();
                return cursorMode || super.dispatchKeyEvent(e);
            }
            if (up) {
                long dur = e.getEventTime() - centerDownAt;
                if (dur >= LONG_PRESS_MS) { toggleBar(); return true; }
                if (cursorMode) { tapAtCursor(); return true; }
                return super.dispatchKeyEvent(e);
            }
            return true;
        }

        // Mode curseur : la croix directionnelle déplace le pointeur
        if (cursorMode && handleCursorKey(e)) return true;

        return super.dispatchKeyEvent(e);
    }

    private void handleBack() {
        if (bar.getVisibility() == View.VISIBLE) {
            hideBar();
            return;
        }
        if (web.canGoBack()) {
            pauseThen(web, () -> { if (web != null) web.goBack(); });
            return;
        }
        long now = SystemClock.uptimeMillis();
        if (now - lastBack < 2000) {
            finish();
        } else {
            lastBack = now;
            Toast.makeText(this, "Appuyez encore sur Retour pour quitter",
                    Toast.LENGTH_SHORT).show();
        }
    }

    private boolean handleCursorKey(KeyEvent e) {
        int code = e.getKeyCode();
        boolean isDir = code == KeyEvent.KEYCODE_DPAD_LEFT
                || code == KeyEvent.KEYCODE_DPAD_RIGHT
                || code == KeyEvent.KEYCODE_DPAD_UP
                || code == KeyEvent.KEYCODE_DPAD_DOWN;
        boolean isCenter = code == KeyEvent.KEYCODE_DPAD_CENTER
                || code == KeyEvent.KEYCODE_ENTER
                || code == KeyEvent.KEYCODE_NUMPAD_ENTER
                || code == KeyEvent.KEYCODE_BUTTON_A;

        if (isCenter) {
            // OK = clic au curseur. (L'appui long -> barre est géré en amont,
            // dans dispatchKeyEvent, indépendamment du mode curseur.)
            if (e.getAction() == KeyEvent.ACTION_UP) tapAtCursor();
            return true;
        }
        if (isDir) {
            if (e.getAction() == KeyEvent.ACTION_DOWN) {
                int step = baseStep + Math.min(e.getRepeatCount(), 14) * 7;
                moveCursor(code, step);
            }
            return true;
        }
        return false;
    }

    private void ensureCursorVisible() {
        if (!cursorMode) return;
        if (cursor.getVisibility() != View.VISIBLE) cursor.setVisibility(View.VISIBLE);
        // (Re)lance le compte à rebours de masquage automatique.
        cursor.removeCallbacks(hideCursorRunnable);
        cursor.postDelayed(hideCursorRunnable, CURSOR_HIDE_MS);
        if (!cursorInit) {
            int w = root.getWidth();
            int h = root.getHeight();
            cursorX = (w > 0 ? w : 960) / 2f;
            cursorY = (h > 0 ? h : 540) / 2f;
            cursorInit = true;
            cursor.setX(cursorX);
            cursor.setY(cursorY);
        }
    }

    private void moveCursor(int code, int step) {
        ensureCursorVisible();
        int w = root.getWidth();
        int h = root.getHeight();
        if (w <= 0 || h <= 0) return;

        float nx = cursorX;
        float ny = cursorY;
        int margin = 2;
        WebView target = activeWeb();

        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            nx -= step;
            if (nx < margin) { target.scrollBy(-step, 0); nx = margin; }
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            nx += step;
            if (nx > w - margin) { target.scrollBy(step, 0); nx = w - margin; }
        } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
            ny -= step;
            if (ny < margin) { target.scrollBy(0, -step); ny = margin; }
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            ny += step;
            if (ny > h - margin) { target.scrollBy(0, step); ny = h - margin; }
        }

        cursorX = nx;
        cursorY = ny;
        cursor.setX(cursorX);
        cursor.setY(cursorY);
    }

    private void tapAtCursor() {
        ensureCursorVisible(); // le curseur réapparaît quand on clique
        float x = cursorX + cursor.getWidth() / 2f;
        float y = cursorY + cursor.getHeight() / 2f;
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, x, y, 0);
        WebView target = activeWeb();
        target.dispatchTouchEvent(down);
        target.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
    }

    // ------------------------------------------------------------- Contrôle vidéo
    //
    // Pourquoi un script dans CHAQUE frame : sur beaucoup de sites, le lecteur
    // est une iframe d'un AUTRE domaine. Le JavaScript de la page principale
    // n'a pas le droit d'y toucher (règle de même origine) — c'est pour ça que
    // les tentatives précédentes échouaient. WebViewCompat.addDocumentStartJavaScript
    // injecte notre script directement DANS chaque frame, origine comprise, et
    // l'objet AndroidVideo (JavascriptInterface) y est disponible. Chaque frame :
    //   - signale si elle a une <video> et si elle joue  (report)
    //   - récupère nos commandes et les applique à SA vidéo (poll)
    //   - se met en pause quand pauseGen change          (pauseGen)
    // La commande va en priorité à la frame dont la vidéo JOUE ; sinon à la
    // première qui a une vidéo. Une commande non consommée expire (800 ms).

    private static final String VIDEO_SCRIPT =
            "(function(){if(window.__wtvCtl)return;window.__wtvCtl=1;"
            + "var A=window.AndroidVideo;if(!A)return;var lastGen=-1;"
            + "function vids(){try{return Array.prototype.slice.call(document.querySelectorAll('video'));}catch(e){return[];}}"
            + "function best(){var a=vids();if(!a.length)return null;"
            + "for(var i=0;i<a.length;i++){if(!a[i].paused&&a[i].readyState>0)return a[i];}"
            + "var b=a[0];for(var j=1;j<a.length;j++){if((a[j].clientWidth*a[j].clientHeight)>(b.clientWidth*b.clientHeight))b=a[j];}return b;}"
            + "setInterval(function(){try{var v=best();"
            + "var g=A.pauseGen();if(g!==lastGen){var first=(lastGen===-1);lastGen=g;if(v&&!first){try{v.pause();}catch(e){}}}"
            + "if(!v){A.report(0,0);return;}A.report(1,v.paused?0:1);"
            + "var c=A.poll(v.paused?0:1);if(!c)return;"
            + "if(c.indexOf('seek:')===0){var d=parseFloat(c.substring(5))||0;"
            + "var dur=isFinite(v.duration)?v.duration:1e9;v.currentTime=Math.max(0,Math.min(dur,v.currentTime+d));}"
            + "else if(c==='toggle'){if(v.paused){var p=v.play();if(p&&p.catch)p.catch(function(){});}else{v.pause();}}"
            + "}catch(e){}},120);})();";

    /** Installe le script de contrôle dans toutes les frames de cette WebView. */
    private void installVideoScript(WebView w) {
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)) {
                WebViewCompat.addDocumentStartJavaScript(w, VIDEO_SCRIPT,
                        Collections.singleton("*"));
            }
            // Sinon : secours via evaluateJavascript dans onPageFinished (frame principale).
        } catch (Exception ignored) { }
    }

    /** Pont appelé par le script de chaque frame (threads JavaScript). */
    private final class VideoBridge {
        @JavascriptInterface
        public void report(int has, int playing) {
            if (has == 1) {
                videoSeenAt = SystemClock.uptimeMillis();
                videoPlaying = playing == 1;
            }
        }

        @JavascriptInterface
        public String poll(int playing) {
            synchronized (videoLock) {
                if (videoCmd == null) return "";
                long age = SystemClock.uptimeMillis() - videoCmdAt;
                if (age > 800) { videoCmd = null; return ""; }     // périmée
                if (playing == 1 || age > 250) {                    // priorité à la vidéo qui joue
                    String c = videoCmd;
                    videoCmd = null;
                    return c;
                }
                return "";
            }
        }

        @JavascriptInterface
        public int pauseGen() {
            return pauseGen;
        }
    }

    private boolean videoAvailable() {
        return SystemClock.uptimeMillis() - videoSeenAt < 700;
    }

    private void issueVideoCmd(String cmd) {
        synchronized (videoLock) {
            videoCmd = cmd;
            videoCmdAt = SystemClock.uptimeMillis();
        }
    }

    private void seekOsd(int seconds) {
        issueVideoCmd("seek:" + seconds);
        showOsd((seconds > 0 ? "⏩  +" : "⏪  −") + Math.abs(seconds) + " s");
    }

    private void togglePlayOsd() {
        boolean wasPlaying = videoPlaying;
        issueVideoCmd("toggle");
        showOsd(wasPlaying ? "⏸  Pause" : "▶  Lecture");
    }

    /** Indicateur à l'écran, disparaît tout seul. */
    private void showOsd(String text) {
        if (osd == null) return;
        osd.setText(text);
        osd.setVisibility(View.VISIBLE);
        osd.bringToFront();
        osd.removeCallbacks(osdHideRunnable);
        osd.postDelayed(osdHideRunnable, 900);
    }

    // ------------------------------------------------------------- Divers UI

    private void stepTextZoom(int dir) {
        int idx = 1; // 100 %
        for (int i = 0; i < TEXT_ZOOMS.length; i++) if (TEXT_ZOOMS[i] == textZoom) idx = i;
        idx = Math.max(0, Math.min(TEXT_ZOOMS.length - 1, idx + dir));
        textZoom = TEXT_ZOOMS[idx];
        applyTextZoom();
        saveSettings();
        Toast.makeText(this, "Texte " + textZoom + " %", Toast.LENGTH_SHORT).show();
    }

    private void sslToast(String failingUrl) {
        long now = SystemClock.uptimeMillis();
        if (now - lastSslToast < 4000) return;
        lastSslToast = now;
        String host = null;
        try { host = failingUrl != null ? Uri.parse(failingUrl).getHost() : null; } catch (Exception ignored) { }
        Toast.makeText(this, "Certificat de sécurité invalide"
                + (host != null ? " (" + host + ")" : "") + " — contenu bloqué",
                Toast.LENGTH_LONG).show();
    }

    // ------------------------------------------------------------- Clavier

    private void showKeyboard(View v) {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT);
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        View f = getCurrentFocus();
        if (imm != null && f != null) imm.hideSoftInputFromWindow(f.getWindowToken(), 0);
    }

    // ------------------------------------------------------------- Cycle de vie

    @Override
    protected void onPause() {
        super.onPause();
        if (web != null) web.onPause();
        if (popupWeb != null) popupWeb.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
        if (popupWeb != null) popupWeb.onResume();

        // Retour des réglages après avoir autorisé l'installation : on reprend
        // la mise à jour qui attendait.
        if (pendingUpdate != null && (Build.VERSION.SDK_INT < Build.VERSION_CODES.O
                || getPackageManager().canRequestPackageInstalls())) {
            Updater.Release r = pendingUpdate;
            pendingUpdate = null;
            startUpdateDownload(r);
        }
    }

    @Override
    protected void onDestroy() {
        closePopup();
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
