package xyz.parti.catan;

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
	private View m_vwWaitScreen;
	private ProgressBar m_prgsView;
	private UfoWebView m_webView;

	private int m_nPageFinishCount = 0;
	private boolean m_isInitialWaitDone = false;
	private long m_timeToHideWait;

	private String m_urlToGoDelayed;
	private Bundle m_delayedBundle;

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

		// 개발중일때 타겟 서버를 바꾸는 헬퍼입니다. 디버그로 릴리즈서버 바라볼 때는 주석처리해주세요.
		if (BuildConfig.IS_DEBUG) CatanApp.getApiManager().setDevMode();
	}

	@Override
	public void onStart()
	{
		super.onStart();

		if (m_webView != null)
		{
			m_webView.onStart(this);
			return;
		}

		m_vwSplashScreen = findViewById(R.id.splash);
		m_vwWaitScreen = findViewById(R.id.waitScr);
		m_prgsView = (ProgressBar) findViewById(R.id.prgsBar);
		m_webView = new UfoWebView(this, findViewById(R.id.web), this);

		if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
		{
			// Create channel to show notifications.
			String channelId  = getString(R.string.default_notification_channel_id);
			String channelName = getString(R.string.default_notification_channel_name);
			NotificationManager notificationManager = getSystemService(NotificationManager.class);
			notificationManager.createNotificationChannel(new NotificationChannel(channelId,
				channelName, NotificationManager.IMPORTANCE_LOW));
		}

		Bundle bun = getIntent().getExtras();
		if (bun != null && isPushBundle(bun))
		{
			m_delayedBundle = bun;
		}

		// 앱이 최초 로드할 웹서버의 주소입니다. 필요시 변경 가능합니다. 웹서버에도 변경은 필수!
		m_webView.loadRemoteUrl(ApiMan.getBaseUrl() + "mobile_app/start");

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
	public void onActivityResult(int requestCode, int resultCode, Intent intent)
	{
		if (requestCode == UfoWebView.REQCODE_CHOOSE_FILE)
		{
			m_webView.onFileChooseResult(resultCode, intent);
		}
	}

	private boolean isPushBundle(Bundle bun)
	{
		return bun.containsKey(PUSHARG_URL);
	}

	@Override
	protected void onNewIntent(Intent intent)
	{
		super.onNewIntent(intent);
		Util.d("MainAct.onNewIntent: " + intent);

		Bundle bun = intent.getExtras();
		if (bun != null && isPushBundle(bun))
		{
			if (isShowWait())
			{
				m_delayedBundle = bun;
			}
			else
			{
				alertPushDialog(bun);
			}
		}
	}

	public void alertPushDialog(Bundle bun)
	{
		m_delayedBundle = null;
		final String url = bun.getString(PUSHARG_URL);
		if (!Util.isNullOrEmpty(url))
		{
			safelyGoToURL(url);
		}
/*
		String title = bun.getString(PUSHARG_TITLE);
		String msg = bun.getString(PUSHARG_MESSAGE);
        m_delayedBundle = null;
		if (!Util.isNullOrEmpty(url))
		{
			safelyGoToURL(url);
		}
*/
	}

	private void safelyGoToURL(String url)
	{
		if (url.startsWith("/"))
		{
			url = ApiMan.getBaseUrl() + url.substring(1);
		}

		if (isShowWait())
		{
			m_urlToGoDelayed = url;
		}
		else
		{
			m_urlToGoDelayed = null;
			m_webView.resetLastOnlineUrl();

			showWaitMark(true);
			m_webView.loadRemoteUrl(url);
		}
	}

	@Override
	public void onBackPressed()
	{
		if (isShowWait())
		{
			return;
		}

		if (m_webView.canGoBack())
		{
			m_webView.goBack();
		}
		else
		{
			this.finish();
		}
	}

	private boolean isShowWait()
	{
		return (m_vwWaitScreen.getVisibility() == View.VISIBLE);
	}

	private static final long AUTO_HIDE_TIMEOUT_MILLIS = 5000;

	public void showWaitMark(boolean show)
	{
		Util.d("showWaitMark(%b)", show);

		if (show != isShowWait())
		{
			int vis = show ? View.VISIBLE : View.GONE;
			m_vwWaitScreen.setVisibility(vis);
			m_prgsView.setVisibility(vis);
		}

		if (show)
		{
			// 로딩화면(스크린 터치를 못하게 함)을 자동으로 hide시키는 타임아웃을 설정합니다.
			// HTML 페이지 내의 어떤 요소(예:이미지)가 로딩이 실패할 경우 onPageLoadFinished 가
			// 호출되지 않을 수 있는데, 그런 경우 최소 Back 버튼이나 다른 링크라도 누를 수 있도록 하기 위해서 입니다.
			m_timeToHideWait = System.currentTimeMillis() + AUTO_HIDE_TIMEOUT_MILLIS - 300;
			m_prgsView.postDelayed(new Runnable()
			{
				@Override
				public void run()
				{
					if (m_timeToHideWait < System.currentTimeMillis())
					{
						showWaitMark(false);
					}
				}
			}, AUTO_HIDE_TIMEOUT_MILLIS);
		}
		else if (m_urlToGoDelayed != null)
		{
			showWaitMark(true);
			m_webView.loadRemoteUrl(m_urlToGoDelayed);
			m_urlToGoDelayed = null;
		}
		else if (m_delayedBundle != null)
		{
			alertPushDialog(m_delayedBundle);
		}
	}

	@Override
	public void onPageLoadFinished(String url)
	{
		Util.d("onPageLoadFinished: %s", url);

		if (m_isInitialWaitDone == false && isShowWait())
		{
			// 최초 mobile_app/start 페이지 방문 후 다음(두번째) 페이지 (보통 초기페이지) 로드 완료 시,
			// 앱 구동때부터 보이던 WaitScreen 을 감춘다.
			if (++m_nPageFinishCount >= 2 || BuildConfig.API_BASE_URL.equals(url))
			{
				Util.d("InitialWaitDone, clearNavHistory");
				m_isInitialWaitDone = true;
				m_webView.clearNavHistory();
				showWaitMark(false);

				// 이후 링크 클릭시 자동으로 로딩화면이 show/hide 될 수 있도록 함
				//m_webView.setAutoWait(true); 안함
			}
		}
	}

	private String getAuthKey()
	{
		SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(this);
		return sp.getString(KEY_AUTHKEY, null);
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

			String appId = getPackageName();
			CatanApp.getApiManager().requestRegisterToken(this, authkey, pushToken, appId);

			// (HTML쪽에서 로그인ID도 보내주면 활용할 수 있음)
			//Crashlytics.setUserName(loginId);
			Crashlytics.setUserIdentifier(authkey);
		}
		else if ("logout".equals(action))
		{
			if (m_isInitialWaitDone == false)
			{
				// FORM 전송 리퀘스트 횟수 차감
				--m_nPageFinishCount;
			}

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
					showWaitMark(true);
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

		showWaitMark(false);
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
			showWaitMark(false);
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
			Util.toastShort(this, "이 파일을 처리할 수 있는 앱이 없는 것 같습니다.");
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
