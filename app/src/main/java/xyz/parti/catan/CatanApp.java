package xyz.parti.catan;

import android.app.Activity;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;

public class CatanApp extends Application {
  private static CatanApp s_this;

  private HttpMan m_httpMan;
  private ApiMan m_apiMan;
  private Activity m_curActivity;
  private boolean m_isBackground = true;

  @Override
  public void onCreate() {
    super.onCreate();
    s_this = this;
  }

  public boolean onStartup() {
    if (m_httpMan != null) {
      // already initialized
      return false;
    }

    registerActivityLifecycleCallbacks(m_alc);
    listenForScreenTurningOff();

    m_httpMan = new HttpMan();
    m_apiMan = new ApiMan();

    return true;
  }

  private void notifyForeground() {
    // This is where you can notify listeners, handle session tracking, etc
  }

  private void notifyBackground() {
    // This is where you can notify listeners, handle session tracking, etc

  }

  public boolean isBackground() {
    return m_isBackground;
  }

  @Override
  public void onTrimMemory(int level) {
    super.onTrimMemory(level);
    if (level == TRIM_MEMORY_UI_HIDDEN) {
      m_isBackground = true;
      notifyBackground();
    }
  }

  private void listenForScreenTurningOff() {
    IntentFilter screenStateFilter = new IntentFilter(Intent.ACTION_SCREEN_OFF);
    registerReceiver(new BroadcastReceiver() {
      @Override
      public void onReceive(Context context, Intent intent) {
        m_isBackground = true;
        notifyBackground();
      }
    }, screenStateFilter);
  }

  Application.ActivityLifecycleCallbacks m_alc = new Application.ActivityLifecycleCallbacks() {

    @Override
    public void onActivityCreated(Activity activity, Bundle savedInstanceState) {
    }

    @Override
    public void onActivityStarted(Activity activity) {
      m_curActivity = activity;
    }

    @Override
    public void onActivityResumed(Activity activity) {
      if (m_isBackground) {
        m_isBackground = false;
        notifyForeground();
      }
    }

    @Override
    public void onActivityPaused(Activity activity) {
    }

    @Override
    public void onActivityStopped(Activity activity) {
      if (m_curActivity == activity)
        m_curActivity = null;
    }

    @Override
    public void onActivitySaveInstanceState(Activity activity, Bundle outState) {
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
    }
  };

  public static Activity getCurActivity() {
    if (s_this != null) {
      return s_this.m_curActivity;
    }

    return null;
  }

  public static CatanApp getApp() {
    return s_this;
  }

  public static ApiMan getApiManager() {
    return s_this.m_apiMan;
  }

  public HttpMan getHttpManager() {
    return m_httpMan;
  }
}
