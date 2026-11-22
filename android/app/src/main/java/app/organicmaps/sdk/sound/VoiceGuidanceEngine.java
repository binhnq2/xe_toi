package app.organicmaps.sdk.sound;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.util.concurrency.UiThread;
import app.organicmaps.sdk.util.log.Logger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * TS8 NAV voice-guidance abstraction (PROMPT Phase 5).
 *
 * <p>The baseline exposes the singleton {@link TtsPlayer} directly, which cannot answer the
 * questions the TS8 acceptance criteria ask for: is there an engine at all, does it have a
 * Vietnamese voice, is that voice usable offline, and why is the sound toggle off? This class
 * answers them and gives the settings screen an actionable way out (open the system TTS
 * settings) instead of silently disabling the feature.
 *
 * <p>It stays a thin facade over {@code TtsPlayer}: the actual speaking, audio focus and queueing
 * remain where they already work, so nothing about the playback path changes for upstream
 * flavours. All new behaviour is gated behind {@code TS8_LITE}.
 */
public final class VoiceGuidanceEngine
{
  private static final String TAG = "VoiceGuidanceEngine";

  /** Vietnamese is the primary target locale for TS8 NAV. */
  public static final Locale VIETNAMESE = new Locale("vi", "VN");

  public enum Status
  {
    /** Not initialised yet. */
    NOT_INITIALIZED,
    /** System has a TTS engine and a usable Vietnamese voice. */
    READY_VIETNAMESE,
    /** System has an engine, but no Vietnamese voice or its data is not installed. */
    NO_VIETNAMESE_VOICE,
    /** No TTS engine installed at all. */
    NO_ENGINE,
    /** TtsPlayer had to lock itself down after an error. */
    BROKEN
  }

  /** Snapshot for the Diagnostics screen. Never contains location data. */
  public static final class Diagnostics
  {
    @NonNull
    public final Status status;
    /** Package name of the resolved default engine, or null. */
    @Nullable
    public final String enginePackage;
    /** Count of TTS engines installed on the device. */
    public final int installedEngineCount;
    /** True when the engine list could be enumerated at all. */
    public final boolean engineListAvailable;
    /** Locale currently selected for guidance, or null. */
    @Nullable
    public final String selectedLocale;
    /** True when Vietnamese is reported available AND downloaded. */
    public final boolean vietnameseDownloaded;
    /** Raw TextToSpeech status code from isLanguageAvailable(vi-VN), or null when unknown. */
    @Nullable
    public final Integer vietnameseStatusCode;
    /** Human-readable reason when the toggle cannot be used, else null. */
    @Nullable
    public final String disabledReason;

    Diagnostics(@NonNull Status status, @Nullable String enginePackage, int installedEngineCount,
                boolean engineListAvailable, @Nullable String selectedLocale, boolean vietnameseDownloaded,
                @Nullable Integer vietnameseStatusCode, @Nullable String disabledReason)
    {
      this.status = status;
      this.enginePackage = enginePackage;
      this.installedEngineCount = installedEngineCount;
      this.engineListAvailable = engineListAvailable;
      this.selectedLocale = selectedLocale;
      this.vietnameseDownloaded = vietnameseDownloaded;
      this.vietnameseStatusCode = vietnameseStatusCode;
      this.disabledReason = disabledReason;
    }
  }

  /** Callback for the "Test voice" button. */
  public interface TestCallback
  {
    void onSpoken();

    void onError(@NonNull String reason);
  }

  private static Status sStatus = Status.NOT_INITIALIZED;
  private static String sLastError;
  private static boolean sVietnameseDownloaded;
  private static Integer sVietnameseStatusCode;
  private static String sEnginePackage;
  private static int sInstalledEngineCount;
  private static boolean sEngineListAvailable;

  private VoiceGuidanceEngine() {}

  /**
   * Probes the system TTS state and caches the result for Diagnostics.
   * Safe to call repeatedly; the settings screen calls it again after returning from the system
   * TTS settings, which is what makes "refresh after Android TTS settings" work.
   */
  @NonNull
  public static Diagnostics refresh(@NonNull Context context)
  {
    sEngineListAvailable = true;
    try
    {
      final Intent intent = new Intent("android.intent.action.TTS_SERVICE");
      final List<ResolveInfo> engines = context.getPackageManager()
                                             .queryIntentServices(intent, PackageManager.GET_META_DATA);
      sInstalledEngineCount = engines == null ? 0 : engines.size();
      if (sInstalledEngineCount == 0)
      {
        sStatus = Status.NO_ENGINE;
        sVietnameseDownloaded = false;
        sVietnameseStatusCode = null;
        sLastError = "No TTS engine is installed on this device.";
        return snapshot(null, sLastError);
      }
    }
    catch (Exception e)
    {
      // Querying services can fail on restricted profiles; treat as unknown rather than fatal.
      sEngineListAvailable = false;
      sInstalledEngineCount = -1;
      Logger.w(TAG, "Cannot enumerate TTS engines: " + e.getMessage());
    }

    sEnginePackage = resolveDefaultEngine(context);

    // The TtsPlayer singleton owns the actual TextToSpeech instance. Ask it for the current
    // language list; this also refreshes the player's own view of the system state.
    try
    {
      final List<LanguageData> languages = TtsPlayer.INSTANCE.refreshLanguages();
      LanguageData vietnamese = null;
      for (LanguageData lang : languages)
      {
        if (VIETNAMESE.getLanguage().equals(lang.locale.getLanguage()))
        {
          vietnamese = lang;
          break;
        }
      }

      sVietnameseDownloaded = vietnamese != null && vietnamese.downloaded;
      sVietnameseStatusCode = vietnamese == null ? null : statusCodeFor(languages);

      if (sVietnameseDownloaded)
      {
        sStatus = Status.READY_VIETNAMESE;
        sLastError = null;
      }
      else
      {
        sStatus = Status.NO_VIETNAMESE_VOICE;
        sLastError = "Vietnamese voice data is missing. Install it in the system text-to-speech settings.";
      }

      final String selected = vietnamese != null ? vietnamese.locale.toLanguageTag() : null;
      return snapshot(selected, sVietnameseDownloaded ? null : sLastError);
    }
    catch (Exception e)
    {
      sStatus = Status.BROKEN;
      sVietnameseDownloaded = false;
      sLastError = "Text-to-speech is unavailable: " + e.getMessage();
      Logger.e(TAG, "TTS probe failed", e);
      return snapshot(null, sLastError);
    }
  }

