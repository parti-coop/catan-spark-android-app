package xyz.parti.catan;

import android.net.Uri;
import android.os.Handler;
import android.os.Message;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSession;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

// Simple HTTP Manager (with Background thread worker)
public class HttpMan implements Runnable
{
	private static final boolean ENABLE_FAKE_SSL = false;

	public static final int RESULT_IGNORE = 0;
	public static final int RESULT_TEXT = 1;
	public static final int RESULT_JSON = 2;
	public static final int RESULT_JSON_ARRAY = 3;
	public static final int RESULT_BINARY = 4;
	public static final int RESULT_SAVE_TO_FILE = 5;    // Object result should have the pathname
	public static final int RESULT_SIGNAL = 6;

	public static final int REQUESTMETHOD_GET = 0;
	public static final int REQUESTMETHOD_POST = 1;
	public static final int REQUESTMETHOD_PUT = 2;
	public static final int REQUESTMETHOD_DELETE = 3;
	public static final int REQUESTMETHOD_DELETE_OVERRIDE = 4;  // some firewall only accept GET and POST

	public static final int ERROR_UNKNOWN = -1;
	public static final int ERROR_CONNECTION_TIMEOUT = -2;
	public static final int ERROR_UNKNOWN_HOST = -3;
	public static final int ERROR_REQUEST_FAIL = -4;
	public static final int ERROR_RESULT_ERROR = -5;

	private static final String SVR_ENCODING = "UTF-8";

	public interface OnHttpListener
	{
		void onHttpFail(int jobId, QuerySpec spec, int failHttpStatus);
		void onHttpSuccess(int jobId, QuerySpec spec, Object result);
	}

	public static class QuerySpec
	{
		public int port;    // can be 0 if 80
		public int resultType;
		public int requestMethod;
		public boolean isSecure;
		public boolean isNotifyOnNetThread;

		public String address;
		public String path;
		public Object postBody;

		public HashMap<String, String> userVars;    // userVars will not be sent to web server
		public Object userObj;

		public void setUrl(String url)
		{
			int r1 = url.indexOf("://");
			if (r1 <= 1)
			{
				throw new RuntimeException("Invalid http url: " + url);
			}

			this.isSecure = (url.charAt(r1 - 1) == 's');

			String core;
			r1 += 3;
			int pathBegin = url.indexOf('/', r1);
			if (pathBegin <= 0)
			{
				// has no uri
				this.path = null;
				core = url.substring(r1);
			}
			else
			{
				this.path = url.substring(pathBegin + 1);
				core = url.substring(r1, pathBegin);
			}

			int colon = core.indexOf(':');
			if (colon <= 0)
			{
				this.port = 0;    // default http port (80)
				this.address = core;
			}
			else
			{
				this.port = Integer.parseInt(core.substring(colon + 1));
				this.address = core.substring(0, colon);
			}
		}

/*
		// General unique key params
		public HashMap<String, String> params;

		public void addParam(String key, String val)
		{
			if (this.params == null)
				this.params = new HashMap<String, String>();

			//Util.d("addParam(%s,%s)", key, val);
			this.params.put(key, val);
		}

		public String getParam(String key)
		{
			if (params == null)
				return null;

			//return params.containsKey(key) ? params.get(key) : null;
			return params.get(key);
		}
*/
		// RubyOnRails multiple same-key params
		public HashMap<String,Object> params;

		public void addParam(String key, String newVal)
		{
			if (newVal == null)
				return;

			if (this.params == null)
				this.params = new HashMap<String, Object>();

			//Util.d("addParam(%s,%s)", key, newVal);
			if (this.params.containsKey(key))
			{
				ArrayList<String> arrStrs;

				Object oldVal = this.params.get(key);
				if (oldVal instanceof ArrayList)
				{
					arrStrs = (ArrayList) oldVal;
				}
				else
				{
					arrStrs = new ArrayList<>(2);
					arrStrs.add((String)oldVal);
				}

				arrStrs.add(newVal);
				this.params.put(key, arrStrs);
			}
			else
			{
				this.params.put(key, newVal);
			}
		}

		public Object getParam(String key)
		{
			if (params == null)
				return null;

			//return params.containsKey(key) ? params.get(key) : null;
			return params.get(key);
		}

		public void addParam(String key, int ival)
		{
			addParam(key, Integer.toString(ival));
		}

		public void addParam(String key, boolean bval)
		{
			addParam(key, bval ? "true": "false");
		}

		public void addParam(String key, float fval)
		{
			addParam(key, Float.toString(fval));
		}

		public void addUserVar(String key, String val)
		{
			if (userVars == null)
				userVars = new HashMap<String, String>();

			userVars.put(key, val);
			//Util.d("uservar.put(%s,%s)", key,val);
		}

