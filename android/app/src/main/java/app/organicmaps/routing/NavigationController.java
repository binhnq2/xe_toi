package app.organicmaps.routing;

import android.content.Context;
import android.location.Location;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import app.organicmaps.MwmApplication;
import app.organicmaps.R;
import app.organicmaps.maplayer.MapButtonsViewModel;
import app.organicmaps.sdk.Framework;
import app.organicmaps.sdk.Router;
import app.organicmaps.sdk.maplayer.traffic.TrafficManager;
import app.organicmaps.sdk.routing.CarDirection;
import app.organicmaps.sdk.routing.RoutingInfo;
import app.organicmaps.sdk.routing.SpeedLimitState;
import app.organicmaps.sdk.util.StringUtils;
import app.organicmaps.sdk.util.UiUtils;
import app.organicmaps.util.Utils;
import app.organicmaps.util.WindowInsetUtils;
import app.organicmaps.widget.LanesView;
import app.organicmaps.widget.SpeedLimitView;
import app.organicmaps.widget.menu.NavMenu;
import com.google.android.material.bottomsheet.BottomSheetBehavior;

public class NavigationController implements TrafficManager.TrafficCallback, NavMenu.NavMenuListener
{
  private final View mFrame;

  private final ImageView mNextTurnImage;
  private final TextView mNextTurnDistance;
  private final TextView mCircleExit;

  private final View mNextNextTurnFrame;
  private final ImageView mNextNextTurnImage;

  private final View mStreetFrame;
  private final TextView mNextStreet;

  @NonNull
  private final LanesView mLanesView;
  @NonNull
  private final SpeedLimitView mSpeedLimit;

  /** Over-speed hysteresis/debounce state; reset when navigation (re)starts. */
  private final SpeedLimitState mSpeedLimitState = new SpeedLimitState();

  private final View mSpeedWarningFrame;
  private final TextView mSpeedWarningText;
  private final TextView mSpeedWarningDelta;
  /** Last rendered warning state, so the "one-shot" per-entry animation fires only once. */
  private SpeedLimitState.State mLastWarningState = SpeedLimitState.State.NO_LIMIT;

  private final MapButtonsViewModel mMapButtonsViewModel;

  private final NavMenu mNavMenu;
  View.OnClickListener mOnSettingsClickListener;

  private void addWindowsInsets(@NonNull View topFrame)
  {
    ViewCompat.setOnApplyWindowInsetsListener(
        topFrame.findViewById(R.id.nav_next_turn_container), (view, windowInsets) -> {
          view.setPadding(windowInsets.getInsets(WindowInsetsCompat.Type.systemBars()).left, view.getPaddingTop(),
                          view.getPaddingEnd(), view.getPaddingBottom());
          return windowInsets;
        });
  }

