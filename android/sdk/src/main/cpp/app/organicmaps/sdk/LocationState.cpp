#include "Framework.hpp"
#include "map/gps_tracker.hpp"

#include "app/organicmaps/sdk/core/jni_helper.hpp"

#include "app/organicmaps/sdk/platform/AndroidPlatform.hpp"

extern "C"
{

static void LocationStateModeChanged(location::EMyPositionMode mode,
                                     std::shared_ptr<jobject> const & listener)
{
  JNIEnv * env = jni::GetEnv();
  env->CallVoidMethod(*listener, jni::GetMethodID(env, *listener.get(),
                      "onMyPositionModeChanged", "(I)V"), static_cast<jint>(mode));
}

//  public static void nativeSwitchToNextMode();
JNIEXPORT void JNICALL
Java_app_organicmaps_sdk_location_LocationState_nativeSwitchToNextMode(JNIEnv * env, jclass clazz)
{
  ASSERT(g_framework, ());
  g_framework->SwitchMyPositionNextMode();
}

// private static int nativeGetMode();
JNIEXPORT jint JNICALL
Java_app_organicmaps_sdk_location_LocationState_nativeGetMode(JNIEnv * env, jclass clazz)
{
  // GetMyPositionMode() is initialized only after drape creation.
  // https://github.com/organicmaps/organicmaps/issues/1128#issuecomment-1784435190
  ASSERT(g_framework && g_framework->IsDrapeEngineCreated(), ());
  return g_framework->GetMyPositionMode();
}

//  public static void nativeSetListener(ModeChangeListener listener);
JNIEXPORT void JNICALL
Java_app_organicmaps_sdk_location_LocationState_nativeSetListener(JNIEnv * env, jclass clazz,
                                                                  jobject listener)
{
  ASSERT(g_framework, ());
  g_framework->SetMyPositionModeListener(std::bind(&LocationStateModeChanged, std::placeholders::_1,
                                                   jni::make_global_ref(listener)));
}

//  public static void nativeRemoveListener();
JNIEXPORT void JNICALL
Java_app_organicmaps_sdk_location_LocationState_nativeRemoveListener(JNIEnv * env, jclass clazz)
{
  ASSERT(g_framework, ());
  g_framework->SetMyPositionModeListener(location::TMyPositionModeChanged());
}

JNIEXPORT void JNICALL
Java_app_organicmaps_sdk_location_LocationState_nativeOnLocationError(JNIEnv * env, jclass clazz, int errorCode)
{
  ASSERT(g_framework, ());
  g_framework->OnLocationError(errorCode);
}

JNIEXPORT void JNICALL
Java_app_organicmaps_sdk_location_LocationState_nativeLocationUpdated(JNIEnv * env, jclass clazz, jlong time,
                                                                  jdouble lat, jdouble lon, jfloat accuracy,
                                                                  jdouble altitude, jfloat speed, jfloat bearing)
{
  ASSERT(g_framework, ());
  location::GpsInfo info;
  info.m_source = location::EAndroidNative;

  info.m_timestamp = static_cast<double>(time) / 1000.0;
  info.m_latitude = lat;
  info.m_longitude = lon;

  if (accuracy > 0.0)
    info.m_horizontalAccuracy = accuracy;

  if (altitude != 0.0)
  {
    info.m_altitude = altitude;
    info.m_verticalAccuracy = accuracy;
  }

  // TS8 fix: zero is a *valid* value for speed and bearing.
  //   * A stationary vehicle reports speed 0. Dropping it left m_speed at the -1.0
  //     "unknown" sentinel (platform/location.hpp), so downstream code kept a stale speed
  //     and the speed-limit comparison used a value that was never refreshed.
  //   * Bearing 0 degrees is due north. Dropping it made the camera keep a stale heading
  //     whenever a route ran north, which is visible at low speed.
  // Android reports "no data" as a negative value (or via hasSpeed()/hasBearing()), so
  // only negative values are treated as unknown here.
  if (bearing >= 0.0f)
    info.m_bearing = bearing;

  if (speed >= 0.0f)
    info.m_speed = speed;

  g_framework->OnLocationUpdated(info);
  GpsTracker::Instance().OnLocationUpdated(info);
}
} // extern "C"
