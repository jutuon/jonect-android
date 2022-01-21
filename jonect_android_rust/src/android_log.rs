/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

use std::ffi::CString;

use lazy_static::lazy_static;

lazy_static! {
    static ref LOG_TAG: CString = CString::new("jonect_rust").unwrap();
}

use log::Level;

pub enum LogLevel {
    Level(Level),
    Panic,
}


/// Print to Android Studio's log.
pub fn android_println(text: &str, level: LogLevel) {
    let text = match CString::new(text) {
        Ok(text) => text,
        Err(e) => {
            let error = format!("Logging null error: {}", e);
            CString::new(error).unwrap_or_else(|_| CString::new("Logging null error").unwrap())
        }
    };

    let android_log_level = match level {
        LogLevel::Level(level) => match level {
            Level::Error => ndk_sys::android_LogPriority_ANDROID_LOG_ERROR,
            Level::Warn => ndk_sys::android_LogPriority_ANDROID_LOG_WARN,
            Level::Info => ndk_sys::android_LogPriority_ANDROID_LOG_INFO,
            Level::Debug => ndk_sys::android_LogPriority_ANDROID_LOG_DEBUG,
            Level::Trace => ndk_sys::android_LogPriority_ANDROID_LOG_VERBOSE,
        },
        LogLevel::Panic => ndk_sys::android_LogPriority_ANDROID_LOG_FATAL,
    }.try_into().unwrap();

    unsafe {
        ndk_sys::__android_log_write(
            android_log_level,
            LOG_TAG.as_ptr(),
            text.as_ptr(),
        );
    }
}

pub struct AndroidLogger;

impl log::Log for AndroidLogger {
    fn enabled(&self, metadata: &log::Metadata) -> bool {
        true
    }

    fn flush(&self) {}

    fn log(&self, record: &log::Record) {
        if self.enabled(record.metadata()) {
            let message = format!("{}", record.args());
            android_println(&message, LogLevel::Level(record.level()))
        }
    }
}