		public String getUserVar(String key)
		{
			if (userVars != null && userVars.containsKey(key))
			{
				return userVars.get(key);
			}

			Util.e("getUserVar(%s) not found", key);
			return null;
		}

		public HashMap<String,String> headers;
		public void addHeader(String key, String val)
		{
			if (this.headers == null)
				this.headers = new HashMap<String, String>();

			this.headers.put(key, val);
		}

	}

	private static class JobItem
	{
		public OnHttpListener listener;
		public int jobId;
		public QuerySpec spec;
		public Object result;
	}

	private Handler m_handler;
	private LinkedBlockingQueue<JobItem> m_jobQueue;
	private Thread m_thread;
	private boolean m_stopThreadFired;

	public HttpMan(boolean doNotCreateHandler)
	{
		m_jobQueue = new LinkedBlockingQueue<JobItem>();

		if (doNotCreateHandler)
			return;

		if (ENABLE_FAKE_SSL)
		{
			installFakeSslFactory();
		}

		m_handler = new Handler()
		{
			public void handleMessage(Message msg)
			{
				callListener((JobItem) msg.obj, msg.what);
			}
		};
	}

	public HttpMan()
	{
		this(false);
	}

	public void postSignal(OnHttpListener lsnr, int jobId, Object userObj, boolean notifyOnNetThread)
	{
		QuerySpec spec = new QuerySpec();
		spec.isNotifyOnNetThread = notifyOnNetThread;
		spec.userObj = userObj;
		spec.resultType = RESULT_SIGNAL;

		request(lsnr, jobId, spec);
	}

	public void request(OnHttpListener lsnr, int jobId, QuerySpec spec)
	{
		JobItem job = new JobItem();
		job.listener = lsnr;
		job.jobId = jobId;
		job.spec = spec;

		addJobToQueue(job);
	}

	public void requestDownload(OnHttpListener lsnr, int jobId, QuerySpec spec, String pathToSaveFile)
	{
		JobItem job = new JobItem();
		job.listener = lsnr;
		job.jobId = jobId;
		job.spec = spec;
		job.spec.resultType = RESULT_SAVE_TO_FILE;
		job.result = pathToSaveFile;

		addJobToQueue(job);
	}

	public boolean isQueueEmpty()
	{
		return m_jobQueue.isEmpty();
	}

	private void addJobToQueue(JobItem job)
	{
		int retry = 5;
		while (--retry > 0)
		{
			try
			{
				m_jobQueue.put(job);
				break;
			}
			catch (InterruptedException e)
			{
				e.printStackTrace();
			}
		}

		if (m_thread == null)
		{
			m_thread = new Thread(this);
			m_thread.start();
		}
	}

