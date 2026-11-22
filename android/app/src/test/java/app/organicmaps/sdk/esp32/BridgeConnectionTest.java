package app.organicmaps.sdk.esp32;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Phase 7 gate (no hardware): attach/detach, handshake, heartbeat, stale detection, backoff.
 */
public class BridgeConnectionTest
{
  private static String frame(int seq, String type)
  {
    return "{\"tlp\":1,\"seq\":" + seq + ",\"ts\":1,\"type\":\"" + type + "\"}";
  }

  @Test
  public void startsDisconnected()
  {
    assertEquals(BridgeConnection.State.DISCONNECTED, new BridgeConnection().state());
  }

  @Test
  public void attachThenHelloThenSyncReachesReady()
  {
    final BridgeConnection c = new BridgeConnection();
    c.onAttached(0);
    assertEquals(BridgeConnection.State.HANDSHAKING, c.state());

    assertNotNull(c.onFrame(frame(0, TlpFrame.TYPE_HELLO), 10));
    // Still handshaking: READY requires config sync, not just a hello.
    assertEquals(BridgeConnection.State.HANDSHAKING, c.state());

    c.markSynchronised();
    assertEquals(BridgeConnection.State.READY, c.state());
    c.resetBackoff();
  }

  @Test
  public void detachResetsToDisconnected()
  {
    final BridgeConnection c = new BridgeConnection();
    c.onAttached(0);
    c.onFrame(frame(0, TlpFrame.TYPE_HELLO), 10);
    c.markSynchronised();
    c.onDetached();
    assertEquals(BridgeConnection.State.DISCONNECTED, c.state());
  }

  /** A malformed frame is refused and leaves the state untouched. */
  @Test
  public void malformedFrameIsRefused()
  {
    final BridgeConnection c = new BridgeConnection();
    c.onAttached(0);
    assertNull(c.onFrame("{oops", 10));
    assertEquals(TlpFrame.Error.NOT_JSON, c.lastError());
    assertEquals(BridgeConnection.State.HANDSHAKING, c.state());
  }

  /** Duplicate frames are dropped (the contract allows exactly one retry). */
  @Test
  public void duplicateFrameIsDropped()
  {
    final BridgeConnection c = new BridgeConnection();
    c.onAttached(0);
    assertNotNull(c.onFrame(frame(3, TlpFrame.TYPE_STATE), 10));
    assertNull(c.onFrame(frame(3, TlpFrame.TYPE_STATE), 11));
    assertEquals(TlpFrame.Error.DUPLICATE, c.lastError());
  }

  @Test
  public void becomesStaleAfterThreeSeconds()
  {
    final BridgeConnection c = new BridgeConnection();
    c.onAttached(0);
    c.onFrame(frame(0, TlpFrame.TYPE_HELLO), 10);
    c.markSynchronised();

    c.tick(2_000);
    assertEquals("2 s is still fresh", BridgeConnection.State.READY, c.state());

    c.tick(4_000);
    assertEquals(BridgeConnection.State.STALE, c.state());
  }

  @Test
  public void freshFrameClearsStale()
  {
    final BridgeConnection c = new BridgeConnection();
    c.onAttached(0);
    c.onFrame(frame(0, TlpFrame.TYPE_HELLO), 10);
    c.markSynchronised();
    c.tick(5_000);
    assertEquals(BridgeConnection.State.STALE, c.state());

    assertNotNull(c.onFrame(frame(1, TlpFrame.TYPE_STATE), 6_000));
    assertEquals(BridgeConnection.State.READY, c.state());
  }

  @Test
  public void heartbeatIsSentOncePerSecond()
  {
    final BridgeConnection c = new BridgeConnection();
    c.onAttached(0);
    c.onFrame(frame(0, TlpFrame.TYPE_HELLO), 10);
    c.markSynchronised();

    assertTrue(c.shouldSendHeartbeat(1_000));
    c.onHeartbeatSent(1_000);
    assertTrue("must not repeat within the same second", !c.shouldSendHeartbeat(1_500));
    assertTrue(c.shouldSendHeartbeat(2_000));
  }

  /** The backoff ladder is exactly the contract's, and does not grow past 15 s. */
  @Test
  public void backoffLadderMatchesContract()
  {
    final long[] expected = {500, 1000, 2000, 4000, 8000, 15000};
    final long[] actual = BridgeConnection.backoffLadder();
    assertEquals(expected.length, actual.length);
    for (int i = 0; i < expected.length; i++)
      assertEquals(expected[i], actual[i]);

    final BridgeConnection c = new BridgeConnection();
    for (long e : expected)
      assertEquals(e, c.nextReconnectDelayMs());
    assertEquals("capped, not growing", 15000, c.nextReconnectDelayMs());
  }

  @Test
  public void resetBackoffRestartsTheLadder()
  {
    final BridgeConnection c = new BridgeConnection();
    c.nextReconnectDelayMs();
    c.nextReconnectDelayMs();
    c.resetBackoff();
    assertEquals(500, c.nextReconnectDelayMs());
  }

  @Test
  public void sequenceIsMonotonic()
  {
    final BridgeConnection c = new BridgeConnection();
    assertEquals(0, c.nextSequence());
    assertEquals(1, c.nextSequence());
    assertEquals(2, c.nextSequence());
  }

  /** No device: every call is safe and the state never claims READY. */
  @Test
  public void noDeviceOperationIsSafe()
  {
    final BridgeConnection c = new BridgeConnection();
    c.tick(100_000);
    assertEquals(BridgeConnection.State.DISCONNECTED, c.state());
    assertNull(c.onFrame(null, 100_000));
    c.onDetached();
    assertEquals(BridgeConnection.State.DISCONNECTED, c.state());
  }
}
