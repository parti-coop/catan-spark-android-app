package xyz.parti.catan;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.Uri;
import android.util.Log;
import android.view.View;
import android.widget.Toast;

import java.util.List;

public class Util
{
  public static final boolean ENABLE_LOG = BuildConfig.IS_DEBUG;
  private static final String LOG_TAG = "ufo";

  public static void v(String format, Object ... args)
  {
    if (ENABLE_LOG)
      Log.v(LOG_TAG, args.length == 0 ? format : String.format(format, args));
  }

  public static void d(String format, Object ... args)
  {
    if (ENABLE_LOG)
      Log.d(LOG_TAG, args.length == 0 ? format : String.format(format, args));
  }

  public static void i(String format, Object ... args)
  {
    if (ENABLE_LOG)
      Log.i(LOG_TAG, args.length == 0 ? format : String.format(format, args));
  }

  public static void e(String format, Object ... args)
  {
    if (ENABLE_LOG)
      Log.e(LOG_TAG, args.length == 0 ? format : String.format(format, args));
  }

  public static void w(String format, Object ... args)
  {
    if (ENABLE_LOG)
      Log.w(LOG_TAG, args.length == 0 ? format : String.format(format, args));
  }

  public static void assure(boolean v)
  {
    if (!v) throw new RuntimeException("assurance failed.");
  }

  public static void assure(String failMsg)
  {
    throw new RuntimeException(failMsg);
  }

  public static boolean isNullOrEmpty(String s)
  {
    return (s == null || s.length() == 0);
  }

  public static String toHexString(byte[] digest)
  {
    StringBuilder sb = new StringBuilder();

    for (int i=0; i<digest.length; i++)
    {
      sb.append(Integer.toString((digest[i] & 0xff) + 0x100, 16).substring(1));
    }

    return sb.toString();
  }

  public static void toastShort(Activity activity, String msg)
  {
    if (activity == null || activity.isFinishing()){
      return;
    }
    Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show();
  }

  public static void toastShort(Activity activity, int strId)
  {
    if (activity == null || activity.isFinishing()){
      return;
    }
    Toast.makeText(activity, strId, Toast.LENGTH_SHORT).show();
  }

  public static void setOnClickListener(Activity act, int id, View.OnClickListener lsnr)
  {
    View v = act.findViewById(id);
    if (v == null)
      Util.e("setOnClickListener(%d) view is null", id);
    else
      v.setOnClickListener(lsnr);
  }

  public static void setOnClickListener(View parentView, int id, View.OnClickListener lsnr)
  {
    View v = parentView.findViewById(id);
    if (v == null)
      Util.e("setOnClickListener(%d) view is null", id);
    else
      v.setOnClickListener(lsnr);
  }

  public static void showSimpleAlert(Context ctx, String msg)
  {
    AlertDialog.Builder alert = new AlertDialog.Builder(ctx);
    alert.setMessage(msg);
    alert.setNeutralButton(android.R.string.ok,
      new DialogInterface.OnClickListener() {
          public void onClick( DialogInterface dialog, int which) {
            dialog.dismiss();
          }
      });

    try
    {
      alert.show();
    }
    catch(Exception ex)
    {
      // unable to recover
      ex.printStackTrace();
      //killMySelf();
    }
  }

  public static void showSimpleConfirm(Context ctx, int resId, DialogInterface.OnClickListener lsnr)
  {
    AlertDialog.Builder alert = new AlertDialog.Builder(ctx);
    alert.setMessage(resId);
    alert.setPositiveButton(android.R.string.ok, lsnr);
    alert.setNegativeButton(android.R.string.cancel,
      new DialogInterface.OnClickListener() {
        public void onClick(DialogInterface dialog, int id) {
        // will be closed
      }
    });
    alert.show();
  }

  public static void showSimpleAlertLsnr(Context ctx, int resId, DialogInterface.OnClickListener lsnr)
  {
    AlertDialog.Builder alert = new AlertDialog.Builder(ctx);
    alert.setMessage(resId);
    alert.setNeutralButton(android.R.string.ok, lsnr);
    alert.setCancelable(false);
    alert.show();
  }

  public static void showSimpleAlertLsnr(Context ctx, String msg, DialogInterface.OnClickListener lsnr)
  {
    AlertDialog.Builder alert = new AlertDialog.Builder(ctx);
    alert.setMessage(msg);
    alert.setNeutralButton(android.R.string.ok, lsnr);
    alert.setCancelable(false);
    alert.show();
  }

  public static void showSimpleAlert(Context ctx, int msgId)
  {
    new AlertDialog.Builder(ctx)
      .setMessage(msgId)
      .setNeutralButton(android.R.string.ok, null)
      .show();
  }

  public static void showSimpleAlert(Context ctx, int titleId, int msgId)
  {
    new AlertDialog.Builder(ctx)
      .setTitle(titleId)
      .setMessage(msgId)
      .setNeutralButton(android.R.string.ok, null)
      .show();
  }

  public static void showSimpleAlert(Context ctx, int titleId, String msg)
  {
    new AlertDialog.Builder(ctx)
      .setTitle(titleId)
      .setMessage(msg)
      .setNeutralButton(android.R.string.ok, null)
      .show();
  }

  public static void showSimpleAlert(Context ctx, String title, String msg)
  {
    try
    {
      new AlertDialog.Builder(ctx)
        .setTitle(title)
        .setMessage(msg)
        .setNeutralButton(android.R.string.ok, null)
        .show();
    }
    catch(Exception ex) {}
  }

  public static ProgressDialog showWaitDialog(Context ctx, int resId)
  {
    return ProgressDialog.show(ctx, "", ctx.getResources().getString(resId), true, false);
  }

  public static ProgressDialog showWaitDialog(Context ctx, int resId, DialogInterface.OnCancelListener lsnr)
  {
    return ProgressDialog.show(ctx, "", ctx.getResources().getString(resId), true, true, lsnr);
  }

  public static boolean startWebBrowser(Context ctx, String url) {
    PackageManager packageManager = ctx.getPackageManager();
    try {
      if (url.startsWith("intent://")) {
        Intent itt = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
        if (itt != null) {
          ResolveInfo info = packageManager.resolveActivity(itt, PackageManager.MATCH_DEFAULT_ONLY);
          if (info != null) {
            ctx.startActivity(itt);
          } else {
            String fallbackUrl = itt.getStringExtra("browser_fallback_url");
            if(fallbackUrl != null) {
              startWebBrowser(ctx, fallbackUrl);
            } else {
              return false;
            }
          }
          return true;
        } else {
          return false;
        }
      } else if (url.startsWith("market://")) {
        Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
        if (intent != null) {
          ctx.startActivity(intent);
          return true;
        }
        return false;
      } else{
        Intent itt = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
        ctx.startActivity(itt);
        return true;
      }
    } catch (Exception e) {
      Util.e("startWebBrowser %s", url, e.getMessage());
      return false;
    }
  }

  public static boolean isNetworkOnline(Context context)
  {
    ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
    NetworkInfo netInfo = cm.getActiveNetworkInfo();
    return (netInfo != null && netInfo.isAvailable() && netInfo.isConnected());
  }

  public static boolean isForground() {
    return !CatanApp.getApp().isBackground();
  }
}
