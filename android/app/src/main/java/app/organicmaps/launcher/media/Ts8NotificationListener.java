package app.organicmaps.launcher.media;

import android.service.notification.NotificationListenerService;
import app.organicmaps.sdk.util.log.Logger;

/**
 * Minimal notification listener.
 *
 * <p>The platform requires a bound {@code NotificationListenerService} before an app may call
 * {@link android.media.session.MediaSessionManager#getActiveSessions}. This class does nothing with
 * notifications themselves - it never reads their content, never logs them and never posts
 * anything. It exists solely so {@link MediaSessionRepository} can enumerate media sessions.
 *
 * <p>Deliberately no {@code android:label} or UI: the user grants access once in system settings,
 * and the manifest entry makes that possible. The service is declared in the ts8lite manifest only.
 */
public class Ts8NotificationListener extends NotificationListenerService
{
  private static final String TAG = "Ts8NotificationListener";

  @Override
  public void onCreate()
  {
    super.onCreate();
    // No listener registration: notification content is out of scope.
    Logger.i(TAG, "Notification listener started (media session access only)");
  }

  @Override
  public void onListenerConnected()
  {
    super.onListenerConnected();
    Logger.i(TAG, "Listener connected");
  }
}
