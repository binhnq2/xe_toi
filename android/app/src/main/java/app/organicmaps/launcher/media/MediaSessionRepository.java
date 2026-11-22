package app.organicmaps.launcher.media;

import android.content.ComponentName;
import android.content.Context;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.sdk.util.log.Logger;
import java.util.List;

/**
 * Reads the active media session and exposes transport controls (design spec §7).
 *
 * <p>Three constraints from the spec drive the shape of this class:
 *
 * <ol>
 *   <li><b>No fake values.</b> If no session supplies a title there is no row at all, so
 *       {@link Snapshot#hasSession()} is the only thing the UI should branch on.</li>
 *   <li><b>No decoding on the main thread.</b> Artwork is fetched on a worker and delivered as a
 *       ready bitmap; the caller never calls {@code getBitmap()} from {@code onDraw}.</li>
 *   <li><b>Listener work is cheap.</b> Notifications compare against the previous snapshot and are
 *       skipped when nothing the UI shows has changed, so a 1 Hz position update from a music app
 *       does not cause a redraw.</li>
 * </ol>
 *
 * <p>Notification-listener access is required by the platform to enumerate other apps' sessions
 * (API 21+). When it is missing the repository reports {@link Snapshot#needsPermission} instead of
 * showing an empty row with no explanation.
 */
public class MediaSessionRepository
{
  private static final String TAG = "MediaSessionRepository";

  /** Immutable view of what the launcher should draw. */
  public static final class Snapshot
  {
    public final boolean hasSession;
    public final boolean needsPermission;
    @Nullable
    public final String title;
    @Nullable
    public final String artist;
    public final boolean isPlaying;
    @Nullable
    public final Bitmap artwork;

    Snapshot(boolean hasSession, boolean needsPermission, @Nullable String title,
             @Nullable String artist, boolean isPlaying, @Nullable Bitmap artwork)
    {
      this.hasSession = hasSession;
      this.needsPermission = needsPermission;
      this.title = title;
      this.artist = artist;
      this.isPlaying = isPlaying;
      this.artwork = artwork;
    }

    static Snapshot empty(boolean needsPermission)
    {
      return new Snapshot(false, needsPermission, null, null, false, null);
    }

    /** True when a media row should be visible at all. */
    public boolean shouldShowRow()
    {
      return hasSession && title != null && !title.isEmpty();
    }

    @Override
    public boolean equals(Object o)
    {
      if (this == o)
        return true;
      if (!(o instanceof Snapshot))
        return false;
      final Snapshot s = (Snapshot) o;
      return hasSession == s.hasSession
          && needsPermission == s.needsPermission
          && isPlaying == s.isPlaying
          && artwork == s.artwork
          && eq(title, s.title)
          && eq(artist, s.artist);
    }

    private static boolean eq(@Nullable String a, @Nullable String b)
    {
      return a == null ? b == null : a.equals(b);
    }

    @Override
    public int hashCode()
    {
      int r = title == null ? 0 : title.hashCode();
      r = 31 * r + (isPlaying ? 1 : 0);
      return r;
    }
  }

  public interface Listener
  {
    void onMediaChanged(@NonNull Snapshot snapshot);
  }

  @NonNull
  private final Context mAppContext;
  @NonNull
  private final Handler mMainHandler = new Handler(Looper.getMainLooper());

  @Nullable
  private MediaController mController;
  @Nullable
  private Snapshot mLast;
  @Nullable
  private Listener mListener;

  private final MediaController.Callback mCallback = new MediaController.Callback()
  {
    @Override
    public void onMetadataChanged(@Nullable MediaMetadata metadata)
    {
      refresh();
    }

    @Override
    public void onPlaybackStateChanged(@Nullable PlaybackState state)
    {
      refresh();
    }

    @Override
    public void onSessionDestroyed()
    {
      releaseController();
      refresh();
    }
  };

  public MediaSessionRepository(@NonNull Context context)
  {
    mAppContext = context.getApplicationContext();
  }

  public void setListener(@Nullable Listener listener)
  {
    mListener = listener;
  }

