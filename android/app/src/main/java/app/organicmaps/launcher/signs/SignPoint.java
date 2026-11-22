package app.organicmaps.launcher.signs;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import org.json.JSONObject;

/** One traffic sign / hazard / service point, either bundled or delivered by an endpoint. */
public final class SignPoint
{
  public final double lat;
  public final double lon;
  /** sign:limit, sign:r420, hazard:rc, toll, fuel, park, rest, ev - decides the bookmark icon. */
  @NonNull
  public final String type;
  @NonNull
  public final String id;
  @Nullable
  public final String name;
  /** Numeric payload: speed limit in km/h, or 0 when the source has none. */
  public final int value;

  private SignPoint(double lat, double lon, @NonNull String type, @NonNull String id,
                    @Nullable String name, int value)
  {
    this.lat = lat;
    this.lon = lon;
    this.type = type;
    this.id = id;
    this.name = name;
    this.value = value;
  }

  /** Builds a point from the bundled binary index. */
  @NonNull
  public static SignPoint of(double lat, double lon, @NonNull String type, @Nullable String name,
                             int value)
  {
    return new SignPoint(lat, lon, type, type + "@" + lat + "," + lon, name, value);
  }

  /** Returns null when the object has no usable coordinates. */
  @Nullable
  public static SignPoint fromJson(@NonNull JSONObject o)
  {
    if (!o.has("lat") || !o.has("lon"))
      return null;
    final double lat = o.optDouble("lat", Double.NaN);
    final double lon = o.optDouble("lon", Double.NaN);
    if (Double.isNaN(lat) || Double.isNaN(lon) || Math.abs(lat) > 90 || Math.abs(lon) > 180)
      return null;

    return of(lat, lon, o.optString("type", "other"), o.has("name") ? o.optString("name") : null,
              o.optInt("value", 0));
  }

  @NonNull
  public String title()
  {
    if (name != null && !name.isEmpty())
      return name;
    if (type.equals("sign:limit"))
      return value > 0 ? "Tốc độ " + value + " km/h" : "Biển tốc độ";
    if (type.startsWith("sign:r"))
      return "Số hiệu đường";
    if (type.startsWith("sign:"))
      return "Biển báo";
    if (type.equals("hazard:cam"))
      return "Camera giao thông";
    if (type.startsWith("hazard:"))
      return "Điểm nguy hiểm";
    switch (type)
    {
      case "toll": return "Trạm thu phí";
      case "fuel": return "Cây xăng";
      case "park": return "Bãi đỗ xe";
      case "rest": return "Trạm dừng nghỉ";
      case "ev": return "Trạm sạc điện";
      default: return type;
    }
  }

  @NonNull
  public String description()
  {
    final StringBuilder sb = new StringBuilder(type);
    if (value > 0)
      sb.append(" (").append(value).append(')');
    sb.append('\n').append(lat).append(", ").append(lon);
    return sb.toString();
  }
}
