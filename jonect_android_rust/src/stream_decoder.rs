use std::{net::{TcpListener, ToSocketAddrs, TcpStream}, io::{Read, Write}, ffi::{CString}};

use audiopus::coder::Decoder;

pub fn android_println(text: &str) {
    let tag = CString::new("jonect_rust").unwrap();
    let text = CString::new(text).unwrap();

    unsafe {
        ndk_sys::__android_log_write(
            ndk_sys::android_LogPriority_ANDROID_LOG_INFO.try_into().unwrap(),
            tag.as_ptr(),
            text.as_ptr(),
        );
    }
}


pub struct StreamDecoder {
    decoder: Decoder,
    decoder_input_buffer: Vec<u8>,
    decoder_output_buffer: Vec<i16>,
    address: String,
    port: u16,
}

impl StreamDecoder {
    pub fn new(address: String, port: u16) -> Self {
        let decoder = Decoder::new(audiopus::SampleRate::Hz48000, audiopus::Channels::Stereo).unwrap();

        Self {
            decoder,
            decoder_input_buffer: Vec::new(),
            // Output buffer lenght: channels * frame/packet sample count at 48kHz.
            decoder_output_buffer: vec![0; 2 * 120],
            address,
            port,
        }
    }

    pub fn start(&mut self) {
        Self::config_thread_priority();

        let address = ("127.0.0.1", 12345).to_socket_addrs().unwrap().next().unwrap();
        let listener = TcpListener::bind(address).unwrap();
        let (mut java_audio_thread_connection, _) = listener.accept().unwrap();


        let address = (self.address.as_str(), self.port).to_socket_addrs().unwrap().next().unwrap();
        let mut input_stream = match TcpStream::connect(address) {
            Ok(stream) => stream,
            Err(_) => {
                java_audio_thread_connection.shutdown(std::net::Shutdown::Both).unwrap();
                return
            }
        };

        loop {
            // Read packet byte count from input stream.
            let mut packet_byte_count = [0u8; 4];
            match input_stream.read_exact(&mut packet_byte_count) {
                Ok(()) => (),
                Err(_) => {
                    android_println("Packet byte count read error.");
                    java_audio_thread_connection.shutdown(std::net::Shutdown::Both).unwrap();
                    return
                }
            }

            // Read packet from input stream.
            let packet_bytes: usize = i32::from_be_bytes(packet_byte_count).try_into().unwrap();
            self.decoder_input_buffer.resize(packet_bytes, 0);
            match input_stream.read_exact(&mut self.decoder_input_buffer) {
                Ok(()) => (),
                Err(_) => {
                    android_println("Packet read error.");
                    java_audio_thread_connection.shutdown(std::net::Shutdown::Both).unwrap();
                    return
                }
            }

            // Decode packet.
            let packet = (&self.decoder_input_buffer).try_into().unwrap();
            let channel_sample_count = self.decoder.decode(
                Some(packet),
                (&mut self.decoder_output_buffer).try_into().unwrap(),
                false)
            .unwrap();

            // Send PCM audio to Java audio thread.
            for sample in &self.decoder_output_buffer[0..(channel_sample_count*2)] {
                match java_audio_thread_connection.write_all(&sample.to_le_bytes()) {
                    Ok(_) => (),
                    Err(_) => {
                        android_println("PCM data send error.");
                        input_stream.shutdown(std::net::Shutdown::Both).unwrap();
                        return;
                    }
                }
            }

            // Check quit notification.
            if super::QUIT_NOTIFICATION.load(std::sync::atomic::Ordering::Relaxed) {
                android_println("Quit detected.");
                return;
            }
        }

    }

    fn config_thread_priority() {
        let result = unsafe {
            // Set thread priority for current thread. Currently on Linux
            // libc::setpriority will set thread nice value but this might
            // change in the future. Alternative would be sched_setattr system
            // call. Value of Android API constant Process.THREAD_PRIORITY_AUDIO
            // is -16.
            libc::setpriority(libc::PRIO_PROCESS, 0, -16)
        };

        if result == -1 {
            android_println("Setting thread priority failed.");
        }

        let get_result = unsafe {
            libc::getpriority(libc::PRIO_PROCESS, 0)
        };

        if get_result == -1 {
            android_println("libc::getpriority returned -1 which might be error or not.");
        } else {
            android_println(&format!("Thread priority: {}", get_result));
        }
    }
}
