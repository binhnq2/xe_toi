package app.organicmaps.launcher.signs;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.WorkerThread;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the sign points bundled in assets/vn.
 *
 * <p>The bundle is produced offline from 903 gzip-JSON tiles; at runtime it is three flat files so
 * a head unit never parses megabytes of JSON at startup:
 *
 * <pre>
 *   signs.bin    fixed 16-byte records, little endian: int32 lat*1e5, int32 lon*1e5,
 *                uint16 type index, uint16 name index, uint16 value, uint16 reserved
 *   signs.types  type names, one per line, index = uint16 value
 *   signs.names  place names, one per line, index = uint16 value
 * </pre>
 *
 * The loader only returns the nearest {@code limit} points inside {@code radiusMeters}, which keeps
 * the bookmark import small enough for the bookmark manager.
 */
public final class SignsBundledData
{
  private static final String DIR = "vn";
  private static final int RECORD_BYTES = 16;

  private SignsBundledData() {}

  /** @return points sorted by distance, at most {@code limit}; empty when the bundle is missing. */
  @WorkerThread
  @NonNull
  public static List<SignPoint> loadNear(@NonNull Context context, double centerLat, double centerLon,
                                         double radiusMeters, int limit)
  {
    final byte[] raw;
    try
    {
      raw = readAsset(context, DIR + "/signs.bin");
    }
    catch (IOException e)
    {
      android.util.Log.w("TS8Signs", "cannot read bundled signs: " + e.getMessage());
      return Collections.emptyList();
    }
    android.util.Log.i("TS8Signs", "bundled signs bytes=" + raw.length);
    final String[] types = splitLines(readAssetQuiet(context, DIR + "/signs.types"));
    final String[] names = splitLines(readAssetQuiet(context, DIR + "/signs.names"));

    final int count = raw.length / RECORD_BYTES;
    final ByteBuffer buf = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
    final List<double[]> candidates = new ArrayList<>(256);

    for (int i = 0; i < count; i++)
    {
      final int off = i * RECORD_BYTES;
      final double lat = buf.getInt(off) / 1e5;
      final double lon = buf.getInt(off + 4) / 1e5;
      final double d = distanceMeters(centerLat, centerLon, lat, lon);
      if (d > radiusMeters)
        continue;
      // int16 indices are unsigned in the file.
      final int type = buf.getShort(off + 8) & 0xFFFF;
      final int name = buf.getShort(off + 10) & 0xFFFF;
      final int value = buf.getShort(off + 12) & 0xFFFF;
      candidates.add(new double[] {d, lat, lon, type, name, value});
      // Keeping every candidate would allocate 54k arrays on a 1 GB device; a radius bucket of a
      // few thousand is already far more than the bookmark layer can show.
      if (candidates.size() >= 4000)
        break;
    }

    Collections.sort(candidates, (a, b) -> Double.compare(a[0], b[0]));
    android.util.Log.i("TS8Signs", "candidates=" + candidates.size() + " records=" + count);
    final int n = Math.min(limit, candidates.size());
    final List<SignPoint> out = new ArrayList<>(n);
    for (int i = 0; i < n; i++)
    {
      final double[] c = candidates.get(i);
      final int type = (int) c[3];
      final int name = (int) c[4];
      final int value = (int) c[5];
      out.add(SignPoint.of(c[1], c[2], type < types.length ? types[type] : "sign",
                           name < names.length ? names[name] : "", value));
    }
    return out;
  }

  /** Equirectangular approximation: accurate to well under 1% at the radii used here. */
  static double distanceMeters(double lat1, double lon1, double lat2, double lon2)
  {
    final double kEarth = 6371000.0;
    final double rad = Math.PI / 180.0;
    final double x = (lon2 - lon1) * rad * Math.cos((lat1 + lat2) * 0.5 * rad);
    final double y = (lat2 - lat1) * rad;
    return kEarth * Math.sqrt(x * x + y * y);
  }

  @NonNull
  private static byte[] readAsset(@NonNull Context context, @NonNull String path) throws IOException
  {
    try (InputStream in = context.getAssets().open(path))
    {
      final ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
      final byte[] chunk = new byte[16384];
      int n;
      while ((n = in.read(chunk)) > 0)
        out.write(chunk, 0, n);
      return out.toByteArray();
    }
  }

  @NonNull
  private static String readAssetQuiet(@NonNull Context context, @NonNull String path)
  {
    try
    {
      return new String(readAsset(context, path), StandardCharsets.UTF_8);
    }
    catch (IOException e)
    {
      return "";
    }
  }

  @NonNull
  private static String[] splitLines(@NonNull String text)
  {
    return text.isEmpty() ? new String[0] : text.split("\n", -1);
  }
}