  public NavigationController(AppCompatActivity activity, View.OnClickListener onSettingsClickListener,
                              NavMenu.OnMenuSizeChangedListener onMenuSizeChangedListener)
  {
    mMapButtonsViewModel = new ViewModelProvider(activity).get(MapButtonsViewModel.class);

    mFrame = activity.findViewById(R.id.navigation_frame);
    mNavMenu = new NavMenu(activity, this, onMenuSizeChangedListener);
    mOnSettingsClickListener = onSettingsClickListener;

    // Top frame
    View topFrame = mFrame.findViewById(R.id.nav_top_frame);
    View turnFrame = topFrame.findViewById(R.id.nav_next_turn_frame);
    mNextTurnImage = turnFrame.findViewById(R.id.turn);
    mNextTurnDistance = turnFrame.findViewById(R.id.distance);
    mCircleExit = turnFrame.findViewById(R.id.circle_exit);

    addWindowsInsets(topFrame);

    mNextNextTurnFrame = topFrame.findViewById(R.id.nav_next_next_turn_frame);
    mNextNextTurnImage = mNextNextTurnFrame.findViewById(R.id.turn);

    mStreetFrame = topFrame.findViewById(R.id.street_frame);
    mNextStreet = mStreetFrame.findViewById(R.id.street);

    mLanesView = topFrame.findViewById(R.id.lanes);

    mSpeedLimit = topFrame.findViewById(R.id.nav_speed_limit);

    // TS8 NAV over-speed banner (PROMPT Phase 5 speed warning UX).
    mSpeedWarningFrame = topFrame.findViewById(R.id.nav_speed_warning);
    mSpeedWarningText = topFrame.findViewById(R.id.nav_speed_warning_text);
    mSpeedWarningDelta = topFrame.findViewById(R.id.nav_speed_warning_delta);

    // Show a blank view below the navbar to hide the menu content
    final View navigationBarBackground = mFrame.findViewById(R.id.nav_bottom_sheet_nav_bar);
    final View nextTurnContainer = mFrame.findViewById(R.id.nav_next_turn_container);
    ViewCompat.setOnApplyWindowInsetsListener(mStreetFrame, (v, windowInsets) -> {
      UiUtils.setViewInsetsPaddingNoBottom(v, windowInsets);

      final Insets safeDrawingInsets = windowInsets.getInsets(WindowInsetUtils.TYPE_SAFE_DRAWING);
      nextTurnContainer.setPadding(safeDrawingInsets.left, nextTurnContainer.getPaddingTop(),
                                   nextTurnContainer.getPaddingEnd(), nextTurnContainer.getPaddingBottom());
      navigationBarBackground.getLayoutParams().height = safeDrawingInsets.bottom;
      // The gesture navigation bar stays at the bottom in landscape
      // We need to add a background only above the nav menu
      navigationBarBackground.getLayoutParams().width = mFrame.findViewById(R.id.nav_bottom_sheet).getWidth();
      return windowInsets;
    });
  }

  private void updateVehicle(@NonNull RoutingInfo info)
  {
    mNextTurnDistance.setText(Utils.formatDistance(mFrame.getContext(), info.distToTurn));
    info.carDirection.setTurnDrawable(mNextTurnImage);

    if (CarDirection.isRoundAbout(info.carDirection))
      UiUtils.setTextAndShow(mCircleExit, String.valueOf(info.exitNum));
    else
      UiUtils.hide(mCircleExit);

    UiUtils.visibleIf(info.nextCarDirection.containsNextTurn(), mNextNextTurnFrame);
    if (info.nextCarDirection.containsNextTurn())
      info.nextCarDirection.setNextTurnDrawable(mNextNextTurnImage);

    mLanesView.setLanes(info.lanes);

    updateSpeedLimit(info);
  }

  private void updatePedestrian(@NonNull RoutingInfo info)
  {
    mNextTurnDistance.setText(Utils.formatDistance(mFrame.getContext(), info.distToTurn));

    info.pedestrianTurnDirection.setTurnDrawable(mNextTurnImage);
  }

  public void updateNorth()
  {
    if (!RoutingController.get().isNavigating())
      return;

    update(Framework.nativeGetRouteFollowingInfo());
  }

  public void update(@Nullable RoutingInfo info)
  {
    if (info == null)
      return;

    if (Router.get() == Router.Pedestrian)
      updatePedestrian(info);
    else
      updateVehicle(info);

    updateStreetView(info);
    // The controller owns the one speed-warning state; the bottom strip renders from it instead
    // of keeping a second machine, which is how the two panels stay in sync.
    mNavMenu.setSpeedWarningState(mSpeedLimitState.evaluate(
        app.organicmaps.sdk.launcher.NavigationStateMapper.sanitiseSpeedLimitMps(info.speedLimitMps),
        currentSpeedMps(), currentAccuracy(), SystemClock.elapsedRealtime()));
    mNavMenu.update(info);
  }

  /** Last known GPS speed in m/s, or the unknown sentinel. */
  private double currentSpeedMps()
  {
    final Location location =
        app.organicmaps.MwmApplication.from(mFrame.getContext()).getLocationHelper().getSavedLocation();
    return location == null ? app.organicmaps.sdk.launcher.NavigationState.UNKNOWN
                            : app.organicmaps.sdk.launcher.NavigationStateMapper.sanitiseSpeedMps(
                                  location.getSpeed());
  }

