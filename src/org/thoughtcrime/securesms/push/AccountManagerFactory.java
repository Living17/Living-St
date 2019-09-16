package org.thoughtcrime.securesms.push;

import android.content.Context;
import android.os.AsyncTask;
import org.thoughtcrime.securesms.logging.Log;

import com.google.android.gms.security.ProviderInstaller;

import org.thoughtcrime.securesms.BuildConfig;
import org.thoughtcrime.securesms.util.TextSecurePreferences;
import org.thoughtcrime.securesms.util.concurrent.SignalExecutors;
import org.whispersystems.signalservice.api.SignalServiceAccountManager;

public class AccountManagerFactory {

  private static final String TAG = AccountManagerFactory.class.getSimpleName();

  public static SignalServiceAccountManager createAuthenticated(Context context, String uuid, String number, String password) {
    if (new SignalServiceNetworkAccess(context).isCensored(number)) {
      SignalExecutors.BOUNDED.execute(() -> {
        try {
          ProviderInstaller.installIfNeeded(context);
        } catch (Throwable t) {
          Log.w(TAG, t);
        }
      });
    }

    return new SignalServiceAccountManager(new SignalServiceNetworkAccess(context).getConfiguration(number),
                                           uuid, number, password, BuildConfig.USER_AGENT);
  }

  /**
   * Should only be used during registration when you haven't yet been assigned a UUID.
   */
  public static SignalServiceAccountManager createUnauthenticated(final Context context, String number, String password) {
    if (new SignalServiceNetworkAccess(context).isCensored(number)) {
      SignalExecutors.BOUNDED.execute(() -> {
        try {
          ProviderInstaller.installIfNeeded(context);
        } catch (Throwable t) {
          Log.w(TAG, t);
        }
      });
    }

    return new SignalServiceAccountManager(new SignalServiceNetworkAccess(context).getConfiguration(number),
                                           null, number, password, BuildConfig.USER_AGENT);
  }

}
