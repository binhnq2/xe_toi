package app.organicmaps.sdk.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Converts raw routing values into a {@link NavigationState}.
 *
 * <p>Kept free of Android and of {@code RoutingInfo} itself (which is a JNI mirror and therefore
 * cannot be constructed in a JVM test) so the mapping rules can be tested directly. The rules are
 * the ones the design spec calls out explicitly:
 *
 * <ul>
 *   <li>A negative speed limit means "no sign" and must never be turned into a number.</li>
 *   <li>A negative speed means "no fix"; the app must not display it.</li>
 *   <li>No route means no route strip, regardless of leftover numbers.</li>
 * </ul>
 */
public final class NavigationStateMapper
{
  private NavigationStateMapper() {}

  /**
   * Maps a navigating snapshot.
   *
   * @param speedLimitMps from {@code RoutingInfo}; negative means "no data"
   * @param speedMps      from the last fix; negative means "unknown"
   * @param hasGpsFix     false when the location provider has no usable fix
   */
  @NonNull
  public static NavigationState navigating(double distToTurnM, double distToTargetM,
                                           double remainingSeconds, @Nullable String nextStreet,
                                           int turnOrdinal, int exitNumber, double speedLimitMps,
                                           double speedMps, double completionPercent, boolean hasGpsFix)
  {
    final NavigationState state =
        NavigationState.navigating(distToTurnM, distToTargetM, remainingSeconds, nextStreet, turnOrdinal,
                                   exitNumber, speedLimitMps, speedMps, completionPercent);
    return hasGpsFix ? state : state.asNoGps();
  }

  /**
   * Decides the phase from the routing flags the activity actually has.
   *
   * @param isNavigating  following a route
   * @param isBuilding    route computation in flight
   * @param isBuilt       a route exists
   * @param hasGpsFix     a usable location is available
   */
  @NonNull
  public static NavigationState.Phase phaseFor(boolean isNavigating, boolean isBuilding, boolean isBuilt,
                                               boolean hasGpsFix)
  {
    if (isBuilding)
      return NavigationState.Phase.PLANNING;
    if (isNavigating)
      return hasGpsFix ? NavigationState.Phase.NAVIGATING : NavigationState.Phase.NO_GPS;
    if (isBuilt)
      return NavigationState.Phase.PREVIEW;
    return NavigationState.Phase.IDLE;
  }

  /**
   * Sanitises a speed limit coming from routing. {@code routing::FollowingInfo} uses a negative
   * value for "no data" and a literal zero for "no restriction"; both must render as no sign.
   */
  public static double sanitiseSpeedLimitMps(double raw)
  {
    return raw > 0 ? raw : NavigationState.UNKNOWN;
  }

  /** Sanitises a GPS speed; a negative or absurd value becomes "unknown". */
  public static double sanitiseSpeedMps(double raw)
  {
    // 150 m/s is 540 km/h; anything above is a glitch, not a vehicle.
    if (raw < 0 || raw > 150.0)
      return NavigationState.UNKNOWN;
    return raw;
  }
}
