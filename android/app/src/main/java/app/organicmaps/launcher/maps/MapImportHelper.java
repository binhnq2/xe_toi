package app.organicmaps.launcher.maps;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Imports a .mwm chosen by the user into the folder the app scans.
 *
 * <p>The scanner only looks inside {@code files/<countriesVersion>/}, where the version comes from
 * {@code "v"} in the bundled {@code countries.txt}. A map dropped anywhere else - the root of the
 * app folder, or a folder named after the map's own build date - is invisible to the app and it
 * offers a download instead (verified on device: the Hanoi zoom showed a "Vietnam 263 MB DOWNLOAD"
 * card until the file was moved into that exact folder).
 */
public final class MapImportHelper
{
  public static final String EXTENSION = ".mwm";

  private static final Pattern VERSION_PATTERN = Pattern.compile("\"v\"\\s*:\\s*(\\d+)");
  private static final int BUFFER = 1 << 20;

  /** Outcome of an import, already phrased for the settings pane. */
  public static final class Result
  {
    public final boolean ok;
    @NonNull
    public final String message;

    Result(boolean ok, @NonNull String message)
    {
      this.ok = ok;
      this.message = message;
    }
  }

  public interface Progress
  {
    void onProgress(int percent);
  }

  private MapImportHelper() {}

  /** Reads {@code "v"} from the bundled countries file, e.g. 250708. Empty when unreadable. */
  @NonNull
  public static String readCountriesVersion(@NonNull Context context)
  {
    try (InputStream in = context.getAssets().open("countries.txt"))
    {
      final byte[] head = new byte[512];
      final int n = in.read(head);
      if (n <= 0)
        return "";
      final String text = new String(head, 0, n, StandardCharsets.UTF_8);
      final Matcher m = VERSION_PATTERN.matcher(text);
      return m.find() ? m.group(1) : "";
    }
    catch (IOException e)
    {
      return "";
    }
  }

  /** The folder the app scans for downloadable maps. */
  @NonNull
  public static File targetDir(@NonNull Context context)
  {
    final String version = readCountriesVersion(context);
    final File base = context.getExternalFilesDir(null);
    return new File(base, version.isEmpty() ? "250708" : version);
  }

  @Nullable
  public static String displayName(@NonNull Context context, @NonNull Uri uri)
  {
    try (Cursor c = context.getContentResolver()
                        .query(uri, new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null))
    {
      if (c != null && c.moveToFirst())
      {
        final int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (idx >= 0)
          return c.getString(idx);
      }
    }
    catch (RuntimeException ignored)
    {
    }
    final String last = uri.getLastPathSegment();
    return last == null ? null : last.substring(last.lastIndexOf('/') + 1);
  }

  /**
   * Copies {@code uri} into {@link #targetDir}. Must run off the UI thread: a country map is
   * hundreds of megabytes.
   */
  @WorkerThread
  @NonNull
  public static Result importFrom(@NonNull Context context, @NonNull Uri uri,
                                  @Nullable Progress progress)
  {
    String name = displayName(context, uri);
    if (name == null || name.isEmpty())
      name = "map" + EXTENSION;
    if (!name.toLowerCase().endsWith(EXTENSION))
      return new Result(false, "not-mwm:" + name);

    final File dir = targetDir(context);
    if (!dir.exists() && !dir.mkdirs())
      return new Result(false, "no-dir:" + dir.getAbsolutePath());

    final File target = new File(dir, name);
    final File tmp = new File(dir, name + ".part");

    long copied = 0;
    try (InputStream in = context.getContentResolver().openInputStream(uri))
    {
      if (in == null)
        return new Result(false, "no-stream");

      // Free-space guard: the copy would fail half way and leave a .part file otherwise.
      final long usable = dir.getUsableSpace();
      final long declared = declaredSize(context, uri);
      if (declared > 0 && declared + (16L << 20) > usable)
        return new Result(false, "no-space:" + declared);

      try (FileOutputStream out = new FileOutputStream(tmp))
      {
        final byte[] buf = new byte[BUFFER];
        int n;
        long lastReported = -1;
        while ((n = in.read(buf)) > 0)
        {
          out.write(buf, 0, n);
          copied += n;
          if (declared > 0 && progress != null)
          {
            final int percent = (int) Math.min(100, copied * 100 / declared);
            if (percent != lastReported)
            {
              lastReported = percent;
              progress.onProgress(percent);
            }
          }
        }
        out.getFD().sync();
      }
      catch (IOException e)
      {
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        throw e;
      }

      if (target.exists() && !target.delete())
        return new Result(false, "cannot-replace:" + target.getAbsolutePath());
      if (!tmp.renameTo(target))
        return new Result(false, "cannot-rename:" + target.getAbsolutePath());

      return new Result(true, target.getAbsolutePath());
    }
    catch (IOException | SecurityException e)
    {
      //noinspection ResultOfMethodCallIgnored
      tmp.delete();
      return new Result(false, String.valueOf(e.getMessage()));
    }
  }

  private static long declaredSize(@NonNull Context context, @NonNull Uri uri)
  {
    try (Cursor c = context.getContentResolver()
                        .query(uri, new String[] {OpenableColumns.SIZE}, null, null, null))
    {
      if (c != null && c.moveToFirst())
      {
        final int idx = c.getColumnIndex(OpenableColumns.SIZE);
        if (idx >= 0 && !c.isNull(idx))
          return c.getLong(idx);
      }
    }
    catch (RuntimeException ignored)
    {
    }
    return 0;
  }

  /** Human readable size, e.g. 263 MB. */
  @NonNull
  public static String formatSize(long bytes)
  {
    if (bytes >= (1L << 30))
      return String.format(java.util.Locale.US, "%.1f GB", bytes / (double) (1L << 30));
    if (bytes >= (1L << 20))
      return String.format(java.util.Locale.US, "%.0f MB", bytes / (double) (1L << 20));
    if (bytes >= (1L << 10))
      return String.format(java.util.Locale.US, "%.0f KB", bytes / (double) (1L << 10));
    return bytes + " B";
  }
}
