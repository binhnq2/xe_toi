package app.organicmaps.launcher;

import android.content.Intent;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.MwmActivity;
import app.organicmaps.R;
import androidx.appcompat.app.AppCompatActivity;
import app.organicmaps.sdk.launcher.NavigationState;import app.organicmaps.sdk.launcher.OrganicMapsFacade;
import app.organicmaps.sdk.util.log.Logger;

/**
 * HOME/DEFAULT/LAUNCHER entry point for TS8 Lite.
 *
 * <p>This activity is a thin shell, on purpose. The audit found that {@code MapFragment} binds the
 * renderer to its host Activity ({@code MapFragment.java:87}); hosting a second renderer here would
 * rebuild the map engine every time the user pressed Home, which the design spec forbids
 * ("Pressing Android Home always returns here without rebuilding the map engine").
 *
 * <p>So the shell does not own a renderer. It attaches to the existing {@link MwmActivity} and
 * presents the launcher overlay above it. When the user opens the map, the overlay is dismissed and
 * the already-running map activity is brought forward - no engine teardown, no route loss.
 *
 * <p>Diagnostics rule from the spec: this screen never shows USB, BLE or ESP32 status.
 */
public class Ts8HomeActivity extends AppCompatActivity implements OrganicMapsFacade.NavigationListener,
                                                                           app.organicmaps.launcher.media.MediaSessionRepository.Listener
{
  private static final String TAG = "Ts8HomeActivity";

  @Nullable
  private Ts8HomeLauncherView mLauncherView;
  @Nullable
  private app.organicmaps.launcher.media.MediaSessionRepository mMedia;

  @Override
  protected void onCreate(@Nullable Bundle savedInstanceState)
  {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_ts8_home);

    mLauncherView = findViewById(R.id.ts8_home_root);
    if (mLauncherView == null)
    {
      Logger.e(TAG, "activity_ts8_home has no ts8_home_root");
      return;
    }

    mLauncherView.setListener(mLauncherListener);
    mLauncherView.render(facade().getNavigationState());
    facade().addNavigationListener(this);

    // Media row shows real session data or nothing at all (design spec §5, §7).
    mMedia = new app.organicmaps.launcher.media.MediaSessionRepository(this);
    mMedia.setListener(this);
  }

  @Override
  protected void onResume()
  {
    super.onResume();
    // Coming back from the map: refresh from the live routing state.
    LauncherFacadeHolder.impl(this).poll();
    if (mLauncherView != null)
      mLauncherView.render(facade().getNavigationState());

    // Query the active session only when the screen is actually visible.
    if (mMedia != null)
      mMedia.refresh();
  }

  @Override
  protected void onPause()
  {
    super.onPause();
    if (mMedia != null)
      mMedia.release();
  }

  @Override
  protected void onDestroy()
  {
    facade().removeNavigationListener(this);
    if (mMedia != null)
      mMedia.release();
    super.onDestroy();
  }

  // ---------------------------------------------------------------- media callback

  @Override
  public void onMediaChanged(@NonNull app.organicmaps.launcher.media.MediaSessionRepository.Snapshot snapshot)
  {
    final Ts8HomeLauncherView view = mLauncherView;
    if (view == null)
      return;

    if (snapshot.needsPermission)
    {
      // Explain instead of showing an inert row (design spec §10: never silently disabled).
      view.setMediaRow(true, getString(R.string.ts8_music_permission_required), false);
      view.setMediaArtwork(null);
      return;
    }

    view.setMediaRow(snapshot.shouldShowRow(), snapshot.title, snapshot.isPlaying);
    if (snapshot.shouldShowRow())
      view.setMediaArtwork(snapshot.artwork);
  }

  @NonNull
  private OrganicMapsFacade facade()
  {
    return LauncherFacadeHolder.get(this);
  }

  @Override
  public void onNavigationStateChanged(@NonNull NavigationState state)
  {
    if (mLauncherView != null)
      mLauncherView.render(state);
  }

  private final Ts8HomeLauncherView.Listener mLauncherListener = new Ts8HomeLauncherView.Listener()
  {
    @Override
    public void onOpenMap()
    {
      // Bring the existing map activity forward instead of creating a new one.
      final Intent intent = new Intent(Ts8HomeActivity.this, MwmActivity.class);
      intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
      startActivity(intent);
    }

    @Override
    public void onOpenDrawer()
    {
      startActivity(new Intent(Ts8HomeActivity.this, Ts8AppDrawerActivity.class));
    }

    @Override
    public void onOpenMusic()
    {
      startActivity(new Intent(Ts8HomeActivity.this, Ts8MusicActivity.class));
    }

    @Override
    public void onOpenAssistant()
    {
      startActivity(new Intent(Ts8HomeActivity.this, Ts8AssistantActivity.class));
    }

    @Override
    public void onSearch(@NonNull String query)
    {
      LauncherSearchDispatcher.dispatch(Ts8HomeActivity.this, query);
    }

    @Override
    public void onMediaPlayPause()
    {
      if (mMedia != null)
        mMedia.playPause();
    }

    @Override
    public void onMediaNext()
    {
      if (mMedia != null)
        mMedia.next();
    }

    @Override
    public void onMediaPrevious()
    {
      if (mMedia != null)
        mMedia.previous();
    }
  };

  /** Keeps the back button from leaving the launcher when this is the home screen. */
  @Override
  public void onBackPressed()
  {
    if (mLauncherView != null && !mLauncherView.isAtTopLevel())
    {
      mLauncherView.collapse();
      return;
    }
    // On the home screen, Back must not exit to a blank screen.
    moveTaskToBack(false);
  }
}
