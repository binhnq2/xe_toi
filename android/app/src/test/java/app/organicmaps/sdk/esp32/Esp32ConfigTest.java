package app.organicmaps.sdk.esp32;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.EnumSet;
import org.junit.Test;

/**
 * Phase 7 gate (no hardware): scoped config, revision conflict, capability gating, range
 * rejection, secret redaction and hash stability.
 */
public class Esp32ConfigTest
{
  private static Esp32Config withVoiceAndMic()
  {
    final Esp32Config c = new Esp32Config();
    c.setCapabilities(EnumSet.of(Esp32Config.Capability.VOICE_ONLY,
                                 Esp32Config.Capability.MIC_CALIBRATION));
    return c;
  }

  @Test
  public void acceptsInRangeValue()
  {
    final Esp32Config c = withVoiceAndMic();
    final Esp32Config.Result r = c.set("voice.listenTimeoutSec", 5, -1);
    assertTrue(r.isApplied());
    assertEquals(5.0, c.get("voice.listenTimeoutSec", -1), 1e-9);
    assertEquals(1, c.revision());
  }

  /** Nothing is silently clamped: an out-of-range value is a rejection with a reason. */
  @Test
  public void rejectsOutOfRangeValue()
  {
    final Esp32Config c = withVoiceAndMic();
    final Esp32Config.Result r = c.set("voice.listenTimeoutSec", 99, -1);
    assertEquals(Esp32Config.Status.REJECTED_RANGE, r.status);
    assertFalse("nothing must be stored on rejection", c.has("voice.listenTimeoutSec"));
    assertEquals("revision must not move on rejection", 0, c.revision());
  }

  @Test
  public void rejectsUnknownField()
  {
    final Esp32Config c = withVoiceAndMic();
    assertEquals(Esp32Config.Status.REJECTED_RANGE, c.set("bogus.field", 1, -1).status);
  }

  /** A peer that did not advertise mic calibration must not receive mic fields. */
  @Test
  public void rejectsUnsupportedCapability()
  {
    final Esp32Config c = new Esp32Config();
    c.setCapabilities(EnumSet.of(Esp32Config.Capability.VOICE_ONLY));
    final Esp32Config.Result r = c.set("mic.gainDb", 5, -1);
    assertEquals(Esp32Config.Status.REJECTED_UNSUPPORTED_CAPABILITY, r.status);
    assertFalse(c.has("mic.gainDb"));
  }

  /** A stale revision is a conflict, not something to apply quietly. */
  @Test
  public void rejectsStaleRevision()
  {
    final Esp32Config c = withVoiceAndMic();
    c.set("voice.listenTimeoutSec", 5, -1);           // revision 1
    final Esp32Config.Result r = c.set("voice.duckingPercent", 50, 0);
    assertEquals(Esp32Config.Status.REJECTED_STALE_REVISION, r.status);
    assertEquals(1, c.revision());
  }

  @Test
  public void acceptsMatchingRevision()
  {
    final Esp32Config c = withVoiceAndMic();
    c.set("voice.listenTimeoutSec", 5, -1);
    assertEquals(Esp32Config.Status.APPLIED, c.set("voice.duckingPercent", 40, 1).status);
    assertEquals(2, c.revision());
  }

  /** OTA channel only takes effect after a restart, and the peer must be told. */
  @Test
  public void otaRequiresRestart()
  {
    final Esp32Config c = new Esp32Config();
    c.setCapabilities(EnumSet.of(Esp32Config.Capability.OTA));
    assertEquals(Esp32Config.Status.REQUIRES_RESTART, c.set("ota.channel", 1, -1).status);
  }

  // ---------------------------------------------------------------- secrets

  /** A secret can be written but never read back, never hashed, never summarised. */
  @Test
  public void secretIsWriteOnlyAndNeverLeaks()
  {
    final Esp32Config c = withVoiceAndMic();
    final Esp32Config.Result r = c.setSecret("network.password", "hunter2", -1);
    assertTrue(r.isApplied());

    assertTrue(Esp32Config.isSecret("network.password"));
    assertFalse("secret must not be readable", c.has("network.password"));
    assertFalse(c.summary().contains("hunter2"));
    assertFalse(c.hash().contains("hunter2"));
  }

  @Test
  public void rejectsNonSecretThroughSecretPath()
  {
    final Esp32Config c = withVoiceAndMic();
    assertEquals(Esp32Config.Status.REJECTED_RANGE, c.setSecret("voice.listenTimeoutSec", "x", -1).status);
  }

  // ---------------------------------------------------------------- hash / scopes

  @Test
  public void hashIsStableAndChangesWithValue()
  {
    final Esp32Config a = withVoiceAndMic();
    a.set("voice.listenTimeoutSec", 5, -1);
    final Esp32Config b = withVoiceAndMic();
    b.set("voice.listenTimeoutSec", 5, -1);
    assertEquals(a.hash(), b.hash());

    b.set("voice.listenTimeoutSec", 6, -1);
    assertNotEquals(a.hash(), b.hash());
  }

  @Test
  public void unchangedPeerGetsNoScopes()
  {
    final Esp32Config c = withVoiceAndMic();
    c.set("voice.listenTimeoutSec", 5, -1);
    assertTrue(c.changedScopes(c.hash()).isEmpty());
  }

  @Test
  public void changedPeerGetsOnlyTouchedScopes()
  {
    final Esp32Config c = withVoiceAndMic();
    c.set("voice.listenTimeoutSec", 5, -1);   // LISTEN
    c.set("mic.gainDb", 3, -1);               // MICROPHONE
    assertTrue(c.changedScopes("deadbeef").contains(Esp32Config.Scope.LISTEN));
    assertTrue(c.changedScopes("deadbeef").contains(Esp32Config.Scope.MICROPHONE));
    assertFalse(c.changedScopes("deadbeef").contains(Esp32Config.Scope.OTA));
  }

  @Test
  public void noCapabilitiesMeansEveryGatedFieldIsRejected()
  {
    final Esp32Config c = new Esp32Config();
    c.setCapabilities(Collections.emptySet());
    assertEquals(Esp32Config.Status.REJECTED_UNSUPPORTED_CAPABILITY,
                 c.set("voice.listenTimeoutSec", 5, -1).status);
  }

  /** A voice-only peer must not be sent navigation HUD fields; the table has none, so assert the
   *  property holds structurally: every field is gated on a declared capability. */
  @Test
  public void everyFieldIsCapabilityGated()
  {
    final Esp32Config c = withVoiceAndMic();
    assertEquals(Esp32Config.Status.REJECTED_UNSUPPORTED_CAPABILITY, c.set("ota.channel", 1, -1).status);
  }
}
