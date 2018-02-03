package xyz.parti.catan;

import android.os.Handler;
import android.os.Message;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class ApiMan implements HttpMan.OnHttpListener
{
  private static String API_BASEURL = "https://parti.xyz/";

  public static void setDevMode()
  {
    API_BASEURL = BuildConfig.API_BASE_URL;
    Util.toastShort(CatanApp.getApp(), "개발자모드");
  }

  public static String getBaseUrl()
  {
    return API_BASEURL;
  }

  public interface Listener
  {
    boolean onApiError(int jobId, String errMsg);
    void onApiResult(int jobId, Object _param);
  }

  public static final int JOBID_REGISTER_TOKEN =1;
  public static final int JOBID_DELETE_TOKEN =2;
  public static final int JOBID_DOWNLOAD_FILE =3;

  public static final int WHAT_FILE_PROGRESS_UPDATE =1;

  private ConcurrentHashMap<Listener, AtomicLong> m_activeListeners;
  private Handler m_fileProgressHandler;

  public ApiMan()
  {
    m_activeListeners = new ConcurrentHashMap<Listener, AtomicLong>();
  }

  private static HttpMan.QuerySpec getEmptySpec(String uri)
  {
    HttpMan.QuerySpec spec = new HttpMan.QuerySpec();
    spec.setUrl(API_BASEURL + uri);
    spec.requestMethod = HttpMan.REQUESTMETHOD_POST;
    //spec.resultType = HttpMan.RESULT_JSON;
    spec.resultType = HttpMan.RESULT_TEXT;
    return spec;
  }

  private void sendRequest(Listener lsnr, int jobId, HttpMan.QuerySpec spec)
  {
    if (lsnr != null)
    {
      m_activeListeners.putIfAbsent(lsnr, new AtomicLong(0));
      m_activeListeners.get(lsnr).incrementAndGet();
    }

    spec.userObj = lsnr;
    CatanApp.getApp().getHttpManager().request(this, jobId, spec);
  }

  public void requestRegisterToken(Listener lsnr, String authkey, String pushToken, String appId)
  {
    HttpMan.QuerySpec spec = getEmptySpec("api/v1/device_tokens");
    spec.addHeader("Authorization", "Bearer " + authkey);

    spec.addParam("registration_id", pushToken);
    spec.addParam("application_id", appId);

    Util.d("requestRegisterToken(%s,%s,%s)", authkey, pushToken, appId);
    sendRequest(lsnr, JOBID_REGISTER_TOKEN, spec);
  }

  private String parseRegisterResult(JSONObject json) throws JSONException
  {
    //String userToken = json.getString("token");
    return json.toString();
  }

  public void requestDeleteToken(Listener lsnr, String authkey, String pushToken)
  {
    HttpMan.QuerySpec spec = getEmptySpec("api/v1/device_tokens");
    spec.requestMethod = HttpMan.REQUESTMETHOD_DELETE;
    spec.addHeader("Authorization", "Bearer " + authkey);

    spec.addParam("registration_id", pushToken);

    Util.d("requestDeleteToken(%s,%s)", authkey, pushToken);
    sendRequest(lsnr, JOBID_DELETE_TOKEN, spec);
  }

  public void requestFileDownload(Listener lsnr, String authkey, int postId, int fileId, String pathToSave, Handler prgsHandler)
  {
    String uri = String.format("api/v1/posts/%d/download_file/%d", postId, fileId);
    HttpMan.QuerySpec spec = getEmptySpec(uri);
    spec.requestMethod = HttpMan.REQUESTMETHOD_GET;

    if (authkey != null)
    {
      spec.addHeader("Authorization", "Bearer " + authkey);
    }

    Util.d("requestFileDownload(%s,%s)", authkey, uri);

    if (lsnr != null)
    {
      m_activeListeners.putIfAbsent(lsnr, new AtomicLong(0));
      m_activeListeners.get(lsnr).incrementAndGet();
    }

    m_fileProgressHandler = prgsHandler;

    spec.userObj = lsnr;
    CatanApp.getApp().getHttpManager().requestDownload(this, JOBID_DOWNLOAD_FILE, spec, pathToSave);
  }

  public void cancelDownload()
  {
    CatanApp.getApp().getHttpManager().cancel(JOBID_DOWNLOAD_FILE);
  }

  private String parseDeleteResult(JSONObject json) throws JSONException
  {
    //String userToken = json.getString("token");
    return json.toString();
  }


  private void notifyApiError(Listener lsnr, int jobId, String errMsg)
  {
    if (!lsnr.onApiError(jobId, errMsg))
    {
      //Util.e("Api(%d)Error: %s", jobId, errMsg);
      Util.showSimpleAlert(CatanApp.getApp().getCurActivity(), errMsg);
    }
  }

  @Override
  public void onHttpFail(int jobId, HttpMan.QuerySpec spec, int failHttpStatus)
  {
    Util.d("ApiMan.HttpFail: job=%d, failCode=%d", jobId, failHttpStatus);

    Listener lsnr = (Listener) spec.userObj;
    if (lsnr == null)
      return;

    AtomicLong refcnt = m_activeListeners.get(lsnr);
    if (refcnt != null)
    {
      if (refcnt.decrementAndGet() == 0)
        m_activeListeners.remove(lsnr);

      String errMsg;
      if (failHttpStatus == HttpMan.ERROR_UNKNOWN_HOST
      || failHttpStatus == HttpMan.ERROR_CONNECTION_TIMEOUT)
      {
        errMsg = CatanApp.getApp().getResources().getString(R.string.error_server_noconn);
      }
      else if (failHttpStatus == HttpMan.ERROR_REQUEST_FAIL
      || failHttpStatus == HttpMan.ERROR_UNKNOWN)
      {
        errMsg = CatanApp.getApp().getResources().getString(R.string.error_server_fail);
      }
      else if (failHttpStatus == HttpMan.ERROR_JOB_CANCEL)
      {
        errMsg = CatanApp.getApp().getResources().getString(R.string.error_download_fail);
      }
      else
      {
        errMsg = "HTTP Failure: " + Integer.toString(failHttpStatus);
      }

      notifyApiError(lsnr, jobId, errMsg);
    }
    else
    {
      Util.d("Ignore Listener-discarded http failure (jobId=%d)", jobId);
    }
  }

  @Override
  public void onHttpFileDownloading(int jobId, HttpMan.QuerySpec spec, long current, long total)
  {
    Message msg = m_fileProgressHandler.obtainMessage();
    msg.what = WHAT_FILE_PROGRESS_UPDATE;
    msg.arg1 = (int)current;
    msg.arg2 = (int)total;
    m_fileProgressHandler.sendMessage(msg);
  }

  @Override
  public void onHttpSuccess(int jobId, HttpMan.QuerySpec spec, Object result)
  {
    Util.d("ApiMan.HttpSuccess: job=%d, result=%s", jobId, result);
    Listener lsnr = (Listener) spec.userObj;
    if (lsnr == null)
      return;

    AtomicLong refcnt = m_activeListeners.get(lsnr);
    if (refcnt == null)
    {
      Util.d("Ignore Listener-discarded http success (jobId=%d)", jobId);
      return;
    }

    if (refcnt.decrementAndGet() == 0)
      m_activeListeners.remove(lsnr);

    try
    {
      JSONObject bodyJson = null;
      String bodyText = null;
      HttpMan.FileDownloadInfo downInfo = null;

      if (spec.resultType == HttpMan.RESULT_JSON)
      {
        bodyJson = (JSONObject) result;
      }
      else if (spec.resultType == HttpMan.RESULT_TEXT)
      {
        bodyText = (String) result;
      }
      else if (spec.resultType == HttpMan.RESULT_FILE_WITH_PROGRESS)
      {
        downInfo = (HttpMan.FileDownloadInfo) result;
      }
      else
      {
        Util.e("ApiMan: Invalid resType " + spec.resultType);
        notifyApiError(lsnr, jobId, "Unknown ResType: " + spec.resultType);
        return;
      }

      Object param;
      switch (jobId)
      {
      case JOBID_REGISTER_TOKEN:
        //param = parseRegisterResult(bodyJson);
        param = bodyText;
        break;

      case JOBID_DELETE_TOKEN:
        //param = parseDeleteResult(bodyJson);
        param = bodyText;
        break;

      case JOBID_DOWNLOAD_FILE:
        param = downInfo;
        break;

      default:
        notifyApiError(lsnr, jobId, "Unknown JobId: " + jobId);
        return;
      }

      lsnr.onApiResult(jobId, param);
    }
    catch (Exception ex)
    {
      ex.printStackTrace();
      notifyApiError(lsnr, jobId, ex.getMessage());
    }
  }
}
