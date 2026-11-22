package app.organicmaps.sdk.util;

/**
 * TS8 NAV: pure, framework-free plausibility rules for GPS fixes.
 *
 * <p>Kept separate from {@link LocationUtils} so the maths can be unit-tested on the JVM without
 * Robolectric: {@code android.location.Location} is final and its constructor is stubbed in local
 * unit tests, so anything that needs a real instance cannot be tested there.
 */
public final class LocationPlausibility
{
  /** 150 m/s = 540 km/h, far above any plausible vehicle. */
  public static final double MAX_PLAUSIBLE_SPEED_MPS = 150.0;
  /** Below this interval the denominator is too small for the check to mean anything. */
  public static final double MIN_CHECK_INTERVAL_SEC = 0.5;

  private LocationPlausibility() {}

  /**
   * True when moving from one fix to the next would require an impossible speed.
   *
   * @param distanceMeters  great-circle distance between the two fixes
   * @param dtSec           elapsed time between them, in seconds
   */
  public static boolean isImplausibleJump(double distanceMeters, double dtSec)
  {
    if (dtSec < MIN_CHECK_INTERVAL_SEC)
      return false;
    if (distanceMeters < 0 || Double.isNaN(distanceMeters) || Double.isNaN(dtSec))
      return false;
    return distanceMeters / dtSec > MAX_PLAUSIBLE_SPEED_MPS;
  }
}
