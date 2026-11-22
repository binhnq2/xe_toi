package app.organicmaps.sdk;

import androidx.annotation.NonNull;
import app.organicmaps.BuildConfig;
import app.organicmaps.sdk.util.log.Logger;

/**
 * TS8 NAV render profile (PROMPT Phase 2).
 *
 * <p>Rather than adding a new renderer subsystem, this maps the "light renderer" requirement onto
 * the {@code power_management::PowerManager} that already exists in the baseline and already has
 * unit tests ({@code map/map_tests/power_manager_tests.cpp}). The native side applies the flags in
 * {@code Framework::OnPowerFacilityChanged} / {@code Framework::OnPowerSchemeChanged}:
 *
 * <ul>
 *   <li>{@code Buildings3d} -> {@code Allow3dMode()}, removes extruded buildings.</li>
 *   <li>{@code PerspectiveView} -> {@code Allow3dMode()}, keeps the camera top-down.</li>
 *   <li>{@code Scheme.EconomyMaximum} -> disables the traffic layer.</li>
 * </ul>
 *
 * <p>Nothing here touches the {@code .mwm} format, the styles or the shaders, so the map files stay
 * byte-identical to upstream. Everything is a runtime flag and therefore reversible at runtime and
 * easy to roll back.
 */
public final class Ts8RenderProfile
{
  private static final String TAG = "Ts8RenderProfile";

  /** Facility ordinals must match {@code power_management::Facility} in C++. */
  public enum Facility
  {
    /** Added to keep the ordinal in sync with the native enum. Never set from here. */
    Buildings3d(0),
    PerspectiveView(1),
    TrackRecording(2),
    TrafficJams(3),
    GpsTrackingForTraffic(4),
    OsmEditsUploading(5),
    UgcUploading(6),
    BookmarkCloudUploading(7),
    MapDownloader(8);

    public final int nativeOrdinal;

    Facility(int nativeOrdinal)
    {
      this.nativeOrdinal = nativeOrdinal;
    }
  }

  /** Scheme ordinals must match {@code power_management::Scheme} in C++. */
  public enum Scheme
  {
    None(0),
    Normal(1),
    EconomyMedium(2),
    EconomyMaximum(3),
    Auto(4);

    public final int nativeOrdinal;

    Scheme(int nativeOrdinal)
    {
      this.nativeOrdinal = nativeOrdinal;
    }
  }

  private static boolean sApplied;

  private Ts8RenderProfile() {}

  /**
   * Applies the TS8 profile. Safe to call more than once; the flags are idempotent on the native
   * side because {@code PowerManager::SetFacility} and {@code SetScheme} simply store state.
   *
   * <p>Must be called <b>after</b> {@code nativeInitFramework()}, because the native power manager
   * is constructed with the framework.
   */
  public static void apply()
  {
    if (!BuildConfig.TS8_LITE)
      return;
    if (sApplied)
      return;

    // EconomyMaximum turns the traffic layer off (Framework::OnPowerSchemeChanged).
    // Live traffic is not available in TS8 NAV anyway (PROMPT Phase 5).
    setScheme(Scheme.EconomyMaximum);

    // No terrain, no contours, no 3D buildings by default; keep top-down perspective so road
    // names and turn arrows stay readable at 1024x600.
    setFacility(Facility.Buildings3d, false);
    setFacility(Facility.PerspectiveView, false);

    sApplied = true;
    Logger.i(TAG, "TS8 render profile applied: scheme=EconomyMaximum, 3d=off, perspective=off");
  }

  /** Restores an upstream-like render configuration, e.g. for a debug toggle. */
  public static void reset()
  {
    if (!BuildConfig.TS8_LITE)
      return;
    setScheme(Scheme.Normal);
    setFacility(Facility.Buildings3d, true);
    setFacility(Facility.PerspectiveView, true);
    sApplied = false;
    Logger.i(TAG, "TS8 render profile reset");
  }

  public static boolean isApplied()
  {
    return sApplied;
  }

  private static void setFacility(@NonNull Facility facility, boolean enabled)
  {
    Framework.nativeSetPowerManagerFacility(facility.nativeOrdinal, enabled);
  }

  private static void setScheme(@NonNull Scheme scheme)
  {
    Framework.nativeSetPowerManagerScheme(scheme.nativeOrdinal);
  }

  /** Reads back the active native scheme, for diagnostics and tests. */
  @NonNull
  public static Scheme getScheme()
  {
    final int ordinal = Framework.nativeGetPowerManagerScheme();
    for (Scheme s : Scheme.values())
      if (s.nativeOrdinal == ordinal)
        return s;
    return Scheme.Normal;
  }
}
