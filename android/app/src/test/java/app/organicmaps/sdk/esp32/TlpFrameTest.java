package app.organicmaps.sdk.esp32;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Phase 7 gate (no hardware): frame acceptance, duplication, size limit, version, redaction.
 */
public class TlpFrameTest
{
  private static String frame(int seq, String type)
  {
    return "{\"tlp\":1,\"seq\":" + seq + ",\"ts\":1000,\"type\":\"" + type + "\"}";
  }

  @Test
  public void acceptsAWellFormedFrame()
  {
    final TlpFrame.Parsed p = TlpFrame.parse(frame(1, TlpFrame.TYPE_HELLO), -1);
    assertTrue(p.isOk());
    assertNotNull(p.frame);
    assertEquals(1, p.frame.seq);
    assertEquals(TlpFrame.TYPE_HELLO, p.frame.type);
    assertTrue(p.frame.isHello());
  }

  @Test
  public void rejectsMalformedJson()
  {
    assertEquals(TlpFrame.Error.NOT_JSON, TlpFrame.parse("{not json", -1).error);
    assertEquals(TlpFrame.Error.NOT_JSON, TlpFrame.parse("", -1).error);
    assertEquals(TlpFrame.Error.NOT_JSON, TlpFrame.parse(null, -1).error);
  }

  @Test
  public void rejectsWrongVersion()
  {
    final String v2 = "{\"tlp\":2,\"seq\":1,\"ts\":1,\"type\":\"hello\"}";
    assertEquals(TlpFrame.Error.WRONG_VERSION, TlpFrame.parse(v2, -1).error);
  }

  @Test
  public void rejectsMissingEnvelopeFields()
  {
    assertEquals(TlpFrame.Error.MISSING_VERSION,
                 TlpFrame.parse("{\"seq\":1,\"type\":\"hello\"}", -1).error);
    assertEquals(TlpFrame.Error.MISSING_SEQ,
                 TlpFrame.parse("{\"tlp\":1,\"type\":\"hello\"}", -1).error);
    assertEquals(TlpFrame.Error.MISSING_TYPE,
                 TlpFrame.parse("{\"tlp\":1,\"seq\":1}", -1).error);
  }

  /** The contract allows one retry; a repeated sequence must be dropped, not re-applied. */
  @Test
  public void rejectsDuplicateSequence()
  {
    assertEquals(TlpFrame.Error.DUPLICATE, TlpFrame.parse(frame(5, TlpFrame.TYPE_STATE), 5).error);
    assertEquals(TlpFrame.Error.DUPLICATE, TlpFrame.parse(frame(4, TlpFrame.TYPE_STATE), 5).error);
    assertTrue(TlpFrame.parse(frame(6, TlpFrame.TYPE_STATE), 5).isOk());
  }

  /** 1024 bytes is a hard limit on UTF-8 bytes, not characters. */
  @Test
  public void rejectsOversizedFrame()
  {
    final StringBuilder big = new StringBuilder("{\"tlp\":1,\"seq\":1,\"type\":\"state\",\"pad\":\"");
    while (big.length() < 1100)
      big.append('a');
    big.append("\"}");
    assertEquals(TlpFrame.Error.TOO_LARGE, TlpFrame.parse(big.toString(), -1).error);
  }

  /** Multi-byte content must be measured in bytes: 400 Vietnamese chars exceed 1024 bytes. */
  @Test
  public void sizeLimitCountsUtf8Bytes()
  {
    final StringBuilder big = new StringBuilder("{\"tlp\":1,\"seq\":1,\"type\":\"state\",\"s\":\"");
    for (int i = 0; i < 400; i++)
      big.append('ố');   // 3 bytes in UTF-8
    big.append("\"}");
    assertEquals(TlpFrame.Error.TOO_LARGE, TlpFrame.parse(big.toString(), -1).error);
  }

  @Test
  public void encodeAddsEnvelope()
  {
    final String s = TlpFrame.encode(7, 1234, TlpFrame.TYPE_PONG, "");
    assertTrue(s.contains("\"tlp\":1"));
    assertTrue(s.contains("\"seq\":7"));
    assertTrue(s.contains("\"type\":\"pong\""));
    assertTrue(TlpFrame.parse(s, -1).isOk());
  }

  @Test
  public void encodeCarriesBodyFields()
  {
    final String s = TlpFrame.encode(3, 9, TlpFrame.TYPE_HELLO, "\"fw\":\"1.2.3\",\"board\":\"n16r8\"");
    final TlpFrame.Parsed p = TlpFrame.parse(s, -1);
    assertTrue(p.isOk());
    assertEquals("1.2.3", p.frame.get("fw").asString());
    assertEquals("n16r8", p.frame.get("board").asString());
  }

  /** Secrets must never travel back out of the app, not even in a log line. */
  @Test
  public void redactsSecrets()
  {
    final String raw = "{\"wifi\":{\"password\":\"hunter2\",\"ssid\":\"TS8\"},\"activation_token\":\"abc123\"}";
    final String safe = TlpFrame.redact(raw);
    assertFalse("password must be redacted", safe.contains("hunter2"));
    assertFalse("token must be redacted", safe.contains("abc123"));
    assertTrue("ssid is not secret", safe.contains("TS8"));
  }

  @Test
  public void heartbeatClassification()
  {
    assertTrue(TlpFrame.parse(frame(1, TlpFrame.TYPE_PING), -1).frame.isHeartbeat());
    assertTrue(TlpFrame.parse(frame(1, TlpFrame.TYPE_PONG), -1).frame.isHeartbeat());
    assertFalse(TlpFrame.parse(frame(1, TlpFrame.TYPE_HELLO), -1).frame.isHeartbeat());
  }
}
