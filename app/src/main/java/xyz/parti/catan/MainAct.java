package xyz.parti.catan;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.preference.PreferenceManager;
import android.support.v7.app.AlertDialog;
import android.support.v7.app.AppCompatActivity;
import android.view.View;
import android.widget.ProgressBar;

import com.crashlytics.android.Crashlytics;
import com.google.firebase.iid.FirebaseInstanceId;

import org.json.JSONException;
import org.json.JSONObject;


public class MainAct extends AppCompatActivity implements UfoWebView.Listener, ApiMan.Listener
{
	//private static final String KEY_UID = "xUID";
	private static final String KEY_AUTHKEY = "xAK";

	public static final String PUSHARG_TITLE = "title";
	public static final String PUSHARG_MESSAGE = "body";
	public static final String PUSHARG_URL = "url";

	private View m_vwSplashScreen;
	private View m_vwWaitScreen;
	private ProgressBar m_prgsView;
	private UfoWebView m_webView;

	private int m_nPageFinishCount = 0;
	private boolean m_isInitialWaitDone = false;

	private String m_urlToGoDelayed;
	private Bundle m_delayedBundle;

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
		m_webView.loadRemoteUrl(ApiMan.getBaseUrl() + "_AppStart");

		// 1초간 스플래시 화면을 보여줍니다.
		// iOS는 Launch스크린이 필수라서 대응하며 만든 기능입니다. 이 기능이 필요 없으면 연락주세요.
		m_vwSplashScreen.postDelayed(new Runnable()
		{
			@Override
			public void run()
			{
				m_vwSplashScreen.setVisibility(View.GONE);
			}
		}, 1000);
	}

	private boolean isPushBundle(Bundle bun)
	{
		return (bun.containsKey(PUSHARG_TITLE)
			|| bun.containsKey(PUSHARG_MESSAGE)
			|| bun.containsKey(PUSHARG_URL));
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
		String title = bun.getString(PUSHARG_TITLE);
		String msg = bun.getString(PUSHARG_MESSAGE);
		final String url = bun.getString(PUSHARG_URL);

		if (Util.isNullOrEmpty(title) && Util.isNullOrEmpty(msg))
		{
			if (!Util.isNullOrEmpty(url))
			{
				safelyGoToURL(url);
			}

			return;
		}

		if (Util.isNullOrEmpty(url))
		{
			Util.showSimpleAlert(this, title, msg);
		}
		else
		{
			AlertDialog.Builder alert = new AlertDialog.Builder(this);
			alert.setTitle(title);
			alert.setMessage(msg);
			alert.setNeutralButton(android.R.string.ok,
				new DialogInterface.OnClickListener() {
					public void onClick( DialogInterface dialog, int which) {
						dialog.dismiss();
						safelyGoToURL(url);
					}
				});
			alert.show();
		}
	}

	private void safelyGoToURL(String url)
	{
		if (isShowWait())
		{
			m_urlToGoDelayed = url;
		}
		else
		{
			m_urlToGoDelayed = null;
			showWaitMark(true);

			if (url.startsWith("/"))
			{
				url = ApiMan.getBaseUrl() + url.substring(1);
			}

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

	public void showWaitMark(boolean show)
	{
		if (show != isShowWait())
		{
			int vis = show ? View.VISIBLE : View.GONE;
			m_vwWaitScreen.setVisibility(vis);
			m_prgsView.setVisibility(vis);
		}

		if (show == false && m_urlToGoDelayed != null)
		{
			m_webView.loadRemoteUrl(m_urlToGoDelayed);
			m_urlToGoDelayed = null;
		}
	}

	@Override
	public void onPageLoadFinished(String url)
	{
		Util.d("onPageLoadFinished: %s", url);

		if (isShowWait() && m_isInitialWaitDone == false)
		{
			// 최초 AppStart 페이지 방문 후 다음 페이지 (보통 초기페이지) 로드 완료 시,
			// 앱 구동때부터 돌던 WaitScreen 을 감춘다. (실제로 감추는 코드는 저 아래 블럭에)
			if (++m_nPageFinishCount >= 2)
			{
				Util.d("InitialWaitDone, clearNavHistory");
				m_isInitialWaitDone = true;
				m_webView.clearNavHistory();
			}
		}

		if (m_isInitialWaitDone)
		{
			showWaitMark(false);
			if (m_delayedBundle != null)
			{
				alertPushDialog(m_delayedBundle);
			}
		}
	}

	@Override
	public void onPostAction(String action, JSONObject json) throws JSONException
	{
		Util.d("UfoPost(%s,%s)", action, json);

		if ("noAuth".equals(action))
		{
			// 웹뷰가 AppStart 페이지에서 로그인된 상태가 아닐 경우 여기로 옵니다.
			// 앱에 저장된 인증정보가 있으면 웹뷰의 세션 복구를 시도합니다.
			SharedPreferences sp = PreferenceManager.getDefaultSharedPreferences(CatanApp.getApp());
			String authkey = sp.getString(KEY_AUTHKEY, null);
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

			String appId = "xyz.parti.catan";
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
		else
		{
			Util.d("Unhandled post action: %s", action);
		}
	}


	@Override
	public boolean onApiError(int jobId, String errMsg)
	{
		showWaitMark(false);

		switch (jobId)
		{
		case ApiMan.JOBID_REGISTER_TOKEN:
			break;

		case ApiMan.JOBID_DELETE_TOKEN:
			// UI 표시 없이 조용히 에러 무시함
			Util.e("DeleteToken API failed: %s", errMsg);
			return true;
		}

		// false 리턴하면 alert(errMsg)를 띄우게 된다.
		return false;
	}

	@Override
	public void onApiResult(int jobId, Object _param)
	{
		showWaitMark(false);

		switch (jobId)
		{
		case ApiMan.JOBID_REGISTER_TOKEN:
		case ApiMan.JOBID_DELETE_TOKEN:
			Util.d("MAIN: ApiResult: %s", _param);
			break;
		}

	}

}
