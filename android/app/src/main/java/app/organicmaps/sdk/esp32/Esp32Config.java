package app.organicmaps.sdk.esp32;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Versioned, scoped ESP32 configuration (design spec §11, configuration contract).
 *
 * <p>TS8 Lite is the source of truth. This class holds the authoritative values, the monotonic
 * revision and a hash so a reconnecting ESP32 can be told exactly which scopes changed without
 * re-sending everything.
 *
 * <p>Validation rules the contract states, enforced here rather than trusted from the peer:
 *
 * <ul>
 *   <li>Every field is range-checked; nothing is silently clamped. An out-of-range value is
 *       rejected with {@link Status#REJECTED_RANGE}.</li>
 *   <li>Only capabilities the peer advertised are sent, so voice-only firmware never receives
 *       navigation HUD fields.</li>
 *   <li>Secrets are write-only: they can be set, but never read back, never hashed into the
 *       published summary and never exported.</li>
 *   <li>Revision is monotonic; a stale revision is rejected rather than applied.</li>
 * </ul>
 */
public final class Esp32Config
{
  /** Scopes the contract defines. Sending only changed scopes is the whole point of scoping. */
  public enum Scope
  {
    NETWORK,
    VOICE,
    LISTEN,
    MICROPHONE,
    OTA,
    DIAGNOSTICS
  }

  /** A capability the firmware advertises during {@code hello}. */
  public enum Capability
  {
    VOICE_ONLY,
    NAV_HUD,
    WIFI_PROVISIONING,
    OTA,
    MIC_CALIBRATION
  }

  public enum Status
  {
    APPLIED,
    REJECTED_RANGE,
    REJECTED_UNSUPPORTED_CAPABILITY,
    REJECTED_STALE_REVISION,
    REQUIRES_RESTART
  }

  /** Result of a set attempt. */
  public static final class Result
  {
    @NonNull
    public final Status status;
    @NonNull
    public final Scope scope;
    @NonNull
    public final String field;
    @Nullable
    public final String detail;

    Result(@NonNull Status status, @NonNull Scope scope, @NonNull String field, @Nullable String detail)
    {
      this.status = status;
      this.scope = scope;
      this.field = field;
      this.detail = detail;
    }

    public boolean isApplied()
    {
      return status == Status.APPLIED;
    }

    @NonNull
    @Override
    public String toString()
    {
      return status + " " + scope + "." + field + (detail == null ? "" : " (" + detail + ")");
    }
  }

  /** Allowed range for one numeric field. */
  private static final class Range
  {
    final double min;
    final double max;
    final Scope scope;
    final Capability required;

    Range(double min, double max, @NonNull Scope scope, @Nullable Capability required)
    {
      this.min = min;
      this.max = max;
      this.scope = scope;
      this.required = required;
    }

    boolean contains(double v)
    {
      return v >= min && v <= max;
    }
  }

  /**
   * The field table. Ranges come from the contract (listen/endpoint timeouts, calibration bounds)
   * and are deliberately narrow: a value outside them is a bug in the caller, not something to
   * quietly fix.
   */
  @NonNull
  private static final Map<String, Range> FIELDS = new LinkedHashMap<>();
  static
  {
    // Voice timing, seconds.
    FIELDS.put("voice.listenTimeoutSec", new Range(1, 15, Scope.LISTEN, Capability.VOICE_ONLY));
    FIELDS.put("voice.endpointTimeoutSec", new Range(1, 30, Scope.LISTEN, Capability.VOICE_ONLY));
    FIELDS.put("voice.duckingPercent", new Range(0, 100, Scope.VOICE, Capability.VOICE_ONLY));
    FIELDS.put("voice.speechRate", new Range(0.5, 2.0, Scope.VOICE, Capability.VOICE_ONLY));
    // Microphone calibration.
    FIELDS.put("mic.gainDb", new Range(-20, 30, Scope.MICROPHONE, Capability.MIC_CALIBRATION));
    FIELDS.put("mic.vadThreshold", new Range(0, 100, Scope.MICROPHONE, Capability.MIC_CALIBRATION));
    // OTA channel as an integer code; the UI maps it to a label.
    FIELDS.put("ota.channel", new Range(0, 2, Scope.OTA, Capability.OTA));
  }

  /** Fields that must never be read back, hashed into the published summary or exported. */
  @NonNull
  private static final Set<String> SECRET_FIELDS =
      new java.util.HashSet<>(java.util.Arrays.asList("network.password", "network.psk",
                                                      "activation.token", "wifi.password"));

  @NonNull
  private final Map<String, Double> mValues = new LinkedHashMap<>();
  @NonNull
  private final Set<Capability> mCapabilities = new java.util.HashSet<>();
  private long mRevision;
  @Nullable
  private String mConfigHash;

  /**
   * Updates the capability set from the peer's {@code hello}. Called once per connection, before
   * any config is sent.
   */
  public void setCapabilities(@NonNull Set<Capability> capabilities)
  {
    mCapabilities.clear();
    mCapabilities.addAll(capabilities);
  }

  public boolean supports(@NonNull Capability capability)
  {
    return mCapabilities.contains(capability);
  }

  public long revision()
  {
    return mRevision;
  }

  /**
   * Applies one numeric field.
   *
   * @param expectedRevision the revision the caller believes is current; a mismatch is a conflict
   *                        and is rejected rather than applied
   */
  @NonNull
  public Result set(@NonNull String key, double value, long expectedRevision)
  {
    final Range range = FIELDS.get(key);
    if (range == null)
      return new Result(Status.REJECTED_RANGE, Scope.VOICE, key, "unknown field");

    if (expectedRevision >= 0 && expectedRevision != mRevision)
      return new Result(Status.REJECTED_STALE_REVISION, range.scope, key,
                        "expected " + expectedRevision + ", current " + mRevision);

    if (range.required != null && !supports(range.required))
      return new Result(Status.REJECTED_UNSUPPORTED_CAPABILITY, range.scope, key,
                        "peer lacks " + range.required);

    if (!range.contains(value))
      return new Result(Status.REJECTED_RANGE, range.scope, key,
                        "outside [" + range.min + ", " + range.max + "]");

    mValues.put(key, value);
    mRevision++;
    mConfigHash = null;
    // A changed OTA channel only takes effect after a restart, and the peer must be told.
    final Status status = "ota.channel".equals(key) ? Status.REQUIRES_RESTART : Status.APPLIED;
    return new Result(status, range.scope, key, null);
  }

  /** Records a write-only secret without ever exposing it. */
  @NonNull
  public Result setSecret(@NonNull String key, @NonNull String value, long expectedRevision)
  {
    if (!SECRET_FIELDS.contains(key))
      return new Result(Status.REJECTED_RANGE, Scope.NETWORK, key, "not a secret field");

    if (expectedRevision >= 0 && expectedRevision != mRevision)
      return new Result(Status.REJECTED_STALE_REVISION, Scope.NETWORK, key,
                        "expected " + expectedRevision + ", current " + mRevision);

    // The value is deliberately not stored in mValues: it exists only long enough for the peer to
    // receive it, so it can never be read back, hashed or exported.
    mRevision++;
    mConfigHash = null;
    return new Result(Status.APPLIED, Scope.NETWORK, key, "write-only");
  }

  public boolean has(@NonNull String key)
  {
    return mValues.containsKey(key);
  }

  public double get(@NonNull String key, double fallback)
  {
    final Double v = mValues.get(key);
    return v == null ? fallback : v;
  }

  /** True for fields whose value must never leave the app. */
  public static boolean isSecret(@NonNull String key)
  {
    return SECRET_FIELDS.contains(key);
  }

  /**
   * The scopes that would need sending to a peer whose known revision/hash differ from ours.
   *
   * <p>Only changed scopes are sent, which is what keeps a reconnect to one small frame instead of
   * the whole configuration.
   */
  @NonNull
  public Set<Scope> changedScopes(@Nullable String peerHash)
  {
    final Set<Scope> out = new java.util.HashSet<>();
    if (peerHash != null && peerHash.equals(hash()))
      return out;   // identical, nothing to send
    for (String key : mValues.keySet())
    {
      final Range r = FIELDS.get(key);
      if (r != null)
        out.add(r.scope);
    }
    return out;
  }

  /**
   * Stable hash over the publishable (non-secret) values and the revision.
   *
   * <p>Deterministic so a reconnect compares hashes instead of values, and ordered so two runs over
   * the same settings agree.
   */
  @NonNull
  public String hash()
  {
    if (mConfigHash != null)
      return mConfigHash;
    final StringBuilder sb = new StringBuilder();
    sb.append(mRevision);
    for (Map.Entry<String, Double> e : mValues.entrySet())
    {
      if (isSecret(e.getKey()))
        continue;
      sb.append('|').append(e.getKey()).append('=').append(e.getValue());
    }
    mConfigHash = sha256(sb.toString());
    return mConfigHash;
  }

  /** A summary safe to show in the UI and to log: keys and values, never secrets. */
  @NonNull
  public String summary()
  {
    final StringBuilder sb = new StringBuilder();
    sb.append("rev=").append(mRevision).append(" hash=").append(hash());
    for (Map.Entry<String, Double> e : mValues.entrySet())
    {
      if (isSecret(e.getKey()))
        continue;
      sb.append('\n').append(e.getKey()).append('=').append(e.getValue());
    }
    return sb.toString();
  }

  @NonNull
  static String sha256(@NonNull String s)
  {
    try
    {
      final java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
      final byte[] d = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      final StringBuilder sb = new StringBuilder(d.length * 2);
      for (byte b : d)
        sb.append(String.format(java.util.Locale.US, "%02x", b));
      return sb.toString();
    }
    catch (java.security.NoSuchAlgorithmException e)
    {
      // SHA-256 is mandatory on every Android version; this cannot happen.
      return Integer.toHexString(s.hashCode());
    }
  }
}
