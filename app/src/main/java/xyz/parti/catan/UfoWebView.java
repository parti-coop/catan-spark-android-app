package xyz.parti.catan;

import android.annotation.TargetApi;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.net.ConnectivityManager;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Parcelable;
import android.provider.MediaStore;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.text.TextUtils;
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

import com.karumi.dexter.Dexter;
import com.karumi.dexter.listener.single.DialogOnDeniedPermissionListener;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
    void onPageItemError(String failingUrl);
    void onStartSocialSignIn(String provider);
    void onCallbackSocialSignIn(String provider);
  }

  static final int REQCODE_CHOOSE_FILE = 1234;
  private static final String CATAN_USER_AGENT = " CatanSparkAndroid/3";
  private static final String BASE_URL = BuildConfig.API_BASE_URL;
  private static final String GOOGLE_OAUTH_START_URL = BASE_URL + "users/auth/google_oauth2";
  private static final String START_URL = BASE_URL + "mobile_app/start";

  private Activity m_activity;
  private WebView m_webView;
  private Listener m_listener;
  private boolean m_wasOfflineShown;
  private boolean m_waitingForForegroundShown;
  private String m_defaultUserAgent;

  private String m_basePageUrl;
  private String m_currentUrl;

  private ValueCallback<Uri[]> m_uploadMultiValueCB;
  private ValueCallback<Uri> m_uploadSingleValueCB;
  private String m_cameraPhotoPath;
  private Uri m_capturedImageURI = null;


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

    @Override
    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    public boolean onShowFileChooser(WebView mWebView, final ValueCallback<Uri[]> filePathCallback, WebChromeClient.FileChooserParams fileChooserParams)
    {
      if(m_activity == null) { return false; }

      if(PackageManager.PERMISSION_GRANTED == ContextCompat.checkSelfPermission(m_activity, android.Manifest.permission.CAMERA)) {
        return showMultiFileChooser(filePathCallback);
      } else {
        Dexter.withActivity(m_activity)
                .withPermission(android.Manifest.permission.CAMERA)
                .withListener(
                        DialogOnDeniedPermissionListener.Builder
                                .withContext(m_activity)
                                .withTitle("카메라 권한")
                                .withMessage("카메라로 사진 찍을 권한이 필요합니다")
                                .withButtonText(android.R.string.ok)
                                .withIcon(R.mipmap.ic_launcher)
                                .build()).check();
        return false;
      }
    }

    private boolean showMultiFileChooser(ValueCallback<Uri[]> filePathCallback) {
      if(m_activity == null || filePathCallback == null) { return false; }

      m_uploadSingleValueCB = null;
      if (m_uploadMultiValueCB != null) {
        m_uploadMultiValueCB.onReceiveValue(null);
      }
      m_uploadMultiValueCB = filePathCallback;

      try {
        List<Intent> allIntents = new ArrayList<>();

        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(m_activity.getPackageManager()) != null) {
          // Create the File where the photo should go
          File photoFile = null;
          try {
            photoFile = createImageFile();
          } catch (IOException ex) {
            // Error occurred while creating the File
            Util.e(getClass().getName(), "사진 파일을 저장할 수 없습니다", ex);
          }
          // Continue only if the File was successfully created
          if (photoFile != null) {
            m_cameraPhotoPath = "file:" + photoFile.getAbsolutePath();
            takePictureIntent.putExtra("PhotoPath", m_cameraPhotoPath);
            Uri photoUri = FileProvider.getUriForFile(m_activity, "xyz.parti.catan.fileprovider", photoFile);
            takePictureIntent.putExtra(MediaStore.EXTRA_OUTPUT, photoUri);
          } else {
            takePictureIntent = null;
          }
        }

        if(takePictureIntent != null) {
          allIntents.add(takePictureIntent);
        }

        // 파일
        Intent fileIntent = new Intent(Intent.ACTION_GET_CONTENT);
        fileIntent.addCategory(Intent.CATEGORY_OPENABLE);
        fileIntent.setType("file/*");

        List<ResolveInfo> listFile = m_activity.getPackageManager().queryIntentActivities(fileIntent, 0);
        for (ResolveInfo res : listFile) {
          Intent intent = new Intent(fileIntent);
          ComponentName componentName = new ComponentName(res.activityInfo.packageName, res.activityInfo.name);
          intent.setComponent(componentName);
          intent.setPackage(res.activityInfo.packageName);
          allIntents.add(intent);
        }

        // 갤러리
        Intent galleryIntent = new Intent(Intent.ACTION_GET_CONTENT);
        galleryIntent.addCategory(Intent.CATEGORY_OPENABLE);
        galleryIntent.setType("image/*");

        List<ResolveInfo> listGallery = m_activity.getPackageManager().queryIntentActivities(galleryIntent, 0);
        for (ResolveInfo res : listGallery) {
          Intent intent = new Intent(galleryIntent);
          ComponentName componentName = new ComponentName(res.activityInfo.packageName, res.activityInfo.name);

          boolean alreadyIntent = false;
          for (Intent previousIntent : allIntents) {
            if (previousIntent.getComponent() == null) {
              continue;
            }
            if (previousIntent.getComponent().getClassName().equals(componentName.getClassName())) {
              alreadyIntent = true;
              break;
            }
          }

          if(alreadyIntent) {
            continue;
          }

          intent.setComponent(new ComponentName(res.activityInfo.packageName, res.activityInfo.name));
          intent.setPackage(res.activityInfo.packageName);
          allIntents.add(intent);
        }

        // the main intent is the last in the list (fucking android) so pickup the useless one
        Intent mainIntent = allIntents.get(allIntents.size() - 1);
        allIntents.remove(mainIntent);

        Intent chooserIntent = new Intent(Intent.ACTION_CHOOSER);
        chooserIntent.putExtra(Intent.EXTRA_INTENT, mainIntent);
        chooserIntent.putExtra(Intent.EXTRA_TITLE, "파일 선택");
        chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, allIntents.toArray(new Parcelable[allIntents.size()]));

        m_activity.startActivityForResult(chooserIntent, REQCODE_CHOOSE_FILE);
      } catch (ActivityNotFoundException e) {
        m_uploadSingleValueCB = null;
        m_uploadMultiValueCB = null;
        Util.toastShort(m_activity, "앗 뭔가 잘못되었습니다!");
        return false;
      }

      return true;
    }

    private File createImageFile() throws IOException {
      if(m_activity == null) { return null; }

      File[] cacheDirs = ContextCompat.getExternalCacheDirs(m_activity);
      for (File dir : cacheDirs)
      {
        if (dir.exists())
        {
          // TODO: space check
          return new File( dir, "capture_" + System.currentTimeMillis() + ".jpg" );
        }
      }

      return null;
    }

    // Android 4.4 : 지원 안됨 / 우회 구현 안함
    // Android 4.3 이하 : 아래 호출이 안되는 버그가 있음
    //openFileChooser for Android 4.1 (API level 16) up to Android 4.3 (API level 18)
    @SuppressWarnings("unused")
    public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType, String capture)
    {
      showFileChooser(uploadMsg);
    }

    // openFileChooser for Android 3.0 (API level 11) up to Android 4.0 (API level 15)
    @SuppressWarnings("unused")
    public void openFileChooser(ValueCallback<Uri> uploadMsg, String acceptType)
    {
      showFileChooser(uploadMsg);
    }

    // openFileChooser for Android 2.2 (API level 8) up to Android 2.3 (API level 10)
    @SuppressWarnings("unused")
    public void openFileChooser(ValueCallback<Uri> uploadMsg)
    {
      showFileChooser(uploadMsg);
    }

    // openFileChooser
    private void showFileChooser(ValueCallback uploadMsg)
    {
      if(m_activity == null) { return; }

      m_uploadSingleValueCB = uploadMsg;
      m_uploadMultiValueCB = null;

      // Create AndroidExampleFolder at sdcard
      File imageStorageDir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "parti");
      if (!imageStorageDir.exists()) {
        // Create AndroidExampleFolder at sdcard
        imageStorageDir.mkdirs();
      }
      // Create camera captured image file path and name
      File file = new File(
              imageStorageDir + File.separator + "IMG_"
                      + String.valueOf(System.currentTimeMillis())
                      + ".jpg");
      m_capturedImageURI = Uri.fromFile(file);
      // Camera capture image intent
      final Intent captureIntent = new Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
      captureIntent.putExtra(MediaStore.EXTRA_OUTPUT, m_capturedImageURI);

      Intent i = new Intent(Intent.ACTION_GET_CONTENT);
      i.addCategory(Intent.CATEGORY_OPENABLE);
      i.setType("*/*");
      // Create file chooser intent
      Intent chooserIntent = Intent.createChooser(i, "파일 선택");
      // Set camera intent to file chooser
      chooserIntent.putExtra(Intent.EXTRA_INITIAL_INTENTS, new Parcelable[] { captureIntent });
      // On select image call onActivityResult method of activity
      m_activity.startActivityForResult(chooserIntent, REQCODE_CHOOSE_FILE);
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
    if(Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
      if (m_uploadMultiValueCB == null) { return; }

      Uri[] results = null;

      // Check that the response is a good one
      if (resultCode == Activity.RESULT_OK) {
        if (intent == null) {
          // If there is not data, then we may have taken a photo
          if (m_cameraPhotoPath != null) {
            results = new Uri[]{Uri.parse(m_cameraPhotoPath)};
          }
        } else {
          results = WebChromeClient.FileChooserParams.parseResult(resultCode, intent);
        }
      }

      m_uploadMultiValueCB.onReceiveValue(results);
      m_uploadMultiValueCB = null;
    } else {
      if (m_uploadSingleValueCB == null) { return; }

      Uri result = null;

      if (resultCode == Activity.RESULT_OK) {
        result = intent == null ? m_capturedImageURI : intent.getData();
      }

      m_uploadSingleValueCB.onReceiveValue(result);
      m_uploadSingleValueCB = null;
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
      if (failingUrl != null && !(failingUrl.startsWith("http:") || failingUrl.startsWith("https:"))) {
        Util.d("onReceivedError(%d,%s, %s) ignore for none http(s)", errorCode, description, failingUrl);
        return;
      }

      Util.d("onReceivedError(%d,%s, %s)", errorCode, description, failingUrl);
      if (CatanApp.getApp().isBackground()) {
        onWaitingForForeground();
      } else if (Util.isNetworkOnline(MainActivity.getInstance())) {
        if (view.getUrl().equals(failingUrl)) {
          m_listener.onPageError(failingUrl);
        } else {
          m_listener.onPageItemError(failingUrl);
        }
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
      Util.d("shouldOverrideUrlLoading WebResourceRequest : %s", request.getUrl().toString());
      return this.shouldOverrideUrlLoading(view, request.getUrl().toString());
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

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT && BuildConfig.IS_DEBUG) {
      WebView.setWebContentsDebuggingEnabled(true);
    }
  }

  public void onStart(Activity act) {
    registerReciver(act);
  }

  public void onStop(Activity act)
  {
    act.unregisterReceiver(m_connectivityReceiver);
  }

  public void bootstrap(UrlBundle urlBundle) {
    String pushNotificationUrl = urlBundle.getPushNotifiedUrl();
    String appLinkUrl = urlBundle.getAppLinkUrl();

    if (pushNotificationUrl != null) {
      m_currentUrl = pushNotificationUrl;
      bootstrapForPushNotification(pushNotificationUrl);
    } else if(appLinkUrl != null) {
      m_currentUrl = appLinkUrl;
      bootstrapForUrl(appLinkUrl, null);
    } else {
      bootstrapForUrl(getLastOnlineUrl(), null);
    }
  }

  public void bootstrap() {
    bootstrapForUrl(getLastOnlineUrl(), null);
  }

  private void bootstrapForPushNotification(String pushNotifiedUrl) {
    HashMap customHeader = new HashMap<String, String>();
    customHeader.put("X-Catan-Push-Notified", "true");
    bootstrapForUrl(pushNotifiedUrl, customHeader);
  }

  private void bootstrapForUrl(String url, Map<String, String> customHeader) {
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
    loadRemoteUrlIfNew(url, customHeader);
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

  public boolean canGoBack() {
    return m_webView.getUrl() != null
            && !m_webView.getUrl().equals(UfoWebView.BASE_URL)
            && !isStartUrl(m_webView.getUrl())
            && m_basePageUrl != null;
  }

  public void loadRemoteUrl(String url) {
    loadRemoteUrl(url, null);
  }

  public void loadRemoteUrl(String url, Map<String, String> customHeader) {
    Util.d("loadRemoteUrl: %s", url);
    if ( !shouldOverrideRemoteUrlLoading(m_webView, url) ) {
      if (customHeader == null) {
        m_webView.loadUrl(url);
      } else {
        m_webView.loadUrl(url, customHeader);
      }
    }
  }

  public void loadRemoteUrlIfNew(String url, Map<String, String> customHeader)
  {
    if( url == null || url.equals(m_webView.getUrl()) ) {
      return;
    }
    loadRemoteUrl(url, customHeader);
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
    loadRemoteUrl(getLastOnlineUrl());
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
      Util.startWebBrowser(MainActivity.getInstance(), param);
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
    Util.startWebBrowser(MainActivity.getInstance(), url);
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

        if (TextUtils.isEmpty(m_basePageUrl)) {
          m_basePageUrl = UfoWebView.BASE_URL;
        }
        else if(m_basePageUrl.equals(backBrowserUrl)) {
          m_webView.goBack();
        } else {
          loadRemoteUrl(m_basePageUrl);
        }
      }
    });
  }

  @JavascriptInterface
  public void retryLastRemoteUrl() {
    m_activity.runOnUiThread(new Runnable() {

      @Override
      public void run() {
        loadRemoteUrl(getLastOnlineUrl());
      }
    });
  }

  @JavascriptInterface
  public void changeBasePageUrl(String url) {
    if (url != null) {
      m_basePageUrl = url;
    }
  }

  @JavascriptInterface
  public void changeCurrentUrl(String url) {
    if (url != null) {
      m_currentUrl = url;
    }
  }

  @JavascriptInterface
  public void startSocialSignIn(final String provider) {
    m_listener.onStartSocialSignIn(provider);
  }

  @JavascriptInterface
  public void callbackSocialSignIn(final String provider) {
    m_listener.onCallbackSocialSignIn(provider);
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
    if (!Util.isNetworkOnline(MainActivity.getInstance())) {
      onNetworkOffline();
      return true;
    }

    view.getSettings().setUserAgentString(m_defaultUserAgent);   // set default
    return false;
  }

  private String getLastOnlineUrl() {
    if(Util.isNullOrEmpty(m_currentUrl)) {
      return getStartUrl();
    }
    return m_currentUrl;
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

  public boolean isCancelableLoading() {
    return !Util.isNullOrEmpty(m_currentUrl) && !isStartUrl(m_webView.getUrl());
  }

  public boolean isLoading(int progress) {
    return m_webView.getProgress() < progress;
  }

  public void stopLoading() {
    m_webView.stopLoading();
  }
}
