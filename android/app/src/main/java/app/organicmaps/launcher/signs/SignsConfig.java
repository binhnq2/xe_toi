package app.organicmaps.launcher.signs;

/**
 * Endpoint + storage configuration for the traffic signs layer.
 *
 * <p>Only the URL has to change when the data provider changes: the JSON contract is described in
 * {@link SignsRepository}. Keep this class free of logic so swapping the source stays a one-line
 * edit.
 */
public final class SignsConfig
{
  /**
   * Signs endpoint. Expected body:
   *
   * <pre>
   * { "version": 1,
   *   "signs": [ { "id": "abc", "lat": 21.02, "lon": 105.85,
   *                "type": "speed_limit", "value": 60, "name": "QL1 - 60" } ] }
   * </pre>
   *
   * <p>10.0.2.2 is the host loopback as seen from the emulator, used for the local test fixture.
   * Replace it with the real endpoint; an https URL is expected in production.
   */
  public static final String SIGNS_URL = "http://10.0.2.2:8765/signs.json";

  /** Bookmark category the imported signs live in; also used to replace a previous import. */
  public static final String CATEGORY_NAME = "Biển báo VN";

  /** Cached copy of the last successful download, kept for offline use. */
  public static final String CACHE_FILE = "signs.json";

  /** SharedPreferences name/key for the HTTP ETag, so an unchanged file is not re-imported. */
  public static final String PREFS = "ts8_signs";
  public static final String PREF_ETAG = "etag";

  private SignsConfig() {}
}
