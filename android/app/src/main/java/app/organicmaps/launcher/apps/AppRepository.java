package app.organicmaps.launcher.apps;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.util.log.Logger;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Installed-app index with an icon cache (design spec §8).
 *
 * <p>Two performance rules are the reason this class exists at all:
 *
 * <ul>
 *   <li><b>No PackageManager or icon decode on the main thread.</b> The index is built on a
 *       single background executor; icons are decoded lazily per item and only when a grid cell
 *       actually binds.</li>
 *   <li><b>No re-query on every open.</b> The index is built once and only rebuilt on a package
 *       broadcast. Opening the drawer re-uses it, which is what makes the warm target reachable.</li>
 * </ul>
 *
 * <p>Icons live in an {@link LruCache} sized as a fraction of the heap; a miss returns null and the
 * caller draws its placeholder rather than waiting.
 */
public class AppRepository
{
  private static final String TAG = "AppRepository";

  public interface Callback
  {
    /** Called on the main thread with the ordered, visible list. */
    void onAppsLoaded(@NonNull List<AppList.Entry> apps);
  }

  @NonNull
  private final Context mAppContext;
  @NonNull
  private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
  @NonNull
  private final Handler mMain = new Handler(Looper.getMainLooper());
  @NonNull
  private final LruCache<String, Drawable> mIconCache;

  @Nullable
  private volatile List<AppList.Entry> mIndex;
  private volatile boolean mLoading;

  public AppRepository(@NonNull Context context)
  {
    mAppContext = context.getApplicationContext();
    // Roughly 1/16 of the heap, expressed in icon count with a floor so small heaps still cache.
    final int cacheSize = Math.max(32, (int) (Runtime.getRuntime().maxMemory() / 16 / 4096));
    mIconCache = new LruCache<String, Drawable>(cacheSize)
    {
      @Override
      protected int sizeOf(@NonNull String key, @NonNull Drawable value)
      {
        // Approximate: icons are small but the count matters more than exact bytes.
        return 1;
      }
    };
  }

  /** True when a warm index exists, so the drawer can draw immediately. */
  public boolean isWarm()
  {
    return mIndex != null;
  }

  /** Returns the cached index without touching PackageManager, or null when cold. */
  @Nullable
  public List<AppList.Entry> cachedIndex()
  {
    return mIndex;
  }

  /**
   * Builds the index if needed, then reports the ordered visible list on the main thread.
   * Calling this twice while a build is in flight does not start a second build.
   */
  public void load(@NonNull Callback callback)
  {
    final List<AppList.Entry> warm = mIndex;
    if (warm != null)
    {
      callback.onAppsLoaded(warm);
      return;
    }
    if (mLoading)
    {
      // Another caller is already building; queue behind it with a short re-check.
      mMain.postDelayed(() -> load(callback), 50);
      return;
    }
    mLoading = true;
    mExecutor.execute(() -> {
      List<AppList.Entry> built;
      try
      {
        built = buildIndex();
      }
      catch (Exception e)
      {
        Logger.e(TAG, "Cannot build app index", e);
        built = new ArrayList<>();
      }
      // Captured by the lambda below, so it must be final: assign to a new local first.
      final List<AppList.Entry> result = built;
      mIndex = result;
      mLoading = false;
      mMain.post(() -> callback.onAppsLoaded(result));
    });
  }

  /** Drops the index so the next {@link #load} rebuilds it (package added/removed). */
  public void invalidate()
  {
    mIndex = null;
    mIconCache.evictAll();
  }

  @NonNull
  private List<AppList.Entry> buildIndex()
  {
    final PackageManager pm = mAppContext.getPackageManager();
    final Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
    final List<ResolveInfo> resolved = pm.queryIntentActivities(launcher, 0);

    final String self = mAppContext.getPackageName();
    final List<AppList.Entry> out = new ArrayList<>(resolved.size());
    for (ResolveInfo ri : resolved)
    {
      if (ri.activityInfo == null)
        continue;
      final String pkg = ri.activityInfo.packageName;
      if (self.equals(pkg))
        continue;  // the launcher never lists itself

      CharSequence label = ri.loadLabel(pm);
      final String text = label == null ? pkg : label.toString();
      out.add(new AppList.Entry(pkg, text, 0L, false, false));
    }

    // Ordering needs the locale; the caller's locale is the user's, not the device default, so
    // resolve it here and pass it explicitly rather than letting Collator guess.
    final Locale locale = mAppContext.getResources().getConfiguration().getLocales().get(0);
    // Stored pin/hide flags belong to the user, not to PackageManager, so they are applied here
    // rather than threaded through every caller.
    final List<AppList.Entry> flagged =
        AppList.applyUserFlags(out, app.organicmaps.launcher.LauncherServices.pinned(mAppContext),
                               app.organicmaps.launcher.LauncherServices.hidden(mAppContext));
    return AppList.ordered(flagged, locale);
  }

  /**
   * Returns a cached icon immediately, or null and kicks off a decode.
   *
   * <p>The caller should draw its placeholder when null and expect a later
   * {@link DrawableListener} callback for that row.
   */
  @Nullable
  public Drawable iconOrNull(@NonNull String packageName, @Nullable DrawableListener listener)
  {
    final Drawable cached = mIconCache.get(packageName);
    if (cached != null)
      return cached;

    if (listener != null)
    {
      mExecutor.execute(() -> {
        final Drawable decoded = decodeIcon(packageName);
        if (decoded == null)
          return;
        mIconCache.put(packageName, decoded);
        mMain.post(() -> listener.onIcon(packageName, decoded));
      });
    }
    return null;
  }

  @Nullable
  private Drawable decodeIcon(@NonNull String packageName)
  {
    try
    {
      return mAppContext.getPackageManager().getApplicationIcon(packageName);
    }
    catch (PackageManager.NameNotFoundException e)
    {
      return null;
    }
    catch (Exception e)
    {
      Logger.w(TAG, "Cannot decode icon for " + packageName + ": " + e.getMessage());
      return null;
    }
  }

  public interface DrawableListener
  {
    void onIcon(@NonNull String packageName, @NonNull Drawable drawable);
  }

  /** Launches a package, mapping failure to a false result instead of an exception. */
  public boolean launch(@NonNull String packageName)
  {
    final Intent intent = mAppContext.getPackageManager().getLaunchIntentForPackage(packageName);
    if (intent == null)
      return false;
    try
    {
      intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
      mAppContext.startActivity(intent);
      return true;
    }
    catch (Exception e)
    {
      Logger.w(TAG, "Cannot launch " + packageName + ": " + e.getMessage());
      return false;
    }
  }

  public void release()
  {
    mExecutor.shutdownNow();
    mIconCache.evictAll();
  }

  /** Persists a pin flag and refreshes the index. */
  public void setPinned(@NonNull String packageName, boolean pinned)
  {
    app.organicmaps.launcher.LauncherServices.setPinned(mAppContext, packageName, pinned);
  }

  /** Persists a hide flag and refreshes the index. */
  public void setHidden(@NonNull String packageName, boolean hidden)
  {
    app.organicmaps.launcher.LauncherServices.setHidden(mAppContext, packageName, hidden);
  }
}
