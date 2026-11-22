package app.organicmaps.sdk.routing;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Before;
import org.junit.Test;

/**
 * Deterministic tests for {@link SpeedLimitState}, covering the scenarios required by
 * the TS8 Phase 5 specification.
 */
public class SpeedLimitStateTest
{
  /** 5 km/h in m/s; the default start offset. */
  private static final double START = 5.0 / 3.6;
  /** 2 km/h in m/s; the default stop/near offset. */
  private static final double STOP = 2.0 / 3.6;

  private SpeedLimitState mState;

  @Before
  public void setUp()
  {
    mState = new SpeedLimitState();
  }

  /** Converts km/h to m/s for readability in the test cases. */
  private static double kmh(double v)
  {
    return v / 3.6;
  }

  // ---------------------------------------------------------------- no data

  /**
   * Regression for the baseline bug: {@code speedLimitMps == -1} is the "no data" sentinel,
   * and {@code -1 < 20} was true, so every road without a limit reported a violation.
   */
  @Test
  public void noSpeedLimitDataNeverWarns()
  {
    SpeedLimitState.Result r = mState.evaluate(-1.0, kmh(80), 5.0, 0L);
    assertEquals(SpeedLimitState.State.NO_LIMIT, r.state);
    assertFalse("must not report a violation without a speed limit", r.justExceeded);
    assertEquals(0.0, r.overLimitMps, 1e-9);
  }

  /** A literal zero means "no restriction"; it must also not produce a warning. */
  @Test
  public void zeroSpeedLimitIsTreatedAsNoLimit()
  {
    SpeedLimitState.Result r = mState.evaluate(0.0, kmh(120), 5.0, 0L);
    assertEquals(SpeedLimitState.State.NO_LIMIT, r.state);
  }

  @Test
  public void unknownSpeedDoesNotWarn()
  {
    SpeedLimitState.Result r = mState.evaluate(kmh(50), -1.0, 5.0, 0L);
    assertEquals(SpeedLimitState.State.NO_LIMIT, r.state);
  }

  // ---------------------------------------------------------------- normal / near / exceeded

  @Test
  public void belowLimitIsNormal()
  {
    SpeedLimitState.Result r = mState.evaluate(kmh(60), kmh(50), 5.0, 0L);
    assertEquals(SpeedLimitState.State.NORMAL, r.state);
    assertFalse(r.justExceeded);
  }

  @Test
  public void startsWarningAtLimitPlusFive()
  {
    // 64.9 km/h is still below 60 + 5.
    assertEquals(SpeedLimitState.State.NEAR_LIMIT,
                 mState.evaluate(kmh(60), kmh(64.9), 5.0, 0L).state);
    // 65.0 km/h reaches the threshold.
    assertEquals(SpeedLimitState.State.EXCEEDED, mState.evaluate(kmh(60), kmh(65.0), 5.0, 0L).state);
  }

  @Test
  public void reportsOverLimitDelta()
  {
    SpeedLimitState.Result r = mState.evaluate(kmh(60), kmh(72), 5.0, 0L);
    assertEquals(SpeedLimitState.State.EXCEEDED, r.state);
    assertTrue(r.justExceeded);
    assertEquals(kmh(12), r.overLimitMps, 1e-6);
  }

  // ---------------------------------------------------------------- hysteresis

  /**
   * Oscillating around the threshold must not flap: once exceeded, stay exceeded until the speed
   * drops below limit + 2 km/h.
   */
  @Test
  public void oscillatingAroundThresholdDoesNotFlap()
  {
    mState.evaluate(kmh(60), kmh(66), 5.0, 0L); // enter EXCEEDED at +6
    assertEquals(SpeedLimitState.State.EXCEEDED, mState.getState());

    // Drop to +4: between stop (+2) and start (+5) -> must stay EXCEEDED.
    assertEquals(SpeedLimitState.State.EXCEEDED,
                 mState.evaluate(kmh(60), kmh(64), 5.0, 1_000L).state);

    // Back up to +6: still EXCEEDED and NOT a fresh event.
    SpeedLimitState.Result back = mState.evaluate(kmh(60), kmh(66), 5.0, 2_000L);
    assertEquals(SpeedLimitState.State.EXCEEDED, back.state);
    assertFalse("no re-trigger while still exceeded", back.justExceeded);

    // Finally drop below +2 -> NORMAL.
    assertEquals(SpeedLimitState.State.NORMAL,
                 mState.evaluate(kmh(60), kmh(61), 5.0, 3_000L).state);
  }

  // ---------------------------------------------------------------- limit transition

  /** Entering a slower zone (60 -> 40) while going 60 km/h must immediately warn. */
  @Test
  public void limitDropFrom60To40TriggersWarning()
  {
    assertEquals(SpeedLimitState.State.NORMAL,
                 mState.evaluate(kmh(60), kmh(60), 5.0, 0L).state);

    SpeedLimitState.Result after = mState.evaluate(kmh(40), kmh(60), 5.0, 1_000L);
    assertEquals(SpeedLimitState.State.EXCEEDED, after.state);
    assertTrue(after.justExceeded);
    assertEquals(kmh(20), after.overLimitMps, 1e-6);
  }

  /** Leaving a slow zone must clear the warning. */
  @Test
  public void limitRiseFrom40To60ClearsWarning()
  {
    mState.evaluate(kmh(40), kmh(50), 5.0, 0L);
    assertEquals(SpeedLimitState.State.EXCEEDED, mState.getState());
    assertEquals(SpeedLimitState.State.NORMAL,
                 mState.evaluate(kmh(60), kmh(50), 5.0, 1_000L).state);
  }

