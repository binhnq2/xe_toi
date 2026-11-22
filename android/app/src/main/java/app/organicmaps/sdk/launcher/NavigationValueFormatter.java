package app.organicmaps.sdk.launcher;

import androidx.annotation.NonNull;

/**
 * Formats the numbers the launcher shows, without inventing any.
 *
 * <p>Design spec §2: "No fake runtime values. Missing speed, limit, ETA, voice or GPS must be
 * represented by `--`, hidden controls or an actionable error." Every method here takes the
 * {@link NavigationState#UNKNOWN} sentinel and returns the placeholder, so a missing value can
 * never reach the screen as "0".
 *
 * <p>Pure Java on purpose: the formatting rules are part of the contract and are unit-tested on
 * the JVM (see {@code NavigationValueFormatterTest}).
 */
public final class NavigationValueFormatter
{
  /** Shown wherever a value is absent. Two characters, not an em dash: it renders on API 23. */
  public static final String PLACEHOLDER = "--";

  private static final double METRES_PER_KM = 1000.0;

  private NavigationValueFormatter() {}

  /** Distance for the manoeuvre panel: "350 m", "1.2 km", or the placeholder. */
  @NonNull
  public static String formatDistance(double metres)
  {
    if (metres < 0)
      return PLACEHOLDER;
    if (metres < METRES_PER_KM)
      return Math.round(metres) + " m";
    return String.format(java.util.Locale.US, "%.1f km", metres / METRES_PER_KM);
  }

  /**
   * Remaining time for the route strip: "18 phút", "1 h 5 phút", or the placeholder.
   *
   * @param minutesWord the localised word for "minute"; passed in so this class stays free of
   *                    resources and testable.
   * @param hoursWord   the localised word for "hour"
   */
  @NonNull
  public static String formatRemainingTime(double seconds, @NonNull String minutesWord,
                                           @NonNull String hoursWord)
  {
    if (seconds < 0)
      return PLACEHOLDER;
    final long totalMinutes = Math.round(seconds / 60.0);
    if (totalMinutes < 60)
      return totalMinutes + " " + minutesWord;
    final long hours = totalMinutes / 60;
    final long minutes = totalMinutes % 60;
    if (minutes == 0)
      return hours + " " + hoursWord;
    return hours + " " + hoursWord + " " + minutes + " " + minutesWord;
  }

  /**
   * ETA as a wall-clock time: "15:42", or the placeholder.
   *
   * @param nowMs    current time, injectable for tests
   * @param remainSeconds remaining seconds
   */
  @NonNull
  public static String formatEta(long nowMs, double remainSeconds)
  {
    if (remainSeconds < 0)
      return PLACEHOLDER;
    final java.util.Calendar cal = java.util.Calendar.getInstance();
    cal.setTimeInMillis(nowMs + Math.round(remainSeconds * 1000.0));
    return String.format(java.util.Locale.US, "%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY),
                         cal.get(java.util.Calendar.MINUTE));
  }

  /** Current speed rounded to whole km/h, or the placeholder. */
  @NonNull
  public static String formatSpeedKmh(double speedMps)
  {
    if (speedMps < 0)
      return PLACEHOLDER;
    return String.valueOf(Math.round(speedMps * 3.6));
  }

  /** Speed-limit sign value in km/h, or null when there is no sign to draw. */
  public static Integer speedLimitKmh(double speedLimitMps)
  {
    // Mirrors NavigationState.hasSpeedLimit(): zero and negative both mean "no sign".
    if (speedLimitMps <= 0)
      return null;
    return (int) Math.round(speedLimitMps * 3.6);
  }

  /**
   * Over-limit delta for the warning banner, e.g. "+12 km/h".
   *
   * @return null when there is nothing to warn about
   */
  @NonNull
  public static String formatOverLimit(double overLimitMps, @NonNull String kmhWord)
  {
    if (overLimitMps <= 0)
      return PLACEHOLDER;
    return "+" + Math.round(overLimitMps * 3.6) + " " + kmhWord;
  }
}
