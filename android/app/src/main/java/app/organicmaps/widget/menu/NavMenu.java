package app.organicmaps.widget.menu;

import android.location.Location;
import android.util.Pair;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.sound.TtsPlayer;
import app.organicmaps.sdk.util.StringUtils;
import app.organicmaps.sdk.util.UiUtils;
import app.organicmaps.util.Graphics;
import app.organicmaps.util.ThemeUtils;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.TimeUnit;

public class NavMenu
{
  private final BottomSheetBehavior<View> mNavBottomSheetBehavior;
  private final View mBottomSheetBackground;
  private final View mHeaderFrame;

  private final ImageView mTts;
  private final View mSpeedViewContainer;
  private final TextView mSpeedValue;
  private final TextView mSpeedUnits;

  /**
   * TS8 NAV: the bottom strip no longer owns its own speed-warning state.
   *
   * <p>{@code NavigationController} is the single owner and passes the already-evaluated result in
   * via {@link #updateSpeedView(RoutingInfo, SpeedLimitState.Result)}, so the strip and the
   * navigation panel cannot disagree - which would happen with one state machine per view.
   */
  private final TextView mTimeHourValue;
  private final TextView mTimeHourUnits;
  private final TextView mTimeMinuteValue;
  private final TextView mTimeMinuteUnits;
  private final TextView mTimeEstimate;
  private final TextView mDistanceValue;
  private final TextView mDistanceUnits;
  private final LinearProgressIndicator mRouteProgress;

  private final AppCompatActivity mActivity;
  private final NavMenuListener mNavMenuListener;

  private int currentPeekHeight = 0;

  public interface OnMenuSizeChangedListener
  {
    void OnMenuSizeChange();
  }

  private final OnMenuSizeChangedListener mOnMenuSizeChangedListener;

  public NavMenu(AppCompatActivity activity, NavMenuListener navMenuListener,
                 OnMenuSizeChangedListener onMenuSizeChangedListener)
  {
    mActivity = activity;
    mNavMenuListener = navMenuListener;
    final View bottomFrame = mActivity.findViewById(R.id.nav_bottom_frame);
    mHeaderFrame = bottomFrame.findViewById(R.id.line_frame);
    mOnMenuSizeChangedListener = onMenuSizeChangedListener;
    mHeaderFrame.setOnClickListener(v -> toggleNavMenu());
    mHeaderFrame.addOnLayoutChangeListener((view, i, i1, i2, i3, i4, i5, i6, i7) -> setPeekHeight());
    mNavBottomSheetBehavior = BottomSheetBehavior.from(mActivity.findViewById(R.id.nav_bottom_sheet));
    mBottomSheetBackground = mActivity.findViewById(R.id.nav_bottom_sheet_background);
    mBottomSheetBackground.setOnClickListener(v -> collapseNavBottomSheet());
    mBottomSheetBackground.setVisibility(View.GONE);
    mBottomSheetBackground.setAlpha(0);
    mNavBottomSheetBehavior.addBottomSheetCallback(new BottomSheetBehavior.BottomSheetCallback() {
      @Override
      public void onStateChanged(@NonNull View bottomSheet, int newState)
      {
        if (newState == BottomSheetBehavior.STATE_COLLAPSED || newState == BottomSheetBehavior.STATE_HIDDEN)
        {
          mBottomSheetBackground.setVisibility(View.GONE);
          mBottomSheetBackground.setAlpha(0);
        }
        else
        {
          mBottomSheetBackground.setVisibility(View.VISIBLE);
        }
      }

      @Override
      public void onSlide(@NonNull View bottomSheet, float slideOffset)
      {
        mBottomSheetBackground.setAlpha(slideOffset);
      }
    });

    // Bottom frame
    mSpeedViewContainer = bottomFrame.findViewById(R.id.speed_view_container);
    mSpeedValue = bottomFrame.findViewById(R.id.speed_value);
    mSpeedUnits = bottomFrame.findViewById(R.id.speed_dimen);
    mTimeHourValue = bottomFrame.findViewById(R.id.time_hour_value);
    mTimeHourUnits = bottomFrame.findViewById(R.id.time_hour_dimen);
    mTimeMinuteValue = bottomFrame.findViewById(R.id.time_minute_value);
    mTimeMinuteUnits = bottomFrame.findViewById(R.id.time_minute_dimen);
    mTimeEstimate = bottomFrame.findViewById(R.id.time_estimate);
    mDistanceValue = bottomFrame.findViewById(R.id.distance_value);
    mDistanceUnits = bottomFrame.findViewById(R.id.distance_dimen);
    mRouteProgress = bottomFrame.findViewById(R.id.navigation_progress);

    // Bottom frame buttons
    ImageView mSettings = bottomFrame.findViewById(R.id.settings);
    mSettings.setOnClickListener(v -> onSettingsClicked());
    mTts = bottomFrame.findViewById(R.id.tts_volume);
    mTts.setOnClickListener(v -> onTtsClicked());
    Button stop = bottomFrame.findViewById(R.id.stop);
    stop.setOnClickListener(v -> onStopClicked());
    UiUtils.updateRedButton(stop);
  }

