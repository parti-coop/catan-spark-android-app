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
import android.net.NetworkInfo;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
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
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

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
		String getBaseURL();
	}

	public static final int REQCODE_CHOOSE_FILE = 1234;
	private static final String FAKE_USER_AGENT_FOR_GOOGLE_OAUTH = "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_9_3) AppleWebKit/537.75.14 (KHTML, like Gecko) Version/7.0.3 Safari/7046A194A";
	private static final String GOOGLE_OAUTH_START_URL = BuildConfig.API_BASE_URL + "users/auth/google_oauth2";

	private Activity m_activity;
	private WebView m_webView;
	private Listener m_listener;
	private boolean m_wasOffline;
	private List<String> m_onlineUrls = new ArrayList<>();

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
/*
		@Override
		public void onPageStarted(WebView view, String url, Bitmap favicon)
		{
			super.onPageStarted(view, url, favicon);
		}

		public void onLoadResource (WebView view, String url)
		{
		}
*/
		@Override
		public void onPageStarted(WebView view, String url, Bitmap favicon)
		{
			m_listener.onPageStarted(url);
			super.onPageStarted(view, url, favicon);
		}

		@SuppressWarnings("deprecation")
		@Override
		public void onReceivedError(WebView view, int errorCode, String description, String failingUrl) {
			Util.d("onReceivedError(%d,%s, %s)", errorCode, description, failingUrl);
			if (Util.isNetworkOnline(MainAct.getInstance())) {
				m_listener.onPageError(failingUrl);
			} else {
				onNetworkOffline();
			}
		}

		private boolean isNetworkOffline() {
			ConnectivityManager cm =
					(ConnectivityManager) getView().getContext().getSystemService(Context.CONNECTIVITY_SERVICE);

			NetworkInfo activeNetwork = cm.getActiveNetworkInfo();
			return activeNetwork != null &&
					activeNetwork.isConnectedOrConnecting();
		}

		@TargetApi(Build.VERSION_CODES.M)
		@Override
		public void onReceivedError(WebView view, WebResourceRequest request, WebResourceError error)
		{
			onReceivedError(view, error.getErrorCode(), error.getDescription().toString(), request.getUrl().toString());
		}

		private Pattern m_reIsMySite = Pattern.compile(BuildConfig.API_BASE_URL_REGX);

		@Override
		public void onPageFinished(WebView view, String url)
		{
			m_listener.onPageFinished(url);
			super.onPageFinished(view, url);
		}

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

