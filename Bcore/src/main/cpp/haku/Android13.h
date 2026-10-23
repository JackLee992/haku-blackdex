// Copyright 2026 Haku contributors. SPDX-License-Identifier: Apache-2.0
#pragma once
#include <jni.h>
namespace haku {
void CaptureCookie(JNIEnv* env, jlong cookie, jstring directory, jboolean deep);
void InstallAndroid13Hook(JNIEnv* env, jstring directory);
}
