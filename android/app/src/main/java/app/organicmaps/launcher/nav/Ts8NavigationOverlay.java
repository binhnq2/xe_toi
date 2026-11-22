package app.organicmaps.launcher.nav;

import android.content.Context;
import android.view.View;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import app.organicmaps.R;
import app.organicmaps.sdk.launcher.NavigationState;
import app.organicmaps.sdk.launcher.NavigationValueFormatter;
import app.organicmaps.sdk.routing.CarDirection;
import app.organicmaps.widget.ArrowView;
import app.organicmaps.widget.SpeedLimitView;

/**
 * Binds {@link NavigationState} to the navigation overlay (design spec §6).
 *
 * <p>Everything drawn here comes from the runtime snapshot. There is no sample distance, speed,
 * ETA or street anywhere in this class, and every absent value goes through
 * {@link NavigationValueFormatter}, which turns it into {@code --} or hides the control.
 *
 * <p>No allocation in the per-frame path: all views are resolved once in the constructor, and
 * {@link #render} only calls setters. Strings are only built when a value actually changed, so a
 * steady 30 fps render does not churn the heap.
 */
public class Ts8NavigationOverlay
{
  @NonNull
  private final Context mContext;

  @NonNull
  private final ArrowView mTurnIcon;
  @NonNull
  private final TextView mDistance;
  @NonNull
  private final TextView mInstruction;
  @NonNull
  private final TextView mStreet;
  @NonNull
  private final TextView mExit;

  @NonNull
  private final TextView mSpeedValue;
  @NonNull
  private final TextView mSpeedUnits;
  @NonNull
  private final SpeedLimitView mSpeedLimit;
  @NonNull
  private final View mSpeedWarning;

  @NonNull
  private final TextView mEta;
  @NonNull
  private final TextView mRemaining;
  @NonNull
  private final TextView mDistanceRemaining;

  @NonNull
  private final ImageButton mMute;
  @NonNull
  private final ImageButton mRecenter;

  @Nullable
  private NavigationState mLast;

  public Ts8NavigationOverlay(@NonNull View root)
  {
    mContext = root.getContext();

    mTurnIcon = root.findViewById(R.id.ts8_nav_turn_icon);
    mDistance = root.findViewById(R.id.ts8_nav_distance);
    mInstruction = root.findViewById(R.id.ts8_nav_instruction);
    mStreet = root.findViewById(R.id.ts8_nav_street);
    mExit = root.findViewById(R.id.ts8_nav_exit);

    mSpeedValue = root.findViewById(R.id.ts8_nav_speed_value);
    mSpeedUnits = root.findViewById(R.id.ts8_nav_speed_units);
    mSpeedLimit = root.findViewById(R.id.ts8_nav_speed_limit);
    mSpeedWarning = root.findViewById(R.id.nav_speed_warning);

    mEta = root.findViewById(R.id.ts8_nav_eta);
    mRemaining = root.findViewById(R.id.ts8_nav_remaining);
    mDistanceRemaining = root.findViewById(R.id.ts8_nav_distance_remaining);

    mMute = root.findViewById(R.id.ts8_nav_mute);
    mRecenter = root.findViewById(R.id.ts8_nav_recenter);
  }

  public void setMuteState(boolean muted)
  {
    mMute.setImageResource(muted ? R.drawable.ic_voice_off : R.drawable.ic_voice_on);
    mMute.setContentDescription(
        mContext.getString(muted ? R.string.ts8_nav_unmute : R.string.ts8_nav_mute));
  }

  public void setOnMuteClickListener(@Nullable View.OnClickListener l)
  {
    mMute.setOnClickListener(l);
  }

  public void setOnRecenterClickListener(@Nullable View.OnClickListener l)
  {
    mRecenter.setOnClickListener(l);
  }

  public void setOnRouteMenuClickListener(@Nullable View.OnClickListener l)
  {
    final View v = mDistanceRemaining.getRootView().findViewById(R.id.ts8_nav_route_menu);
    if (v != null)
      v.setOnClickListener(l);
  }

  /** Renders one snapshot. Safe to call at the UI refresh rate. */
  public void render(@NonNull NavigationState state)
  {
    if (state.equals(mLast))
      return;
    final NavigationState previous = mLast;
    mLast = state;

    renderManeuver(state);
    renderSpeed(state, previous);
    renderRouteStrip(state);
  }

  private void renderManeuver(@NonNull NavigationState state)
  {
    if (!state.isFollowing())
    {
      mDistance.setText(NavigationValueFormatter.formatDistance(NavigationState.UNKNOWN));
      mInstruction.setText("");
      mStreet.setText("");
      mExit.setVisibility(View.GONE);
      return;
    }

    mDistance.setText(NavigationValueFormatter.formatDistance(state.distToTurnM));
    mInstruction.setText(instructionFor(state.turnDrawableOrdinal));

    // Reuse CarDirection.setTurnDrawable(): it is the same mapping the native panel uses, so the
    // icon can never disagree with the instruction text.
    final CarDirection dir = directionFor(state.turnDrawableOrdinal);
    if (dir != null)
      dir.setTurnDrawable(mTurnIcon);
    mStreet.setText(state.nextStreet == null ? "" : state.nextStreet);
    mStreet.setVisibility(state.nextStreet == null || state.nextStreet.isEmpty() ? View.GONE
                                                                                 : View.VISIBLE);

    if (state.exitNumber > 0)
    {
      mExit.setText(mContext.getString(R.string.ts8_nav_exit_number, state.exitNumber));
      mExit.setVisibility(View.VISIBLE);
    }
    else
      mExit.setVisibility(View.GONE);
  }

