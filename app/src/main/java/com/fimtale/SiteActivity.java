package com.fimtale;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.webkit.CookieManager;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;
import androidx.appcompat.app.AppCompatActivity;
import androidx.activity.OnBackPressedCallback;
import com.fimtale.model.CurrentUser;
import com.fimtale.network.RetrofitClient;
import com.fimtale.network.SiteUrls;
import com.fimtale.utils.UserPreferences;
import com.google.android.material.appbar.MaterialToolbar;
import retrofit2.Call;
import retrofit2.Callback;
import retrofit2.Response;

/** Authenticated site content; all sign-in requests are routed to the native login screen. */
public class SiteActivity extends AppCompatActivity {
    public static final String EXTRA_PATH = "site_path";
    private WebView webView;
    private com.fimtale.ui.PageErrorView pageError;
    private boolean pageFailed;
    private String failedUrl;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean verifying;
    private String startPath;
    private boolean loginOpening;
    private final androidx.activity.result.ActivityResultLauncher<Intent> login = registerForActivityResult(
            new androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult(), result -> {
                loginOpening = false;
                if (result.getResultCode() == RESULT_OK) loadSite();
                else finish();
            });
    private boolean pageLoaded;
    private String lastRejectedToken;
    private Call<CurrentUser> verification;
    private final Runnable checkSession = new Runnable() {
        @Override public void run() {
            if (!isFinishing()) readSession();
            handler.postDelayed(this, 1000);
        }
    };

