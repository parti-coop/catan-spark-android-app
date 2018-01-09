package xyz.parti.catan;

import android.app.Activity;
import android.app.Application;
import android.os.Bundle;

import com.crashlytics.android.Crashlytics;

import io.fabric.sdk.android.Fabric;

public class CatanApp extends Application
{
	private static CatanApp s_this;

	private HttpMan m_httpMan;
	private ApiMan m_apiMan;
	private Activity m_curActivity;

	@Override
	public void onCreate()
	{
		super.onCreate();
		Fabric.with(this, new Crashlytics());
		s_this = this;
	}

	public boolean onStartup()
	{
		if (m_httpMan != null)
		{
			// already initialized
			return false;
		}

		registerActivityLifecycleCallbacks(m_alc);

		m_httpMan = new HttpMan();
		m_apiMan = new ApiMan();

		return true;
	}

	Application.ActivityLifecycleCallbacks m_alc = new Application.ActivityLifecycleCallbacks() {

		@Override
		public void onActivityCreated(Activity activity, Bundle savedInstanceState)
		{
		}

		@Override
		public void onActivityStarted(Activity activity)
		{
			m_curActivity = activity;
		}

		@Override
		public void onActivityResumed(Activity activity)
		{
		}

		@Override
		public void onActivityPaused(Activity activity)
		{
		}

		@Override
		public void onActivityStopped(Activity activity)
		{
			if (m_curActivity == activity)
				m_curActivity = null;
		}

		@Override
		public void onActivitySaveInstanceState(Activity activity, Bundle outState)
		{
		}

		@Override
		public void onActivityDestroyed(Activity activity)
		{
		}
	};

	public static Activity getCurActivity()
	{
		if (s_this != null)
		{
			return s_this.m_curActivity;
		}

		return null;
	}

	public static CatanApp getApp()
	{
		return s_this;
	}

	public static ApiMan getApiManager()
	{
		return s_this.m_apiMan;
	}

	public HttpMan getHttpManager()
	{
		return m_httpMan;
	}
}
