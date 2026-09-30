package com.jason.webtv;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Message;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
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

import java.io.ByteArrayInputStream;
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
    private Button btnCursor, btnAd, btnAddFav, btnFav, btnUA;

    private LinearLayout favOverlay;
    private LinearLayout favList;
    private TextView favEmpty;

    private volatile boolean adblockEnabled = true;
    private boolean desktopMode = true;
    private boolean cursorMode = true;
    private boolean cursorInit = false;
    private float cursorX, cursorY;
    private final int baseStep = 28;
    private long lastBack = 0L;

    private View customView;
    private WebChromeClient.CustomViewCallback customViewCallback;

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
        favOverlay = findViewById(R.id.favOverlay);
        favList = findViewById(R.id.favList);
        favEmpty = findViewById(R.id.favEmpty);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        // Charge la liste anti-pub en arrière-plan pour ne pas bloquer le démarrage.
        final Context app = getApplicationContext();
        new Thread(new Runnable() {
            @Override public void run() { AdBlocker.init(app); }
        }).start();

        configureWebView();
        setupButtons();
        updateToggleLabels();

        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);

        web.loadUrl(HOME_URL);

        // Au démarrage, on montre la barre d'adresse prête à recevoir une URL.
        bar.setVisibility(View.VISIBLE);
        cursor.setVisibility(View.GONE);
        url.requestFocus();
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
        s.setUserAgentString(desktopMode ? DESKTOP_UA : MOBILE_UA);

        web.setBackgroundColor(Color.BLACK);
        web.setFocusable(true);
        web.setFocusableInTouchMode(true);

        // Pont pour que la page d'accueil (home.html) affiche les favoris.
        web.addJavascriptInterface(new FavBridge(), "AndroidFav");

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
            }

            @Override
            public void onPageFinished(WebView view, String u) {
                if (u != null) url.setText(u);
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
                final WebView temp = new WebView(MainActivity.this);
                temp.getSettings().setUserAgentString(
                        desktopMode ? DESKTOP_UA : MOBILE_UA);
                temp.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        String u = (request != null && request.getUrl() != null)
                                ? request.getUrl().toString() : null;
                        openInMainWindow(u, temp);
                        return true;
                    }

                    @SuppressWarnings("deprecation")
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String u) {
                        openInMainWindow(u, temp);
                        return true;
                    }
                });
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(temp);
                resultMsg.sendToTarget();
                return true;
            }

            @Override
            public void onShowCustomView(View view, CustomViewCallback callback) {
                showFullscreen(view, callback);
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
        return !(u.startsWith("http://") || u.startsWith("https://")
                || u.startsWith("file:") || u.startsWith("about:"));
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

    /** Charge dans la WebView principale l'URL d'un popup, puis jette la WebView temporaire. */
    private void openInMainWindow(String u, WebView temp) {
        if (u != null && (u.startsWith("http://") || u.startsWith("https://"))) {
            web.loadUrl(u);
        }
        temp.post(() -> {
            temp.stopLoading();
            temp.destroy();
        });
    }

    // ------------------------------------------------------------- Boutons UI

    private void setupButtons() {
        ((Button) findViewById(R.id.btnBack)).setOnClickListener(v -> {
            if (web.canGoBack()) web.goBack();
        });
        ((Button) findViewById(R.id.btnFwd)).setOnClickListener(v -> {
            if (web.canGoForward()) web.goForward();
        });
        ((Button) findViewById(R.id.btnReload)).setOnClickListener(v -> web.reload());
        ((Button) findViewById(R.id.btnHome)).setOnClickListener(v -> web.loadUrl(HOME_URL));
        ((Button) findViewById(R.id.btnExit)).setOnClickListener(v -> finish());

        btnCursor.setOnClickListener(v -> {
            cursorMode = !cursorMode;
            updateToggleLabels();
            if (cursorMode && bar.getVisibility() != View.VISIBLE) {
                cursor.setVisibility(View.VISIBLE);
                ensureCursorVisible();
            } else {
                cursor.setVisibility(View.GONE);
            }
        });

        btnAd.setOnClickListener(v -> {
            adblockEnabled = !adblockEnabled;
            updateToggleLabels();
            web.reload();
        });

        btnAddFav.setOnClickListener(v -> addCurrentToFavorites());
        btnFav.setOnClickListener(v -> showFavorites());
        ((Button) findViewById(R.id.btnFavClose)).setOnClickListener(v -> hideFavorites());

        btnUA.setOnClickListener(v -> {
            desktopMode = !desktopMode;
            web.getSettings().setUserAgentString(desktopMode ? DESKTOP_UA : MOBILE_UA);
            updateToggleLabels();
            web.reload();
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
        btnUA.setText(desktopMode ? "Vue: PC" : "Vue: Mobile");
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
        showKeyboard(url);
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

    /** Pont JavaScript : la page d'accueil lit les favoris et peut en supprimer. */
    private final class FavBridge {
        @JavascriptInterface
        public String list() {
            return Favorites.toJson(getApplicationContext());
        }

        @JavascriptInterface
        public void remove(final String favUrl) {
            Favorites.remove(getApplicationContext(), favUrl);
        }
    }

    // ------------------------------------------------------------- Plein écran vidéo

    private void showFullscreen(View view, WebChromeClient.CustomViewCallback cb) {
        if (customView != null) {
            cb.onCustomViewHidden();
            return;
        }
        customView = view;
        customViewCallback = cb;
        bar.setVisibility(View.GONE);
        cursor.setVisibility(View.GONE);
        web.setVisibility(View.GONE);
        root.addView(customView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        enterImmersive();
    }

    private void hideFullscreen() {
        if (customView == null) return;
        root.removeView(customView);
        customView = null;
        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
        web.setVisibility(View.VISIBLE);
        exitImmersive();
        if (cursorMode && bar.getVisibility() != View.VISIBLE) {
            cursor.setVisibility(View.VISIBLE);
        }
    }

    private void enterImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
    }

    private void exitImmersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
    }

    // ------------------------------------------------------------- Télécommande

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();

        // Panneau des favoris ouvert : Retour ferme, MENU ignoré, le reste
        // laisse la navigation D-pad native entre les éléments.
        if (favoritesVisible()) {
            if (code == KeyEvent.KEYCODE_BACK) {
                if (e.getAction() == KeyEvent.ACTION_UP) hideFavorites();
                return true;
            }
            if (code == KeyEvent.KEYCODE_MENU) return true;
            return super.dispatchKeyEvent(e);
        }

        // Touche MENU : afficher/cacher la barre d'adresse
        if (code == KeyEvent.KEYCODE_MENU) {
            if (e.getAction() == KeyEvent.ACTION_UP) toggleBar();
            return true;
        }

        // Touche RETOUR
        if (code == KeyEvent.KEYCODE_BACK) {
            if (customView != null) {
                if (e.getAction() == KeyEvent.ACTION_UP) hideFullscreen();
                return true;
            }
            if (e.getAction() == KeyEvent.ACTION_UP) handleBack();
            return true;
        }

        // Barre visible : navigation native entre les boutons / champ texte
        if (bar.getVisibility() == View.VISIBLE) {
            return super.dispatchKeyEvent(e);
        }

        // Mode curseur : la croix directionnelle déplace le pointeur, OK clique
        if (cursorMode && customView == null) {
            if (handleCursorKey(e)) return true;
        }

        return super.dispatchKeyEvent(e);
    }

    private void handleBack() {
        if (bar.getVisibility() == View.VISIBLE) {
            hideBar();
            return;
        }
        if (web.canGoBack()) {
            web.goBack();
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

        if (code == KeyEvent.KEYCODE_DPAD_LEFT) {
            nx -= step;
            if (nx < margin) { web.scrollBy(-step, 0); nx = margin; }
        } else if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
            nx += step;
            if (nx > w - margin) { web.scrollBy(step, 0); nx = w - margin; }
        } else if (code == KeyEvent.KEYCODE_DPAD_UP) {
            ny -= step;
            if (ny < margin) { web.scrollBy(0, -step); ny = margin; }
        } else if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
            ny += step;
            if (ny > h - margin) { web.scrollBy(0, step); ny = h - margin; }
        }

        cursorX = nx;
        cursorY = ny;
        cursor.setX(cursorX);
        cursor.setY(cursorY);
    }

    private void tapAtCursor() {
        float x = cursorX + cursor.getWidth() / 2f;
        float y = cursorY + cursor.getHeight() / 2f;
        long t = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(t, t + 80, MotionEvent.ACTION_UP, x, y, 0);
        web.dispatchTouchEvent(down);
        web.dispatchTouchEvent(up);
        down.recycle();
        up.recycle();
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
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (web != null) web.onResume();
    }

    @Override
    protected void onDestroy() {
        if (web != null) {
            web.loadUrl("about:blank");
            web.destroy();
            web = null;
        }
        super.onDestroy();
    }
}
