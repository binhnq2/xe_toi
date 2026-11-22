package app.organicmaps.launcher;

import android.os.Bundle;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.base.BaseMwmFragmentActivity;

/**
 * Assistant sheet host (design spec §9).
 *
 * <p>Phase 1 scope only: launchable, shares the dock. The offline intent allowlist, online path and
 * confirmation flow are Phase 8.
 */
public class Ts8AssistantActivity extends BaseMwmFragmentActivity
{
  @Override
  protected int getContentLayoutResId()
  {
    return R.layout.activity_ts8_assistant;
  }

  @Override
  protected void onSafeCreate(@Nullable Bundle savedInstanceState)
  {
    super.onSafeCreate(savedInstanceState);
  }
}
