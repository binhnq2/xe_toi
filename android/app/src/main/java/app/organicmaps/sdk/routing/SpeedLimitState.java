package app.organicmaps.sdk.routing;

import androidx.annotation.NonNull;

/**
 * Runtime state of the speed-limit indicator shown during navigation.
 *
 * <p>This is pure logic with no Android framework dependency so it can be unit-tested on the JVM.
 * It exists because the baseline {@code NavigationController.updateSpeedLimit()} compared
 * {@code info.speedLimitMps < location.getSpeed()} directly, which is always true when
 * {@code speedLimitMps == -1} ("no data" sentinel, see {@code routing/following_info.hpp:87})
 * because {@code -1 < any positive speed}. That made the app report a speed violation on every
 * road without a speed limit.
 *
 * <p>Specification (TS8 Phase 5):
 * <ul>
 *   <li>Never infer a missing speed limit: if no limit is known, the state is
 *       {@link State#NO_LIMIT} and nothing is highlighted.</li>
 *   <li>Warning starts at {@code limit + startOffset}, stops below {@code limit + stopOffset}
 *       (hysteresis).</li>
 *   <li>Suppress the warning while location accuracy is worse than the configured maximum.</li>
 *   <li>Debounce voice announcements with a cooldown.</li>
 * </ul>
 */
public final class SpeedLimitState
{
  /** What the UI should render. */
  public enum State
  {
    /** No speed-limit data for the current road. Render the limit sign as absent/neutral. */
    NO_LIMIT,
    /** Speed is within the limit (below limit + start offset). White current speed. */
    NORMAL,
    /** Speed is near the threshold (>= start offset, before hysteresis trigger). Amber. */
    NEAR_LIMIT,
    /** Speed exceeded the hysteresis threshold. Red banner + over-limit delta. */
    EXCEEDED
  }

  /** Result of one evaluation step. Immutable so it is safe to hand to the UI thread. */
  public static final class Result
  {
    @NonNull
    public final State state;
    /** Current speed limit in meters per second, or -1 when unknown. */
    public final double speedLimitMps;
    /** Over-limit delta in meters per second; 0 unless {@link State#EXCEEDED}. */
    public final double overLimitMps;
    /** True only on the step where the state transitions into {@link State#EXCEEDED}. */
    public final boolean justExceeded;
    /** True when the warning is currently suppressed because of poor accuracy. */
    public final boolean suppressedByAccuracy;

    /**
     * Package-private on the wire, but public for the one legitimate caller that needs a neutral
     * default: {@code NavMenu} holds a "nothing known yet" result until the controller supplies a
     * real one. That must render as NO_LIMIT, never as a guessed state.
     */
    public Result(@NonNull State state, double speedLimitMps, double overLimitMps, boolean justExceeded,
                  boolean suppressedByAccuracy)
    {
      this.state = state;
      this.speedLimitMps = speedLimitMps;
      this.overLimitMps = overLimitMps;
      this.justExceeded = justExceeded;
      this.suppressedByAccuracy = suppressedByAccuracy;
    }
  }

  /** Configurable thresholds. Distances/speeds in SI units (meters, m/s). */
  public static final class Config
  {
    /** Offset above the limit at which the warning starts. */
    public final double startOffsetMps;
    /** Offset above the limit at which the warning stops (must be < startOffsetMps). */
    public final double stopOffsetMps;
    /** Start showing the amber "near limit" hint at this offset. */
    public final double nearOffsetMps;
    /** Suppress the warning when horizontal accuracy is worse than this many meters. */
    public final double maxAccuracyMeters;
    /** Minimum time between two "exceeded" events (ms). */
    public final long voiceCooldownMs;

    public Config(double startOffsetMps, double stopOffsetMps, double nearOffsetMps,
                  double maxAccuracyMeters, long voiceCooldownMs)
    {
      if (startOffsetMps <= 0)
        throw new IllegalArgumentException("startOffsetMps must be > 0");
      if (stopOffsetMps < 0 || stopOffsetMps >= startOffsetMps)
        throw new IllegalArgumentException("stopOffsetMps must be in [0, startOffsetMps)");
      if (nearOffsetMps < 0 || nearOffsetMps >= startOffsetMps)
        throw new IllegalArgumentException("nearOffsetMps must be in [0, startOffsetMps)");
      this.startOffsetMps = startOffsetMps;
      this.stopOffsetMps = stopOffsetMps;
      this.nearOffsetMps = nearOffsetMps;
      this.maxAccuracyMeters = maxAccuracyMeters;
      this.voiceCooldownMs = voiceCooldownMs;
    }