  /** Accuracy of the last fix in metres, or 0 when the provider did not report one. */
  private double currentAccuracy()
  {
    final Location location =
        app.organicmaps.MwmApplication.from(mFrame.getContext()).getLocationHelper().getSavedLocation();
    return location == null || !location.hasAccuracy() ? 0.0 : location.getAccuracy();
  }

  private void updateStreetView(@NonNull RoutingInfo info)
  {
    boolean hasStreet = !TextUtils.isEmpty(info.nextStreet);
    // Sic: don't use UiUtils.showIf() here because View.GONE breaks layout
    // https://github.com/organicmaps/organicmaps/issues/3732
    UiUtils.visibleIf(hasStreet, mStreetFrame);
    if (!TextUtils.isEmpty(info.nextStreet))
      mNextStreet.setText(info.nextStreet);
    int margin = UiUtils.dimen(mFrame.getContext(), R.dimen.nav_frame_padding);
    if (hasStreet)
      margin += mStreetFrame.getHeight();
    mMapButtonsViewModel.setTopButtonsMarginTop(margin);
  }

  public void show(boolean show)
  {
    if (show && !UiUtils.isVisible(mFrame))
    {
      collapseNavMenu();
      // Fresh navigation: drop the previous route's hysteresis and voice cooldown.
      mSpeedLimitState.reset();
      mLastWarningState = SpeedLimitState.State.NO_LIMIT;
      hideSpeedWarning();
    }
    UiUtils.showIf(show, mFrame);
  }

  public boolean isNavMenuCollapsed()
  {
    return mNavMenu.getBottomSheetState() == BottomSheetBehavior.STATE_COLLAPSED;
  }

  public boolean isNavMenuHidden()
  {
    return mNavMenu.getBottomSheetState() == BottomSheetBehavior.STATE_HIDDEN;
  }

  public void collapseNavMenu()
  {
    mNavMenu.collapseNavBottomSheet();
  }

  public void refresh()
  {
    mNavMenu.refreshTts();
  }

  @Override
  public void onEnabled()
  {
    // mNavMenu.refreshTraffic();
  }

  @Override
  public void onDisabled()
  {
    // mNavMenu.refreshTraffic();
  }

  @Override
  public void onWaitingData()
  {
    // no op
  }

  @Override
  public void onOutdated()
  {
    // no op
  }

  @Override
  public void onNoData()
  {
    // no op
  }

  @Override
  public void onNetworkError()
  {
    // no op
  }

  @Override
  public void onExpiredData()
  {
    // no op
  }

  @Override
  public void onExpiredApp()
  {
    // no op
  }

  @Override
  public void onSettingsClicked()
  {
    mOnSettingsClickListener.onClick(null);
  }

  @Override
  public void onStopClicked()
  {
    RoutingController.get().cancel();
  }

  private void updateSpeedLimit(@NonNull final RoutingInfo info)
  {
    final Location location = MwmApplication.from(mFrame.getContext()).getLocationHelper().getSavedLocation();
    if (location == null)
    {
      mSpeedLimit.setSpeedLimit(0, false);
      return;
    }

    // Never infer a missing speed limit. routing::FollowingInfo uses a negative
    // m_speedLimitMps for "no data" (routing/following_info.hpp:87). The baseline compared
    // it directly, so "-1 < speed" was always true and every road without a sign reported
    // a violation. SpeedLimitState also adds hysteresis and the accuracy gate.
    final double accuracy = location.hasAccuracy() ? location.getAccuracy() : 0.0;
    final SpeedLimitState.Result result =
        mSpeedLimitState.evaluate(info.speedLimitMps, location.getSpeed(), accuracy,
                                  SystemClock.elapsedRealtime());

    // Rendering the limit itself is only meaningful when a limit exists.
    final int limitKmh = (result.state == SpeedLimitState.State.NO_LIMIT)
                             ? 0
                             : (int) Math.round(info.speedLimitMps * 3.6);
    mSpeedLimit.setSpeedLimit(limitKmh, result.state == SpeedLimitState.State.EXCEEDED);

    onSpeedLimitStateChanged(result, location);
  }

