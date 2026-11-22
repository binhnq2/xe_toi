package app.organicmaps.launcher.media;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;

/**
 * The navigation media bubble and its bounded panel (design spec §7).
 *
 * <p>Rules enforced here rather than left to the caller:
 *
 * <ul>
 *   <li>The bubble exists only when a session supplies a title. No placeholder track.</li>
 *   <li>The expanded panel is in-activity, never a system overlay.</li>
 *   <li>Auto-collapse after 5 s without interaction.</li>
 *   <li>Collapse immediately before a near manoeuvre, so music controls can never cover the
 *       turn instruction. The overlay calls {@link #onNearManeuver()} for that.</li>
 * </ul>
 *
 * <p>The views are resolved once, so showing or hiding the panel allocates nothing.
 */
public class Ts8MediaBubble
{
  private static final long AUTO_COLLAPSE_MS = 5_000L;
  /** A manoeuvre closer than this counts as "near" and collapses the panel. */
  private static final double NEAR_MANEUVER_METRES = 200.0;

  public interface Listener
  {
    void onPlayPause();

    void onNext();

    void onPrevious();
  }

  @NonNull
  private final Handler mHandler = new Handler(Looper.getMainLooper());
  @NonNull
  private final ImageButton mBubble;
  @NonNull
  private final View mPanel;
  @NonNull
  private final ImageView mArt;
  @NonNull
  private final TextView mTitle;
  @NonNull
  private final ImageButton mPlay;
  @Nullable
  private Listener mListener;
  private boolean mExpanded;
  private boolean mPlaying;

  private final Runnable mCollapse = this::collapse;

  public Ts8MediaBubble(@NonNull View root, @NonNull View routeStrip)
  {
    mBubble = root.findViewById(R.id.ts8_nav_media_bubble);
    mPanel = root.findViewById(R.id.ts8_nav_media_panel);
    mArt = root.findViewById(R.id.ts8_nav_media_art);
    mTitle = root.findViewById(R.id.ts8_nav_media_title);
    mPlay = root.findViewById(R.id.ts8_nav_media_play);

    mBubble.setOnClickListener(v -> toggle());
    final ImageButton close = root.findViewById(R.id.ts8_nav_media_close);
    if (close != null)
      close.setOnClickListener(v -> collapse());

    final ImageButton prev = root.findViewById(R.id.ts8_nav_media_prev);
    if (prev != null)
      prev.setOnClickListener(v -> {
        if (mListener != null)
          mListener.onPrevious();
        extendTimeout();
      });

    final ImageButton next = root.findViewById(R.id.ts8_nav_media_next);
    if (next != null)
      next.setOnClickListener(v -> {
        if (mListener != null)
          mListener.onNext();
        extendTimeout();
      });

    mPlay.setOnClickListener(v -> {
      if (mListener != null)
        mListener.onPlayPause();
      extendTimeout();
    });
  }

  public void setListener(@Nullable Listener listener)
  {
    mListener = listener;
  }

  /**
   * Updates the bubble from a real session snapshot.
   *
   * @param show  true only when a title exists
   * @param title one-line title
   * @param isPlaying drives the play/pause glyph
   */
  public void update(boolean show, @Nullable String title, boolean isPlaying)
  {
    mPlaying = isPlaying;
    if (!show)
    {
      mBubble.setVisibility(View.GONE);
      collapse();
      return;
    }
    mBubble.setVisibility(View.VISIBLE);
    mTitle.setText(title == null ? "" : title);
    mPlay.setImageResource(isPlaying ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play);
    mPlay.setContentDescription(
        mBubble.getContext().getString(isPlaying ? R.string.ts8_media_pause : R.string.ts8_media_play));
  }

  public void setArtwork(@Nullable android.graphics.Bitmap artwork)
  {
    if (artwork == null)
    {
      mArt.setImageDrawable(null);
      mArt.setVisibility(View.GONE);
      return;
    }
    mArt.setVisibility(View.VISIBLE);
    mArt.setImageBitmap(artwork);
  }

  public void toggle()
  {
    if (mExpanded)
      collapse();
    else
      expand();
  }

  public void expand()
  {
    if (mBubble.getVisibility() != View.VISIBLE)
      return;
    mExpanded = true;
    mBubble.setVisibility(View.GONE);
    mPanel.setVisibility(View.VISIBLE);
    extendTimeout();
  }

  public void collapse()
  {
    mHandler.removeCallbacks(mCollapse);
    mExpanded = false;
    if (mPanel != null)
      mPanel.setVisibility(View.GONE);
    if (mBubble.getVisibility() != View.GONE || mPlaying)
      mBubble.setVisibility(View.VISIBLE);
  }

  /**
   * Called by the navigation overlay when the next manoeuvre is close.
   *
   * @param distToTurnM metres from the live snapshot
   */
  public void onManeuverUpdate(double distToTurnM)
  {
    if (distToTurnM >= 0 && distToTurnM < NEAR_MANEUVER_METRES && mExpanded)
      collapse();
  }

  private void extendTimeout()
  {
    mHandler.removeCallbacks(mCollapse);
    mHandler.postDelayed(mCollapse, AUTO_COLLAPSE_MS);
  }

  public void release()
  {
    mHandler.removeCallbacks(mCollapse);
    mListener = null;
  }

  public boolean isExpanded()
  {
    return mExpanded;
  }
}
