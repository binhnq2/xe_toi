package app.organicmaps.launcher.settings;

import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.SwitchCompat;
import app.organicmaps.R;
import app.organicmaps.launcher.maps.MapImportHelper;
import app.organicmaps.launcher.signs.SignPoint;
import app.organicmaps.launcher.signs.SignsBundledData;
import app.organicmaps.launcher.signs.SignsConfig;
import app.organicmaps.launcher.signs.SignsImporter;
import app.organicmaps.sdk.bookmarks.data.BookmarkCategory;
import app.organicmaps.sdk.bookmarks.data.BookmarkManager;
import app.organicmaps.sdk.sound.TtsPlayer;
import app.organicmaps.sdk.sound.VoiceGuidanceEngine;
import app.organicmaps.sdk.util.Config;
import java.io.File;
import java.util.List;

/**
 * TS8 Lite settings, two-pane for landscape (design spec §10).
 *
 * <p>Every row here changes real state: the switches write the same {@code Config} store the map and
 * the voice engine read, and the theme row rewrites the UI theme used by the whole app. Nothing is
 * decorative - a switch with no backing would be worse than no switch at all.
 *
 * <p>Deliberately a plain AppCompatActivity, not BaseMwmFragmentActivity: that base redirects to
 * SplashActivity and finishes whenever the map core is not initialised, which made this screen (and
 * the launcher gear that opens it) unreachable after a cold boot.
 */
public class Ts8SettingsActivity extends AppCompatActivity
{
  private enum Pane
  {
    APPEARANCE(R.string.ts8_settings_appearance),
    NAVIGATION(R.string.ts8_settings_navigation),
    AUDIO(R.string.ts8_settings_audio),
    ASSISTANT(R.string.ts8_settings_assistant),
    DEVICES(R.string.ts8_settings_devices),
    DIAGNOSTICS(R.string.ts8_settings_diagnostics),
    SIGNS(R.string.ts8_settings_signs),
    MAPS(R.string.ts8_settings_maps);

    final int titleRes;

    Pane(int titleRes)
    {
      this.titleRes = titleRes;
    }
  }

  /** Radius used to pick signs around the vehicle; 25 km keeps the bookmark list usable. */
  private static final double SIGNS_RADIUS_METERS = 25000;
  /** Hard cap on markers per import: the bookmark layer is not meant for 54k points. */
  private static final int SIGNS_MAX_MARKERS = 400;

