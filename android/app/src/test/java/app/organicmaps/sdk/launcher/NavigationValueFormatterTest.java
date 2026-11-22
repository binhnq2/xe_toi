package app.organicmaps.sdk.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Phase 2 gate: the "no fake values" rules (design spec §2).
 *
 * <p>Every absent value must render as {@code --}, never as 0, and a missing speed limit must
 * produce no sign at all.
 */
public class NavigationValueFormatterTest
{
  private static final String MIN = "min";
  private static final String HOUR = "h";
  private static final String KMH = "km/h";

  // ---------------------------------------------------------------- distance

  @Test
  public void unknownDistanceIsPlaceholder()
  {
    assertEquals("--", NavigationValueFormatter.formatDistance(NavigationState.UNKNOWN));
  }

  @Test
  public void shortDistanceInMetres()
  {
    assertEquals("350 m", NavigationValueFormatter.formatDistance(350));
    assertEquals("0 m", NavigationValueFormatter.formatDistance(0));
  }

  @Test
  public void longDistanceInKilometres()
  {
    assertEquals("1.2 km", NavigationValueFormatter.formatDistance(1240));
    assertEquals("12.4 km", NavigationValueFormatter.formatDistance(12400));
  }

  // ---------------------------------------------------------------- time

  @Test
  public void unknownTimeIsPlaceholder()
  {
    assertEquals("--", NavigationValueFormatter.formatRemainingTime(NavigationState.UNKNOWN, MIN, HOUR));
  }

  @Test
  public void minutesOnly()
  {
    assertEquals("18 min", NavigationValueFormatter.formatRemainingTime(18 * 60, MIN, HOUR));
    assertEquals("0 min", NavigationValueFormatter.formatRemainingTime(10, MIN, HOUR));
  }

  @Test
  public void hoursAndMinutes()
  {
    assertEquals("1 h 5 min", NavigationValueFormatter.formatRemainingTime(65 * 60, MIN, HOUR));
  }

  @Test
  public void exactHoursOmitMinutes()
  {
    assertEquals("2 h", NavigationValueFormatter.formatRemainingTime(2 * 3600, MIN, HOUR));
  }

  // ---------------------------------------------------------------- speed

  @Test
  public void unknownSpeedIsPlaceholder()
  {
    assertEquals("--", NavigationValueFormatter.formatSpeedKmh(NavigationState.UNKNOWN));
  }

  @Test
  public void speedConvertsToKmh()
  {
    // 13.888... m/s is 50 km/h.
    assertEquals("50", NavigationValueFormatter.formatSpeedKmh(50 / 3.6));
  }

  /** A stationary vehicle is 0, which is a real value and must be shown, not replaced. */
  @Test
  public void zeroSpeedIsShownNotHidden()
  {
    assertEquals("0", NavigationValueFormatter.formatSpeedKmh(0));
  }

  // ---------------------------------------------------------------- speed limit

  @Test
  public void negativeLimitMeansNoSign()
  {
    assertNull(NavigationValueFormatter.speedLimitKmh(-1.0));
  }

  @Test
  public void zeroLimitMeansNoSign()
  {
    assertNull(NavigationValueFormatter.speedLimitKmh(0.0));
  }

  @Test
  public void positiveLimitIsRounded()
  {
    assertEquals(Integer.valueOf(60), NavigationValueFormatter.speedLimitKmh(60 / 3.6));
    assertEquals(Integer.valueOf(40), NavigationValueFormatter.speedLimitKmh(40 / 3.6));
  }

  // ---------------------------------------------------------------- over-limit

  @Test
  public void noOverLimitIsPlaceholder()
  {
    assertEquals("--", NavigationValueFormatter.formatOverLimit(0, KMH));
    assertEquals("--", NavigationValueFormatter.formatOverLimit(-1, KMH));
  }

  @Test
  public void overLimitShowsDelta()
  {
    assertEquals("+12 km/h", NavigationValueFormatter.formatOverLimit(12 / 3.6, KMH));
  }

  // ---------------------------------------------------------------- ETA

  @Test
  public void unknownEtaIsPlaceholder()
  {
    assertEquals("--", NavigationValueFormatter.formatEta(0, NavigationState.UNKNOWN));
  }

  @Test
  public void etaAddsRemainingTime()
  {
    // 2026-01-01T10:00:00Z-ish; only the arithmetic matters, not the absolute date.
    final java.util.Calendar cal = java.util.Calendar.getInstance();
    cal.set(java.util.Calendar.HOUR_OF_DAY, 10);
    cal.set(java.util.Calendar.MINUTE, 0);
    cal.set(java.util.Calendar.SECOND, 0);
    final long now = cal.getTimeInMillis();
    assertEquals("11:00", NavigationValueFormatter.formatEta(now, 3600));
  }
}