  private void onStopClicked()
  {
    mNavMenuListener.onStopClicked();
  }

  private void onSettingsClicked()
  {
    mNavMenuListener.onSettingsClicked();
  }

  private void onTtsClicked()
  {
    TtsPlayer.setEnabled(!TtsPlayer.isEnabled());
    refreshTts();
  }

  private void toggleNavMenu()
  {
    if (getBottomSheetState() == BottomSheetBehavior.STATE_EXPANDED)
      collapseNavBottomSheet();
    else
      expandNavBottomSheet();
  }

  public void setPeekHeight()
  {
    int headerHeight = mHeaderFrame.getHeight();
    if (currentPeekHeight != headerHeight)
    {
      currentPeekHeight = headerHeight;
      mNavBottomSheetBehavior.setPeekHeight(currentPeekHeight);
      mOnMenuSizeChangedListener.OnMenuSizeChange();
    }
  }

  public void collapseNavBottomSheet()
  {
    mNavBottomSheetBehavior.setState(BottomSheetBehavior.STATE_COLLAPSED);
  }

  public void expandNavBottomSheet()
  {
    mNavBottomSheetBehavior.setState(BottomSheetBehavior.STATE_EXPANDED);
  }

  public int getBottomSheetState()
  {
    return mNavBottomSheetBehavior.getState();
  }

  public void refreshTts()
  {
    mTts.setImageDrawable(TtsPlayer.isEnabled()
                              ? Graphics.tint(mActivity, R.drawable.ic_voice_on, androidx.appcompat.R.attr.colorAccent)
                              : Graphics.tint(mActivity, R.drawable.ic_voice_off));
  }

  private void updateTime(int seconds)
  {
    updateTimeLeft(seconds);
    updateTimeEstimate(seconds);
  }

  private void updateTimeLeft(int seconds)
  {
    final long hours = TimeUnit.SECONDS.toHours(seconds);
    final long minutes = TimeUnit.SECONDS.toMinutes(seconds) % 60;
    mTimeMinuteValue.setText(String.valueOf(minutes));
    String min = mActivity.getResources().getString(R.string.minute);
    mTimeMinuteUnits.setText(min);
    if (hours == 0)
    {
      UiUtils.hide(mTimeHourUnits, mTimeHourValue);
      return;
    }
    UiUtils.setTextAndShow(mTimeHourValue, String.valueOf(hours));
    String hour = mActivity.getResources().getString(R.string.hour);
    UiUtils.setTextAndShow(mTimeHourUnits, hour);
  }