  @Nullable
  private LinearLayout mContent;
  @NonNull
  private Pane mPane = Pane.NAVIGATION;
  @NonNull
  private String mSignsStatus = "";
  /** Set while a signs import runs, so a second tap cannot start a parallel import. */
  private boolean mSignsBusy;
  @NonNull
  private String mMapStatus = "";
  private boolean mMapBusy;
  private boolean mMapImportedOk;
  private static final int REQUEST_IMPORT_MAP = 1001;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_ts8_settings);

    mContent = findViewById(R.id.ts8_settings_content);

    bind(R.id.ts8_settings_nav_appearance, Pane.APPEARANCE);
    bind(R.id.ts8_settings_nav_navigation, Pane.NAVIGATION);
    bind(R.id.ts8_settings_nav_audio, Pane.AUDIO);
    bind(R.id.ts8_settings_nav_assistant, Pane.ASSISTANT);
    bind(R.id.ts8_settings_nav_devices, Pane.DEVICES);
    bind(R.id.ts8_settings_nav_signs, Pane.SIGNS);
    bind(R.id.ts8_settings_nav_maps, Pane.MAPS);
    bind(R.id.ts8_settings_nav_diagnostics, Pane.DIAGNOSTICS);

    show(mPane);
  }

  private void bind(int viewId, @NonNull Pane pane)
  {
    final View v = findViewById(viewId);
    if (v != null)
      v.setOnClickListener(view -> show(pane));
  }

  private void show(@NonNull Pane pane)
  {
    mPane = pane;
    if (mContent == null)
      return;
    mContent.removeAllViews();
    buildPane(pane, mContent);
    markSelected(pane);
  }

  private void markSelected(@NonNull Pane pane)
  {
    final int[] ids = {R.id.ts8_settings_nav_appearance, R.id.ts8_settings_nav_navigation,
                       R.id.ts8_settings_nav_audio, R.id.ts8_settings_nav_assistant,
                       R.id.ts8_settings_nav_devices, R.id.ts8_settings_nav_diagnostics,
                       R.id.ts8_settings_nav_signs, R.id.ts8_settings_nav_maps};
    for (int i = 0; i < ids.length; i++)
    {
      final View v = findViewById(ids[i]);
      if (v != null)
        v.setSelected(i == pane.ordinal());
    }
  }

  /** Refreshes the visible pane after a value changed underneath it. */
  private void refresh()
  {
    show(mPane);
  }

  private void buildPane(@NonNull Pane pane, @NonNull LinearLayout root)
  {
    switch (pane)
    {
      case APPEARANCE -> buildAppearancePane(root);
      case NAVIGATION -> buildNavigationPane(root);
      case AUDIO -> buildAudioPane(root);
      case ASSISTANT -> buildAssistantPane(root);
      case DEVICES -> buildDevicePane(root);
      case DIAGNOSTICS -> buildDiagnosticsPane(root);
      case SIGNS -> buildSignsPane(root);
      case MAPS -> buildMapsPane(root);
    }
  }

  // ------------------------------------------------------------------ appearance

  private void buildAppearancePane(@NonNull LinearLayout root)
  {
    addSection(root, R.string.ts8_settings_theme);
    final String current = Config.getUiThemeSettings(this);
    addChoice(root, R.string.ts8_theme_light, getString(R.string.theme_default), current,
              R.string.ts8_theme_light_hint);
    addChoice(root, R.string.ts8_theme_dark, getString(R.string.theme_night), current, 0);
    addChoice(root, R.string.ts8_theme_auto, getString(R.string.theme_nav_auto), current, 0);

    addSection(root, R.string.ts8_settings_display);
    addSwitch(root, R.string.ts8_set_keep_screen_on, Config.isKeepScreenOnEnabled(), checked -> {
      Config.setKeepScreenOnEnabled(checked);
      refresh();
    });
    addSwitch(root, R.string.ts8_set_large_fonts, Config.isLargeFontsSize(), checked -> {
      Config.setLargeFontsSize(checked);
      refresh();
    });
    addSwitch(root, R.string.ts8_set_zoom_buttons, Config.showZoomButtons(), checked -> {
      Config.setShowZoomButtons(checked);
      refresh();
    });
  }

  // ------------------------------------------------------------------ navigation

  private void buildNavigationPane(@NonNull LinearLayout root)
  {
    addSection(root, R.string.ts8_settings_voice);
    addSwitch(root, R.string.ts8_set_voice, TtsPlayer.isEnabled(), checked -> {
      TtsPlayer.setEnabled(checked);
      refresh();
    });
    addNote(root, R.string.ts8_set_voice_note);

    addSection(root, R.string.ts8_settings_signs);
    addNote(root, R.string.ts8_signs_hint);
    addSwitch(root, R.string.ts8_signs_show_on_map, isSignsCategoryVisible(), checked -> {
      setSignsCategoryVisible(checked);
      refresh();
    });
  }

  // ------------------------------------------------------------------ audio

  private void buildAudioPane(@NonNull LinearLayout root)
  {
    final VoiceGuidanceEngine.Diagnostics d = VoiceGuidanceEngine.refresh(this);
    addSection(root, R.string.ts8_settings_audio);
    addValue(root, R.string.ts8_audio_engine, d.enginePackage == null ? "-" : d.enginePackage);
    addValue(root, R.string.ts8_audio_status, String.valueOf(d.status));
    addValue(root, R.string.ts8_audio_vietnamese,
             d.vietnameseDownloaded ? getString(R.string.ts8_audio_installed)
                                    : getString(R.string.ts8_audio_missing));
    if (d.disabledReason != null)
      addValue(root, R.string.ts8_audio_problem, d.disabledReason);

    addButton(root, R.string.ts8_tts_open_settings, v -> startActivity(
                   new android.content.Intent(android.provider.Settings.ACTION_SETTINGS)));
    addButton(root, R.string.ts8_test_voice, v -> VoiceGuidanceEngine.refresh(this));
  }

  // ------------------------------------------------------------------ assistant

  private void buildAssistantPane(@NonNull LinearLayout root)
  {
    addSection(root, R.string.ts8_settings_assistant);
    addNote(root, R.string.ts8_assistant_offline_note);
    addNote(root, R.string.ts8_assistant_privacy_note);
  }

  // ------------------------------------------------------------------ devices

  private void buildDevicePane(@NonNull LinearLayout root)
  {
    addSection(root, R.string.ts8_settings_devices);
    final app.organicmaps.launcher.esp32.Esp32UsbBridge bridge = LauncherDevices.bridgeOrNull();
    if (bridge == null)
    {
      addNote(root, R.string.ts8_devices_not_running);
      return;
    }
    addNote(root, bridge.diagnosticsSummary());
  }

  // ------------------------------------------------------------------ diagnostics

  private void buildDiagnosticsPane(@NonNull LinearLayout root)
  {
    addSection(root, R.string.ts8_settings_diagnostics);
    final File file = app.organicmaps.sdk.diagnostics.DiagnosticsExport.export(this);
    if (file == null)
    {
      addNote(root, R.string.ts8_diagnostics_failed);
      return;
    }
    addValue(root, R.string.ts8_diagnostics_path, file.getAbsolutePath());
    addNote(root, R.string.ts8_diagnostics_note);
  }

  // ------------------------------------------------------------------ signs

  private void buildSignsPane(@NonNull LinearLayout root)
  {
    addSection(root, R.string.ts8_settings_signs);
    if (!mSignsStatus.isEmpty())
      addNote(root, mSignsStatus);
    addNote(root, R.string.ts8_signs_hint);

    final Button load = addButton(root, R.string.ts8_signs_download, v -> importNearbySigns());
    load.setEnabled(!mSignsBusy);

    addSwitch(root, R.string.ts8_signs_show_on_map, isSignsCategoryVisible(), checked -> {
      setSignsCategoryVisible(checked);
      refresh();
    });

    final BookmarkManager mgr = BookmarkManager.INSTANCE;
    if (isCoreReady())
    {
      for (BookmarkCategory category : mgr.getCategories())
      {
        if (SignsConfig.CATEGORY_NAME.equals(category.getName()))
        {
          addValue(root, R.string.ts8_signs_count,
                   String.valueOf(category.getBookmarksCount()));
          break;
        }
      }
    }
  }

  /**
   * Loads the bundled sign index around the last known position and imports the nearest points as
   * one bookmark category.
   *
   * <p>54k points live in assets; only a few hundred go to the bookmark manager, because that layer
   * draws and clusters every item it owns.
   */
  private void importNearbySigns()
  {
    if (mSignsBusy)
      return;
    // The bookmark manager is a native singleton owned by the map core; calling it before the core
    // is up crashes with SIGSEGV inside nativeGetBookmarkCategories. Ask the user to open the map
    // once instead of taking the process down.
    if (!app.organicmaps.MwmApplication.from(this).getOrganicMaps().arePlatformAndCoreInitialized())
    {
      mSignsStatus = getString(R.string.ts8_signs_need_map);
      refresh();
      return;
    }
    mSignsBusy = true;
    mSignsStatus = getString(R.string.ts8_signs_loading);
    refresh();

    final Context context = getApplicationContext();
    final double[] center = lastKnownCenter();
    new Thread(() -> {
      final List<SignPoint> signs = SignsBundledData.loadNear(context, center[0], center[1],
                                                              SIGNS_RADIUS_METERS,
                                                              SIGNS_MAX_MARKERS);
      runOnUiThread(() -> {
        mSignsBusy = false;
        final int imported = SignsImporter.replace(context, signs);
        mSignsStatus = getString(R.string.ts8_signs_imported, imported)
            + String.format(java.util.Locale.US, " (%.4f, %.4f · %.0f km)", center[0], center[1],
                            SIGNS_RADIUS_METERS / 1000);
        refresh();
      });
    }, "ts8-signs").start();
  }

  private boolean isSignsCategoryVisible()
  {
    if (!isCoreReady())
      return false;
    final BookmarkManager mgr = BookmarkManager.INSTANCE;
    try
    {
      for (BookmarkCategory category : mgr.getCategories())
      {
        if (SignsConfig.CATEGORY_NAME.equals(category.getName()))
          return mgr.isVisible(category.getId());
      }
    }
    catch (RuntimeException ignored)
    {
      // Core not ready yet: report "off" instead of crashing on a native call.
    }
    return false;
  }

  private void setSignsCategoryVisible(boolean visible)
  {
    if (!isCoreReady())
      return;
    final BookmarkManager mgr = BookmarkManager.INSTANCE;
    try
    {
      for (BookmarkCategory category : mgr.getCategories())
      {
        if (SignsConfig.CATEGORY_NAME.equals(category.getName()))
        {
          mgr.setVisibility(category.getId(), visible);
          return;
        }
      }
    }
    catch (RuntimeException ignored)
    {
    }
  }

  /**
   * The bookmark manager is a native singleton owned by the map core. Touching it before the core
   * starts is a hard SIGSEGV (verified: opening Settings on a fresh install killed the process), and
   * a native crash cannot be caught by the try/catch around the calls.
   */
  private boolean isCoreReady()
  {
    return app.organicmaps.MwmApplication.from(this).getOrganicMaps().arePlatformAndCoreInitialized();
  }

  /** Last known fix from the platform, or Ha Noi when the device has no GPS fix yet. */
  @NonNull
  private double[] lastKnownCenter()
  {
    try
    {
      final android.location.LocationManager lm =
          (android.location.LocationManager) getSystemService(Context.LOCATION_SERVICE);
      android.location.Location l =
          lm == null ? null : lm.getLastKnownLocation(android.location.LocationManager.GPS_PROVIDER);
      if (l == null && lm != null)
        l = lm.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
      if (l != null)
        return new double[] {l.getLatitude(), l.getLongitude()};
    }
    catch (SecurityException | IllegalArgumentException ignored)
    {
      // No permission yet: fall through to the fallback centre.
    }
    return new double[] {21.0285, 105.8048};
  }

  // ------------------------------------------------------------------ row builders

  private void addSection(@NonNull LinearLayout root, int titleRes)
  {
    final TextView tv = new TextView(this);
    tv.setText(titleRes);
    tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.ts8_text_primary));
    tv.setTextSize(18);
    tv.setPadding(0, dp(16), 0, dp(8));
    root.addView(tv);
  }

  private void addNote(@NonNull LinearLayout root, int textRes)
  {
    addNote(root, getString(textRes));
  }

  private void addNote(@NonNull LinearLayout root, @NonNull String text)
  {
    final TextView tv = new TextView(this);
    tv.setText(text);
    tv.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.ts8_text_secondary));
    tv.setTextSize(14);
    tv.setLineSpacing(0, 1.25f);
    tv.setPadding(0, dp(4), 0, dp(4));
    root.addView(tv);
  }

  private void addValue(@NonNull LinearLayout root, int labelRes, @NonNull String value)
  {
    addNote(root, getString(labelRes) + ": " + value);
  }

  private void addSwitch(@NonNull LinearLayout root, int labelRes, boolean checked,
                         @NonNull java.util.function.Consumer<Boolean> onChanged)
  {
    final LinearLayout row = new LinearLayout(this);
    row.setOrientation(LinearLayout.HORIZONTAL);
    row.setGravity(android.view.Gravity.CENTER_VERTICAL);
    row.setPadding(0, dp(6), 0, dp(6));

    final TextView label = new TextView(this);
    label.setText(labelRes);
    label.setTextColor(androidx.core.content.ContextCompat.getColor(this, R.color.ts8_text_primary));
    label.setTextSize(16);
    final LinearLayout.LayoutParams lp =
        new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    row.addView(label, lp);

    final SwitchCompat toggle = new SwitchCompat(this);
    toggle.setChecked(checked);
    toggle.setOnCheckedChangeListener((buttonView, isChecked) -> onChanged.accept(isChecked));
    row.addView(toggle);

    root.addView(row);
  }

  private void addChoice(@NonNull LinearLayout root, int labelRes, @NonNull String themeValue,
                         @NonNull String currentTheme, int hintRes)
  {
    final boolean selected = currentTheme.equals(themeValue);
    final Button b = addButton(root, selected ? R.string.ts8_theme_selected_format : labelRes, null);
    if (selected)
      b.setText(getString(R.string.ts8_theme_selected_format, getString(labelRes)));
    else
      b.setText(labelRes);
    if (hintRes != 0)
      addNote(root, hintRes);
    b.setOnClickListener(v -> {
      if (Config.setUiThemeSettings(this, themeValue))
        recreate();
      else
        refresh();
    });
  }

  @NonNull
  private Button addButton(@NonNull LinearLayout root, int textRes,
                           @Nullable View.OnClickListener listener)
  {
    final Button b = new Button(this);
    b.setText(textRes);
    if (listener != null)
      b.setOnClickListener(listener);
    root.addView(b);
    return b;
  }

  private int dp(int value)
  {
    return Math.round(value * getResources().getDisplayMetrics().density);
  }

  // ------------------------------------------------------------------ maps

  private void buildMapsPane(@NonNull LinearLayout root)
  {
    addSection(root, R.string.ts8_settings_maps);
    final String version = MapImportHelper.readCountriesVersion(this);
    final File dir = MapImportHelper.targetDir(this);

    addValue(root, R.string.ts8_maps_folder, dir.getAbsolutePath());
    addValue(root, R.string.ts8_maps_free_space, MapImportHelper.formatSize(dir.getUsableSpace()));

    final File vietnam = new File(dir, "Vietnam.mwm");
    addValue(root, R.string.ts8_maps_vietnam,
             vietnam.isFile() ? MapImportHelper.formatSize(vietnam.length())
                              : getString(R.string.ts8_maps_missing));

    if (!mMapStatus.isEmpty())
      addNote(root, mMapStatus);
    addNote(root, getString(R.string.ts8_maps_hint, version.isEmpty() ? "250708" : version));

    final Button importButton = addButton(root, R.string.ts8_maps_import, v -> pickMapFile());
    importButton.setEnabled(!mMapBusy);

    if (mMapImportedOk)
      addButton(root, R.string.ts8_maps_restart, v -> restartApp());
  }

  private void pickMapFile()
  {
    final android.content.Intent intent =
        new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT);
    intent.addCategory(android.content.Intent.CATEGORY_OPENABLE);
    // .mwm has no registered MIME type, so the picker has to show every openable file.
    intent.setType("*/*");
    try
    {
      startActivityForResult(intent, REQUEST_IMPORT_MAP);
    }
    catch (android.content.ActivityNotFoundException e)
    {
      mMapStatus = getString(R.string.ts8_maps_import_failed, getString(R.string.ts8_maps_no_picker));
      refresh();
    }
  }

  @Override
  protected void onActivityResult(int requestCode, int resultCode, @Nullable android.content.Intent data)
  {
    super.onActivityResult(requestCode, resultCode, data);
    if (requestCode != REQUEST_IMPORT_MAP || resultCode != RESULT_OK || data == null)
      return;
    final android.net.Uri uri = data.getData();
    if (uri != null)
      startMapImport(uri);
  }

  /**
   * Copies the picked file into the folder the app scans. Runs on a worker thread: a country map is
   * a few hundred megabytes, and progress is reported at most every 10 percent so the pane is not
   * rebuilt a hundred times.
   */
  private void startMapImport(@NonNull android.net.Uri uri)
  {
    if (mMapBusy)
      return;
    mMapBusy = true;
    mMapImportedOk = false;
    mMapStatus = getString(R.string.ts8_maps_importing);
    refresh();

    final Context context = getApplicationContext();
    new Thread(() -> {
      final MapImportHelper.Result result =
          MapImportHelper.importFrom(context, uri, percent -> {
            if (percent % 10 != 0)
              return;
            runOnUiThread(() -> {
              mMapStatus = getString(R.string.ts8_maps_progress, percent);
              if (mPane == Pane.MAPS)
                refresh();
            });
          });
      runOnUiThread(() -> {
        mMapBusy = false;
        mMapImportedOk = result.ok;
        mMapStatus = result.ok ? getString(R.string.ts8_maps_import_ok, result.message) + "\n"
                                     + getString(R.string.ts8_maps_restart_needed)
                               : getString(R.string.ts8_maps_import_failed, describeFailure(result.message));
        refresh();
      });
    }, "ts8-map-import").start();
  }

  @NonNull
  private String describeFailure(@NonNull String code)
  {
    if (code.startsWith("not-mwm:"))
      return getString(R.string.ts8_maps_wrong_ext);
    if (code.startsWith("no-space:"))
    {
      final String bytes = code.substring("no-space:".length());
      try
      {
        return getString(R.string.ts8_maps_not_enough_space,
                         MapImportHelper.formatSize(Long.parseLong(bytes)));
      }
      catch (NumberFormatException ignored)
      {
        return getString(R.string.ts8_maps_not_enough_space, bytes);
      }
    }
    return code;
  }

  /** Restarts the process so Storage rescans the map folder at startup. */
  private void restartApp()
  {
    final android.content.Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
    if (intent != null)
    {
      intent.addFlags(
          android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP | android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
      startActivity(intent);
    }
    android.os.Process.killProcess(android.os.Process.myPid());
  }

  /** Holds the optional bridge so settings can report it without starting it. */
  static final class LauncherDevices
  {
    @Nullable
    private static app.organicmaps.launcher.esp32.Esp32UsbBridge sBridge;

    private LauncherDevices() {}

    static void set(@Nullable app.organicmaps.launcher.esp32.Esp32UsbBridge bridge)
    {
      sBridge = bridge;
    }

    @Nullable
    static app.organicmaps.launcher.esp32.Esp32UsbBridge bridgeOrNull()
    {
      return sBridge;
    }
  }
}