	public void run()
	{
		JobItem job = null;

		while (m_stopThreadFired == false)
		{
			try
			{
				job = m_jobQueue.take();

				if (job == null)
				{
					Util.i("Null JobItem taken, quit loop");
					break;
				}

				if (job.spec.address == null)
				{
					// empty job
					sendMessageToHandler(job, 0);
					continue;
				}

				Uri.Builder ub = new Uri.Builder();
				ub.scheme(job.spec.isSecure ? "https" : "http");
				ub.encodedAuthority(job.spec.port > 0 ? (job.spec.address + ":" + job.spec.port) : job.spec.address);
				if (job.spec.path != null)
					ub.appendEncodedPath(job.spec.path);

				String requestMethod;
				String postBody = null;
				MultipartFormData mfd = null;

				if (job.spec.requestMethod == REQUESTMETHOD_GET)
				{
					requestMethod = "GET";

					if (job.spec.params != null)
					{
/*
						for (HashMap.Entry<String, String> entry : job.spec.params.entrySet())
						{
							ub.appendQueryParameter(entry.getKey(), entry.getValue());
						}
*/
						// RoR array param support
						for (HashMap.Entry<String, Object> entry : job.spec.params.entrySet())
						{
							Object val = entry.getValue();
							if (val instanceof ArrayList)
							{
								ArrayList<String> arr = (ArrayList<String>)val;
								for (String sval : arr)
								{
									ub.appendQueryParameter(entry.getKey(), sval);
								}
							}
							else
							{
								ub.appendQueryParameter(entry.getKey(), (String)val);
							}
						}
					}
				}
				else
				{
					switch (job.spec.requestMethod)
					{
					case REQUESTMETHOD_POST:
						requestMethod = "POST";
						break;
					case REQUESTMETHOD_PUT:
						requestMethod = "PUT";
						break;
					case REQUESTMETHOD_DELETE:
						requestMethod = "DELETE";
						break;
					default:
						throw new RuntimeException("Invalid request method: " + job.spec.requestMethod);
					}

					if (job.spec.params != null && !job.spec.params.isEmpty())
					{
						StringBuilder sb = new StringBuilder();
/*
						for (HashMap.Entry<String, String> entry : job.spec.params.entrySet())
						{
							sb.append(URLEncoder.encode(entry.getKey(), SVR_ENCODING));
							sb.append('=');
							sb.append(URLEncoder.encode(entry.getValue(), SVR_ENCODING));
							sb.append('&');
						}
*/
						// RoR array param support
						for (HashMap.Entry<String, Object> entry : job.spec.params.entrySet())
						{
							Object val = entry.getValue();
							if (val instanceof ArrayList)
							{
								ArrayList<String> arr = (ArrayList<String>)val;
								for (String sval : arr)
								{
									sb.append(URLEncoder.encode(entry.getKey(), SVR_ENCODING));
									sb.append('=');
									sb.append(URLEncoder.encode(sval, SVR_ENCODING));
									sb.append('&');
								}
							}
							else
							{
								sb.append(URLEncoder.encode(entry.getKey(), SVR_ENCODING));
								sb.append('=');
								sb.append(URLEncoder.encode((String)val, SVR_ENCODING));
								sb.append('&');
							}
						}

						sb.deleteCharAt(sb.length() - 1);
						postBody = sb.toString();
					}
					else if (job.spec.postBody instanceof MultipartFormData)
					{
						mfd = (MultipartFormData)job.spec.postBody;
					}
					else if (job.spec.postBody != null)
					{
						postBody = job.spec.postBody.toString();
					}
				}

				URL url = new URL(ub.build().toString());
				HttpURLConnection conn;

				if (job.spec.isSecure)
				{
					HttpsURLConnection sconn = (HttpsURLConnection) url.openConnection();
					if (ENABLE_FAKE_SSL)
					{
						sconn.setHostnameVerifier(m_fakeHostVerifier);
					}
					conn = sconn;
				}
				else
				{
					conn = (HttpURLConnection) url.openConnection();
				}

				if (job.spec.requestMethod == REQUESTMETHOD_DELETE_OVERRIDE)
				{
					conn.setRequestProperty("X-HTTP-Method-Override", "DELETE");
					conn.setRequestMethod("POST");
				}
				else
				{
					conn.setRequestMethod(requestMethod);
				}

				if (job.spec.headers != null)
				{
					for (HashMap.Entry<String, String> entry : job.spec.headers.entrySet())
					{
						conn.setRequestProperty(entry.getKey(), entry.getValue());
						//Util.d("JobHeader[%s]=%s", entry.getKey(), entry.getValue());
					}
				}

				conn.setDoInput(true);
				conn.setUseCaches(false);

				if (mfd != null)
				{
					conn.setDoOutput(true);
					conn.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + mfd.m_boundary);

					OutputStream os = conn.getOutputStream();
					mfd.m_baos.writeTo(os);
					os.close();
				}
				else if (postBody != null)
				{
					conn.setDoOutput(true);
					if (job.spec.postBody != null)
					{
						conn.setRequestProperty("Content-Type", "application/json");
					}

					OutputStream os = conn.getOutputStream();
					BufferedWriter br = new BufferedWriter(new OutputStreamWriter(os, SVR_ENCODING));
					br.write(postBody);
					br.flush();
					br.close();
					os.close();
				}

				conn.connect();
				int responseCode = conn.getResponseCode();
				Util.i("HttpMan(%s) RespCode:%d", url.toString(), responseCode);

				if (job.spec.resultType == RESULT_IGNORE)
					continue;

				//if (responseCode != HttpURLConnection.HTTP_OK)
				if (responseCode < 200 || responseCode >= 300)
				{
					sendMessageToHandler(job, ERROR_REQUEST_FAIL);
					continue;
				}

				InputStream inStrm = conn.getInputStream();
				OutputStream outStrm;
				ByteArrayOutputStream ba;
				if (job.spec.resultType == RESULT_SAVE_TO_FILE)
				{
					FileOutputStream fos = new FileOutputStream((String) job.result);
					outStrm = fos;
					ba = null;
				}
				else
				{
					ba = new ByteArrayOutputStream();
					outStrm = ba;
				}

				byte[] buff = new byte[2048];

				while (true)
				{
					int read = inStrm.read(buff);
					if (read <= 0)
						break;

					outStrm.write(buff, 0, read);
				}
				outStrm.close();

				switch (job.spec.resultType)
				{
				case RESULT_TEXT:
					job.result = new String(ba.toByteArray(), SVR_ENCODING);
					break;

				case RESULT_JSON:
				case RESULT_JSON_ARRAY:
					String jsonSrc = new String(ba.toByteArray(), SVR_ENCODING);
					try
					{
						if (job.spec.resultType == RESULT_JSON)
							job.result = new JSONObject(jsonSrc);
						else
							job.result = new JSONArray(jsonSrc);
					}
					catch (JSONException jse)
					{
						Util.e("JSON parsing failed: " + jsonSrc);
						throw jse;
					}
					break;

				case RESULT_BINARY:
					job.result = ba.toByteArray();
					break;
				case RESULT_SAVE_TO_FILE:
					Util.d("HTTP stream saved to file: " + job.result);
					break;
				default:
					Util.e("Unknown resultType: %d", job.spec.resultType);
					continue;
				}

				sendMessageToHandler(job, 0);
			}
			catch (UnknownHostException uhe)
			{
				Util.e("HttpMan: Unknown host, " + uhe.getMessage());
				sendMessageToHandler(job, ERROR_UNKNOWN_HOST);
			}
			catch (JSONException jse)
			{
				sendMessageToHandler(job, ERROR_RESULT_ERROR);
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
				sendMessageToHandler(job, ERROR_UNKNOWN);
			}
		}

