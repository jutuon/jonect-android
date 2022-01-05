//! Opus decoder for Jonect Android client. This code is not thread safe.
//! Use this code only from one thread.

use std::{thread::{self, JoinHandle}, sync::atomic::AtomicBool, panic};

use jni::{JNIEnv, objects::{JClass, JString}, sys::jint};

use stream_decoder::{StreamDecoder};

pub mod stream_decoder;

static QUIT_NOTIFICATION: AtomicBool = AtomicBool::new(false);

static mut DECODING_THREAD_HANDLE: Option<JoinHandle<()>> = None;


/// Start Opus decoding thread. Warning: this code is not thread safe.
///
/// # Parameters
/// * `env` - JNI requires this parameter.
/// * `class` - JNI requires this parameter.
/// * `address` - Jonect server IP address.
/// * `port` - Jonect server TCP port number for audio data.
#[no_mangle]
pub extern "system" fn Java_com_example_jonect_OpusDecoder_startDecodingThread(
    env: JNIEnv,
    class: JClass,
    address: JString,
    port: jint,
) {
    let thread_handle = unsafe {
        &mut DECODING_THREAD_HANDLE
    };

    if thread_handle.is_some() {
        let message = "Decoder thread is already running.";
        stream_decoder::android_println(message);
        panic!("{}", message);
    }

    let address = env.get_string(address).unwrap().to_string_lossy().to_string();
    let port = port.try_into().unwrap();
    let handle = thread::spawn(move || {
        StreamDecoder::new(address, port).start();
    });

    *thread_handle = Some(handle);

}

/// Quit Opus decoding thread. Warning: this code is not thread safe.
///
/// # Parameters
/// * `env` - JNI requires this parameter.
/// * `class` - JNI requires this parameter.
#[no_mangle]
pub extern "system" fn Java_com_example_jonect_OpusDecoder_quit(
    env: JNIEnv,
    class: JClass,
) {
    let thread_handle = unsafe {
        DECODING_THREAD_HANDLE.take().unwrap()
    };

    QUIT_NOTIFICATION.store(true, std::sync::atomic::Ordering::Relaxed);
    match thread_handle.join() {
        Ok(()) => (),
        Err(_) => {
            stream_decoder::android_println("Decoder thread panicked.");
        }
    }
    QUIT_NOTIFICATION.store(false, std::sync::atomic::Ordering::Relaxed);
}