    @SuppressLint("SetJavaScriptEnabled")
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        setContentView(R.layout.activity_site);
        com.fimtale.utils.EditorWindowStyle.apply(this);
        MaterialToolbar toolbar = findViewById(R.id.toolbar);
        toolbar.setNavigationOnClickListener(v -> finish());
        String path = getIntent().getStringExtra(EXTRA_PATH);
        if (path == null || !path.startsWith("/") || path.startsWith("//")) path = "/";
        if (com.fimtale.modules.ModuleLinks.open(this, path)) { finish(); return; }
        startPath = path;
        toolbar.setTitle(R.string.app_name);
        webView = findViewById(R.id.site_webview);
        com.fimtale.ui.PullToRefresh.attach(webView, webView::reload, () -> !loginOpening);
        pageError = com.fimtale.ui.PageErrorView.wrap(webView);
        webView.getSettings().setJavaScriptEnabled(true);
        webView.getSettings().setDomStorageEnabled(true);
        webView.getSettings().setAllowFileAccess(false);
        webView.getSettings().setAllowContentAccess(false);
        CookieManager cookies = CookieManager.getInstance();
        cookies.setAcceptCookie(true);
        webView.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (SiteUrls.isSite(url)) {
                    if (request.isForMainFrame() && isLoginUrl(url)) { openNativeLogin(); return true; }
                    if (request.isForMainFrame() && com.fimtale.modules.ModuleLinks.open(SiteActivity.this, url)) return true;
                    return false;
                }
                if (request.isForMainFrame() && ("https".equals(request.getUrl().getScheme()) || "http".equals(request.getUrl().getScheme()))) {
                    try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); }
                    catch (android.content.ActivityNotFoundException ignored) {}
                }
                return request.isForMainFrame();
            }
            @Override public void onPageStarted(WebView view, String url, android.graphics.Bitmap icon) {
                pageLoaded = false;
                pageFailed = false;
                failedUrl = url;
                pageError.hide();
            }
            @Override public void onPageFinished(WebView view, String url) {
                com.fimtale.ui.PullToRefresh.finish(webView);
                if (pageFailed) return;
                pageLoaded = true;
                if (isLoginUrl(url)) openNativeLogin();
                else if (SiteUrls.isSite(url)) readSession();
            }
            @Override public void doUpdateVisitedHistory(WebView view, String url, boolean isReload) {
                if (isLoginUrl(url)) openNativeLogin();
            }
            @Override public void onReceivedError(WebView view, WebResourceRequest request,
                    android.webkit.WebResourceError error) {
                if (request.isForMainFrame()) showPageError(request.getUrl().toString(), null);
            }
            @Override public void onReceivedHttpError(WebView view, WebResourceRequest request,
                    android.webkit.WebResourceResponse error) {
                if (request.isForMainFrame()) showPageError(request.getUrl().toString(),
                        getString(R.string.site_http_error, error.getStatusCode()));
            }
            @Override public void onReceivedSslError(WebView view, android.webkit.SslErrorHandler handler,
                    android.net.http.SslError error) {
                handler.cancel();
                if (error.getUrl().equals(view.getUrl())) showPageError(error.getUrl(), getString(R.string.site_ssl_error));
            }
        });
        if (isLoginUrl(SiteUrls.SITE + startPath)) { startPath = "/"; openNativeLogin(); }
        else loadSite();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (webView.canGoBack()) webView.goBack(); else finish();
            }
        });
    }
    private boolean isLoginUrl(String url) {
        return SiteUrls.isSite(url) && ("/user/login".equals(Uri.parse(url).getPath())
                || "/user/login/".equals(Uri.parse(url).getPath()));
    }
    private void showPageError(String url, String message) {
        com.fimtale.ui.PullToRefresh.finish(webView);
        if (isFinishing() || isDestroyed()) return;
        pageFailed = true; pageLoaded = false; failedUrl = url;
        pageError.show(message, () -> {
            if (SiteUrls.isSite(failedUrl)) webView.loadUrl(failedUrl); else loadSite();
        });
    }
    private void openNativeLogin() {
        if (loginOpening) return;
        loginOpening = true;
        pageError.hide();
        webView.stopLoading(); webView.setVisibility(android.view.View.INVISIBLE);
        login.launch(new Intent(this, LoginActivity.class));
    }
    private void loadSite() {
        pageError.hide();
        pageLoaded = false;
        String token = UserPreferences.getToken(this);
        String cookie = token.isEmpty() ? "ft_token=; Path=/; Max-Age=0; Secure"
                : "ft_token=" + token + "; Path=/; Secure; SameSite=Lax";
        CookieManager.getInstance().setCookie(SiteUrls.SITE, cookie, accepted -> {
            if (isFinishing() || isDestroyed()) return;
            webView.setVisibility(android.view.View.VISIBLE);
            webView.loadUrl(SiteUrls.SITE + startPath);
        });
    }
    private void readSession() {
        if (loginOpening || !pageLoaded || verifying || !SiteUrls.isSite(webView.getUrl())) return;
        String cookies = CookieManager.getInstance().getCookie(SiteUrls.SITE);
        if (cookies == null) cookies = "";
        for (String cookie : cookies.split(";")) {
            String value = cookie.trim();
            if (!value.startsWith("ft_token=")) continue;
            String token = Uri.decode(value.substring("ft_token=".length()));
            if (token.isEmpty() || token.equals(lastRejectedToken)) return;
            if (token.equals(UserPreferences.getToken(this))) return;
            verifying = true;
            verification = RetrofitClient.getInstance().getCurrentUser(token);
            verification.enqueue(new Callback<CurrentUser>() {
                @Override public void onResponse(Call<CurrentUser> call, Response<CurrentUser> response) {
                    verifying = false;
                    if (isFinishing() || isDestroyed()) return;
                    CurrentUser user = response.body();
                    if (response.isSuccessful() && user != null && user.id > 0) {
                        UserPreferences.saveSession(SiteActivity.this, token, user);
                        CookieManager.getInstance().flush();
                    } else {
                        lastRejectedToken = token;
                        Toast.makeText(SiteActivity.this, getString(R.string.error_invalid_session), Toast.LENGTH_SHORT).show();
                    }
                }
                @Override public void onFailure(Call<CurrentUser> call, Throwable t) { verifying = false; }
            });
            return;
        }
        if (UserPreferences.isLoggedIn(this)) UserPreferences.clearSession(this);
    }
    @Override protected void onResume() { super.onResume(); handler.post(checkSession); }
    @Override protected void onPause() { handler.removeCallbacks(checkSession); super.onPause(); }
    @Override protected void onDestroy() {
        handler.removeCallbacks(checkSession);
        if (verification != null) verification.cancel();
        if (webView != null) webView.destroy();
        super.onDestroy();
    }
}
