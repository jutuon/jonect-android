/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#include <android/log.h>
#include "oboe/Oboe.h"

extern "C" void hello_cpp() {
    __android_log_write(ANDROID_LOG_INFO, "jonect", "Hello world c++");

    oboe::AudioStreamBuilder builder;

    __android_log_write(ANDROID_LOG_INFO, "jonect", "Oboe");
}