package app.organicmaps.sdk.launcher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Phase 1 gate: the navigation-state contract.
 *
 * <p>The design spec forbids inventing values for missing data, so most of these tests assert that
 * an absent value stays absent rather than becoming a plausible-looking number.
 */
public class NavigationStateTest
{
  // ---------------------------------------------------------------- phase mapping

  @Test
  public void idleWhenNothingIsHappening()
  {
    assertEquals(NavigationState.Phase.IDLE,
                 NavigationStateMapper.phaseFor(false, false, false, true));
  }

  @Test
  public void planningWhileBuilding()
  {
    assertEquals(NavigationState.Phase.PLANNING,
                 NavigationStateMapper.phaseFor(false, true, false, true));
  }

  @Test
  public void previewWhenBuiltButNotFollowing()
  {
    assertEquals(NavigationState.Phase.PREVIEW,
                 NavigationStateMapper.phaseFor(false, false, true, true));
  }

  @Test
  public void navigatingOnlyWithAUsableFix()
  {
    assertEquals(NavigationState.Phase.NAVIGATING,
                 NavigationStateMapper.phaseFor(true, false, true, true));
    assertEquals(NavigationState.Phase.NO_GPS,
                 NavigationStateMapper.phaseFor(true, false, true, false));
  }

  /** Building wins over following: a reroute must not keep claiming NAVIGATING. */
  @Test
  public void buildingWinsOverNavigating()
  {
    assertEquals(NavigationState.Phase.PLANNING,
                 NavigationStateMapper.phaseFor(true, true, true, true));
  }

  // ---------------------------------------------------------------- missing data

  /** A negative limit is the native "no data" sentinel; it must not become a number. */
  @Test
  public void negativeSpeedLimitMeansNoSign()
  {
    assertEquals(NavigationState.UNKNOWN, NavigationStateMapper.sanitiseSpeedLimitMps(-1.0), 1e-9);
    assertFalse(NavigationState.navigating(100, 5000, 300, "A", 0, 0, -1.0, 10, 50)
                    .hasSpeedLimit());
  }

  /** Zero is routing's "no restriction"; still no sign to draw. */
  @Test
  public void zeroSpeedLimitMeansNoSign()
  {
    assertFalse(NavigationState.navigating(100, 5000, 300, "A", 0, 0, 0.0, 10, 50).hasSpeedLimit());
  }

  @Test
  public void positiveSpeedLimitIsKept()
  {
    final double limit = 60.0 / 3.6;
    assertTrue(NavigationState.navigating(100, 5000, 300, "A", 0, 0, limit, 10, 50).hasSpeedLimit());
  }

  @Test
  public void unknownSpeedHasNoNumber()
  {
    assertFalse(NavigationState.navigating(100, 5000, 300, "A", 0, 0, 10, -1.0, 50).hasSpeed());
  }

  /** A glitch far above any vehicle speed is treated as no data, not as 900 km/h. */
  @Test
  public void absurdSpeedIsTreatedAsUnknown()
  {
    assertEquals(NavigationState.UNKNOWN, NavigationStateMapper.sanitiseSpeedMps(500.0), 1e-9);
    assertEquals(NavigationState.UNKNOWN, NavigationStateMapper.sanitiseSpeedMps(-3.0), 1e-9);
  }

  // ---------------------------------------------------------------- no-GPS transition

  @Test
  public void noGpsDropsSpeedButKeepsRoute()
  {
    final NavigationState s =
        NavigationStateMapper.navigating(120, 8000, 600, "Lê Duẩn", 3, 2, 13.9, 15.0, 40, false);
    assertEquals(NavigationState.Phase.NO_GPS, s.phase);
    assertFalse("speed must not be shown without a fix", s.hasSpeed());
    assertTrue("the route is still active", s.hasRoute);
    assertEquals("Lê Duẩn", s.nextStreet);
    assertEquals(2, s.exitNumber);
  }

  @Test
  public void followingOnlyInNavigationPhases()
  {
    assertTrue(NavigationState.navigating(1, 2, 3, null, 0, 0, 10, 10, 1).isFollowing());
    assertFalse(NavigationState.idle().isFollowing());
    assertFalse(NavigationState.planning().isFollowing());
    assertFalse(NavigationState.preview(100, 60, 10).isFollowing());
  }

  // ---------------------------------------------------------------- idle invariants

  @Test
  public void idleCarriesNoNumbers()
  {
    final NavigationState s = NavigationState.idle();
    assertEquals(NavigationState.UNKNOWN, s.distToTurnM, 1e-9);
    assertEquals(NavigationState.UNKNOWN, s.distToTargetM, 1e-9);
    assertEquals(NavigationState.UNKNOWN, s.speedLimitMps, 1e-9);
    assertFalse(s.hasRoute);
    assertFalse(s.hasSpeed());
  }
}
