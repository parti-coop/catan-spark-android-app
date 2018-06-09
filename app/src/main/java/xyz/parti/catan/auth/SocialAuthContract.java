package xyz.parti.catan.auth;

public abstract class SocialAuthContract {
  private final static SocialAuthContract[] managers = {sharedFacebookAuthContract(), sharedGoogleAuthContract()};
  protected SocialAuthListner mListner;

  private static volatile FacebookAuthContract facebookAuthContract = null;
  public static FacebookAuthContract sharedFacebookAuthContract(){
    if(facebookAuthContract == null){
      synchronized (FacebookAuthContract.class){
        if(facebookAuthContract == null){
          facebookAuthContract = new FacebookAuthContract();
        }
      }
    }
    return facebookAuthContract;
  }

  private static volatile GoogleAuthContract googleAuthContract = null;
  public static GoogleAuthContract sharedGoogleAuthContract() {
    if (googleAuthContract == null) {
      synchronized (GoogleAuthContract.class) {
        if (googleAuthContract == null) {
          googleAuthContract = new GoogleAuthContract();
        }
      }
    }
    return googleAuthContract;
  }

  private static volatile NullAuthContract nullAuthContract = null;
  public static NullAuthContract sharedNullAuthContract(){
    if(nullAuthContract == null){
      synchronized (NullAuthContract.class){
        if(nullAuthContract == null){
          nullAuthContract = new NullAuthContract();
        }
      }
    }
    return nullAuthContract;
  }

  public static SocialAuthContract shareInstance(final String provider) {
    for(SocialAuthContract manager: managers) {
      if(manager.isMatch(provider)) {
        return manager;
      }
    }

    return sharedNullAuthContract();
  }

  public static void init(SocialAuthListner listner) {
    for(SocialAuthContract manager: managers) {
      manager.setListner(listner);
    }
  }

  public void setListner(SocialAuthListner listner) {
    mListner = listner;
  }

  private boolean isMatch(final String provider) {
    return getProvider().equals(provider);
  }
  public abstract void onStart();
  public abstract void onCallback();
  public abstract String getProvider();
}
