package xyz.parti.catan.auth;

public class NullAuthContract extends SocialAuthContract {
  @Override
  public void onStart() {
    super.mListner.onStartNullSignIn();
  }

  @Override
  public void onCallback() {
    super.mListner.onStartNullSignIn();
  }

  @Override
  public String getProvider() {
    return "null";
  }
}
