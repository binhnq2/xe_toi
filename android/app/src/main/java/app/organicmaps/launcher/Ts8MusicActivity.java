package app.organicmaps.launcher;

import android.os.Bundle;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.base.BaseMwmFragmentActivity;

/**
 * Media screen host (design spec §7).
 *
 * <p>Phase 1 scope only: launchable, shares the dock. The MediaSession integration, bubble and
 * auto-collapse behaviour are Phase 4.
 */
public class Ts8MusicActivity extends BaseMwmFragmentActivity
{
  @Override
  protected int getContentLayoutResId()
  {
    return R.layout.activity_ts8_music;
  }

  @Override
  protected void onSafeCreate(@Nullable Bundle savedInstanceState)
  {
    super.onSafeCreate(savedInstanceState);
  }
}
