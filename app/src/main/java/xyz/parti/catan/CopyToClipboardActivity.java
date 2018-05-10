package xyz.parti.catan;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

public class CopyToClipboardActivity extends Activity {
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);

    Uri uri = getIntent().getData();
    if(uri != null) {
      copyTextToClipboar(uri.toString());
      Util.toastShort(this, "복사되었습니다");
    }

    finish();
  }

  private void copyTextToClipboar(String url) {
    ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
    ClipData clip = ClipData.newPlainText("URL", url);
    clipboard.setPrimaryClip(clip);
  }
}