  private void renderSpeed(@NonNull NavigationState state, @Nullable NavigationState previous)
  {
    if (state.phase == NavigationState.Phase.NO_GPS)
    {
      // Explicit, actionable state instead of a stale number.
      mSpeedValue.setText(NavigationValueFormatter.PLACEHOLDER);
      mSpeedValue.setTextColor(mContext.getColor(R.color.ts8_text_secondary));
    }
    else
    {
      mSpeedValue.setText(NavigationValueFormatter.formatSpeedKmh(state.speedMps));
      mSpeedValue.setTextColor(mContext.getColor(R.color.ts8_text_primary));
    }
    mSpeedUnits.setText(mContext.getString(R.string.ts8_unit_kmh));

    final Integer limitKmh = NavigationValueFormatter.speedLimitKmh(state.speedLimitMps);
    if (limitKmh == null)
    {
      // No sign to draw. SpeedLimitView hides itself for a non-positive value, and the content
      // description says why, so TalkBack never reads an invented limit.
      mSpeedLimit.setSpeedLimit(0, false);
      mSpeedLimit.setContentDescription(mContext.getString(R.string.ts8_speed_limit_none_description));
    }
    else
    {
      mSpeedLimit.setSpeedLimit(limitKmh, false);
    }
  }

  private void renderRouteStrip(@NonNull NavigationState state)
  {
    final String min = mContext.getString(R.string.ts8_unit_min);
    final String hour = mContext.getString(R.string.ts8_unit_hour);

    mEta.setText(NavigationValueFormatter.formatEta(System.currentTimeMillis(), state.remainingSeconds));
    mRemaining.setText(NavigationValueFormatter.formatRemainingTime(state.remainingSeconds, min, hour));
    mDistanceRemaining.setText(NavigationValueFormatter.formatDistance(state.distToTargetM));
  }

  /** Maps the native turn ordinal to a localised instruction. */
  @NonNull
  private String instructionFor(int turnOrdinal)
  {
    final CarDirection dir = directionFor(turnOrdinal);
    if (dir == null)
      return "";

    switch (dir)
    {
      case TURN_RIGHT:
        return mContext.getString(R.string.ts8_nav_turn_right);
      case EXIT_HIGHWAY_TO_RIGHT:
        return mContext.getString(R.string.ts8_nav_turn_slight_right);
      case TURN_LEFT:
        return mContext.getString(R.string.ts8_nav_turn_left);
      case EXIT_HIGHWAY_TO_LEFT:
        return mContext.getString(R.string.ts8_nav_turn_slight_left);
      case GO_STRAIGHT:
      case NO_TURN:
      case START_AT_THE_END_OF_STREET:
        return mContext.getString(R.string.ts8_nav_turn_straight);
      case TURN_SLIGHT_RIGHT:
        return mContext.getString(R.string.ts8_nav_turn_slight_right);
      case TURN_SLIGHT_LEFT:
        return mContext.getString(R.string.ts8_nav_turn_slight_left);
      case TURN_SHARP_RIGHT:
        return mContext.getString(R.string.ts8_nav_turn_sharp_right);
      case TURN_SHARP_LEFT:
        return mContext.getString(R.string.ts8_nav_turn_sharp_left);
      case U_TURN_LEFT:
      case U_TURN_RIGHT:
        return mContext.getString(R.string.ts8_nav_turn_uturn);
      case ENTER_ROUND_ABOUT:
      case STAY_ON_ROUND_ABOUT:
        return mContext.getString(R.string.ts8_nav_turn_roundabout);
      case LEAVE_ROUND_ABOUT:
        return mContext.getString(R.string.ts8_nav_turn_straight);
      case REACHED_YOUR_DESTINATION:
        return mContext.getString(R.string.ts8_nav_turn_finish);
      default:
        return "";
    }
  }

  /**
   * Resolves the ordinal the JNI layer sent back to the enum.
   *
   * <p>The native side sends {@code info.m_turn}, which is the enum ordinal, so an out-of-range
   * value means the Java and C++ enums drifted apart - worth logging once rather than crashing.
   */
  @Nullable
  private static CarDirection directionFor(int turnOrdinal)
  {
    if (turnOrdinal < 0)
      return null;
    final CarDirection[] all = CarDirection.values();
    if (turnOrdinal >= all.length)
    {
      app.organicmaps.sdk.util.log.Logger.w("Ts8NavigationOverlay",
                                           "turn ordinal out of range: " + turnOrdinal);
      return null;
    }
    return all[turnOrdinal];
  }

  /** Shows or hides the over-speed banner from the shared speed-warning state machine. */
  public void setSpeedWarningVisible(boolean visible)
  {
    if (mSpeedWarning != null)
      mSpeedWarning.setVisibility(visible ? View.VISIBLE : View.GONE);
  }
}
