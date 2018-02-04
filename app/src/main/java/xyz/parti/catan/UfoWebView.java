package xyz.parti.catan;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Handler;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.ValueCallback;
import android.webkit.WebBackForwardList;
import android.webkit.WebChromeClient;
import android.webkit.WebHistoryItem;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;


public class UfoWebView
{
  public interface Listener
  {
    void onPostAction(String action, JSONObject json) throws JSONException;
    void onProgressChange(int progress);
    void onPageStarted(String url);
    void onPageFinished(String url);
    void onPageError(String failingUrl);
  }

  static final int REQCODE_CHOOSE_FILE = 1234;
  private static final String CATAN_USER_AGENT = " CatanSparkAndroid/2";
  private static final String FAKE_USER_AGENT_FOR_GOOGLE_OAUTH = "Mozilla/5.0 (Linux; Android 6.0.1; SM-G920V Build/MMB29K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/52.0.2743.98 Mobile Safari/537.36" + CATAN_USER_AGENT;
  private static final String BASE_URL = BuildConfig.API_BASE_URL;
  private static final String GOOGLE_OAUTH_START_URL = BASE_URL + "users/auth/google_oauth2";
  private static final String START_URL = BASE_URL + "mobile_app/start";

  private Activity m_activity;
  private WebView m_webView;
  private Listener m_listener;
  private boolean m_wasOfflineShown;
  private boolean m_waitingForForegroundShown;
  private List<String> m_onlineUrls = new ArrayList<>();
  private String m_defaultUserAgent;

  private ValueCallback<Uri[]> m_uploadMultiValueCB;
  private ValueCallback<Uri> m_uploadSingleValueCB;

  private WebChromeClient m_chromeClient = new WebChromeClient()
  {
    @Override
    public boolean onJsAlert(WebView view, String url, String message, final android.webkit.JsResult result)
    {
      new AlertDialog.Builder(view.getContext())
        .setTitle(R.string.app_name)
        .setMessage(message)
        .setPositiveButton(android.R.string.ok, new AlertDialog.OnClickListener() {
          public void onClick(DialogInterface dialog, int which)
          {
            result.confirm();
          }
        })
        .setCancelable(false)
        .create()
        .show();

      return true;
    };

    @Override
    public boolean onJsConfirm(WebView view, String url, String message, final JsResult result)
    {
      new AlertDialog.Builder(view.getContext())
        .setMessage(message)
        .setPositiveButton(android.R.string.yes,
          new AlertDialog.OnClickListener(){
            public void onClick(DialogInterface dialog, int which) {
              result.confirm();
            }
          })
        .setNegativeButton(android.R.string.no,
          new AlertDialog.OnClickListener(){
            public void onClick(DialogInterface dialog, int which) {
              result.cancel();
            }
          })
        .setCancelable(false)
        .create()
        .show();
      return true;
    }

    public boolean onJsPrompt(WebView view, String url, String message, String defaultValue, JsPromptResult result)
    {
      return false;
    }

    @Override
    public boolean onCreateWindow(WebView view, boolean dialog, boolean userGesture, android.os.Message resultMsg) {
      final Context context = view.getContext();
      final WebView dummyWebView = new WebView(context); // pass a context
      dummyWebView.setWebViewClient(new WebViewClient() {
        @Override
        public void onPageStarted(WebView view, String url,
                      Bitmap favicon) {
          handleLinksOnCreateWindow(context, url); // you can get your target url here
          dummyWebView.stopLoading();
          dummyWebView.destroy();
        }
      });
      WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
      transport.setWebView(dummyWebView);
      resultMsg.sendToTarget();
      return true;
    }

    private void handleLinksOnCreateWindow(Context context, String url) {
      Util.d("handleLinksOnCreateWindow: %s", url);

      if (Pattern.compile(BuildConfig.API_BASE_URL_REGX).matcher(url).lookingAt()) {
        loadRemoteUrl(url);
      } else {
        Util.startWebBrowser(context, url);
      }
    }

    private boolean startFileChooser(ValueCallback<Uri> single, ValueCallback<Uri[]> multi, Intent itt)
    {
      m_uploadSingleValueCB = single;
      m_uploadMultiValueCB = multi;

      try
      {
        m_activity.startActivityForResult(itt, REQCODE_CHOOSE_FILE);
      }
      catch (ActivityNotFoundException e)
      {
        m_uploadSingleValueCB = null;
        m_uploadMultiValueCB = null;
        Util.toastShort(m_activity, "Cannot open file chooser");
        return false;
      }

      return true;
    }

    @Override
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public boolean onShowFileChooser(WebView mWebView, ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams)
    {
      if (m_uploadMultiValueCB != null)
      {
        m_uploadMultiValueCB.onReceiveValue(null);
      }

      return startFileChooser(null, filePathCallback, fileChooserParams.createIntent());
    }

    public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture)
    {
      openFileChooser(uploadMsg, acceptType);
    }

