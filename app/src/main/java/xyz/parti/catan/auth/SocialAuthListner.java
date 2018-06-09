package xyz.parti.catan.auth;

public interface SocialAuthListner {
  void onStartGoogleSignIn();
  void onCallbackGoogleSignIn();
  void onStartFacebookSignIn();
  void onCallbackFacebookSignIn();
  void onStartNullSignIn();
  void onCallbackNullSignIn();
}
