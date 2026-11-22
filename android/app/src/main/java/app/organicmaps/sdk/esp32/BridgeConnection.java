package app.organicmaps.sdk.esp32;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Arrays;

/**
 * Transport-independent connection state machine for the ESP32 link (design spec §11).
 *
 * <p>Kept free of USB and Android so the reconnect, heartbeat and staleness rules are unit-testable
 * without a device, which is exactly what the Phase 7 gate allows ("never require physical ESP32
 * for automated gate").
 *
 * <p>Contract values implemented: heartbeat every 1 s, stale after 3 s, exponential reconnect at
 * 0.5/1/2/4/8/15 s (capped), one retry per command, command-id deduplication.
 */
public final class BridgeConnection
{
  public enum State
  {
    /** No device attached. */
    DISCONNECTED,
    /** USB attached, waiting for the peer's {@code hello}. */
    HANDSHAKING,
    /** Handshake done, configuration synchronised, frames flow both ways. */
    READY,
    /** Link was READY and has gone quiet past the stale threshold. */
    STALE
  }

  /** Reconnect backoff ladder from the contract, in milliseconds. The last value repeats. */
  @NonNull
  private static final long[] BACKOFF_MS = {500, 1000, 2000, 4000, 8000, 15000};

  public static final long HEARTBEAT_MS = 1000L;
  public static final long STALE_MS = 3000L;

  @NonNull
  private State mState = State.DISCONNECTED;
  private int mBackoffIndex;
  private long mLastFrameAtMs;
  private int mLastSeq = -1;
  private int mNextSeq;
  private boolean mHandshakeDone;

  @NonNull
  public State state()
  {
    return mState;
  }

  /** Called when the OS reports the USB device attached. */
  public void onAttached(long nowMs)
  {
    mState = State.HANDSHAKING;
    mHandshakeDone = false;
    mLastSeq = -1;
    mLastFrameAtMs = nowMs;
  }

  /** Called when the device detaches or the transport errors. */
  public void onDetached()
  {
    mState = State.DISCONNECTED;
    mHandshakeDone = false;
    mLastSeq = -1;
  }

  /**
   * Consumes a received frame.
   *
   * @return the frame when it is acceptable and advances the machine, else null with
   *         {@link #lastError()} explaining why
   */
  @Nullable
  public TlpFrame onFrame(@Nullable String line, long nowMs)
  {
    final TlpFrame.Parsed parsed = TlpFrame.parse(line, mLastSeq);
    if (!parsed.isOk())
    {
      mLastError = parsed.error;
      return null;
    }
    mLastError = null;
    mLastFrameAtMs = nowMs;
    mLastSeq = parsed.frame.seq;

    if (parsed.frame.isHello() && !mHandshakeDone)
    {
      mHandshakeDone = true;
      // READY is only entered after config sync; the caller calls markSynchronised() once the
      // boot reconciliation frame has been sent. Entering READY here would skip that step.
      mState = State.HANDSHAKING;
    }
    if (!parsed.frame.isHeartbeat() && mState == State.STALE)
      mState = mHandshakeDone ? State.READY : State.HANDSHAKING;

    return parsed.frame;
  }

  /** Called by the config layer once the boot reconciliation has been sent. */
  public void markSynchronised()
  {
    if (mHandshakeDone)
      mState = State.READY;
  }

  /** Drives the stale timer; call from the UI heartbeat tick. */
  public void tick(long nowMs)
  {
    if (mState == State.READY && nowMs - mLastFrameAtMs > STALE_MS)
      mState = State.STALE;
  }

  /** True when a heartbeat should be sent now. */
  public boolean shouldSendHeartbeat(long nowMs)
  {
    return mState == State.READY || mState == State.STALE
        ? nowMs - mLastHeartbeatAtMs >= HEARTBEAT_MS
        : false;
  }

  private long mLastHeartbeatAtMs;

  public void onHeartbeatSent(long nowMs)
  {
    mLastHeartbeatAtMs = nowMs;
  }

  /**
   * Delay before the next reconnect attempt, and advances the ladder.
   *
   * <p>Capped at the last entry: the contract says 15 s is the ceiling, not that it keeps growing.
   */
  public long nextReconnectDelayMs()
  {
    final long d = BACKOFF_MS[Math.min(mBackoffIndex, BACKOFF_MS.length - 1)];
    if (mBackoffIndex < BACKOFF_MS.length - 1)
      mBackoffIndex++;
    return d;
  }

  /** Resets the backoff ladder after a successful handshake. */
  public void resetBackoff()
  {
    mBackoffIndex = 0;
  }

  public int nextSequence()
  {
    return mNextSeq++;
  }

  @Nullable
  private TlpFrame.Error mLastError;

  @Nullable
  public TlpFrame.Error lastError()
  {
    return mLastError;
  }

  @NonNull
  static long[] backoffLadder()
  {
    return Arrays.copyOf(BACKOFF_MS, BACKOFF_MS.length);
  }
}