/*
			if (url.startsWith("tel:"))
			{
				Intent itt = new Intent(Intent.ACTION_CALL, Uri.parse(url));
				m_activity.startActivity(itt);
				return true;
			}
*/

			if (url.startsWith("http:") || url.startsWith("https:"))
			{
				if ( BuildConfig.IS_DEBUG) {
					// 구글 Oauth에서 parti.dev로 인증결과가 넘어오면 로컬 개발용이다.
					// 그러므로 Config.apiBaseUrl로 주소를 바꾸어 인증하도록 한다
					String GOOGLE_OAUTH_FOR_DEV_URL = "https://parti.dev/users/auth/google_oauth2/callback";
					if ( url.contains(GOOGLE_OAUTH_FOR_DEV_URL) ) {
						view.loadUrl(url.replace("https://parti.dev/", BuildConfig.API_BASE_URL), UfoWebView.extraHttpHeaders());
						return true;
					}
				}

				if (url.contains(GOOGLE_OAUTH_START_URL))
				{
					// 구글 인증이 시작되었다.
					// 가짜 User-Agent 사용을 시작한다.
					view.getSettings().setUserAgentString(FAKE_USER_AGENT_FOR_GOOGLE_OAUTH);
					view.loadUrl(url, UfoWebView.extraHttpHeaders());
					return true;
				}
				else if ( FAKE_USER_AGENT_FOR_GOOGLE_OAUTH.equals(view.getSettings().getUserAgentString()) )
				{
					// 가짜 User-Agent 사용하는 걸보니 이전 request에서 구글 인증이 시작된 상태이다.
					if ( url.indexOf("https://accounts.google.com") < 0 ) {
						// 구글 인증이 시작된 상태였다가
						// 구글 인증 주소가 아닌 다른 페이지로 이동하는 중이다.
						// 구글 인증이 끝났다고 보고 원래 "User-Agent"로 원복한다.
						view.getSettings().setUserAgentString(null);   // set default
						view.loadUrl(url, UfoWebView.extraHttpHeaders());
						return true;
					} else {
						// 아직 구글로그인 중이다
						view.getSettings().setUserAgentString(FAKE_USER_AGENT_FOR_GOOGLE_OAUTH);
						view.loadUrl(url, UfoWebView.extraHttpHeaders());
						return true;
					}
				}

				view.getSettings().setUserAgentString(null);   // set default
				view.loadUrl(url, UfoWebView.extraHttpHeaders());
				return true;
			}

			return false;
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

		@TargetApi(Build.VERSION_CODES.N)
		@Override
		public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
			return super.shouldOverrideUrlLoading(view, request.getUrl().toString());
		}
	};

	private BroadcastReceiver m_connectivityReceiver = new BroadcastReceiver()
	{
		@Override
		public void onReceive(final Context context, final Intent intent)
		{
			if (Util.isNetworkOnline(context))
			{
				onNetworkReady();
			}
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

		webSettings.setDefaultFontSize(16);
		if (Build.VERSION.SDK_INT > Build.VERSION_CODES.ICE_CREAM_SANDWICH)
			webSettings.setTextZoom(100);

		webSettings.setAppCacheEnabled(!BuildConfig.IS_DEBUG);
		webSettings.setCacheMode(BuildConfig.IS_DEBUG ? WebSettings.LOAD_NO_CACHE : WebSettings.LOAD_DEFAULT);

		m_webView.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);
		m_webView.setWebViewClient(m_webClient);
		m_webView.setWebChromeClient(m_chromeClient);
		m_webView.addJavascriptInterface(this, "ufo");

		onStart(act);
	}

	public void onStart(Activity act)
	{
		final IntentFilter intentFilter = new IntentFilter();
		intentFilter.addAction(ConnectivityManager.CONNECTIVITY_ACTION);
		act.registerReceiver(m_connectivityReceiver, intentFilter);
	}

	public void onStop(Activity act)
	{
		act.unregisterReceiver(m_connectivityReceiver);
	}

	public WebView getView()
	{
		return m_webView;
	}

	public boolean canGoBack()
	{
		return m_onlineUrls.size() > 1;
	}

	public void loadRemoteUrl(String url)
	{
		Util.d("loadRemoteUrl: %s", url);
		m_webView.loadUrl(url, UfoWebView.extraHttpHeaders());
	}

	public void loadLocalHtml(String htmlName)
	{
		Util.d("loadLocalHtml: %s", htmlName);
		String url = "file:///android_asset/" + htmlName;
		m_webView.loadUrl(url);
	}

	public void onNetworkOffline()
	{
		m_wasOffline = true;
		loadLocalHtml("offline.html");
	}

	public void onNetworkReady()
	{
		if (!m_wasOffline)
			return;

		m_wasOffline = false;

		if (getLastOnlineUrl() != null)
		{
			loadRemoteUrl(getLastOnlineUrl());
		}
		else Util.d("onNetworkReady but lastUrl is null");
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

				String backOnlineUrl = m_listener.getBaseURL();
				if(!m_onlineUrls.isEmpty()) {
					m_onlineUrls.remove(m_onlineUrls.size() - 1);
					if (getLastOnlineUrl() != null) {
						backOnlineUrl = getLastOnlineUrl();
					}
				}
				if(backOnlineUrl.equals(backBrowserUrl)) {
					m_webView.goBack();
				} else {
					loadRemoteUrl(backOnlineUrl);
				}
			}
		});
	}

	@JavascriptInterface
	public void addOnlineUrl(String url) {
		if (getLastOnlineUrl() == null || !getLastOnlineUrl().equals(url) ) {
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

	public static Map<String, String> extraHttpHeaders() {
		Map<String, String> headers = new HashMap<>();
		headers.put("catan-agent", "catan-spark-android");
		headers.put("catan-version", "1.0.0");

		return headers;
	}

	private String getLastOnlineUrl() {
		if(m_onlineUrls.isEmpty()) {
			return null;
		}
		return m_onlineUrls.get(m_onlineUrls.size() - 1);
	}

	public void reloadRemoteUrl() {
		String urlString = ( getLastOnlineUrl() != null ? getLastOnlineUrl() : m_listener.getBaseURL());
		loadRemoteUrl(urlString);
	}
}
