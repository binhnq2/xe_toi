package app.organicmaps.launcher;

import android.content.Context;
import android.content.Intent;
import androidx.annotation.NonNull;
import app.organicmaps.search.SearchActivity;

/**
 * Routes a Home search query into the app's existing search screen.
 *
 * <p>Kept as a separate class so {@code Ts8HomeLauncherView} does not hard-wire a second search
 * implementation: there is exactly one search path in Organic Maps, and the launcher only supplies
 * the initial query through the intent extras that {@code SearchFragment} already reads
 * ({@code SearchFragment.java:374}).
 */
final class LauncherSearchDispatcher
{
  private LauncherSearchDispatcher() {}

  static void dispatch(@NonNull Context context, @NonNull String query)
  {
    final Intent intent = SearchActivity.createIntent(context, query);
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    context.startActivity(intent);
  }}
