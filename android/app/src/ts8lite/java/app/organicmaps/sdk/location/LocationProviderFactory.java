package app.organicmaps.sdk.location;

import android.content.Context;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.util.log.Logger;

/**
 * ts8Lite flavour — no Google Play Services.
 *
 * <p>Upstream builds get this class from the {@code google} flavour source set, which pulls
 * {@code com.google.android.gms.*}. TS8 NAV deliberately ships a different class with the same
 * fully-qualified name so that {@code LocationHelper} keeps compiling while the APK carries no
 * GMS dependency at all. The platform {@link AndroidNativeProvider} is used directly.
 */
public class LocationProviderFactory
{
  private static final String TAG = LocationProviderFactory.class.getSimpleName();

  public static boolean isGoogleLocationAvailable(@NonNull Context context)
  {
    return false;
  }

  @NonNull
  public static BaseLocationProvider getProvider(@NonNull Context context,
                                                 @NonNull BaseLocationProvider.Listener listener)
  {
    Logger.d(TAG, "TS8 NAV: using platform location provider (no Google Play Services).");
    return new AndroidNativeProvider(context, listener);
  }
}
