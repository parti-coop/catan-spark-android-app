package xyz.parti.catan;

import android.animation.ObjectAnimator;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.support.v4.content.ContextCompat;
import android.support.v4.content.FileProvider;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.ProgressBar;

import com.crashlytics.android.Crashlytics;
import com.google.firebase.iid.FirebaseInstanceId;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.net.URLConnection;
import java.util.List;

public class MainAct extends AppCompatActivity implements UfoWebView.Listener, ApiMan.Listener
{
  //private static final String KEY_UID = "xUID";
  private static final String KEY_AUTHKEY = "xAK";
  public static final String PUSHARG_URL = "url";

  private View m_vwSplashScreen;
  private UfoWebView m_webView;
  private FrameLayout m_progressLayoutView;
  private ProgressBar m_progressBarView;
  private ProgressDialog m_downloadPrgsDlg;

  private static MainAct s_this;
  public static MainAct getInstance()
  {
    return s_this;
  }

  @Override
  protected void onCreate(Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.act_main);

    s_this = this;
    CatanApp.getApp().onStartup();

    if (m_webView != null)
    {
      // already initialized
      return;
    }

    if (BuildConfig.IS_DEBUG) CatanApp.getApiManager().setDevMode();
  }

  @Override
  public void onStart()
  {
    super.onStart();

    if (m_webView != null)
    {
      m_webView.onStart(this, parsePushBundleUrl(getIntent().getExtras()));
      return;
    }

    m_vwSplashScreen = findViewById(R.id.splash);
    m_webView = new UfoWebView(this, findViewById(R.id.web), this);
    m_progressLayoutView = findViewById(R.id.progressLayout);
    m_progressBarView = findViewById(R.id.progressBar);
    m_progressBarView.setMax(100);
    m_progressBarView.setProgress(0);

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
    {
      // Create channel to show notifications.
      String channelId  = getString(R.string.default_notification_channel_id);
      String channelName = getString(R.string.default_notification_channel_name);
      NotificationManager notificationManager = getSystemService(NotificationManager.class);
      notificationManager.createNotificationChannel(new NotificationChannel(channelId,
        channelName, NotificationManager.IMPORTANCE_LOW));
    }

    m_webView.onStart(this, parsePushBundleUrl(getIntent().getExtras()));

    // 1초간 스플래시 화면을 보여줍니다.
    // iOS는 Launch스크린이 필수라서 대응하며 만든 기능입니다. 이 기능이 필요 없으면 연락주세요.
    m_vwSplashScreen.postDelayed(new Runnable()
    {
      @Override
      public void run()
      {
        m_vwSplashScreen.setVisibility(View.GONE);
      }
    }, 2000);
  }

  @Override
  public void onStop()
  {
    super.onStop();

    if (m_webView != null)
    {
      m_webView.onStop(this);
    }
  }

  @Override
  public void onNewIntent(Intent intent){
    super.onNewIntent(intent);
    if (m_webView != null && intent != null)
    {
      m_webView.onBootstrap(parsePushBundleUrl(intent.getExtras()));
    }
  }

  @Override
  protected void onResume() {
    super.onResume();
    m_webView.onResume();
  }

  @Override
  protected void onPause() {
    super.onResume();
    m_webView.onPause();
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent intent)
  {
    if (requestCode == UfoWebView.REQCODE_CHOOSE_FILE)
    {
      m_webView.onFileChooseResult(resultCode, intent);
    }
  }

  private String parsePushBundleUrl(Bundle bun) {
    if (bun != null && bun.containsKey(PUSHARG_URL)) {
      String url = bun.getString(PUSHARG_URL);
      if (!Util.isNullOrEmpty(url)) {
        return url;
      }
    }
    return null;
  }

  @Override
  public void onBackPressed()
  {
    if (m_webView.canGoBack())
    {
      m_webView.goBack();
    }
    else
    {
      this.finish();
    }
  }

  private String getAuthKey()
  {
    SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
    return sp.getString(KEY_AUTHKEY, null);
  }

  @Override
  public void onPageStarted(String url) {
    m_progressLayoutView.setVisibility(View.VISIBLE);
  }

  @Override
  public void onPageFinished(String url) {
    m_progressLayoutView.setVisibility(View.GONE);
  }

  @Override
  public void onProgressChange(int progress) {
    if (m_progressBarView.getProgress() == progress) {
      return;
    }

    if (progress < 100) {
      if (progress < m_progressBarView.getProgress()) {
        m_progressBarView.setProgress(0);
      }
      m_progressLayoutView.setVisibility(View.VISIBLE);
      ObjectAnimator animation = ObjectAnimator.ofInt(m_progressBarView, "progress", progress);
      animation.setDuration(300); // 0.3 second
      animation.setInterpolator(new DecelerateInterpolator());
      animation.start();
    } else {
      m_progressLayoutView.setVisibility(View.GONE);
      m_progressBarView.setProgress(0);
    }
  }

  @Override
  public void onPageError(String url) {
    m_webView.loadLocalHtml("error.html");
    new Handler().postDelayed(new Runnable() {
      @Override
      public void run() {
        m_webView.retryLastRemoteUrl();
      }
    }, 1000);
  }

  @Override
  public void onPostAction(String action, JSONObject json) throws JSONException
  {
    Util.d("UfoPost(%s,%s)", action, json);

    if ("noAuth".equals(action))
    {
      // 웹뷰가 mobile_app/start 페이지에서 로그인된 상태가 아닐 경우 여기로 옵니다.
      // 앱에 저장된 인증정보가 있으면 웹뷰의 세션 복구를 시도합니다.
      String authkey = getAuthKey();
      if (authkey == null)
      {
        // 서버에서 빈 authkey 를 받으면 앱이 인증정보가 없다는 것으로 간주하도록 작성해야 합니다.
        authkey = "";
      }

      m_webView.evalJs("restoreAuth('%s')", authkey);
    }
    else if ("saveAuth".equals(action))
    {
      // 로그인 후 HTML에서 ufo.post("saveAuth", {"auth":"..."}); 를 호출하여 여기로 옵니다.
      String authkey = json.getString("auth");

      // 로그인 정보를 앱 저장소에 저장하고,
      SharedPreferences.Editor ed = PreferenceManager.getDefaultSharedPreferences(CatanApp.getApp()).edit();
      ed.putString(KEY_AUTHKEY, authkey);
      ed.commit();

      // 서버에 푸시 RegId도 전송합니다.
      String pushToken = FirebaseInstanceId.getInstance().getToken();
      if (pushToken == null)
        pushToken = "";

      String appId = "xyz.parti.catan.android";
      if(BuildConfig.IS_DEBUG) {
        appId += ".debug";
      }
      CatanApp.getApiManager().requestRegisterToken(this, authkey, pushToken, appId);

      // (HTML쪽에서 로그인ID도 보내주면 활용할 수 있음)
      //Crashlytics.setUserName(loginId);
      Crashlytics.setUserIdentifier(authkey);
    }
    else if ("logout".equals(action))
    {
      // 로그아웃 요청이 오면 인증정보를 지우고, 푸시 registrationId 도 삭제 API 요청한다.
      SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(CatanApp.getApp());
      if (sp.contains(KEY_AUTHKEY))
      {
        String lastAuthkey = sp.getString(KEY_AUTHKEY, null);

        SharedPreferences.Editor ed = sp.edit();
        ed.remove(KEY_AUTHKEY);
        ed.commit();

        String pushToken = FirebaseInstanceId.getInstance().getToken();
        if (pushToken != null && pushToken.length() > 0)
        {
          CatanApp.getApiManager().requestDeleteToken(this, lastAuthkey, pushToken);
        }
      }
    }
    else if ("download".equals(action))
    {
      int postId, fileId;
      String fileName;

      try
      {
        postId = json.getInt("post");
        fileId = json.getInt("file");
        fileName = json.getString("name");
      }
      catch (Exception ex)
      {
        Util.showSimpleAlert(this, null, "다운로드 파라메터가 올바르지 않습니다.");
        return;
      }

      String destPath = null;
      File[] cacheDirs = ContextCompat.getExternalCacheDirs(this);
      for (File dir : cacheDirs)
      {
        if (dir.exists())
        {
          // TODO: space check
          File outFile = new File(dir, fileName);
          if (outFile.exists())
          {
            // delete existing old file
            outFile.delete();
          }

          destPath = outFile.getAbsolutePath();
          break;
        }
      }

      if (destPath == null)
      {
        Util.showSimpleAlert(this, null, "다운로드할 저장소가 없는 것 같습니다.");
        return;
      }

      CatanApp.getApiManager().requestFileDownload(this, getAuthKey(), postId, fileId, destPath, m_handler);

      m_downloadPrgsDlg = new ProgressDialog(this);
      m_downloadPrgsDlg.setIndeterminate(false);
      m_downloadPrgsDlg.setProgressStyle(ProgressDialog.STYLE_HORIZONTAL);
      m_downloadPrgsDlg.setProgress(0);
      m_downloadPrgsDlg.setMessage(fileName);
      m_downloadPrgsDlg.setTitle(R.string.downloading);
      m_downloadPrgsDlg.setCancelable(false);
      m_downloadPrgsDlg.setButton(getResources().getString(android.R.string.cancel), new ProgressDialog.OnClickListener() {
        @Override
        public void onClick(DialogInterface dialog, int which) {
          CatanApp.getApiManager().cancelDownload();
        }
      });
      m_downloadPrgsDlg.show();
    }
    else
    {
      Util.d("Unhandled post action: %s", action);
    }
  }

  @Override
  public boolean onApiError(int jobId, String errMsg)
  {
    switch (jobId)
    {
    case ApiMan.JOBID_REGISTER_TOKEN:
      break;

    case ApiMan.JOBID_DELETE_TOKEN:
      // UI 표시 없이 조용히 에러 무시함
      Util.e("DeleteToken API failed: %s", errMsg);
      return true;

    case ApiMan.JOBID_DOWNLOAD_FILE:
      hideDownloadPrgs();
      break;
    }

    // false 리턴하면 alert(errMsg)를 띄우게 된다.
    return false;
  }

  @Override
  public void onApiResult(int jobId, Object _param)
  {
    switch (jobId)
    {
    case ApiMan.JOBID_REGISTER_TOKEN:
    case ApiMan.JOBID_DELETE_TOKEN:
      Util.d("MAIN: ApiResult: %s", _param);
      break;

    case ApiMan.JOBID_DOWNLOAD_FILE:
      HttpMan.FileDownloadInfo param = (HttpMan.FileDownloadInfo) _param;
      onFileDownloaded(param.filePath);
      break;
    }
  }

  private void hideDownloadPrgs()
  {
    if (m_downloadPrgsDlg != null)
    {
      m_downloadPrgsDlg.dismiss();
      m_downloadPrgsDlg = null;
    }
  }

  private void onFileDownloaded(String filePath)
  {
    hideDownloadPrgs();

    Intent newIntent = new Intent(Intent.ACTION_VIEW);
    String contentType = URLConnection.guessContentTypeFromName(filePath);
    Uri uri = FileProvider.getUriForFile(this, "xyz.parti.catan.fileprovider", new File(filePath));
    newIntent.setDataAndType(uri, contentType);

    List<ResolveInfo> resolvedInfoActivities = getPackageManager().queryIntentActivities(newIntent, PackageManager.MATCH_DEFAULT_ONLY);
    for (ResolveInfo ri : resolvedInfoActivities) {
      grantUriPermission(ri.activityInfo.packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
    }

    try
    {
      startActivity(newIntent);
    }
    catch (ActivityNotFoundException ex)
    {
      Util.toastShort(this, "이 파일을 미리 볼 수 있는 앱이 없습니다");
    }
  }

  private Handler m_handler = new Handler()
  {
    public void handleMessage(Message msg)
    {
      if (msg.what == ApiMan.WHAT_FILE_PROGRESS_UPDATE && m_downloadPrgsDlg != null)
      {
        if (m_downloadPrgsDlg.getProgress() == 0)
        {
          m_downloadPrgsDlg.setMax(msg.arg2);
        }

        m_downloadPrgsDlg.setProgress(msg.arg1);
      }
    }
  };
}
