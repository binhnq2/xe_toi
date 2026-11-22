package app.organicmaps.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.launcher.apps.AppList;
import app.organicmaps.launcher.apps.AppRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Process-wide launcher services: the app index and the persisted pin/hide flags.
 *
 * <p>Held off the Activity so the drawer, Home and the dock share one index. Opening the drawer a
 * second time must not re-query PackageManager, which is the spec's warm-open requirement.
 *
 * <p>Pin and hide are stored as package-name sets in a private preference file. They are user
 * choices, not app data, so they survive a restart but are never exported.
 */
public final class LauncherServices
{
  private static final String PREFS = "ts8_launcher";
  private static final String KEY_PINNED = "pinned";
  private static final String KEY_HIDDEN = "hidden";

  @Nullable
  private static volatile AppRepository sApps;

  private LauncherServices() {}

  @NonNull
  public static synchronized AppRepository apps(@NonNull Context context)
  {
    AppRepository repo = sApps;
    if (repo == null)
    {
      repo = new AppRepository(context);
      sApps = repo;
    }
    return repo;
  }

  @NonNull
  public static Set<String> pinned(@NonNull Context context)
  {
    return readSet(context, KEY_PINNED);
  }

  @NonNull
  public static Set<String> hidden(@NonNull Context context)
  {
    return readSet(context, KEY_HIDDEN);
  }

  public static void setPinned(@NonNull Context context, @NonNull String pkg, boolean value)
  {
    writeFlag(context, KEY_PINNED, pkg, value);
    if (sApps != null)
      sApps.invalidate();
  }

  public static void setHidden(@NonNull Context context, @NonNull String pkg, boolean value)
  {
    writeFlag(context, KEY_HIDDEN, pkg, value);
    if (sApps != null)
      sApps.invalidate();
  }

  /** Applies the stored flags to a freshly built index. */
  @NonNull
  public static List<AppList.Entry> withUserFlags(@NonNull Context context,
                                                  @NonNull List<AppList.Entry> entries)
  {
    final List<AppList.Entry> flagged = AppList.applyUserFlags(entries, pinned(context), hidden(context));
    final Locale locale = context.getResources().getConfiguration().getLocales().get(0);
    return AppList.ordered(flagged, locale);
  }

  private static Set<String> readSet(@NonNull Context context, @NonNull String key)
  {
    final SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    final Set<String> raw = p.getStringSet(key, Collections.emptySet());
    return raw == null ? new HashSet<>() : new HashSet<>(raw);
  }

  private static void writeFlag(@NonNull Context context, @NonNull String key, @NonNull String pkg,
                                boolean value)
  {
    final SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    final Set<String> current = readSet(context, key);
    final Set<String> next = new HashSet<>(current);
    if (value)
      next.add(pkg);
    else
      next.remove(pkg);
    // Always commit a copy: SharedPreferences must not be handed the same instance it returned.
    p.edit().putStringSet(key, next).apply();
  }

  /** Test hook. Never call from production code. */
  static void resetForTests()
  {
    final AppRepository repo = sApps;
    if (repo != null)
      repo.release();
    sApps = null;
  }
}
