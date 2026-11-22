package app.organicmaps.search;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.fragment.app.Fragment;
import app.organicmaps.base.BaseMwmFragmentActivity;
import app.organicmaps.util.ThemeUtils;

public class SearchActivity extends BaseMwmFragmentActivity
{
  public static final String EXTRA_QUERY = "search_query";
  public static final String EXTRA_LOCALE = "locale";
  public static final String EXTRA_SEARCH_ON_MAP = "search_on_map";

  public static void start(@NonNull Activity activity, @Nullable String query)
  {
    start(activity, query, null /* locale */, false /* isSearchOnMap */);
  }

  public static void start(@NonNull Activity activity, @Nullable String query, @Nullable String locale,
                           boolean isSearchOnMap)
  {
    activity.startActivity(createIntent(activity, query, locale, isSearchOnMap));
  }

  /**
   * Builds the same intent {@link #start} uses.
   *
   * <p>TS8 Lite's launcher needs to launch search from a non-Activity {@code Context}
   * (a View holding the app context), so the intent construction is factored out instead of
   * duplicating the extras contract in the launcher. Extras are identical; behaviour is unchanged.
   */
  @NonNull
  public static Intent createIntent(@NonNull android.content.Context context, @Nullable String query)
  {
    return createIntent(context, query, null /* locale */, false /* isSearchOnMap */);
  }

  @NonNull
  public static Intent createIntent(@NonNull android.content.Context context, @Nullable String query,
                                    @Nullable String locale, boolean isSearchOnMap)
  {
    final Intent i = new Intent(context, SearchActivity.class);
    Bundle args = new Bundle();
    args.putString(EXTRA_QUERY, query);
    args.putString(EXTRA_LOCALE, locale);
    args.putBoolean(EXTRA_SEARCH_ON_MAP, isSearchOnMap);
    i.putExtras(args);
    return i;
  }

  @Override
  @StyleRes
  public int getThemeResourceId(@NonNull String theme)
  {
    return ThemeUtils.getCardBgThemeResourceId(getApplicationContext(), theme);
  }

  @Override
  protected Class<? extends Fragment> getFragmentClass()
  {
    return SearchFragment.class;
  }
}
