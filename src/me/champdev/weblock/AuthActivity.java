package me.champdev.weblock;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * A bare browser to sign in to a tracker (AniList, MyAnimeList). It opens `url`; when the page is sent on to `redirect` (the address
 * registered with the tracker) it stops there and returns that address as the result's data. Back closes it with nothing.
 */
public class AuthActivity extends Activity {
    WebView web;
    String redirect;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        redirect = getIntent().getStringExtra("redirect");
        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        web.setBackgroundColor(Home.BG);
        web.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest r) {
                return arrived(r.getUrl());
            }
        });
        setContentView(web);
        web.loadUrl(getIntent().getStringExtra("url"));
    }

    boolean arrived(Uri u) {
        if (redirect == null || !u.toString().startsWith(redirect)) return false;
        setResult(RESULT_OK, new Intent().setData(u));
        finish();
        return true;
    }

    @Override public void onBackPressed() {
        if (web.canGoBack()) web.goBack(); else super.onBackPressed();
    }

    @Override protected void onDestroy() {
        web.destroy();
        super.onDestroy();
    }
}
