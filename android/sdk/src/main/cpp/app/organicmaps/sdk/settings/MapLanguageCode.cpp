#include "app/organicmaps/sdk/Framework.hpp"
// TS8 build host: jni::ToNativeString/ToJavaString are declared here. Unity
// build used to expose them transitively; required when unity is disabled.
#include "app/organicmaps/sdk/core/jni_helper.hpp"

#include "platform/settings.hpp"

extern "C"
{
JNIEXPORT void JNICALL
Java_app_organicmaps_sdk_settings_MapLanguageCode_setMapLanguageCode(JNIEnv * env, jobject, jstring languageCode)
{
  g_framework->SetMapLanguageCode(jni::ToNativeString(env, languageCode));
}

JNIEXPORT jstring JNICALL
Java_app_organicmaps_sdk_settings_MapLanguageCode_getMapLanguageCode(JNIEnv * env, jobject)
{
  return jni::ToJavaString(env, g_framework->GetMapLanguageCode());
}
}
