//! Opus decoder for Jonect Android client. This code is not thread safe.
//! Use this code only from one thread.

use std::{thread::{self, JoinHandle}, sync::atomic::AtomicBool, panic};

use jni::{JNIEnv, objects::{JClass, JString}, sys::jint};

use stream_decoder::{StreamDecoder};

pub mod stream_decoder;

static QUIT_NOTIFICATION: AtomicBool = AtomicBool::new(false);

static mut DECODER_STATE: Option<DecoderState> = None;

struct DecoderState {
    handle: JoinHandle<()>,
}

impl DecoderState {
    fn new(handle: JoinHandle<()>) -> Self {
        Self {
            handle,
        }
    }
}

#[no_mangle]
pub extern "system" fn Java_com_example_jonect_OpusDecoder_startDecodingThread(
    env: JNIEnv,
    class: JClass,
    address: JString,
    port: jint,
) {
    let state = unsafe {
        &mut DECODER_STATE
    };

    if state.is_some() {
        let message = "Decoder thread is already running.";
        stream_decoder::android_println(message);
        panic!("{}", message);
    }

    let address = env.get_string(address).unwrap().to_string_lossy().to_string();
    let port = port.try_into().unwrap();
    let handle = thread::spawn(move || {
        StreamDecoder::new(address, port).start();
    });

    *state = Some(DecoderState::new(handle));

}

#[no_mangle]
pub extern "system" fn Java_com_example_jonect_OpusDecoder_quit(
    env: JNIEnv,
    class: JClass,
) {
    let decoder = unsafe {
        DECODER_STATE.take().unwrap()
    };

    QUIT_NOTIFICATION.store(true, std::sync::atomic::Ordering::Relaxed);
    match decoder.handle.join() {
        Ok(()) => (),
        Err(_) => {
            stream_decoder::android_println("Decoder thread panicked.");
        }
    }
    QUIT_NOTIFICATION.store(false, std::sync::atomic::Ordering::Relaxed);
}