    public void openFileChooser(ValueCallback uploadMsg, String acceptType)
    {
      Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
      intent.addCategory(Intent.CATEGORY_OPENABLE);
      intent.setType(acceptType);

      startFileChooser(uploadMsg, null, Intent.createChooser(intent, "File Browser"));
    }

    public void openFileChooser(ValueCallback<Uri> uploadMsg)
    {
      openFileChooser(uploadMsg, "image/*");
    }

    @Override
    public void onProgressChanged(WebView view, int newProgress) {
      Util.d("onProgressChanged %d", newProgress);
      m_listener.onProgressChange(newProgress);
      super.onProgressChanged(view, newProgress);
    }
  };

  public void onFileChooseResult(int resultCode, Intent intent)
  {
    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP)
    {
      if (m_uploadMultiValueCB != null)
      {
        m_uploadMultiValueCB.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, intent));
        m_uploadMultiValueCB = null;
      }
    }
    else
    {
      if (m_uploadSingleValueCB != null)
      {
        Uri result = intent == null || resultCode != Activity.RESULT_OK ? null : intent.getData();
        m_uploadSingleValueCB.onReceiveValue(result);
        m_uploadSingleValueCB = null;
      }
    }
  }

  private WebViewClient m_webClient = new WebViewClient()
  {
    @Override
    public void onPageStarted(WebView view, String url, Bitmap favicon)
    {
      Util.d("onPageStarted(%s)", url);
      m_listener.onPageStarted(url);
      super.onPageStarted(view, url, favicon);
    }

    @Override
    public void onPageFinished(WebView view, String url)
    {
      Util.d("onPageFinished(%s)", url);
      m_listener.onPageFinished(url);
      super.onPageFinished(view, url);
    }

    @SuppressWarnings("deprecation")
    @Override
    public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
      Util.d("onReceivedError(%d,%s, %s)", errorCode, description, failingUrl);
      if (failingUrl != null && !(failingUrl.startsWith("http:") || failingUrl.startsWith("https:"))) {
        Util.d("onReceivedError(%d,%s, %s) ignore for none http(s)", errorCode, description, failingUrl);
      }
      if (CatanApp.getApp().isBackground()) {
        onWaitingForForeground();
      } else if (Util.isNetworkOnline(MainAct.getInstance())) {
        m_listener.onPageError(failingUrl);
      } else {
        onNetworkOffline();
      }
    }

    @TargetApi(Build.VERSION_CODES.M)
    @Override
    public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error)
    {
      onReceivedError(view, error.getErrorCode(), error.getDescription().toString(), request.getUrl().toString());
    }

    /**
     * @return True if the host application wants to leave the current WebView and handle the url itself, otherwise return false.
     */
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, final String url)
    {
      Util.d("shouldOverrideUrlLoading: %s", url);

      if (url.startsWith("ufo"))
      {
        handleUfoLink(url.substring(4));
        return true;
      }

      if (url.startsWith("mailto:"))
      {
        Intent itt = new Intent(Intent.ACTION_SENDTO, Uri.parse(url));
        m_activity.startActivity(itt);
        return true;
      }

      if (url.startsWith("http:") || url.startsWith("https:"))
      {
        return shouldOverrideRemoteUrlLoading(view, url);
      }

      // 해당되는 경우가 없으므로 계속 진행한다
      return false;
    }

    @Override
    public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
      return super.shouldInterceptRequest(view, request);
    }

    @TargetApi(Build.VERSION_CODES.N)
    @Override
    public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
      return super.shouldOverrideUrlLoading(view, request.getUrl().toString());
    }

    @Override
    public void onReceivedSslError(WebView view, SslErrorHandler handler, SslError error)
    {
      if(BuildConfig.IS_DEBUG) {
        handler.proceed();
      } else {
        super.onReceivedSslError(view, handler, error);
      }
    }
  };

  private BroadcastReceiver m_connectivityReceiver = new BroadcastReceiver()
  {
    @Override
    public void onReceive(final Context context, final Intent intent)
    {
      new Handler().postDelayed(new Runnable() {
        @Override
        public void run() {
          if (Util.isNetworkOnline(context)) {
            Util.d("m_connectivityReceiver(onNetworkReady)");
            onNetworkReady();
          } else {
            Util.d("m_connectivityReceiver(onNetworkOffline)");
            onNetworkOffline();
          }
        }
      }, 3000);
    }
  };

  public UfoWebView(Activity act, View webView, Listener lsnr)
  {
    m_activity = act;
    m_webView = (WebView) webView;
    m_listener = lsnr;

    WebSettings webSettings = m_webView.getSettings();
    webSettings.setSaveFormData(false);
    webSettings.setJavaScriptEnabled(true);
    webSettings.setJavaScriptCanOpenWindowsAutomatically(true);
    webSettings.setSupportMultipleWindows(true);
    String userAgent = webSettings.getUserAgentString();
    m_defaultUserAgent = userAgent + CATAN_USER_AGENT;
    webSettings.setUserAgentString(m_defaultUserAgent);

    webSettings.setDefaultFontSize(18);
    if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH)
      webSettings.setTextZoom(100);

    webSettings.setAppCacheEnabled(!BuildConfig.IS_DEBUG);
    webSettings.setCacheMode(BuildConfig.IS_DEBUG ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_DEFAULT);

    m_webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
    m_webView.setWebViewClient(m_webClient);
    m_webView.setWebChromeClient(m_chromeClient);
    m_webView.addJavascriptInterface(this, "ufo");
  }

  public void onStart(Activity act, String afterStartUrl) {
    registerReciver(act);

    String url;
    if (afterStartUrl == null) {
      url = getLastOnlineUrl(getStartUrl());
    } else {
      url = getStartUrl(afterStartUrl);
    }
    onBootstrap(url);
  }

  public void onStop(Activity act)
  {
    act.unregisterReceiver(m_connectivityReceiver);
  }

  public void onBootstrap(String url) {
    if (!Util.isNetworkOnline(m_webView.getContext())) {
      onNetworkOffline();
      return;
    }
    if (m_wasOfflineShown) {
      m_wasOfflineShown = false;
    }
    if (m_waitingForForegroundShown) {
      m_waitingForForegroundShown = false;
      if (!isStartUrl(url)) {
        url = getStartUrl(url);
      }
    }
    loadRemoteUrlIfNew(url);
  }

  public void onResume() {
    m_webView.resumeTimers();
    callHiddenWebViewMethod("onResume");
  }

  public void onPause() {
    m_webView.pauseTimers();
    callHiddenWebViewMethod("onPause");
  }

  private void callHiddenWebViewMethod(String name){
    if( m_webView != null ){
      try {
        Method method = WebView.class.getMethod(name);
        method.invoke(m_webView);
      } catch (NoSuchMethodException e) {
        Util.e("No such method: " + name , e.toString());
      } catch (IllegalAccessException e) {
        Util.e("Illegal Access: " + name, e.toString());
      } catch (InvocationTargetException e) {
        Util.e("Invocation Target Exception: " + name, e.toString());
      }
    }
  }

  private void registerReciver(Activity act) {
    final IntentFilter intentFilter = new IntentFilter();
    intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
    act.registerReceiver(m_connectivityReceiver, intentFilter);
  }

  public boolean canGoBack()
  {
    return m_webView.getUrl() != null
            && !m_webView.getUrl().equals(UfoWebView.BASE_URL)
            && !isStartUrl(m_webView.getUrl());
  }

  public void loadRemoteUrl(String url)
  {
    Util.d("loadRemoteUrl: %s", url);
    if ( !shouldOverrideRemoteUrlLoading(m_webView, url) ) {
      m_webView.loadUrl(url);
    }
  }

  public void loadRemoteUrlIfNew(String url)
  {
    if( url == null || url.equals(m_webView.getUrl()) ) {
      return;
    }
    loadRemoteUrl(url);
  }

  public void loadLocalHtml(String htmlName)
  {
    Util.d("loadLocalHtml: %s", htmlName);
    String url = "file:///android_asset/" + htmlName;
    m_webView.loadUrl(url);
  }

  public void onNetworkOffline()
  {
    if (m_wasOfflineShown) {
      return;
    }
    m_wasOfflineShown = true;
    loadLocalHtml("offline.html");
  }

  public void onNetworkReady()
  {
    if (!m_wasOfflineShown) {
      return;
    }
    m_wasOfflineShown = false;
    loadRemoteUrl(getLastOnlineUrl(getStartUrl()));
  }

  public void onWaitingForForeground()
  {
    Util.d("Wating for foreground");
    if (m_waitingForForegroundShown) {
      return;
    }
    m_waitingForForegroundShown = true;
    loadLocalHtml("background.html");
  }

  public void handleUfoLink(String link)
  {
    String action, param;

    int slash = link.indexOf('/');
    if (slash > 0)
    {
      action = link.substring(0, slash);
      param = link.substring(slash +1);
    }
    else
    {
      action = link;
      param = null;
    }

    if ("post".equalsIgnoreCase(action))
    {
      post_(param, null);
    }
    else if ("fork".equalsIgnoreCase(action))
    {
      Util.d("fork: %s", param);
      Util.startWebBrowser(MainAct.getInstance(), param);
    }
    else if ("eval".equalsIgnoreCase(action))
    {
      if (param != null)
      {
        evalJs(param);
      }
    }
    else
    {
      Util.e("Unhandled action: %s(%s)", action, param);
    }
  }

  public void evalJs(String format, Object ... args)
  {
    String js = args.length == 0 ? format : String.format(format, args);
Util.d("JS: %s", js);
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT)
    {
      m_webView.evaluateJavascript(js, null);
    }
    else
    {
      m_webView.loadUrl("javascript:"+js);
    }
  }

  @JavascriptInterface
  public void alert(final String msg)
  {
    m_activity.runOnUiThread(new Runnable()
    {
      public void run()
      {
        new AlertDialog.Builder(m_activity)
          .setTitle(R.string.app_name)
          .setMessage(msg)
          .setPositiveButton(android.R.string.ok, null)
          .setCancelable(false)
          .create()
          .show();
      }
    });
  }

  @JavascriptInterface
  public void forkPage(String url)
  {
    if (url.startsWith("ufo:fork/"))
      url = url.substring(9);

    Util.d("forkPage: %s", url);
    Util.startWebBrowser(MainAct.getInstance(), url);
  }

  @JavascriptInterface
  public void goBack()
  {
    m_activity.runOnUiThread(new Runnable() {
      @Override
      public void run() {
        WebBackForwardList webBackForwardList = m_webView.copyBackForwardList();
        int itemIndex = webBackForwardList.getCurrentIndex();
        String backBrowserUrl = null;
        if(itemIndex > 1) {
          WebHistoryItem item = webBackForwardList.getItemAtIndex(itemIndex - 1);
          if(item != null) {
            backBrowserUrl = item.getUrl();
          }
        }

        if(!m_onlineUrls.isEmpty()) {
          m_onlineUrls.remove(m_onlineUrls.size() - 1);
        }
        String backOnlineUrl = getLastOnlineUrl(UfoWebView.BASE_URL);

        if(backOnlineUrl.equals(backBrowserUrl)) {
          m_webView.goBack();
        } else {
          loadRemoteUrl(backOnlineUrl);
        }
      }
    });
  }

  @JavascriptInterface
  public void retryLastRemoteUrl() {
    m_activity.runOnUiThread(new Runnable() {

      @Override
      public void run() {
        loadRemoteUrl(getLastOnlineUrl(getStartUrl()));
      }
    });
  }

  @JavascriptInterface
  public void addOnlineUrl(String url) {
    if (url != null && !url.equals(getLastOnlineUrl()) ) {
      m_onlineUrls.add(url);
    }
  }

  @JavascriptInterface
  public void post_(final String action, String jsonStr)
  {
    if (m_listener == null)
    {
      Util.e("ActionListener is null, ignored: %s(%s)", action, jsonStr);
      return;
    }

    JSONObject _json = null;
    if (jsonStr != null && !jsonStr.equals("undefined"))
    {
      try
      {
        _json = new JSONObject(jsonStr);
      }
      catch (JSONException e)
      {
        Util.e("JSON parse failed: '%s'", jsonStr);
      }
    }

    final JSONObject json = _json;
    m_activity.runOnUiThread(new Runnable()
    {
      public void run()
      {
        try
        {
          m_listener.onPostAction(action, json);
        }
        catch (JSONException e)
        {
          e.printStackTrace();
          Util.e("handleAction(%s) JSON(%s) ex: %s", action, json, e.getMessage());
        }
      }
    });
  }

  public static String escapeJsQuoteString(String src)
  {
    if (src == null)
      return "";

    return src.replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r");
  }

  /**
   * @return True if the host application wants to leave the current WebView and handle the url itself, otherwise return false.
   */
  protected boolean shouldOverrideRemoteUrlLoading(WebView view, String url) {
    Util.d("shouldOverrideRemoteUrlLoading: %s", url);
    if (!Util.isNetworkOnline(MainAct.getInstance())) {
      onNetworkOffline();
      return true;
    }

    if (BuildConfig.IS_DEBUG) {
      // 구글 Oauth에서 parti.dev로 인증결과가 넘어오면 로컬 개발용이다.
      // 그러므로 Config.apiBaseUrl로 주소를 바꾸어 인증하도록 한다
      String GOOGLE_OAUTH_FOR_DEV_URL = "https://parti.dev/users/auth/google_oauth2/callback";
      if ( url.startsWith(GOOGLE_OAUTH_FOR_DEV_URL) ) {
        view.getSettings().setUserAgentString(m_defaultUserAgent);
        view.loadUrl(url.replace("https://parti.dev/", UfoWebView.BASE_URL));
        return true;
      }
    }

    if (url.startsWith(GOOGLE_OAUTH_START_URL)) {
      // 구글 인증이 시작되었다.
      // 가짜 User-Agent 사용을 시작한다.
      view.getSettings().setUserAgentString(FAKE_USER_AGENT_FOR_GOOGLE_OAUTH);
      return false;
    } else if (FAKE_USER_AGENT_FOR_GOOGLE_OAUTH.equals(view.getSettings().getUserAgentString())) {
      // 가짜 User-Agent 사용하는 걸보니 이전 request에서 구글 인증이 시작된 상태이다.
      if ( url.startsWith("https://accounts.google.com") ) {
        // 구글 인증이 시작된 상태였다가
        // 구글 인증 주소가 아닌 다른 페이지로 이동하는 중이다.
        // 구글 인증이 끝났다고 보고 원래 "User-Agent"로 원복한다.
        view.getSettings().setUserAgentString(m_defaultUserAgent);   // set default
        return false;
      } else {
        // 아직 구글로그인 중이다
        view.getSettings().setUserAgentString(FAKE_USER_AGENT_FOR_GOOGLE_OAUTH);
        return false;
      }
    }

    view.getSettings().setUserAgentString(m_defaultUserAgent);   // set default
    return false;
  }

  private String getLastOnlineUrl() {
    return getLastOnlineUrl(null);
  }

  private String getLastOnlineUrl(String fallback) {
    if(m_onlineUrls.isEmpty()) {
      return fallback;
    }
    return m_onlineUrls.get(m_onlineUrls.size() - 1);
  }

  public boolean isStartUrl(String url) {
    if (url == null) return false;
    try {
      if (url.startsWith(UfoWebView.START_URL) && new URL(UfoWebView.START_URL).getPath() == new URL(url).getPath()) {
        return true;
      }
    } catch (MalformedURLException e) {}
    return false;
  }

  private String getStartUrl() {
    return getStartUrl(null);
  }

  private String getStartUrl(String afterStartUrl) {
    String encoded = "";
    try {
      encoded = URLEncoder.encode((afterStartUrl != null ? afterStartUrl : UfoWebView.BASE_URL), "UTF-8");
    } catch (UnsupportedEncodingException ignored) {
    }
    return UfoWebView.START_URL + "?after=" + encoded;
  }
}