		m_thread = null;
	}

	private void sendMessageToHandler(JobItem job, int errorCode)
	{
		if (job == null || job.listener == null)
			return;

		if (job.spec.isNotifyOnNetThread)
		{
			callListener(job, errorCode);
		}
		else
		{
			Message msg = new Message();
			msg.obj = job;
			msg.what = errorCode;
			m_handler.sendMessage(msg);
		}
	}

	private void callListener(JobItem job, int errorCode)
	{
		if (errorCode == 0)
		{
			job.listener.onHttpSuccess(job.jobId, job.spec, job.result);
		}
		else
		{
			if (job.spec.resultType == RESULT_SAVE_TO_FILE)
			{
				// delete file if exists
				try
				{
					File f = new File((String) job.result);
					if (f.exists())
						f.delete();
				}
				catch (Exception ex)
				{
				}
			}

			try
			{
				job.listener.onHttpFail(job.jobId, job.spec, errorCode);
			}
			catch (Exception ex)
			{
				ex.printStackTrace();
			}
		}
	}

	private HostnameVerifier m_fakeHostVerifier = new HostnameVerifier() {
		@Override
		public boolean verify(String hostname, SSLSession session) {
			return true;
		}
	};

	private static void installFakeSslFactory()
	{
		// Create a trust manager that does not validate certificate chains
		TrustManager[] trustAllCerts = new TrustManager[] { new X509TrustManager() {
			public java.security.cert.X509Certificate[] getAcceptedIssuers()
			{
				return new java.security.cert.X509Certificate[] {};
			}

			public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException
			{
			}

			public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException
			{
			}
		} };

		// Install the all-trusting trust manager
		try
		{
			SSLContext sc = SSLContext.getInstance("TLS");
			sc.init(null, trustAllCerts, new java.security.SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());
		}
		catch (Exception e)
		{
			e.printStackTrace();
		}
	}

	public static class MultipartFormData
	{
		private static final byte[] CRLF = new byte[] { '\r','\n' };

		private String m_boundary;
		private ByteArrayOutputStream m_baos;
		private byte[] m_boundaryBytes;

		public MultipartFormData()
		{
			m_boundary = java.util.UUID.randomUUID().toString();
			m_baos = new ByteArrayOutputStream();

			m_boundaryBytes = ("--"+m_boundary).getBytes();
		}

		public void add(String key, String value) throws IOException
		{
			m_baos.write(m_boundaryBytes);

			String data = String.format("\r\nContent-Disposition: form-data; name=\"%s\"\r\n\r\n%s\r\n", key, value);
			m_baos.write(data.getBytes());
		}

		private void startFile(String key, String filename, String mimeType) throws IOException
		{
			m_baos.write(m_boundaryBytes);
			//String head = String.format("\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\nContent-Type: %s\r\nContent-Transfer-Encoding: binary\r\n\r\n", key, filename, mimeType);
			String head = String.format("\r\nContent-Disposition: form-data; name=\"%s\"; filename=\"%s\"\r\nContent-Type: %s\r\n\r\n", key, filename, mimeType);
			m_baos.write(head.getBytes());
		}

		public void addFile(String key, String filename, byte[] file, String mimeType) throws IOException
		{
			startFile(key, filename, mimeType);
			m_baos.write(file);
			m_baos.write(CRLF);
		}

		public void addFile(String key, File file, String mimeType) throws IOException
		{
			startFile(key, file.getName(), mimeType);

			byte[] buff = new byte[2048];
			FileInputStream fis = new FileInputStream(file);
			for(;;)
			{
				int len = fis.read(buff);
				if (len <= 0)
					break;

				m_baos.write(buff, 0, len);
			}
			fis.close();
			//m_baos.write(CRLF);
		}

		public void finish() throws IOException
		{
			m_baos.write(m_boundaryBytes);
			m_baos.write(m_boundaryBytes, 0, 2);
			m_baos.write(CRLF);
			m_baos.close();
		}
	}
}