  // ---------------------------------------------------------------- accuracy

  /** A bad fix must suppress the warning and not emit a voice event. */
  @Test
  public void poorAccuracySuppressesWarning()
  {
    SpeedLimitState.Result r = mState.evaluate(kmh(60), kmh(80), 80.0, 0L);
    assertTrue("accuracy > 50 m must be reported as suppressed", r.suppressedByAccuracy);
    assertFalse("no voice event from a bad fix", r.justExceeded);
  }

  /** Recovering a good fix while still speeding must be allowed to warn. */
  @Test
  public void warningResumesAfterAccuracyRecovers()
  {
    mState.evaluate(kmh(60), kmh(80), 80.0, 0L);
    SpeedLimitState.Result r = mState.evaluate(kmh(60), kmh(80), 5.0, 1_000L);
    assertEquals(SpeedLimitState.State.EXCEEDED, r.state);
    assertTrue(r.justExceeded);
  }

  // ---------------------------------------------------------------- repeated updates + cooldown

  /** Repeated identical updates must not spam voice events. */
  @Test
  public void repeatedUpdatesDoNotReEmitVoice()
  {
    SpeedLimitState.Result first = mState.evaluate(kmh(60), kmh(70), 5.0, 0L);
    assertTrue(mState.tryConsumeVoiceEvent(first, 0L));

    for (long t = 1_000; t <= 20_000; t += 1_000)
    {
      SpeedLimitState.Result r = mState.evaluate(kmh(60), kmh(70), 5.0, t);
      assertFalse("steady speeding must not re-trigger", r.justExceeded);
      assertFalse(mState.tryConsumeVoiceEvent(r, t));
    }
  }

  /** After dropping out and exceeding again within the cooldown, voice stays silent. */
  @Test
  public void voiceCooldownEnforced()
  {
    SpeedLimitState.Result a = mState.evaluate(kmh(60), kmh(70), 5.0, 0L);
    assertTrue(mState.tryConsumeVoiceEvent(a, 0L));

    // Slow down, then speed up again after 10 s (cooldown is 30 s).
    mState.evaluate(kmh(60), kmh(55), 5.0, 8_000L);
    SpeedLimitState.Result b = mState.evaluate(kmh(60), kmh(70), 5.0, 10_000L);
    assertTrue("state should re-enter EXCEEDED", b.justExceeded);
    assertFalse("but voice must respect the 30 s cooldown", mState.tryConsumeVoiceEvent(b, 10_000L));

    // After the cooldown elapses and the state flaps again, voice is allowed.
    mState.evaluate(kmh(60), kmh(55), 5.0, 40_000L);
    SpeedLimitState.Result c = mState.evaluate(kmh(60), kmh(70), 5.0, 41_000L);
    assertTrue(c.justExceeded);
    assertTrue(mState.tryConsumeVoiceEvent(c, 41_000L));
  }

  // ---------------------------------------------------------------- map-matching segment change

  /**
   * When map matching moves to a segment with a different limit, the state must follow the new
   * limit rather than keeping the old one.
   */
  @Test
  public void segmentChangeUsesNewLimit()
  {
    mState.evaluate(kmh(60), kmh(58), 5.0, 0L);
    assertEquals(SpeedLimitState.State.NORMAL, mState.getState());

    // New segment: 30 km/h zone, still doing 58.
    SpeedLimitState.Result r = mState.evaluate(kmh(30), kmh(58), 5.0, 1_000L);
    assertEquals(SpeedLimitState.State.EXCEEDED, r.state);
    assertEquals(kmh(30), r.speedLimitMps, 1e-6);

    // New segment has no limit data at all.
    assertEquals(SpeedLimitState.State.NO_LIMIT,
                 mState.evaluate(-1.0, kmh(58), 5.0, 2_000L).state);
  }

  // ---------------------------------------------------------------- reset

  @Test
  public void resetClearsHysteresisAndCooldown()
  {
    SpeedLimitState.Result r = mState.evaluate(kmh(60), kmh(70), 5.0, 0L);
    assertTrue(mState.tryConsumeVoiceEvent(r, 0L));

    mState.reset();
    assertEquals(SpeedLimitState.State.NO_LIMIT, mState.getState());

    SpeedLimitState.Result again = mState.evaluate(kmh(60), kmh(70), 5.0, 1_000L);
    assertTrue("cooldown must be cleared by reset", mState.tryConsumeVoiceEvent(again, 1_000L));
  }

  // ---------------------------------------------------------------- config validation

  @Test(expected = IllegalArgumentException.class)
  public void rejectsStopOffsetNotBelowStartOffset()
  {
    new SpeedLimitState.Config(START, START, STOP, 50.0, 30_000L);
  }

  @Test
  public void customOffsetsAreRespected()
  {
    // Warn at +10 km/h, stop at +0.1 km/h.
    SpeedLimitState custom = new SpeedLimitState(new SpeedLimitState.Config(kmh(10), kmh(0.1), kmh(5), 50.0,
                                                                        30_000L));
    assertEquals(SpeedLimitState.State.NEAR_LIMIT, custom.evaluate(kmh(60), kmh(69), 5.0, 0L).state);
    assertEquals(SpeedLimitState.State.EXCEEDED, custom.evaluate(kmh(60), kmh(70), 5.0, 1_000L).state);
  }
}
