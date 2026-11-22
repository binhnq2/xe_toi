package app.organicmaps.sdk.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * TS8 NAV Phase 3 tests: reject GPS fixes that imply an impossible speed.
 *
 * <p>This is the most visible form of "the arrow doesn't follow the road": one bad fix moves the
 * marker kilometres away for a single tick, the camera chases it, then everything snaps back.
 */
public class LocationPlausibilityTest
{
  /** 50 km/h in m/s. */
  private static final double FIFTY_KMH = 50.0 / 3.6;

  @Test
  public void acceptsNormalVehicleSpeed()
  {
    assertFalse(LocationPlausibility.isImplausibleJump(FIFTY_KMH * 1.0, 1.0)); // 50 km/h, 1 s
  }

  @Test
  public void acceptsHighwaySpeed()
  {
    assertFalse(LocationPlausibility.isImplausibleJump(130.0 / 3.6, 1.0)); // 130 km/h, 1 s
  }

  @Test
  public void rejectsJumpAcrossCity()
  {
    assertTrue(LocationPlausibility.isImplausibleJump(11_000.0, 1.0)); // 11 km in 1 s
  }

  @Test
  public void rejectsShortJumpAtAbsurdSpeed()
  {
    assertTrue(LocationPlausibility.isImplausibleJump(400.0, 1.0)); // 1440 km/h
  }

  @Test
  public void ignoresTooShortInterval()
  {
    // Under 0.5 s the denominator is meaningless; do not reject real movement.
    assertFalse(LocationPlausibility.isImplausibleJump(100.0, 0.3));
  }

  @Test
  public void acceptsTunnelExitWithLongGap()
  {
    // 60 s gap, 1 km travelled -> ~16.7 m/s, entirely plausible.
    assertFalse(LocationPlausibility.isImplausibleJump(1_000.0, 60.0));
  }

  @Test
  public void rejectsTunnelExitWithJumpFarTooBig()
  {
    // 60 s gap but 20 km -> 333 m/s, impossible.
    assertTrue(LocationPlausibility.isImplausibleJump(20_000.0, 60.0));
  }

  @Test
  public void handlesNonFiniteInputSafely()
  {
    assertFalse(LocationPlausibility.isImplausibleJump(Double.NaN, 1.0));
    assertFalse(LocationPlausibility.isImplausibleJump(100.0, Double.NaN));
    assertFalse(LocationPlausibility.isImplausibleJump(-1.0, 1.0));
  }
}
