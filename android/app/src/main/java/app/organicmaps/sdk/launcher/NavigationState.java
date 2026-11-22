package app.organicmaps.sdk.launcher;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Bounded, read-only view of the navigation state for the launcher shell.
 *
 * <p>Why this exists: the launcher needs to render a maneuver panel and route strip, but it must
 * not reach into {@code RoutingController} internals or hold a reference to the renderer. This is
 * a plain immutable snapshot that the shell can consume, cache, and unit-test without Android.
 *
 * <p>Every field is nullable or explicitly marked "unknown" rather than carrying a made-up
 * default: the design spec forbids inventing values for a missing speed, limit or ETA, so the
 * absent case has to be representable in the type.
 */
public final class NavigationState
{
  /** Sentinel for "the data source has no value". Never displayed as a number. */
  public static final double UNKNOWN = -1.0;

  public enum Phase
  {
    /** No route, no navigation. */
    IDLE,
    /** Route is being computed. */
    PLANNING,
    /** Route built and shown for review, not yet following. */
    PREVIEW,
    /** Following the route. */
    NAVIGATING,
    /** Navigation active but the GPS fix is unusable. */
    NO_GPS
  }

  @NonNull
  public final Phase phase;

  /** Metres to the next manoeuvre, or {@link #UNKNOWN}. */
  public final double distToTurnM;

  /** Metres to the destination, or {@link #UNKNOWN}. */
  public final double distToTargetM;

  /** Seconds remaining, or {@link #UNKNOWN}. */
  public final double remainingSeconds;

  /** Next street name, or null when OSM has no name for it. */
  @Nullable
  public final String nextStreet;

  /** Turn icon id from the native {@code CarDirection} enum ordinal, or -1. */
  public final int turnDrawableOrdinal;

  /** Roundabout exit number, 0 when not a roundabout. */
  public final int exitNumber;

  /** Speed limit in m/s, or {@link #UNKNOWN}. A negative value means "no sign". */
  public final double speedLimitMps;

  /** Current GPS speed in m/s, or {@link #UNKNOWN}. */
  public final double speedMps;

  /** Route completion 0..100, or {@link #UNKNOWN}. */
  public final double completionPercent;

  /** True when a route exists at all. */
  public final boolean hasRoute;

  private NavigationState(@NonNull Phase phase, double distToTurnM, double distToTargetM,
                          double remainingSeconds, @Nullable String nextStreet, int turnDrawableOrdinal,
                          int exitNumber, double speedLimitMps, double speedMps, double completionPercent,
                          boolean hasRoute)
  {
    this.phase = phase;
    this.distToTurnM = distToTurnM;
    this.distToTargetM = distToTargetM;
    this.remainingSeconds = remainingSeconds;
    this.nextStreet = nextStreet;
    this.turnDrawableOrdinal = turnDrawableOrdinal;
    this.exitNumber = exitNumber;
    this.speedLimitMps = speedLimitMps;
    this.speedMps = speedMps;
    this.completionPercent = completionPercent;
    this.hasRoute = hasRoute;
  }

  /** The state before any route exists. */
  @NonNull
  public static NavigationState idle()
  {
    return new NavigationState(Phase.IDLE, UNKNOWN, UNKNOWN, UNKNOWN, null, -1, 0, UNKNOWN, UNKNOWN,
                               UNKNOWN, false);
  }

  /** State while a route is being computed. */
  @NonNull
  public static NavigationState planning()
  {
    return new NavigationState(Phase.PLANNING, UNKNOWN, UNKNOWN, UNKNOWN, null, -1, 0, UNKNOWN, UNKNOWN,
                               UNKNOWN, false);
  }

  /** State with a built route shown for review. */
  @NonNull
  public static NavigationState preview(double distToTargetM, double remainingSeconds,
                                        double completionPercent)
  {
    return new NavigationState(Phase.PREVIEW, UNKNOWN, distToTargetM, remainingSeconds, null, -1, 0,
                               UNKNOWN, UNKNOWN, completionPercent, true);
  }

  /** Full navigation state from live routing data. */
  @NonNull
  public static NavigationState navigating(double distToTurnM, double distToTargetM,
                                           double remainingSeconds, @Nullable String nextStreet,
                                           int turnDrawableOrdinal, int exitNumber, double speedLimitMps,
                                           double speedMps, double completionPercent)
  {
    return new NavigationState(Phase.NAVIGATING, distToTurnM, distToTargetM, remainingSeconds, nextStreet,
                               turnDrawableOrdinal, exitNumber, speedLimitMps, speedMps,
                               completionPercent, true);
  }

  /** Navigation active but the position is not usable. */
  @NonNull
  public NavigationState asNoGps()
  {
    return new NavigationState(Phase.NO_GPS, distToTurnM, distToTargetM, remainingSeconds, nextStreet,
                               turnDrawableOrdinal, exitNumber, speedLimitMps, UNKNOWN, completionPercent,
                               hasRoute);
  }

  /** True when the shell should draw the maneuver panel and route strip. */
  public boolean isFollowing()
  {
    return phase == Phase.NAVIGATING || phase == Phase.NO_GPS;
  }

  /**
   * True when a speed limit sign should be drawn at all.
   *
   * <p>Strictly greater than zero. {@code routing::FollowingInfo} uses a negative value for
   * "no data" and a literal zero for "no restriction"; both mean there is no sign to draw, so
   * {@code >= 0} here would put a "0" sign on every unrestricted road.
   */
  public boolean hasSpeedLimit()
  {
    return speedLimitMps > 0;
  }

  /** True when a numeric current speed can be shown. */
  public boolean hasSpeed()
  {
    return speedMps >= 0;
  }

  @NonNull
  @Override
  public String toString()
  {
    return "NavigationState{" + phase + ", turn=" + distToTurnM + ", target=" + distToTargetM
        + ", street=" + nextStreet + '}';
  }

  /**
   * Value equality. The facade uses this to notify listeners only on a real change, so a steady
   * navigation state must compare equal on every poll - otherwise the shell would redraw at the
   * polling rate.
   */
  @Override
  public boolean equals(@Nullable Object o)
  {
    if (this == o)
      return true;
    if (!(o instanceof NavigationState))
      return false;
    final NavigationState other = (NavigationState) o;
    return phase == other.phase
        && Double.compare(distToTurnM, other.distToTurnM) == 0
        && Double.compare(distToTargetM, other.distToTargetM) == 0
        && Double.compare(remainingSeconds, other.remainingSeconds) == 0
        && turnDrawableOrdinal == other.turnDrawableOrdinal
        && exitNumber == other.exitNumber
        && Double.compare(speedLimitMps, other.speedLimitMps) == 0
        && Double.compare(speedMps, other.speedMps) == 0
        && Double.compare(completionPercent, other.completionPercent) == 0
        && hasRoute == other.hasRoute
        && (nextStreet == null ? other.nextStreet == null : nextStreet.equals(other.nextStreet));
  }

  @Override
  public int hashCode()
  {
    int result = phase.hashCode();
    long bits = Double.doubleToLongBits(distToTurnM);
    result = 31 * result + (int) (bits ^ (bits >>> 32));
    bits = Double.doubleToLongBits(distToTargetM);
    result = 31 * result + (int) (bits ^ (bits >>> 32));
    result = 31 * result + turnDrawableOrdinal;
    result = 31 * result + exitNumber;
    bits = Double.doubleToLongBits(speedLimitMps);
    result = 31 * result + (int) (bits ^ (bits >>> 32));
    return result;
  }
}
