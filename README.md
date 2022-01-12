# Jonect Android client

This Android application plays PCM or Opus audio stream from the Jonect server.

The app requires Android 5.0 or later.

When the app runs on Android 7.0 or later then audio underrun count is printed
to the app's log.

When the app runs on Android 8.0 or later then
AudioTrack.PERFORMANCE_MODE_LOW_LATENCY is enabled if Android device's native
sample rate is 44100 Hz or 48000 Hz when playing PCM audio stream. When playing
Opus audio stream it is enabled only if native sample rate is 48000 Hz.

## Building and running on Ubuntu 20.04

1. Install Rust, Android Studio and package `python-is-python3`.

   <https://www.rust-lang.org/>

   <https://developer.android.com/studio>

2. Install Rust toolchains for Android.

   ```
   rustup target add armv7-linux-androideabi
   rustup target add i686-linux-android
   rustup target add aarch64-linux-android
   rustup target add x86_64-linux-android
   ```

3. Install NDK version 21.4.7075529 and cmake 3.18.1 from Android Studio's
   Android SDK manager.

4. Start Android Studio with CMAKE environment variable set to the previously
   installed cmake binary.

    For example you can use the following commands to do that if Android Studio
    and Android SDK was installed to default locations:

    ```shell
    export CMAKE="$HOME/Android/Sdk/cmake/3.18.1/bin/cmake"
    cd ~/android-studio/bin
    ./studio.sh
    ```

5. Build and run the app from the Android Studio.

## User guide

**Warning**: Use low volume when you connect to the server to prevent damaging
your ears. Also be careful to not make audio loopback especiously when playing
microphone input or using the app on Android Emulator.

**Warning**: All data which is transferred between the client and the server is
unencrypted.

Opus audio decoding support requires that local TCP port 12345 is available.
Most likely it is available if there are no server software running on the
Android device.

1. Open the app.

2. Write Jonect server IP address. IP address must start with `192.168.`, `10.`
   or `127.`.

    When using Android Emulator IP address `10.0.2.2` is host computer at least
    on Ubuntu.

3. Press connect button.

### Using USB connection

USB connection to the server requires that ADB (Android Debug Bridge) is
enabled from Android device's settings.

1. Install `adb` command. For example it is included with Android SDK.

2. Start the server.

3. Connect Android device to the computer and run adb commands. If using adb
   from Android SDK installed to default location run the following commands
   which opens server ports to the Android device.

   ```
   cd "$HOME/Android/Sdk/platform-tools"
   ./adb reverse tcp:8080 tcp:8080
   ./adb reverse tcp:8082 tcp:8082
   ```

4. Write localhost address `127.0.0.1` to the app's IP address field. Press
   connect button.

## Known issues

* Audio underruns especiously when the audio streaming begins.
* Error handling is not robust enough so there might be crashes and freezes.

## Licenses

Code is licenced under MPL 2.0. App icon is licensed under Apache 2.0.

## App icon information

App icon license is Apache 2.0. Icon xml files have Apache 2.0 marked in the
license headers.

The icon was created using Android Studio's Asset Studio and Apache 2.0 licensed
icon Devices from Material design icon set from Google.
<https://github.com/google/material-design-icons>