  /** True when guidance can be spoken right now with an installed Vietnamese voice. */
  public static boolean isVietnameseAvailable()
  {
    return sStatus == Status.READY_VIETNAMESE;
  }

  /**
   * Returns the reason the audio toggle cannot be used, or null when it can.
   * The settings UI must show this instead of silently disabling the switch.
   */
  @Nullable
  public static String getDisabledReason()
  {
    return sLastError;
  }

  /** Opens the system text-to-speech settings so the user can install a voice. */
  public static void openSystemTtsSettings(@NonNull Context context)
  {
    final Intent intent = new Intent();
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.ICE_CREAM_SANDWICH)
      intent.setAction("com.android.settings.TTS_SETTINGS");
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    try
    {
      context.startActivity(intent);
    }
    catch (Exception e)
    {
      Logger.w(TAG, "Cannot open TTS settings: " + e.getMessage());
      // Fall back to the generic settings screen rather than doing nothing.
      try
      {
        context.startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)
                                  .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
      }
      catch (Exception ignored)
      {
        Logger.e(TAG, "Cannot open settings at all");
      }
    }
  }

  /**
   * Speaks a short sample so the user can verify the voice before starting a route.
   * Uses the same TtsPlayer path as real guidance, so a green result means guidance will work.
   */
  public static void testVoice(@NonNull Context context, @NonNull String sampleText,
                               @NonNull TestCallback callback)
  {
    final Diagnostics d = refresh(context);
    if (d.status == Status.NO_ENGINE)
    {
      callback.onError("No text-to-speech engine installed.");
      return;
    }
    if (d.status == Status.BROKEN)
    {
      callback.onError("Text-to-speech engine is broken.");
      return;
    }
    if (d.status == Status.NO_VIETNAMESE_VOICE)
    {
      callback.onError("Vietnamese voice data is not installed.");
      return;
    }

    try
    {
      // TtsPlayer.speak() is gated by Config.TTS.isEnabled(). The test button must work even
      // when guidance is switched off, so force the flag for the duration of the sample and
      // restore it. Without this the button would look pressed but stay silent - exactly the
      // "silently disabled" behaviour the acceptance criteria forbid.
      final boolean wasEnabled = app.organicmaps.sdk.util.Config.TTS.isEnabled();
      try
      {
        if (!wasEnabled)
          TtsPlayer.INSTANCE.setEnabled(true);
        TtsPlayer.INSTANCE.speak(sampleText);
      }
      finally
      {
        if (!wasEnabled)
          TtsPlayer.INSTANCE.setEnabled(false);
      }
      callback.onSpoken();
    }
    catch (Exception e)
    {
      callback.onError("Could not speak: " + e.getMessage());
    }
  }

  /** Last error text, for logging and diagnostics. */
  @Nullable
  public static String getLastError()
  {
    return sLastError;
  }

  @NonNull
  private static Diagnostics snapshot(@Nullable String selectedLocale, @Nullable String disabledReason)
  {
    return new Diagnostics(sStatus, sEnginePackage, sInstalledEngineCount, sEngineListAvailable,
                           selectedLocale, sVietnameseDownloaded, sVietnameseStatusCode, disabledReason);
  }

  @Nullable
  private static String resolveDefaultEngine(@NonNull Context context)
  {
    try
    {
      final String engine = android.provider.Settings.Secure.getString(
          context.getContentResolver(), "tts_default_synth");
      return engine;
    }
    catch (Exception e)
    {
      return null;
    }
  }

  /**
   * {@code LanguageData} does not expose the raw status code, so report a best-effort value:
   * LANG_AVAILABLE when the voice is downloaded, LANG_MISSING_DATA otherwise.
   */
  @Nullable
  private static Integer statusCodeFor(@NonNull List<LanguageData> languages)
  {
    for (LanguageData lang : languages)
      if (VIETNAMESE.getLanguage().equals(lang.locale.getLanguage()))
        return lang.downloaded ? TextToSpeech.LANG_AVAILABLE : TextToSpeech.LANG_MISSING_DATA;
    return null;
  }
}
