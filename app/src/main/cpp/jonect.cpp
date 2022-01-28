/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#include <cstdint>

#include <unistd.h>

#include <android/log.h>
#include "oboe/Oboe.h"

extern "C" {
    typedef uint8_t (*WriteDataCallback)(int16_t* audio_data, int32_t num_frames, int32_t updateUnderrunCount);
    typedef void (*WaitQuitFunction)();
    typedef void (*SendErrorFunction)(const char *errorMessage);
    typedef void (*SendUnderrunCountErrorFunction)(int32_t getUnderrrunCountError);
}

class OboeDataCallback: public oboe::AudioStreamDataCallback {
private:
    WriteDataCallback rustCallback = nullptr;
    SendUnderrunCountErrorFunction errorFunction = nullptr;

    bool underrunCheck = true;
    int32_t underrunCount = 0;

public:
    OboeDataCallback(WriteDataCallback rustCallback, SendUnderrunCountErrorFunction errorFunction) {
        this->rustCallback = rustCallback;
        this->errorFunction = errorFunction;
    }

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override {
        int32_t updateUnderrunCount = 0;

        if (this->underrunCheck) {
            auto result = audioStream->getXRunCount();
            if (result.error() != oboe::Result::OK) {
                this->underrunCheck = false;
                int32_t error = static_cast<int32_t>(result.error());
                (this->errorFunction)(error);
            } else {
                if (this->underrunCount > result.value()) {
                    this->underrunCount = result.value();
                    updateUnderrunCount = result.value();
                }
            }
        }

        int8_t callbackResult = (this->rustCallback)((int16_t*)audioData, numFrames, updateUnderrunCount);

        if (callbackResult == 1) {
            return oboe::DataCallbackResult::Stop;
        }

        return oboe::DataCallbackResult::Continue;
    }
};

extern "C" void oboe_cpp_code(
        WriteDataCallback callback,
        WaitQuitFunction waitQuit,
        SendErrorFunction sendError,
        SendUnderrunCountErrorFunction sendUnderrunCountError,
        int32_t sampleRate,
        int32_t framesPerBurst,
        int framesPerDataCallback,
        int32_t bufferCapacityInFrames,
        const int16_t *initialData,
        int32_t initialDataFrameCount) {
    oboe::DefaultStreamValues::FramesPerBurst = framesPerBurst;

    OboeDataCallback cppCallback(callback, sendUnderrunCountError);
    std::shared_ptr<oboe::AudioStream> stream;

    oboe::AudioStreamBuilder builder;
    builder.setSharingMode(oboe::SharingMode::Shared)
        ->setDirection(oboe::Direction::Output)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(sampleRate)
        ->setBufferCapacityInFrames(bufferCapacityInFrames)
        // ->setFramesPerDataCallback(framesPerDataCallback)
        ->setDataCallback(&cppCallback)
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency);

    oboe::Result openResult = builder.openStream(stream);

    if (openResult != oboe::Result::OK) {
        const char* errorText = oboe::convertToText(openResult);
        __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe error code: %s", errorText);
        sendError(errorText);
        waitQuit();
        return;
    }

    if (stream->isXRunCountSupported()) {
        __android_log_write(ANDROID_LOG_INFO, "jonect_cpp" , "Oboe audio stream underrrun count support: true");
    } else {
        __android_log_write(ANDROID_LOG_INFO, "jonect_cpp" , "Oboe audio stream underrrun count support: false");
    }

    int bufferCapacity = stream->getBufferCapacityInFrames();
    __android_log_print(ANDROID_LOG_INFO, "jonect_cpp" , "Oboe stream->getBufferCapacityInFrames(): %i", bufferCapacity);

    int bufferSize = stream->getBufferSizeInFrames();
    __android_log_print(ANDROID_LOG_INFO, "jonect_cpp" , "Oboe stream->getBufferSizeInFrames(): %i", bufferSize);

    /*
    auto resultFramesWritten = stream->write(initialData, initialDataFrameCount, 0);

    if (resultFramesWritten.error() != oboe::Result::OK) {
        const char* errorText = oboe::convertToText(resultFramesWritten.error());
        __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe initial buffer write failed: %s", errorText);
        sendError(errorText);
        waitQuit();
        return;
    } else if (resultFramesWritten.value() != initialDataFrameCount) {
        int value = resultFramesWritten.value();
        __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe resultFramesWritten.value() != initialDataFrameCount. Value of resultFramesWritten: %i", value);
        sendError("Initial buffer write failed.");
        waitQuit();
        return;
    }
    */

    stream->requestStart();

    waitQuit();

    auto result = stream->getXRunCount();
    if (result.error() != oboe::Result::OK) {
        const char* errorText = oboe::convertToText(result.error());
        __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe stream->getXRunCount() error code: %s", errorText);
    } else {
        int value = result.value();
        __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe audio stream underruns: %i", value);
    }


    stream->close();
}

