package app.organicmaps.launcher;

import android.content.Context;
import androidx.annotation.NonNull;
import app.organicmaps.sdk.launcher.OrganicMapsFacade;

/**
 * Process-wide holder for the launcher facade.
 *
 * <p>There is exactly one map engine per process ({@code MwmApplication} owns it), so there is
 * exactly one facade. Keeping it off the Activity is what makes "Home returns without rebuilding
 * the engine" true: the facade survives an Activity restart and re-binds to whichever activity is
 * currently hosting routing.
 */
public final class LauncherFacadeHolder
{
  private static volatile OrganicMapsFacadeImpl sInstance;

  private LauncherFacadeHolder() {}

  @NonNull
  public static OrganicMapsFacade get(@NonNull Context context)
  {
    OrganicMapsFacadeImpl instance = sInstance;
    if (instance == null)
    {
      synchronized (LauncherFacadeHolder.class)
      {
        instance = sInstance;
        if (instance == null)
        {
          instance = new OrganicMapsFacadeImpl(context.getApplicationContext());
          sInstance = instance;
        }
      }
    }
    return instance;
  }

  /** The concrete implementation, for code that needs to bind probes/invokers. */
  @NonNull
  public static OrganicMapsFacadeImpl impl(@NonNull Context context)
  {
    return (OrganicMapsFacadeImpl) get(context);
  }

  /** Test hook. Never call from production code. */
  static void resetForTests()
  {
    sInstance = null;
  }
}
