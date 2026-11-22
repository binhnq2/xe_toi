package app.organicmaps.launcher.apps;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.junit.Test;

/**
 * Phase 5 gate: app drawer ordering, hiding, pinning and search (design spec §8).
 */
public class AppListTest
{
  private static final Locale VI = new Locale("vi", "VN");

  private static AppList.Entry e(String pkg, String label)
  {
    return new AppList.Entry(pkg, label, 0, false, false);
  }

  private static List<String> pkgs(List<AppList.Entry> list)
  {
    final java.util.ArrayList<String> out = new java.util.ArrayList<>();
    for (AppList.Entry x : list)
      out.add(x.packageName);
    return out;
  }

  @Test
  public void alphabeticalByLocale()
  {
    final List<AppList.Entry> list = Arrays.asList(e("c", "Camera"), e("a", "Bản đồ"), e("b", "Âm nhạc"));
    final List<String> ordered = pkgs(AppList.ordered(list, VI));
    // Vietnamese collation puts Â next to A, before B.
    assertEquals(Arrays.asList("b", "a", "c"), ordered);
  }

  @Test
  public void pinnedComeFirst()
  {
    final List<AppList.Entry> list = Arrays.asList(e("a", "Alpha"), e("z", "Zulu").withPinned(true));
    assertEquals(Arrays.asList("z", "a"), pkgs(AppList.ordered(list, VI)));
  }

  @Test
  public void recentBeforeAlphabetical()
  {
    final List<AppList.Entry> list = Arrays.asList(
        e("a", "Alpha"), new AppList.Entry("m", "Mike", 1_000L, false, false));
    assertEquals(Arrays.asList("m", "a"), pkgs(AppList.ordered(list, VI)));
  }

  @Test
  public void pinnedBeatsRecent()
  {
    final List<AppList.Entry> list = Arrays.asList(
        new AppList.Entry("r", "Recent", 9_999L, false, false),
        new AppList.Entry("p", "Pinned", 0L, true, false));
    assertEquals(Arrays.asList("p", "r"), pkgs(AppList.ordered(list, VI)));
  }

  @Test
  public void hiddenEntriesAreExcluded()
  {
    final List<AppList.Entry> list = Arrays.asList(e("a", "Alpha"), e("b", "Beta").withHidden(true));
    assertEquals(Collections.singletonList("a"), pkgs(AppList.ordered(list, VI)));
  }

  /** Hidden apps must not be reachable by search either. */
  @Test
  public void hiddenAppsAreNotSearchable()
  {
    final List<AppList.Entry> list = Arrays.asList(e("a", "Alpha"), e("s", "Secret").withHidden(true));
    final List<AppList.Entry> visible = AppList.ordered(list, VI);
    assertTrue(AppList.search(visible, "secret").isEmpty());
  }

  @Test
  public void searchIsAccentInsensitive()
  {
    final List<AppList.Entry> list = Collections.singletonList(e("d", "Điện thoại"));
    assertEquals(1, AppList.search(AppList.ordered(list, VI), "dien").size());
    assertEquals(1, AppList.search(AppList.ordered(list, VI), "thoai").size());
    // "Đ" is not an NFD-decomposable diacritic, so it is mapped explicitly.
    assertEquals(1, AppList.search(AppList.ordered(list, VI), "điện").size());
  }

  @Test
  public void searchIsCaseInsensitive()
  {
    final List<AppList.Entry> list = Collections.singletonList(e("c", "Camera"));
    assertEquals(1, AppList.search(AppList.ordered(list, VI), "CAM").size());
  }

  @Test
  public void emptyQueryReturnsEverything()
  {
    final List<AppList.Entry> list = Arrays.asList(e("a", "A"), e("b", "B"));
    assertEquals(2, AppList.search(AppList.ordered(list, VI), "").size());
    assertEquals(2, AppList.search(AppList.ordered(list, VI), null).size());
  }

  @Test
  public void userFlagsAreApplied()
  {
    final List<AppList.Entry> list = Arrays.asList(e("a", "Alpha"), e("b", "Beta"));
    final Set<String> pinned = new HashSet<>(Collections.singletonList("b"));
    final Set<String> hidden = Collections.singleton("a");
    final List<AppList.Entry> applied = AppList.applyUserFlags(list, pinned, hidden);
    final List<AppList.Entry> visible = AppList.ordered(applied, VI);
    assertEquals(Collections.singletonList("b"), pkgs(visible));
    assertTrue(visible.get(0).pinned);
  }

  /** Ordering must be deterministic: the same input twice gives the same output. */
  @Test
  public void orderingIsStable()
  {
    final List<AppList.Entry> list = Arrays.asList(e("a", "Alpha"), e("b", "Beta"), e("c", "Gamma"));
    assertEquals(pkgs(AppList.ordered(list, VI)), pkgs(AppList.ordered(list, VI)));
  }
}
