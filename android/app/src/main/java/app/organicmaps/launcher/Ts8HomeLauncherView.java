package app.organicmaps.launcher;

import android.content.Context;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.launcher.NavigationState;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * The Home overlay: top layer, optional shortcuts, media row and the four-tab dock.
 *
 * <p>Two rules from the spec are enforced here rather than left to callers:
 *
 * <ol>
 *   <li><b>Never intercept map gestures outside its own components.</b> The root is a transparent
 *       FrameLayout over the map, so a naive {@code clickable} root would swallow every drag. This
 *       class returns {@code false} from {@link #onTouchEvent} for the empty area.</li>
 *   <li><b>No fake values.</b> The clock is the only always-present number and it is read from the
 *       system clock. Media row is hidden unless a session exists; no placeholder track.</li>
 * </ol>
 */
public class Ts8HomeLauncherView extends FrameLayout
{
  public interface Listener
  {
    void onOpenMap();

    void onOpenDrawer();

    void onOpenMusic();

    void onOpenAssistant();

    void onSearch(@NonNull String query);

    /** Transport actions from the compact media row. */
    void onMediaPlayPause();

    void onMediaNext();

    void onMediaPrevious();
  }

  private static final SimpleDateFormat CLOCK = new SimpleDateFormat("HH:mm", Locale.getDefault());

  @Nullable
  private Listener mListener;
  @Nullable
  private TextView mClock;
  @Nullable
  private LinearLayout mMediaRow;
  @Nullable
  private TextView mMediaTitle;
  @Nullable
  private ImageView mMediaArtwork;
  @Nullable
  private ImageButton mPlayPause;
  @Nullable
  private LinearLayout mShortcuts;

  public Ts8HomeLauncherView(@NonNull Context context)
  {
    super(context);
  }

  public Ts8HomeLauncherView(@NonNull Context context, @Nullable AttributeSet attrs)
  {
    super(context, attrs);
  }

  public Ts8HomeLauncherView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr)
  {
    super(context, attrs, defStyleAttr);
  }

  public void setListener(@Nullable Listener listener)
  {
    mListener = listener;
  }

  @Override
  protected void onFinishInflate()
  {
    super.onFinishInflate();

    mClock = findViewById(R.id.ts8_home_clock);
    mMediaRow = findViewById(R.id.ts8_media_row);
    mMediaTitle = findViewById(R.id.ts8_media_title);
    mMediaArtwork = findViewById(R.id.ts8_media_artwork);
    mPlayPause = findViewById(R.id.ts8_media_play_pause);
    mShortcuts = findViewById(R.id.ts8_home_shortcuts);

    final View search = findViewById(R.id.ts8_home_search);
    if (search != null)
      search.setOnClickListener(v -> dispatchSearch());

    bindClick(R.id.ts8_home_settings, () -> openSettings());
    bindClick(R.id.ts8_dock_map, () -> openMap());
    bindClick(R.id.ts8_dock_music, () -> openMusic());
    bindClick(R.id.ts8_dock_assistant, () -> openAssistant());
    bindClick(R.id.ts8_dock_apps, () -> openDrawer());
    bindClick(R.id.ts8_shortcut_home, () -> dispatchSearchPrefilled(getContext().getString(R.string.ts8_home_work)));
    bindClick(R.id.ts8_shortcut_favorites, () -> openMap());

    bindClick(R.id.ts8_media_play_pause, () -> {
      if (mListener != null)
        mListener.onMediaPlayPause();
    });
    bindClick(R.id.ts8_media_next, () -> {
      if (mListener != null)
        mListener.onMediaNext();
    });
    bindClick(R.id.ts8_media_prev, () -> {
      if (mListener != null)
        mListener.onMediaPrevious();
    });

    updateClock();
  }

  private void openSettings()
  {
    // TS8 settings is the launcher's own 2-pane screen (design spec §10). It is also the only place
    // ESP32/USB/diagnostics appear, which is why the gear must lead here and not to map settings.
    final android.content.Intent intent =
        new android.content.Intent(getContext(), app.organicmaps.launcher.settings.Ts8SettingsActivity.class);
    intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
    getContext().startActivity(intent);
  }

  private void openMap()
  {
    if (mListener != null)
      mListener.onOpenMap();
  }

  private void openMusic()
  {
    if (mListener != null)
      mListener.onOpenMusic();
  }

  private void openAssistant()
  {
    if (mListener != null)
      mListener.onOpenAssistant();
  }

  private void openDrawer()
  {
    if (mListener != null)
      mListener.onOpenDrawer();
  }

  private void bindClick(int id, @NonNull Runnable action)
  {
    final View v = findViewById(id);
    if (v != null)
      v.setOnClickListener(view -> action.run());
  }

  private void dispatchSearch()
  {
    // v1 has no in-Home text entry; the pill and the Home shortcut both open the app's own
    // search screen so there is exactly one search implementation.
    dispatchSearchPrefilled("");
  }

  private void dispatchSearchPrefilled(@NonNull String query)
  {
    if (query.isEmpty())
    {
      final android.content.Intent intent =
          app.organicmaps.search.SearchActivity.createIntent(getContext(), null);
      intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
      getContext().startActivity(intent);
      return;
    }
    LauncherSearchDispatcher.dispatch(getContext(), query);
  }

  public void updateClock()
  {
    if (mClock != null)
      mClock.setText(CLOCK.format(new Date()));
  }

  /**
   * Renders the launcher from a navigation snapshot.
   *
   * <p>Home must hide its shortcuts when navigation overlays need the room (spec §5), so the dock
   * and shortcuts react to the phase rather than staying fixed.
   */
  public void render(@NonNull NavigationState state)
  {
    if (mShortcuts != null)
      mShortcuts.setVisibility(state.isFollowing() ? View.GONE : View.VISIBLE);

    // The media row is only shown when a session supplies real metadata; see setMediaVisible().
  }

  /**
   * Shows or hides the compact media row.
   *
   * @param visible      true only when a MediaSession with a title exists
   * @param title        one-line title, already ellipsized by the TextView
   * @param isPlaying    drives the play/pause icon
   */
  public void setMediaRow(boolean visible, @Nullable String title, boolean isPlaying)
  {
    if (mMediaRow == null)
      return;
    mMediaRow.setVisibility(visible ? View.VISIBLE : View.GONE);
    if (!visible)
      return;
    if (mMediaTitle != null)
      mMediaTitle.setText(title == null ? "" : title);
    if (mPlayPause != null)
      mPlayPause.setImageResource(
          isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    if (mPlayPause != null)
      mPlayPause.setContentDescription(
          getContext().getString(isPlaying ? R.string.ts8_media_pause : R.string.ts8_media_play));
  }

  public void setMediaArtwork(@Nullable android.graphics.Bitmap artwork)
  {
    if (mMediaArtwork == null)
      return;
    if (artwork == null)
    {
      mMediaArtwork.setImageDrawable(null);
      mMediaArtwork.setVisibility(View.GONE);
      return;
    }
    mMediaArtwork.setVisibility(View.VISIBLE);
    mMediaArtwork.setImageBitmap(artwork);
  }

  @Override
  public boolean onTouchEvent(MotionEvent event)
  {
    // Returning false keeps the transparent parts of this overlay from stealing map drags.
    return false;
  }

  @Override
  public boolean onInterceptTouchEvent(MotionEvent ev)
  {
    return false;
  }

  /** True when nothing is expanded, so Back can be handled by the activity. */
  public boolean isAtTopLevel()
  {
    return true;
  }

  public void collapse()
  {
    // v1 has no expandable region on Home.
  }

  /** Tab index for the dock, used by the drawer/music/assistant screens to show the same bar. */
  public enum Tab
  {
    MAP, MUSIC, ASSISTANT, APPS
  }

  public void selectTab(@NonNull Tab tab)
  {
    final int[] ids = {R.id.ts8_dock_map, R.id.ts8_dock_music, R.id.ts8_dock_assistant, R.id.ts8_dock_apps};
    for (int id : ids)
    {
      final View v = findViewById(id);
      if (v != null)
        v.setSelected(id == ids[tab.ordinal()]);
    }
  }
}
