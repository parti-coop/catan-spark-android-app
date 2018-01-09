package xyz.parti.catan;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Bundle;
import android.support.v4.app.NotificationCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;


public class MyFirebaseMessagingService extends FirebaseMessagingService
{
	@Override
	public void onMessageReceived(RemoteMessage remoteMessage)
	{
		// [START_EXCLUDE]
		// There are two types of messages data messages and notification messages. Data messages are handled
		// here in onMessageReceived whether the app is in the foreground or background. Data messages are the type
		// traditionally used with GCM. Notification messages are only received here in onMessageReceived when the app
		// is in the foreground. When the app is in the background an automatically generated notification is displayed.
		// When the user taps on the notification they are returned to the app. Messages containing both notification
		// and data payloads are treated as notification messages. The Firebase console always sends notification
		// messages. For more see: https://firebase.google.com/docs/cloud-messaging/concept-options
		// [END_EXCLUDE]

		// TODO(developer): Handle FCM messages here.
		// Not getting messages here? See why this may be: https://goo.gl/39bRNJ
		Util.d("From: " + remoteMessage.getFrom());

		// Check if message contains a notification payload.
		if (remoteMessage.getNotification() != null)
		{
			Activity act = CatanApp.getCurActivity();
			if (act != null && act == MainAct.getInstance())
			{
				final Bundle bun = new Bundle();
				bun.putString(MainAct.PUSHARG_TITLE, remoteMessage.getNotification().getTitle());
				bun.putString(MainAct.PUSHARG_MESSAGE, remoteMessage.getNotification().getBody());
				bun.putString(MainAct.PUSHARG_URL, remoteMessage.getData().get("url"));

				act.runOnUiThread(new Runnable() {
					@Override
					public void run()
					{
						MainAct.getInstance().alertPushDialog(bun);
					}
				});
			}
			else
			{
				sendNotification(remoteMessage);
			}

			sendNotification(remoteMessage);
		}
	}

	private void sendNotification(RemoteMessage rmsg)
	{
		RemoteMessage.Notification noti = rmsg.getNotification();

		Intent intent = new Intent(this, MainAct.class);
		intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
		intent.putExtra(MainAct.PUSHARG_TITLE, noti.getTitle());
		intent.putExtra(MainAct.PUSHARG_MESSAGE, noti.getBody());
		intent.putExtra(MainAct.PUSHARG_URL, rmsg.getData().get("url"));
		PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_ONE_SHOT);

		//Bitmap bmpLargeIcon = BitmapFactory.decodeResource(getApplicationContext().getResources(), R.mipmap.ic_launcher);
		//Util.d("bmp=%s, ctx=%s, res=%s", bmpLargeIcon, getApplicationContext(), getApplicationContext().getResources());

		String channelId = getString(R.string.default_notification_channel_id);
		Uri defaultSoundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION);
		NotificationCompat.Builder notificationBuilder =
			new NotificationCompat.Builder(this, channelId)
				.setSmallIcon(R.drawable.ic_stat_ic_notification)
				//.setLargeIcon(bmpLargeIcon)
				.setTicker(noti.getTitle())
				.setContentTitle(noti.getTitle())
				.setContentText(noti.getBody())
				.setColor(0x26ccf9)
				.setAutoCancel(true)
				.setDefaults(Notification.DEFAULT_ALL)
				.setSound(defaultSoundUri)
				.setContentIntent(pendingIntent);

		NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
		notificationManager.notify(0, notificationBuilder.build());
	}
}
