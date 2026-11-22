package app.organicmaps.sdk.diagnostics;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import app.organicmaps.sdk.Ts8RenderProfile;
import app.organicmaps.sdk.sound.VoiceGuidanceEngine;
import app.organicmaps.sdk.util.Config;
import app.organicmaps.sdk.util.log.Logger;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * TS8 NAV diagnostics snapshot (PROMPT Phase 7).
 *
 * <p>Collects app/device/renderer/GPS/route/speed data so a user can hand over a single local
 * file when something goes wrong on the head unit, without attaching a debugger.
 *
 * <p><b>Privacy:</b> deliberately contains no location history, no coordinates, no route
 * geometry and no user identifiers. Only counters and capability flags.
 */
public final class DiagnosticsExport
{
  private static final String TAG = "DiagnosticsExport";
  public static final String FILE_NAME = "ts8-diagnostics.txt";

  /** Counters updated by the navigation code. Volatile: written from the UI thread, read here. */
  private static volatile int sRouteStarts;
  private static volatile int sReroutes;
  private static volatile int sSpeedWarnings;
  private static volatile int sGpsFixesAccepted;
  private static volatile int sGpsFixesRejectedAccuracy;
  private static volatile int sGpsTeleports;
  private static volatile int sTtsErrors;

  private DiagnosticsExport() {}

  public static void onRouteStarted()
  {
    sRouteStarts++;
  }

  public static void onReroute()
  {
    sReroutes++;
  }

  public static void onSpeedWarning()
  {
    sSpeedWarnings++;
  }

  public static void onGpsFix(boolean accepted, boolean rejectedForAccuracy)
  {
    if (accepted)
      sGpsFixesAccepted++;
    else if (rejectedForAccuracy)
      sGpsFixesRejectedAccuracy++;
  }

  public static void onGpsTeleport()
  {
    sGpsTeleports++;
  }

  public static void onTtsError()
  {
    sTtsErrors++;
  }

  /** Builds the report text. Pure string building, so it can be unit-tested. */
  @NonNull
  public static String build(@NonNull Context context)
  {
    final StringBuilder sb = new StringBuilder(2048);
    final SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    fmt.setTimeZone(TimeZone.getDefault());

    sb.append("TS8 NAV diagnostics\n");
    sb.append("generated: ").append(fmt.format(new Date())).append('\n');
    sb.append('\n');

    // --- app / device ---
    sb.append("[app]\n");
    sb.append("versionName: ").append(getVersionName(context)).append('\n');
    sb.append("versionCode: ").append(getVersionCode(context)).append('\n');
    sb.append("package: ").append(context.getPackageName()).append('\n');
    sb.append("liteFlavor: ").append(app.organicmaps.BuildConfig.TS8_LITE).append('\n');
    sb.append('\n');

    sb.append("[device]\n");
    sb.append("manufacturer: ").append(Build.MANUFACTURER).append('\n');
    sb.append("model: ").append(Build.MODEL).append('\n');
    sb.append("sdkInt: ").append(Build.VERSION.SDK_INT).append('\n');
    sb.append("release: ").append(Build.VERSION.RELEASE).append('\n');
    sb.append("abi: ").append(Build.SUPPORTED_ABIS.length > 0 ? Build.SUPPORTED_ABIS[0] : "?").append('\n');
    sb.append('\n');

    // --- renderer ---
    sb.append("[renderer]\n");
    sb.append("ts8ProfileApplied: ").append(Ts8RenderProfile.isApplied()).append('\n');
    sb.append("powerScheme: ").append(Ts8RenderProfile.getScheme()).append('\n');
    sb.append('\n');

    // --- gps ---
    sb.append("[gps]\n");
    sb.append("fixesAccepted: ").append(sGpsFixesAccepted).append('\n');
    sb.append("fixesRejectedAccuracy: ").append(sGpsFixesRejectedAccuracy).append('\n');
    sb.append("teleportsRejected: ").append(sGpsTeleports).append('\n');
    sb.append('\n');

    // --- route / speed ---
    sb.append("[route]\n");
    sb.append("routeStarts: ").append(sRouteStarts).append('\n');
    sb.append("reroutes: ").append(sReroutes).append('\n');
    sb.append("speedWarnings: ").append(sSpeedWarnings).append('\n');
    sb.append('\n');

    // --- voice ---
    sb.append("[voice]\n");
    sb.append("ttsEnabled: ").append(Config.TTS.isEnabled()).append('\n');
    sb.append("ttsErrors: ").append(sTtsErrors).append('\n');
    sb.append("lastError: ").append(nullToDash(VoiceGuidanceEngine.getLastError())).append('\n');
    final VoiceGuidanceEngine.Diagnostics vd = VoiceGuidanceEngine.refresh(context);
    sb.append("status: ").append(vd.status).append('\n');
    sb.append("enginePackage: ").append(nullToDash(vd.enginePackage)).append('\n');
    sb.append("installedEngines: ")
        .append(vd.engineListAvailable ? String.valueOf(vd.installedEngineCount) : "unavailable")
        .append('\n');
    sb.append("selectedLocale: ").append(nullToDash(vd.selectedLocale)).append('\n');
    sb.append("vietnameseDownloaded: ").append(vd.vietnameseDownloaded).append('\n');
    sb.append("vietnameseStatus: ")
        .append(vd.vietnameseStatusCode == null ? "-" : String.valueOf(vd.vietnameseStatusCode))
        .append('\n');
    sb.append('\n');

    sb.append("[note]\n");
    sb.append("Tệp này chỉ chứa bộ đếm và cờ năng lực thiết bị.\n");
    sb.append("Không có lịch sử vị trí, toạ độ, hình học lộ trình hay định danh người dùng.\n");

    return sb.toString();
  }

  /** Writes the report to app-private storage and returns the file, or null on failure. */
  @Nullable
  public static File export(@NonNull Context context)
  {
    final File dir = context.getExternalFilesDir(null);
    final File target = new File(dir != null ? dir : context.getFilesDir(), FILE_NAME);
    try (FileOutputStream out = new FileOutputStream(target))
    {
      out.write(build(context).getBytes(StandardCharsets.UTF_8));
      Logger.i(TAG, "Diagnostics written to " + target.getAbsolutePath());
      return target;
    }
    catch (IOException e)
    {
      Logger.e(TAG, "Cannot write diagnostics", e);
      return null;
    }
  }

  private static String nullToDash(@Nullable String s)
  {
    return s == null || s.isEmpty() ? "-" : s;
  }

  private static String getVersionName(@NonNull Context context)
  {
    try
    {
      final PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      return info.versionName == null ? "-" : info.versionName;
    }
    catch (PackageManager.NameNotFoundException e)
    {
      return "-";
    }
  }

  private static long getVersionCode(@NonNull Context context)
  {
    try
    {
      final PackageInfo info = context.getPackageManager().getPackageInfo(context.getPackageName(), 0);
      return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P ? info.getLongVersionCode() : info.versionCode;
    }
    catch (PackageManager.NameNotFoundException e)
    {
      return -1;
    }
  }
}