  /**
   * Over-speed presentation (PROMPT Phase 5 "Speed warning UX").
   *
   * <p>Renders two independent runtime values: the current GPS speed (handled by the speed view)
   * and the matched-road limit. The banner only appears in the EXCEEDED state and shows the
   * actual over-limit delta computed from the runtime values - never a sampled number.
   * Entering EXCEEDED plays a single scale animation; staying in it does not re-animate.
   */
  /**
   * The over-speed banner is a TS8 addition and is absent from layouts that do not include it
   * (e.g. an orientation-specific copy). Navigation must never crash because of that, so every
   * show/hide goes through these two guards.
   */
  private void hideSpeedWarning()
  {
    if (mSpeedWarningFrame != null)
      UiUtils.hide(mSpeedWarningFrame);
  }

  private void showSpeedWarning()
  {
    if (mSpeedWarningFrame != null)
      UiUtils.show(mSpeedWarningFrame);
  }
  protected void onSpeedLimitStateChanged(@NonNull SpeedLimitState.Result result, @NonNull Location location)
  {
    // Poor accuracy: keep the last state on screen but never raise a new warning.
    if (result.suppressedByAccuracy)
    {
      hideSpeedWarning();
      mLastWarningState = SpeedLimitState.State.NO_LIMIT;
      return;
    }

    final boolean exceeded = result.state == SpeedLimitState.State.EXCEEDED;
    final boolean justEntered = exceeded && mLastWarningState != SpeedLimitState.State.EXCEEDED;
    mLastWarningState = result.state;

    if (!exceeded)
    {
      hideSpeedWarning();
      return;
    }

    showSpeedWarning();

    if (mSpeedWarningText != null)
      mSpeedWarningText.setText(R.string.ts8_over_speed_warning);
    if (mSpeedWarningDelta != null)
    {
      final int overKmh = (int) Math.round(result.overLimitMps * 3.6);
      mSpeedWarningDelta.setText(mContext().getString(R.string.ts8_over_speed_delta, overKmh));
    }

    if (justEntered)
    {
      app.organicmaps.sdk.diagnostics.DiagnosticsExport.onSpeedWarning();
    }

    // One-shot animation only when the state actually changes into EXCEEDED, so a steady
    // over-speed does not allocate and animate every navigation frame.
    if (justEntered && mSpeedWarningFrame != null)
    {
      mSpeedWarningFrame.setScaleX(0.9f);
      mSpeedWarningFrame.setScaleY(0.9f);
      mSpeedWarningFrame.setAlpha(0f);
      mSpeedWarningFrame.animate()
          .scaleX(1f)
          .scaleY(1f)
          .alpha(1f)
          .setDuration(180L)
          .start();
    }

    // Voice: never overlap a maneuver instruction. TtsPlayer queues with QUEUE_ADD, so an
    // over-speed prompt would otherwise be spoken on top of "turn right in 100 m".
    if (result.justExceeded && mSpeedLimitState.tryConsumeVoiceEvent(result, SystemClock.elapsedRealtime()))
      speakOverSpeedWarning(result);
  }

  /**
   * Speaks the over-speed prompt, but only when no maneuver announcement is in flight.
   * Maneuver instructions always win (PROMPT Phase 5 priority requirement).
   */
  private void speakOverSpeedWarning(@NonNull SpeedLimitState.Result result)
  {
    if (app.organicmaps.sdk.sound.TtsPlayer.INSTANCE.isSpeakingManeuver())
    {
      // Drop this one; the cooldown was already consumed, which is what we want - a missed
      // warning is better than two voices talking over each other.
      return;
    }
    final int overKmh = (int) Math.round(result.overLimitMps * 3.6);
    final String phrase = app.organicmaps.sdk.Framework.nativeGetSpeedLimitExceededText(overKmh);
    if (phrase != null && !phrase.isEmpty())
      app.organicmaps.sdk.sound.TtsPlayer.INSTANCE.speak(phrase);
  }

  @NonNull
  private Context mContext()
  {
    return mFrame.getContext();
  }
}
