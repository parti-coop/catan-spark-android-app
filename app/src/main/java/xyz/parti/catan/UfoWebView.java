package xyz.parti.catan;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.net.http.SslError;
import android.os.Build;
import android.view.View;
import android.webkit.JavascriptInterface;
import android.webkit.JsPromptResult;
import android.webkit.JsResult;
import android.webkit.SslErrorHandler;
import android.webkit.WebChromeClient;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;


public class UfoWebView
{
	public interface Listener
	{
		void onPageLoadFinished(String url);
		void onPostAction(String action, JSONObject json) throws JSONException;
	}

	private Activity m_activity;
	private WebView m_webView;
	private Listener m_listener;
	private boolean m_isAutomaticShowHideWait;

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
	};

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

		public void onPageFinished(WebView view, String url)
		{
			if (m_listener != null)
				m_listener.onPageLoadFinished(url);

			if (m_isAutomaticShowHideWait)
			{
				hideWait();
			}
		}

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

			if (m_launchHttpExternal && (url.startsWith("http:") || url.startsWith("https:")))
			{
				Util.startWebBrowser(m_activity, url);
				return true;
			}
*/

			if (url.startsWith("http:") || url.startsWith("https:"))
			{
				if (m_isAutomaticShowHideWait)
				{
					showWait();
				}

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
	}

	public WebView getView()
	{
		return m_webView;
	}

	public void clearNavHistory()
	{
		m_webView.clearHistory();
	}

	public boolean canGoBack()
	{
		return m_webView.canGoBack();
	}

	public void goBack()
	{
		if (m_isAutomaticShowHideWait)
			showWait();

		m_webView.goBack();
	}

	public void loadRemoteUrl(String url)
	{
		if (m_isAutomaticShowHideWait)
			showWait();

		Util.d("loadRemoteUrl: %s", url);
		m_webView.loadUrl(url);
	}

	public void loadLocalHtml(String htmlName)
	{
		if (m_isAutomaticShowHideWait)
			showWait();

		Util.d("loadLocalHtml: %s", htmlName);
		String url = "file:///android_asset/" + htmlName + ".html";
		m_webView.loadUrl(url);
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
	public void showWait()
	{
		m_activity.runOnUiThread(new Runnable()
		{
			public void run()
			{
				MainAct.getInstance().showWaitMark(true);
			}
		});
	}

	@JavascriptInterface
	public void hideWait()
	{
		m_activity.runOnUiThread(new Runnable()
		{
			public void run()
			{
				MainAct.getInstance().showWaitMark(false);
			}
		});
	}

	@JavascriptInterface
	public void setAutoWait(boolean isAuto)
	{
		m_isAutomaticShowHideWait = isAuto;
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
}
