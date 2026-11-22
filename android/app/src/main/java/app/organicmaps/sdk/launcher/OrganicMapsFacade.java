package app.organicmaps.sdk.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * The launcher's only allowed door into Organic Maps.
 *
 * <p>The shell must not touch {@code RoutingController}, {@code Framework} or any renderer object
 * directly: those are singletons owned by the map Activity, and reaching into them from a second
 * Activity is what would force the engine to be created twice (audit risk R2). Everything the
 * launcher needs is behind this interface, and the implementation is supplied by whoever owns the
 * activity lifecycle.
 *
 * <p>The interface is deliberately small. Adding a method here is a contract change that both the
 * shell and the map host must agree on, which keeps the boundary honest.
 */
public interface OrganicMapsFacade
{
  /** Current navigation snapshot. Never null. */
  @NonNull
  NavigationState getNavigationState();

  /** True when the map engine is initialised and can render. */
  boolean isMapReady();

  /**
   * Starts a search. The facade forwards results through the supplied listener and owns the
   * engine-side listener registration, so the shell never holds a native reference.
   */
  void search(@NonNull String query, @NonNull SearchResultCallback callback);

  /** Cancels an in-flight search; safe to call when none is running. */
  void cancelSearch();

  /** Ends navigation and clears the route. */
  void stopNavigation();

  /** Recentres the map on the current position. */
  void recenter();

  /** Subscribes to navigation-state changes. The caller must unsubscribe. */
  void addNavigationListener(@NonNull NavigationListener listener);

  void removeNavigationListener(@NonNull NavigationListener listener);

  /** True when a route exists, so the shell knows whether to show the route strip. */
  boolean hasRoute();

  interface NavigationListener
  {
    void onNavigationStateChanged(@NonNull NavigationState state);
  }

  interface SearchResultCallback
  {
    void onResults(@NonNull String[] titles, @NonNull double[] latitudes, @NonNull double[] longitudes);

    void onSearchFinished();

    void onSearchError(@Nullable String reason);
  }
}
