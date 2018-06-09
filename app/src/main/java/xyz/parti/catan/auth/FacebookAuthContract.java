package xyz.parti.catan.auth;

public class FacebookAuthContract extends SocialAuthContract {
  @Override
  public void onStart() {
    super.mListner.onStartFacebookSignIn();
  }

  @Override
  public void onCallback() {
    super.mListner.onCallbackFacebookSignIn();
  }

  @Override
  public String getProvider() {
    return "facebook";
  }
}
