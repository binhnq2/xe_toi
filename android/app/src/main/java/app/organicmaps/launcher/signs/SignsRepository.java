package app.organicmaps.launcher.signs;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Downloads the signs file, keeps an offline copy and parses it.
 *
 * <p>Design notes:
 *
 * <ul>
 *   <li>The last good response is cached on disk, so the layer keeps working without network.
 *   <li>ETag / If-None-Match avoids re-parsing an unchanged file: a 304 answers with the cache.
 *   <li>Nothing here touches the UI or the bookmark manager; {@link SignsImporter} does that on the
 *       main thread.
 * </ul>
 */
public final class SignsRepository
{
  /** Outcome of a refresh, already phrased for the settings pane. */
  public static final class Result
  {
    public final int count;
    public final boolean fromCache;
    @NonNull
    public final String message;

    Result(int count, boolean fromCache, @NonNull String message)
    {
      this.count = count;
      this.fromCache = fromCache;
      this.message = message;
    }
  }

  private static final int TIMEOUT_MS = 20000;

  @NonNull
  private final File mCache;
  @NonNull
  private final SharedPreferences mPrefs;

  public SignsRepository(@NonNull Context context)
  {
    final Context app = context.getApplicationContext();
    final File dir = new File(app.getFilesDir(), "signs");
    //noinspection ResultOfMethodCallIgnored
    dir.mkdirs();
    mCache = new File(dir, SignsConfig.CACHE_FILE);
    mPrefs = app.getSharedPreferences(SignsConfig.PREFS, Context.MODE_PRIVATE);
  }

  public boolean hasCache()
  {
    return mCache.isFile() && mCache.length() > 0;
  }

  /**
   * Fetches the endpoint and returns the parsed signs, or the cached ones when the server says 304
   * or the request fails and a cache exists. Must be called off the UI thread.
   */
  @WorkerThread
  @NonNull
  public Result refresh(boolean force)
  {
    String body = null;
    boolean fromCache = false;
    try
    {
      body = download(force);
    }
    catch (Exception e)
    {
      if (hasCache())
      {
        body = readCache();
        fromCache = true;
      }
      else
        return new Result(0, false, "Không tải được: " + e.getMessage());
    }

    if (body == null)
    {
      if (!hasCache())
        return new Result(0, false, "Máy chủ không trả dữ liệu và chưa có bản lưu.");
      body = readCache();
      fromCache = true;
    }

    try
    {
      final List<SignPoint> signs = parse(body);
      return new Result(signs.size(), fromCache,
                        (fromCache ? "Dùng bản lưu: " : "Đã tải: ") + signs.size() + " điểm");
    }
    catch (Exception e)
    {
      return new Result(0, fromCache, "JSON không hợp lệ: " + e.getMessage());
    }
  }

  @WorkerThread
  @NonNull
  public List<SignPoint> loadCached()
  {
    if (!hasCache())
      return Collections.emptyList();
    try
    {
      return parse(readCache());
    }
    catch (Exception e)
    {
      return Collections.emptyList();
    }
  }

  /** @return body, or null on 304 Not Modified. */
  @Nullable
  private String download(boolean force) throws IOException
  {
    final HttpURLConnection conn = (HttpURLConnection) new URL(SignsConfig.SIGNS_URL).openConnection();
    conn.setConnectTimeout(TIMEOUT_MS);
    conn.setReadTimeout(TIMEOUT_MS);
    conn.setRequestProperty("Accept", "application/json");
    final String etag = mPrefs.getString(SignsConfig.PREF_ETAG, null);
    if (!force && etag != null)
      conn.setRequestProperty("If-None-Match", etag);

    try
    {
      final int code = conn.getResponseCode();
      if (code == HttpURLConnection.HTTP_NOT_MODIFIED)
        return null;
      if (code != HttpURLConnection.HTTP_OK)
        throw new IOException("HTTP " + code);

      final String body = readAll(conn.getInputStream());
      final String newEtag = conn.getHeaderField("ETag");
      if (newEtag != null)
        mPrefs.edit().putString(SignsConfig.PREF_ETAG, newEtag).apply();
      writeCache(body);
      return body;
    }
    finally
    {
      conn.disconnect();
    }
  }

  @NonNull
  private static String readAll(@NonNull InputStream in) throws IOException
  {
    final ByteArrayOutputStream out = new ByteArrayOutputStream();
    final byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) > 0)
      out.write(buf, 0, n);
    in.close();
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  @NonNull
  private String readCache()
  {
    try (FileInputStream in = new FileInputStream(mCache))
    {
      return readAll(in);
    }
    catch (IOException e)
    {
      return "";
    }
  }

  private void writeCache(@NonNull String body)
  {
    try (FileOutputStream out = new FileOutputStream(mCache))
    {
      out.write(body.getBytes(StandardCharsets.UTF_8));
    }
    catch (IOException ignored)
    {
      // A failed cache write must not lose an otherwise good download.
    }
  }

  @NonNull
  static List<SignPoint> parse(@NonNull String json) throws Exception
  {
    final JSONObject root = new JSONObject(json);
    final JSONArray arr = root.optJSONArray("signs");
    if (arr == null)
      return Collections.emptyList();

    final List<SignPoint> out = new ArrayList<>(arr.length());
    for (int i = 0; i < arr.length(); i++)
    {
      final SignPoint p = SignPoint.fromJson(arr.getJSONObject(i));
      if (p != null)
        out.add(p);
    }
    return out;
  }
}
