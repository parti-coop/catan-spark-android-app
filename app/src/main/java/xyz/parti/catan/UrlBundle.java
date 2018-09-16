package xyz.parti.catan;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

public class UrlBundle {
  public static final String PUSHARG_URL = "url";
  private final Intent intent;
  private String pushBundleUrl = "";
  private String appLinkBundleUrl = "";

  public UrlBundle(Intent intent) {
    this.intent = intent;
  }

  public String getPushNotifiedUrl() {
    if(this.intent == null) {
      return null;
    }
    if(!"".equals(this.pushBundleUrl)) {
      return this.pushBundleUrl;
    }

    Bundle bundle = intent.getExtras();
    if (bundle != null && bundle.containsKey(PUSHARG_URL)) {
      String url = bundle.getString(PUSHARG_URL);
      if (!Util.isNullOrEmpty(url)) {
        this.pushBundleUrl = url;
        return this.pushBundleUrl;
      }
    }
    this.pushBundleUrl = null;
    return this.pushBundleUrl;
  }

  public String getAppLinkUrl() {
    if(this.intent == null) {
      return null;
    }
    if(!"".equals(this.appLinkBundleUrl)) {
      return this.appLinkBundleUrl;
    }

    String appLinkAction = intent.getAction();
    Uri appLinkData = intent.getData();
    if (Intent.ACTION_VIEW.equals(appLinkAction) && appLinkData != null) {
      this.appLinkBundleUrl = appLinkData.toString();
      return this.appLinkBundleUrl;
    }
    this.appLinkBundleUrl = null;
    return this.appLinkBundleUrl;
  }
}