    /**
     * Default per TS8 spec: warn at limit+5 km/h, stop at limit+2 km/h, amber hint at limit+2 km/h,
     * suppress above 50 m accuracy, 30 s voice cooldown.
     */
    @NonNull
    public static Config defaults()
    {
      return new Config(5.0 / 3.6, 2.0 / 3.6, 2.0 / 3.6, 50.0, 30_000L);
    }
  }

  private static final double NO_LIMIT_SENTINEL = 0.0;

  /**
   * Thresholds come from different floating-point divisions ({@code 65/3.6 - 60/3.6} is not
   * bit-identical to {@code 5/3.6}), so an exact {@code >=} can miss the boundary by one ulp.
   * A driver sitting exactly on the threshold must still get the warning, so compare with a
   * 1e-6 m/s tolerance (~0.004 km/h).
   */
  private static final double SPEED_EPSILON = 1e-6;

  private static boolean atLeast(double value, double threshold)
  {
    return value + SPEED_EPSILON >= threshold;
  }

  private final Config mConfig;
  private State mState = State.NO_LIMIT;
  private long mLastVoiceAtMs = Long.MIN_VALUE;

  public SpeedLimitState(@NonNull Config config)
  {
    mConfig = config;
  }

  public SpeedLimitState()
  {
    this(Config.defaults());
  }

  @NonNull
  public Config getConfig()
  {
    return mConfig;
  }

  /**
   * Evaluates one navigation update.
   *
   * @param speedLimitMps      limit from {@code RoutingInfo.speedLimitMps}; a negative value
   *                           means "no data" and must be passed through unchanged.
   * @param speedMps           current GPS speed. Negative means "unknown".
   * @param accuracyMeters     horizontal accuracy; a non-positive value means "unknown".
   * @param nowMs              monotonic clock for the cooldown.
   */
  @NonNull
  public Result evaluate(double speedLimitMps, double speedMps, double accuracyMeters, long nowMs)
  {
    // Never infer a missing speed limit. Both the explicit negative sentinel and a literal zero
    // (which routing uses for "no restriction") are treated as "no sign to display".
    if (speedLimitMps <= NO_LIMIT_SENTINEL || speedMps < 0)
    {
      mState = State.NO_LIMIT;
      return new Result(State.NO_LIMIT, speedLimitMps, 0, false, false);
    }

    // Poor accuracy: do not change the warning state, just report it as suppressed so the UI can
    // avoid showing a banner based on a bad fix. Keep the previous state for hysteresis stability.
    if (accuracyMeters > mConfig.maxAccuracyMeters)
    {
      final boolean exceeded = (mState == State.EXCEEDED);
      return new Result(mState, speedLimitMps, exceeded ? Math.max(0, speedMps - speedLimitMps) : 0, false,
                        true);
    }

    final double over = speedMps - speedLimitMps;
    final State previous = mState;
    final State next;

    if (previous == State.EXCEEDED)
    {
      // Hysteresis: leaving the warning requires dropping below limit + stopOffset.
      next = atLeast(speedMps, speedLimitMps + mConfig.stopOffsetMps) ? State.EXCEEDED
                                                                     : State.NORMAL;
    }
    else
    {
      if (atLeast(speedMps, speedLimitMps + mConfig.startOffsetMps))
        next = State.EXCEEDED;
      else if (atLeast(speedMps, speedLimitMps + mConfig.nearOffsetMps))
        next = State.NEAR_LIMIT;
      else
        next = State.NORMAL;
    }

    mState = next;
    final boolean justExceeded = (next == State.EXCEEDED && previous != State.EXCEEDED);
    final double overLimit = (next == State.EXCEEDED) ? Math.max(0, over) : 0;

    return new Result(next, speedLimitMps, overLimit, justExceeded, false);
  }

  /**
   * Returns true when an over-speed voice announcement is allowed now, and records the time.
   * Call only after {@link Result#justExceeded} is true.
   */
  public boolean tryConsumeVoiceEvent(@NonNull Result result, long nowMs)
  {
    if (!result.justExceeded)
      return false;
    if (mLastVoiceAtMs != Long.MIN_VALUE && nowMs - mLastVoiceAtMs < mConfig.voiceCooldownMs)
      return false;
    mLastVoiceAtMs = nowMs;
    return true;
  }

  /** Resets hysteresis and cooldown, e.g. when a new route starts. */
  public void reset()
  {
    mState = State.NO_LIMIT;
    mLastVoiceAtMs = Long.MIN_VALUE;
  }

  @NonNull
  public State getState()
  {
    return mState;
  }
}
