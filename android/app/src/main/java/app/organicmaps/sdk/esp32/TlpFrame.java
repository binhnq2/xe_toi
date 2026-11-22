package app.organicmaps.sdk.esp32;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.Map;

/**
 * One TLP/1 frame (design spec §11, ESP32 configuration contract).
 *
 * <p>Frame shape: UTF-8 JSON, one object per line, never over 1024 bytes. Mandatory envelope
 * fields: {@code tlp} (protocol version), {@code seq} (monotonic sequence), {@code ts} (monotonic
 * ms), {@code type}.
 *
 * <p>Parsing is defensive by contract: malformed, oversized, wrong-version and duplicate frames are
 * rejected with a reason rather than trusted. Uses {@link TlpJson} rather than {@code org.json} so
 * every rule is unit-testable on the JVM (the platform {@code JSONObject} is a "not mocked" stub in
 * local tests).
 *
 * <p>The encoder takes a pre-built JSON string body rather than an object, so no serialiser is
 * needed at runtime either.
 */
public final class TlpFrame
{
  /** Protocol version this app speaks. */
  public static final int VERSION = 1;
  /** Hard limit from the contract: a longer frame is dropped, never truncated. */
  public static final int MAX_BYTES = 1024;

  public static final String TYPE_HELLO = "hello";
  public static final String TYPE_READY = "ready";
  public static final String TYPE_STATE = "state";
  public static final String TYPE_CONFIG_SUMMARY = "config.summary";
  public static final String TYPE_CONFIG_GET = "config.get";
  public static final String TYPE_CONFIG_SET = "config.set";
  public static final String TYPE_CONFIG_RESULT = "config.result";
  public static final String TYPE_ACK = "ack";
  public static final String TYPE_PING = "ping";
  public static final String TYPE_PONG = "pong";

  public enum Error
  {
    OK,
    TOO_LARGE,
    NOT_JSON,
    NOT_OBJECT,
    MISSING_VERSION,
    WRONG_VERSION,
    MISSING_SEQ,
    MISSING_TYPE,
    DUPLICATE
  }

  public final int seq;
  public final long ts;
  @NonNull
  public final String type;
  @NonNull
  public final Map<String, TlpJson.Value> body;

  private TlpFrame(int seq, long ts, @NonNull String type, @NonNull Map<String, TlpJson.Value> body)
  {
    this.seq = seq;
    this.ts = ts;
    this.type = type;
    this.body = body;
  }

  /** Result of a parse attempt: either a frame or a reason. Exactly one is meaningful. */
  public static final class Parsed
  {
    @Nullable
    public final TlpFrame frame;
    @NonNull
    public final Error error;

    Parsed(@Nullable TlpFrame frame, @NonNull Error error)
    {
      this.frame = frame;
      this.error = error;
    }

    public boolean isOk()
    {
      return error == Error.OK && frame != null;
    }
  }

  @NonNull
  public static Parsed ok(@NonNull TlpFrame frame)
  {
    return new Parsed(frame, Error.OK);
  }

  @NonNull
  public static Parsed err(@NonNull Error error)
  {
    return new Parsed(null, error);
  }

  /**
   * Parses one line.
   *
   * @param line    raw line without the trailing newline
   * @param lastSeq last sequence accepted from this peer, or -1 for the first frame
   */
  @NonNull
  public static Parsed parse(@Nullable String line, int lastSeq)
  {
    if (line == null)
      return err(Error.NOT_JSON);

    // The limit is on UTF-8 bytes: a Vietnamese label is multi-byte, so counting characters would
    // let an oversized frame through.
    if (line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_BYTES)
      return err(Error.TOO_LARGE);

    final Map<String, TlpJson.Value> obj = TlpJson.parseObject(line);
    if (obj == null)
      return err(Error.NOT_JSON);

    final TlpJson.Value versionValue = obj.get("tlp");
    if (versionValue == null)
      return err(Error.MISSING_VERSION);
    final int version = versionValue.asInt(-1);
    if (version < 0)
      return err(Error.MISSING_VERSION);
    if (version != VERSION)
      return err(Error.WRONG_VERSION);

    final TlpJson.Value seqValue = obj.get("seq");
    if (seqValue == null)
      return err(Error.MISSING_SEQ);
    final int seq = seqValue.asInt(-1);
    if (seq < 0)
      return err(Error.MISSING_SEQ);

    // The contract allows one retry, so a repeated sequence must be idempotent on the receiver.
    if (lastSeq >= 0 && seq <= lastSeq)
      return err(Error.DUPLICATE);

    final TlpJson.Value typeValue = obj.get("type");
    final String type = typeValue == null ? null : typeValue.asString();
    if (type == null || type.isEmpty())
      return err(Error.MISSING_TYPE);

    final TlpJson.Value tsValue = obj.get("ts");
    final long ts = tsValue == null ? 0L : tsValue.asLong(0L);

    return ok(new TlpFrame(seq, ts, type, obj));
  }

  /**
   * Builds a frame line.
   *
   * @param bodyJson a JSON object literal without the outer braces, e.g. {@code "key":"value"}
   *                 or an empty string for no extra fields
   */
  @NonNull
  public static String encode(int seq, long ts, @NonNull String type, @NonNull String bodyJson)
  {
    final StringBuilder sb = new StringBuilder(64 + bodyJson.length());
    sb.append("{\"tlp\":").append(VERSION)
      .append(",\"seq\":").append(seq)
      .append(",\"ts\":").append(ts)
      .append(",\"type\":\"").append(escape(type)).append('"');
    if (!bodyJson.isEmpty())
      sb.append(',').append(bodyJson);
    sb.append('}');
    return sb.toString();
  }

  public boolean isHello()
  {
    return TYPE_HELLO.equals(type);
  }

  public boolean isHeartbeat()
  {
    return TYPE_PING.equals(type) || TYPE_PONG.equals(type);
  }

  @Nullable
  public TlpJson.Value get(@NonNull String key)
  {
    return body.get(key);
  }

  /**
   * Redacts secret fields before anything is logged or exported.
   *
   * <p>The contract is explicit: Wi-Fi passwords and activation tokens are write-only, never
   * readable back, never logged and never in diagnostics. This is the single choke point that makes
   * that true, applied to the raw line rather than the parsed map so it also covers fields this
   * class does not model.
   */
  @NonNull
  public static String redact(@NonNull String json)
  {
    return json.replaceAll(
        "(\"(?:password|passwd|pwd|psk|token|activation_token|secret)\"\\s*:\\s*)\"[^\"]*\"",
        "$1\"***\"");
  }

  @NonNull
  private static String escape(@NonNull String s)
  {
    final StringBuilder sb = new StringBuilder(s.length() + 8);
    for (int i = 0; i < s.length(); i++)
    {
      final char c = s.charAt(i);
      if (c == '"' || c == '\\')
        sb.append('\\').append(c);
      else if (c < 0x20)
        sb.append(String.format(java.util.Locale.US, "\\u%04x", (int) c));
      else
        sb.append(c);
    }
    return sb.toString();
  }
}