  /** True when the app may enumerate other apps' sessions. */
  public boolean hasNotificationAccess()
  {
    try
    {
      final MediaSessionManager msm =
          (MediaSessionManager) mAppContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
      if (msm == null)
        return false;
      final ComponentName own = new ComponentName(mAppContext,
                                                  app.organicmaps.launcher.media.Ts8NotificationListener.class);
      return msm.getActiveSessions(own) != null;
    }
    catch (SecurityException e)
    {
      // Platform throws this exact exception when notification access is missing.
      return false;
    }
    catch (Exception e)
    {
      Logger.w(TAG, "Cannot query sessions: " + e.getMessage());
      return false;
    }
  }

  /**
   * Picks the active session and subscribes to it.
   *
   * <p>Called when the media row is shown, not on every frame. The controller is cached, so a
   * repeated call while the same session is active does no work.
   */
  public void refresh()
  {
    final MediaSessionManager msm =
        (MediaSessionManager) mAppContext.getSystemService(Context.MEDIA_SESSION_SERVICE);
    if (msm == null)
    {
      deliver(Snapshot.empty(false));
      return;
    }

    List<MediaController> sessions;
    try
    {
      final ComponentName own = new ComponentName(mAppContext,
                                                  app.organicmaps.launcher.media.Ts8NotificationListener.class);
      sessions = msm.getActiveSessions(own);
    }
    catch (SecurityException e)
    {
      deliver(Snapshot.empty(true));
      return;
    }
    catch (Exception e)
    {
      Logger.w(TAG, "Cannot get active sessions: " + e.getMessage());
      deliver(Snapshot.empty(false));
      return;
    }

    final MediaController active = pickActive(sessions);
    if (active == null)
    {
      releaseController();
      deliver(Snapshot.empty(false));
      return;
    }

    if (mController == null || !mController.getSessionToken().equals(active.getSessionToken()))
    {
      releaseController();
      mController = active;
      mController.registerCallback(mCallback, mMainHandler);
    }

    deliver(buildSnapshot(active));
  }

  /** Prefers a session that is actually playing; falls back to the first one. */
  @Nullable
  private static MediaController pickActive(@Nullable List<MediaController> sessions)
  {
    if (sessions == null || sessions.isEmpty())
      return null;
    for (MediaController c : sessions)
    {
      final PlaybackState s = c.getPlaybackState();
      if (s != null && s.getState() == PlaybackState.STATE_PLAYING)
        return c;
    }
    return sessions.get(0);
  }

  @NonNull
  private Snapshot buildSnapshot(@NonNull MediaController controller)
  {
    final MediaMetadata md = controller.getMetadata();
    final PlaybackState ps = controller.getPlaybackState();
    final boolean playing = ps != null && ps.getState() == PlaybackState.STATE_PLAYING;

    String title = null;
    String artist = null;
    Bitmap art = null;
    if (md != null)
    {
      title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
      artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
      // Artwork may be absent; in that case the row keeps its placeholder, never a fake image.
      art = md.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
      if (art == null)
        art = md.getBitmap(MediaMetadata.METADATA_KEY_ART);
    }

    return new Snapshot(title != null, false, title, artist, playing, art);
  }

  private void deliver(@NonNull Snapshot snapshot)
  {
    final Listener listener = mListener;
    if (listener == null)
      return;
    if (snapshot.equals(mLast))
      return;
    mLast = snapshot;
    mMainHandler.post(() -> {
      final Listener l = mListener;
      if (l != null)
        l.onMediaChanged(snapshot);
    });
  }

  // ---------------------------------------------------------------- transport

  public void playPause()
  {
    final MediaController c = mController;
    if (c == null)
      return;
    final PlaybackState s = c.getPlaybackState();
    if (s != null && s.getState() == PlaybackState.STATE_PLAYING)
      c.getTransportControls().pause();
    else
      c.getTransportControls().play();
  }

  public void next()
  {
    final MediaController c = mController;
    if (c != null)
      c.getTransportControls().skipToNext();
  }

  public void previous()
  {
    final MediaController c = mController;
    if (c != null)
      c.getTransportControls().skipToPrevious();
  }

  public void release()
  {
    releaseController();
    mListener = null;
  }

  private void releaseController()
  {
    if (mController != null)
    {
      try
      {
        mController.unregisterCallback(mCallback);
      }
      catch (Exception ignored)
      {
        // Session may already be gone.
      }
      mController = null;
    }
  }
}