  private void updateTimeEstimate(int seconds)
  {
    final String format =
        android.text.format.DateFormat.is24HourFormat(mTimeMinuteValue.getContext()) ? "HH:mm" : "h:mm a";
    final LocalTime localTime = LocalTime.now().plusSeconds(seconds);
    mTimeEstimate.setText(localTime.format(DateTimeFormatter.ofPattern(format)));
  }

  /**
   * Renders the numbers row from the single speed-warning result owned by
   * {@code NavigationController}.
   *
   * <p>Passing the result in (rather than evaluating here) is what keeps the two panels in sync:
   * one state machine, one hysteresis timeline, one voice cooldown.
   */
  void updateSpeedView(@NonNull RoutingInfo info,
                       @NonNull app.organicmaps.sdk.routing.SpeedLimitState.Result state)
  {
    final Location last = MwmApplication.from(mActivity).getLocationHelper().getSavedLocation();
    if (last == null)
    {
      // TS8 NAV: no fix means no number. Keeping the previous value on screen would present a
      // stale speed as current, which the design spec forbids.
      mSpeedValue.setText(app.organicmaps.sdk.launcher.NavigationValueFormatter.PLACEHOLDER);
      mSpeedUnits.setText(mActivity.getResources().getString(R.string.ts8_unit_kmh));
      mSpeedViewContainer.setActivated(false);
      return;
    }

    // TS8 NAV: share the formatter with the navigation panel so the two cannot disagree, and so
    // a missing speed renders as "--" rather than "0".
    final double speedMps = app.organicmaps.sdk.launcher.NavigationStateMapper.sanitiseSpeedMps(last.getSpeed());
    mSpeedValue.setText(app.organicmaps.sdk.launcher.NavigationValueFormatter.formatSpeedKmh(speedMps));
    mSpeedUnits.setText(mActivity.getResources().getString(R.string.ts8_unit_kmh));

    switch (state.state)
    {
      case EXCEEDED:
        mSpeedValue.setTextColor(ContextCompat.getColor(mActivity, R.color.ts8_danger));
        break;
      case NEAR_LIMIT:
        mSpeedValue.setTextColor(ContextCompat.getColor(mActivity, R.color.ts8_warning));
        break;
      case NORMAL:
        mSpeedValue.setTextColor(ContextCompat.getColor(mActivity, R.color.white_primary));
        break;
      case NO_LIMIT:
      default:
        mSpeedValue.setTextColor(ThemeUtils.getColor(mActivity, android.R.attr.textColorPrimary));
        break;
    }

    mSpeedViewContainer.setActivated(
        state.state == app.organicmaps.sdk.routing.SpeedLimitState.State.EXCEEDED);
  }

  public void update(@NonNull RoutingInfo info)
  {
    updateSpeedView(info, mSpeedState);
    updateTime(info.totalTimeInSeconds);
    mDistanceValue.setText(info.distToTarget.mDistanceStr);
    mDistanceUnits.setText(info.distToTarget.getUnitsStr(mActivity.getApplicationContext()));
    mRouteProgress.setProgressCompat((int) info.completionPercent, true);
  }

  /**
   * The single speed-warning result, supplied by {@code NavigationController}.
   *
   * <p>Defaults to NO_LIMIT so a caller that has not supplied one renders nothing rather than a
   * guessed state.
   */
  @NonNull
  private app.organicmaps.sdk.routing.SpeedLimitState.Result mSpeedState =
      new app.organicmaps.sdk.routing.SpeedLimitState.Result(
          app.organicmaps.sdk.routing.SpeedLimitState.State.NO_LIMIT,
          app.organicmaps.sdk.launcher.NavigationState.UNKNOWN, 0, false, false);

  /** Called by {@code NavigationController} with the result it already evaluated. */
  public void setSpeedWarningState(@NonNull app.organicmaps.sdk.routing.SpeedLimitState.Result state)
  {
    mSpeedState = state;
  }

  public interface NavMenuListener
  {
    void onStopClicked();

    void onSettingsClicked();
  }
}
