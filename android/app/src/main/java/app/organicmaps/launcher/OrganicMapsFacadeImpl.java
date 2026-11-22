package app.organicmaps.launcher;

import android.content.Context;
import app.organicmaps.R;
import android.location.Location;
import android.os.SystemClock;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.MwmApplication;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.launcher.NavigationState;
import app.organicmaps.sdk.launcher.NavigationStateMapper;
import app.organicmaps.sdk.launcher.OrganicMapsFacade;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.util.log.Logger;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The single implementation of {@link OrganicMapsFacade}, owned by the map-hosting activity.
 *
 * <p>It never creates a renderer and never changes the activity's lifecycle. It reads the routing
 * snapshot that the activity already keeps ({@code RoutingController} caches it) and converts it
 * with the pure {@link NavigationStateMapper}. That is what lets the shell observe navigation
 * without touching native code or forcing a second engine.
 *
 * <p>Polling is pull-based on a software tick rather than per-frame: the shell calls
 * {@link #getNavigationState()} when it wants, and listeners are notified only when the snapshot
 * actually changed. That keeps the launcher off the render path (spec §14: no per-frame
 * allocation).
 */
public class OrganicMapsFacadeImpl implements OrganicMapsFacade
{
  private static final String TAG = "OrganicMapsFacade";

  @NonNull
  private final Context mAppContext;
  @NonNull
  private final List<NavigationListener> mListeners = new CopyOnWriteArrayList<>();

  @Nullable
  private NavigationState mLastState;

  /** Supplier so the facade does not need to know whether routing lives in an activity. */
  public interface RoutingProbe
  {
    @Nullable
    RoutingInfo currentRoutingInfo();

    boolean isNavigating();

    boolean isBuilding();

    boolean isBuilt();
  }

  @Nullable
  private RoutingProbe mProbe;

  public OrganicMapsFacadeImpl(@NonNull Context context)
  {
    mAppContext = context.getApplicationContext();
  }

  /** Binds this facade to the live routing owner. Call from the activity that holds it. */
  public void bindRoutingProbe(@Nullable RoutingProbe probe)
  {
    mProbe = probe;
    notifyIfChanged();
  }

  @Override
  public boolean isMapReady()
  {
    return Framework.nativeIsRouteBuilt() || isMapInitialized();
  }

  private boolean isMapInitialized()
  {
    try
    {
      return MwmApplication.from(mAppContext).getOrganicMaps().arePlatformAndCoreInitialized();
    }
    catch (Exception e)
    {
      Logger.w(TAG, "Map not ready: " + e.getMessage());
      return false;
    }
  }

  @NonNull
  @Override
  public NavigationState getNavigationState()
  {
    final NavigationState state = computeState();
    mLastState = state;
    return state;
  }

  @NonNull
  private NavigationState computeState()
  {
    final RoutingProbe probe = mProbe;
    if (probe == null)
      return NavigationState.idle();

    final Location location = currentLocation();
    final boolean hasGpsFix = location != null && location.hasAccuracy();

    final NavigationState.Phase phase =
        NavigationStateMapper.phaseFor(probe.isNavigating(), probe.isBuilding(), probe.isBuilt(), hasGpsFix);

    if (phase == NavigationState.Phase.IDLE)
      return NavigationState.idle();
    if (phase == NavigationState.Phase.PLANNING)
      return NavigationState.planning();

    final RoutingInfo info = probe.currentRoutingInfo();
    if (info == null)
      return phase == NavigationState.Phase.PREVIEW ? NavigationState.preview(NavigationState.UNKNOWN,
                                                                               NavigationState.UNKNOWN, 0)
                                                     : NavigationState.idle();

    final double speedLimit = NavigationStateMapper.sanitiseSpeedLimitMps(info.speedLimitMps);
    final double speed = location != null
                             ? NavigationStateMapper.sanitiseSpeedMps(location.getSpeed())
                             : NavigationState.UNKNOWN;

    if (phase == NavigationState.Phase.PREVIEW)
      return NavigationState.preview(metres(info.distToTarget), info.totalTimeInSeconds,
                                     info.completionPercent);

    return NavigationStateMapper.navigating(
        metres(info.distToTurn), metres(info.distToTarget),
        info.totalTimeInSeconds, info.nextStreet, info.carDirection == null ? -1 : info.carDirection.ordinal(),
        info.exitNum, speedLimit, speed, info.completionPercent, hasGpsFix);
  }

  /**
   * Metres from a native {@code Distance}.
   *
   * <p>Never read {@code mDistance} directly: it is already in the user's display units, so a
   * kilometre value treated as metres is off by 1000x and a mile value by 1609x.
   */
  private static double metres(@Nullable app.organicmaps.sdk.util.Distance distance)
  {
    return distance == null ? NavigationState.UNKNOWN : distance.getMetres();
  }

  @Nullable
  private Location currentLocation()
  {
    try
    {
      return MwmApplication.from(mAppContext).getLocationHelper().getSavedLocation();
    }
    catch (Exception e)
    {
      return null;
    }
  }

  @Override
  public boolean hasRoute()
  {
    final RoutingProbe probe = mProbe;
    return probe != null && probe.isBuilt();
  }

  /** Computes a fresh snapshot and notifies listeners only if it differs from the last one. */
  public void poll()
  {
    notifyIfChanged();
  }

  private void notifyIfChanged()
  {
    final NavigationState state = computeState();
    if (state.equals(mLastState))
      return;
    mLastState = state;
    for (NavigationListener listener : mListeners)
      listener.onNavigationStateChanged(state);
  }

  @Override
  public void addNavigationListener(@NonNull NavigationListener listener)
  {
    if (!mListeners.contains(listener))
      mListeners.add(listener);
  }

  @Override
  public void removeNavigationListener(@NonNull NavigationListener listener)
  {
    mListeners.remove(listener);
  }

  // ---------------------------------------------------------------- actions

  @Override
  public void search(@NonNull String query, @NonNull SearchResultCallback callback)
  {
    // Search is delegated to the activity that owns the engine; this facade only guarantees
    // the shell never holds a native listener. An unimplemented bind means "not available",
    // which the UI must render as an actionable message rather than an empty list.
    final SearchInvoker invoker = mSearchInvoker;
    if (invoker == null)
    {
      callback.onSearchError(mAppContext.getString(R.string.ts8_search_unavailable));
      return;
    }
    invoker.invoke(query, callback);
  }

  @Override
  public void cancelSearch()
  {
    final SearchInvoker invoker = mSearchInvoker;
    if (invoker != null)
      invoker.cancel();
  }

  @Override
  public void stopNavigation()
  {
    final NavigationActionInvoker invoker = mActionInvoker;
    if (invoker != null)
      invoker.stopNavigation();
  }

  @Override
  public void recenter()
  {
    final NavigationActionInvoker invoker = mActionInvoker;
    if (invoker != null)
      invoker.recenter();
  }

  /** Indirection so this class stays free of the engine singletons. */
  public interface SearchInvoker
  {
    void invoke(@NonNull String query, @NonNull SearchResultCallback callback);

    void cancel();
  }

  public interface NavigationActionInvoker
  {
    void stopNavigation();

    void recenter();
  }

  @Nullable
  private SearchInvoker mSearchInvoker;
  @Nullable
  private NavigationActionInvoker mActionInvoker;

  public void bindSearchInvoker(@Nullable SearchInvoker invoker)
  {
    mSearchInvoker = invoker;
  }

  public void bindActionInvoker(@Nullable NavigationActionInvoker invoker)
  {
    mActionInvoker = invoker;
  }

  /** Monotonic clock helper for the shell's animation budget. */
  public static long nowMs()
  {
    return SystemClock.elapsedRealtime();
  }
}
