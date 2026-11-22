package app.organicmaps.sdk.assistant;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Offline command parser (design spec §9).
 *
 * <p>Offline is the reliable path: it must work with no ESP32, no network and no AI. It recognises a
 * small allowlist of Vietnamese and English phrases and maps them to an {@link Intent}. Anything
 * outside the allowlist is {@link Kind#UNKNOWN}, which the UI reports rather than guesses at.
 *
 * <p>Deliberately not a language model: exact-phrase matching keeps it deterministic, testable and
 * free of hallucinated actions. The online path (schema-validated intents from a configured
 * endpoint) is a separate class and can only produce intents from the same allowlist.
 */
public final class OfflineCommandParser
{
  /** Intents the assistant can execute. Anything not listed is not executable. */
  public enum Kind
  {
    UNKNOWN,
    OPEN_MAP,
    NAVIGATE_HOME,
    NAVIGATE_WORK,
    STOP_NAVIGATION,
    REPEAT_MANEUVER,
    MUTE_GUIDANCE,
    UNMUTE_GUIDANCE,
    ZOOM_IN,
    ZOOM_OUT,
    RECENTER,
    MEDIA_PLAY_PAUSE,
    MEDIA_NEXT,
    MEDIA_PREVIOUS,
    VOLUME_UP,
    VOLUME_DOWN,
    GO_HOME_SCREEN
  }

  /** Parsed intent. {@code consequential} actions require confirmation per the spec. */
  public static final class Intent
  {
    @NonNull
    public final Kind kind;
    /** True when the action changes navigation or device state and must be confirmed. */
    public final boolean consequential;

    Intent(@NonNull Kind kind, boolean consequential)
    {
      this.kind = kind;
      this.consequential = consequential;
    }

    @NonNull
    @Override
    public String toString()
    {
      return kind + (consequential ? " (confirm)" : "");
    }
  }

  /** Phrase table: normalised phrase -> intent. Seeded from the spec's offline examples. */
  @NonNull
  private static final Map<String, Kind> PHRASES = new LinkedHashMap<>();
  static
  {
    // Vietnamese
    put("mo ban do", Kind.OPEN_MAP);
    put("mo ban do di", Kind.OPEN_MAP);
    put("dan duong ve nha", Kind.NAVIGATE_HOME);
    put("ve nha", Kind.NAVIGATE_HOME);
    put("dan duong toi cong ty", Kind.NAVIGATE_WORK);
    put("toi cong ty", Kind.NAVIGATE_WORK);
    put("dung dan duong", Kind.STOP_NAVIGATION);
    put("huy duong di", Kind.STOP_NAVIGATION);
    put("nhac lai", Kind.REPEAT_MANEUVER);
    put("doc lai huong dan", Kind.REPEAT_MANEUVER);
    put("tat tieng", Kind.MUTE_GUIDANCE);
    put("tat huong dan", Kind.MUTE_GUIDANCE);
    put("bat tieng", Kind.UNMUTE_GUIDANCE);
    put("bat huong dan", Kind.UNMUTE_GUIDANCE);
    put("phong to", Kind.ZOOM_IN);
    put("thu nho", Kind.ZOOM_OUT);
    put("can giua", Kind.RECENTER);
    put("ve vi tri", Kind.RECENTER);
    put("phat nhac", Kind.MEDIA_PLAY_PAUSE);
    put("tam dung nhac", Kind.MEDIA_PLAY_PAUSE);
    put("bai tiep", Kind.MEDIA_NEXT);
    put("bai truoc", Kind.MEDIA_PREVIOUS);
    put("tang am luong", Kind.VOLUME_UP);
    put("giam am luong", Kind.VOLUME_DOWN);
    put("ve man hinh chinh", Kind.GO_HOME_SCREEN);

    // English
    put("open map", Kind.OPEN_MAP);
    put("navigate home", Kind.NAVIGATE_HOME);
    put("go home", Kind.NAVIGATE_HOME);
    put("navigate to work", Kind.NAVIGATE_WORK);
    put("stop navigation", Kind.STOP_NAVIGATION);
    put("repeat", Kind.REPEAT_MANEUVER);
    put("repeat instruction", Kind.REPEAT_MANEUVER);
    put("mute", Kind.MUTE_GUIDANCE);
    put("unmute", Kind.UNMUTE_GUIDANCE);
    put("zoom in", Kind.ZOOM_IN);
    put("zoom out", Kind.ZOOM_OUT);
    put("recenter", Kind.RECENTER);
    put("play", Kind.MEDIA_PLAY_PAUSE);
    put("pause", Kind.MEDIA_PLAY_PAUSE);
    put("next track", Kind.MEDIA_NEXT);
    put("previous track", Kind.MEDIA_PREVIOUS);
    put("volume up", Kind.VOLUME_UP);
    put("volume down", Kind.VOLUME_DOWN);
    put("home screen", Kind.GO_HOME_SCREEN);
  }

  private OfflineCommandParser() {}

  private static void put(@NonNull String phrase, @NonNull Kind kind)
  {
    PHRASES.put(phrase, kind);
  }

  /**
   * Parses a spoken command.
   *
   * @return an intent; {@link Kind#UNKNOWN} when the phrase is outside the allowlist
   */
  @NonNull
  public static Intent parse(@Nullable String spoken)
  {
    if (spoken == null)
      return new Intent(Kind.UNKNOWN, false);

    final String normalised = normalise(spoken);
    if (normalised.isEmpty())
      return new Intent(Kind.UNKNOWN, false);

    final Kind kind = PHRASES.get(normalised);
    if (kind == null)
    {
      // Also accept phrases that merely contain a known verb phrase, e.g. "hey, stop navigation
      // please". Longest match wins so "next track" is not shadowed by "next".
      Kind best = null;
      int bestLen = 0;
      for (Map.Entry<String, Kind> e : PHRASES.entrySet())
      {
        if (e.getKey().length() > bestLen && normalised.contains(e.getKey()))
        {
          best = e.getValue();
          bestLen = e.getKey().length();
        }
      }
      if (best == null)
        return new Intent(Kind.UNKNOWN, false);
      return new Intent(best, isConsequential(best));
    }
    return new Intent(kind, isConsequential(kind));
  }

  /** Actions that change navigation or device state need an explicit confirmation. */
  public static boolean isConsequential(@NonNull Kind kind)
  {
    switch (kind)
    {
      case NAVIGATE_HOME:
      case NAVIGATE_WORK:
      case STOP_NAVIGATION:
      case GO_HOME_SCREEN:
        return true;
      default:
        return false;
    }
  }

  /** Diacritic-insensitive lowercase with collapsed whitespace, matching how ASR output varies. */
  @NonNull
  static String normalise(@NonNull String s)
  {
    final String decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
    final StringBuilder sb = new StringBuilder(decomposed.length());
    boolean lastSpace = true;
    for (int i = 0; i < decomposed.length(); i++)
    {
      final char c = decomposed.charAt(i);
      if (Character.getType(c) == Character.NON_SPACING_MARK)
        continue;
      final char lower = Character.toLowerCase(c);
      if (lower == 'đ')
      {
        sb.append('d');
        lastSpace = false;
      }
      else if (Character.isLetterOrDigit(lower))
      {
        sb.append(lower);
        lastSpace = false;
      }
      else if (!lastSpace)
      {
        sb.append(' ');
        lastSpace = true;
      }
    }
    final String out = sb.toString().trim();
    return out;
  }

  /** True when this intent is in the allowlist at all. */
  public static boolean isAllowed(@NonNull Kind kind)
  {
    return kind != Kind.UNKNOWN;
  }

  /** Locale the phrase table covers, for the settings UI. */
  @NonNull
  public static Locale[] supportedLocales()
  {
    return new Locale[] {new Locale("vi", "VN"), Locale.US};
  }
}
