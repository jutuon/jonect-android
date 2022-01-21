/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

//! Start Jonect's main logic

use std::{thread::{self, JoinHandle}, sync::{atomic::AtomicBool, Mutex}, panic};

use android_log::{LogLevel, AndroidLogger};
use jni::{JNIEnv, objects::{JClass, JString}, sys::jint};

use lazy_static::lazy_static;

use libjonect::{Logic, config::LogicConfig, utils::QuitSender};

pub mod android_log;

lazy_static! {
    static ref LOGIC_THREAD_HANDLE: Mutex<Option<(JoinHandle<()>, QuitSender)>> = Mutex::new(None);
}

static LOGGING_INIT: AtomicBool = AtomicBool::new(true);


/// Start logic.
///
/// # Parameters
/// * `env` - JNI requires this parameter.
/// * `class` - JNI requires this parameter.
#[no_mangle]
pub extern "system" fn Java_com_example_jonect_RustLogic_startLogicThread(
    env: JNIEnv,
    class: JClass,
) {
    if LOGGING_INIT.swap(false, std::sync::atomic::Ordering::SeqCst) {
        panic::set_hook(Box::new(|info| {
            android_log::android_println(&info.to_string(), LogLevel::Panic);
        }));

        log::set_logger(&AndroidLogger).unwrap();
        log::set_max_level(log::LevelFilter::Trace);
    }

    let mut handle = LOGIC_THREAD_HANDLE.lock().unwrap();

    if handle.is_some() {
        panic!("{}", "Logic thread is already running.");
    }

    let (sender, receiver) = Logic::create_quit_notification_channel();

    let thread_handle = thread::spawn(move || {
        Logic::run(LogicConfig {
            pa_source_name: None,
            encode_opus: false,
            connect_address: None,
            enable_connection_listening: false,
            enable_ping: false,
        }, Some(receiver));
    });

    *handle = Some((thread_handle, sender));

}

/// Quit logic thread.
///
/// # Parameters
/// * `env` - JNI requires this parameter.
/// * `class` - JNI requires this parameter.
#[no_mangle]
pub extern "system" fn Java_com_example_jonect_RustLogic_quitLogicThread(
    env: JNIEnv,
    class: JClass,
) {
    if let Some((thread_handle, quit_sender)) = LOGIC_THREAD_HANDLE.lock().unwrap().take() {
        quit_sender.send(()).unwrap();
        thread_handle.join().unwrap();
    }
}
