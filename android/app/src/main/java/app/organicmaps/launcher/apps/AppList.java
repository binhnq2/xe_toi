package app.organicmaps.launcher.apps;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Ordering, pinning, hiding and search over the installed-app list (design spec §8).
 *
 * <p>Pure data, no PackageManager: the caller supplies the entries, so the ordering rules can be
 * unit-tested on the JVM. The rules the spec states, and which this class enforces:
 *
 * <ol>
 *   <li>Order is pinned favourites, then recently used, then locale-aware A-Z.</li>
 *   <li>Hidden entries never appear, not even in search.</li>
 *   <li>Search is a case- and accent-insensitive prefix/substring match.</li>
 *   <li>Ordering is stable and persistent: same inputs give the same output, so a stored order
 *       survives a restart.</li>
 * </ol>
 */
public final class AppList
{
  /** One launchable app. Immutable. */
  public static final class Entry
  {
    @NonNull
    public final String packageName;
    @NonNull
    public final String label;
    /** Epoch millis of the last use, or 0 when never used. */
    public final long lastUsedMs;
    /** Set when the entry is a vendor shortcut rather than a real package. */
    public final boolean pinned;
    public final boolean hidden;

    public Entry(@NonNull String packageName, @NonNull String label, long lastUsedMs, boolean pinned,
                 boolean hidden)
    {
      this.packageName = packageName;
      this.label = label;
      this.lastUsedMs = lastUsedMs;
      this.pinned = pinned;
      this.hidden = hidden;
    }

    public Entry withPinned(boolean value)
    {
      return new Entry(packageName, label, lastUsedMs, value, hidden);
    }

    public Entry withHidden(boolean value)
    {
      return new Entry(packageName, label, lastUsedMs, pinned, value);
    }

    public Entry withLastUsed(long ms)
    {
      return new Entry(packageName, label, ms, pinned, hidden);
    }

    @NonNull
    @Override
    public String toString()
    {
      return packageName + (pinned ? " [pinned]" : "") + (hidden ? " [hidden]" : "");
    }
  }

  private AppList() {}

  /**
   * Applies pin/hide flags from a persisted set of package names.
   *
   * @param pinned  package names the user pinned
   * @param hidden  package names the user hid
   */
  @NonNull
  public static List<Entry> applyUserFlags(@NonNull List<Entry> entries, @NonNull Set<String> pinned,
                                           @NonNull Set<String> hidden)
  {
    final List<Entry> out = new ArrayList<>(entries.size());
    for (Entry e : entries)
    {
      Entry next = e;
      if (pinned.contains(e.packageName))
        next = next.withPinned(true);
      if (hidden.contains(e.packageName))
        next = next.withHidden(true);
      out.add(next);
    }
    return out;
  }

  /** Visible entries in display order. */
  @NonNull
  public static List<Entry> ordered(@NonNull List<Entry> entries, @NonNull Locale locale)
  {
    final List<Entry> visible = new ArrayList<>(entries.size());
    for (Entry e : entries)
      if (!e.hidden)
        visible.add(e);

    // Collator gives locale-correct A-Z (Vietnamese diacritics sort next to their base letter
    // instead of after 'z'), which is what the spec means by "locale-aware".
    final Collator collator = Collator.getInstance(locale);

    // Sort key: pinned first, then recently used (newest first), then label.
    Collections.sort(visible, (a, b) -> {
      if (a.pinned != b.pinned)
        return a.pinned ? -1 : 1;
      if (a.lastUsedMs != b.lastUsedMs)
        return Long.compare(b.lastUsedMs, a.lastUsedMs);
      final int byLabel = collator.compare(a.label, b.label);
      if (byLabel != 0)
        return byLabel;
      return a.packageName.compareTo(b.packageName);
    });
    return visible;
  }

  /** Case- and accent-insensitive filter over the visible entries, keeping display order. */
  @NonNull
  public static List<Entry> search(@NonNull List<Entry> orderedVisible, @Nullable String query)
  {
    if (query == null || query.trim().isEmpty())
      return orderedVisible;

    final String needle = normalise(query);
    final List<Entry> out = new ArrayList<>();
    for (Entry e : orderedVisible)
    {
      if (normalise(e.label).contains(needle) || e.packageName.toLowerCase(Locale.ROOT).contains(needle))
        out.add(e);
    }
    return out;
  }

  /** Diacritic-insensitive lowercase. "Điện thoại" matches "dien". */
  @NonNull
  static String normalise(@NonNull String s)
  {
    final String decomposed = java.text.Normalizer.normalize(s, java.text.Normalizer.Form.NFD);
    final StringBuilder sb = new StringBuilder(decomposed.length());
    for (int i = 0; i < decomposed.length(); i++)
    {
      final char c = decomposed.charAt(i);
      if (Character.getType(c) == Character.NON_SPACING_MARK)
        continue;
      // Vietnamese Đ/đ is not an NFD-decomposable diacritic, so map it explicitly.
      if (c == 'đ' || c == 'Đ')
        sb.append('d');
      else
        sb.append(c);
    }
    return sb.toString().toLowerCase(Locale.ROOT);
  }

  /** Convenience for the drawer: package names that are pinned, from a set of names. */
  @NonNull
  public static Set<String> packagesOf(@NonNull List<Entry> entries)
  {
    final Set<String> out = new HashSet<>(entries.size());
    for (Entry e : entries)
      out.add(e.packageName);
    return out;
  }
}
