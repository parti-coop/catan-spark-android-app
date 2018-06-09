package xyz.parti.catan.auth;

public class GoogleAuthContract extends SocialAuthContract {
  @Override
  public void onStart() {
    super.mListner.onStartGoogleSignIn();
  }

  @Override
  public void onCallback() {
    super.mListner.onCallbackGoogleSignIn();
  }

  @Override
  public String getProvider() {
    return "google_oauth2";
  }
}
