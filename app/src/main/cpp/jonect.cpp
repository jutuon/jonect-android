/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

#include <cstdint>
#include <utility>
#include <optional>

#include <unistd.h>

#include <android/log.h>
#include "oboe/Oboe.h"

extern "C" {
    typedef int32_t (*WriteDataCallback)(int16_t* audio_data, int32_t num_frames);
}

const int32_t STATUS_ERROR = -1;
const int32_t STATUS_OK = 0;

class OboeDataCallback: public oboe::AudioStreamDataCallback {
private:
    WriteDataCallback rustCallback = nullptr;

public:
    OboeDataCallback(WriteDataCallback rustCallback) {
        this->rustCallback = rustCallback;
    }

    oboe::DataCallbackResult onAudioReady(
            oboe::AudioStream *audioStream,
            void *audioData,
            int32_t numFrames) override {
        int8_t callbackResult = (this->rustCallback)((int16_t*)audioData, numFrames);

        if (callbackResult == STATUS_ERROR) {
            return oboe::DataCallbackResult::Stop;
        }

        return oboe::DataCallbackResult::Continue;
    }
};

class OboeState {

public:
    std::optional<OboeDataCallback> dataCallback = std::optional<OboeDataCallback>();
    std::shared_ptr<oboe::AudioStream> stream = std::shared_ptr<oboe::AudioStream>();

    OboeState(
            WriteDataCallback writeDataCallback){
        this->dataCallback = OboeDataCallback(writeDataCallback);
    }

    OboeState() {}

    int32_t checkStreamParameters() const {
        // TODO

        if (this->stream->isXRunCountSupported()) {
            __android_log_write(ANDROID_LOG_INFO, "jonect_cpp" , "Oboe audio stream underrrun count support: true");
        } else {
            __android_log_write(ANDROID_LOG_INFO, "jonect_cpp" , "Oboe audio stream underrrun count support: false");
        }

        int bufferCapacity = this->stream->getBufferCapacityInFrames();
        __android_log_print(ANDROID_LOG_INFO, "jonect_cpp" , "Oboe stream->getBufferCapacityInFrames(): %i", bufferCapacity);

        int bufferSize = this->stream->getBufferSizeInFrames();
        __android_log_print(ANDROID_LOG_INFO, "jonect_cpp" , "Oboe stream->getBufferSizeInFrames(): %i", bufferSize);

        return STATUS_OK;
    }
};

static std::optional<OboeState> state = std::optional<OboeState>();

extern "C" int32_t start_oboe_in_callback_mode(
        WriteDataCallback callback,
        int32_t sampleRate,
        int32_t framesPerBurst,
        int32_t bufferCapacityInFrames) {
    oboe::DefaultStreamValues::FramesPerBurst = framesPerBurst;

    if (state.has_value()) {
        __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe stream is already created.");
        return STATUS_ERROR;
    }

    state = OboeState(callback);

    oboe::AudioStreamBuilder builder;
    builder.setSharingMode(oboe::SharingMode::Exclusive)
        ->setDirection(oboe::Direction::Output)
        ->setFormat(oboe::AudioFormat::I16)
        ->setChannelCount(oboe::ChannelCount::Stereo)
        ->setSampleRate(sampleRate)
        ->setBufferCapacityInFrames(bufferCapacityInFrames)
        ->setDataCallback(&state.value().dataCallback.value())
        ->setPerformanceMode(oboe::PerformanceMode::LowLatency);

    oboe::Result openResult = builder.openStream(state.value().stream);

    if (openResult != oboe::Result::OK) {
        const char* errorText = oboe::convertToText(openResult);
        __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe error code: %s", errorText);
        state.reset();
        return STATUS_ERROR;
    }

    if (state.value().checkStreamParameters() == STATUS_ERROR) {
        state.value().stream->close();
        state.reset();
        return STATUS_ERROR;
    }

    return STATUS_OK;
}

extern "C" int32_t start_oboe_in_write_mode(int32_t sampleRate,
                                            int32_t framesPerBurst,
                                            int32_t bufferCapacityInFrames) {
    oboe::DefaultStreamValues::FramesPerBurst = framesPerBurst;

    if (state.has_value()) {
        __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe stream is already created.");
        return STATUS_ERROR;
    }

    state = OboeState();

    oboe::AudioStreamBuilder builder;
    builder.setSharingMode(oboe::SharingMode::Shared)
            ->setDirection(oboe::Direction::Output)
            ->setFormat(oboe::AudioFormat::I16)
            ->setChannelCount(oboe::ChannelCount::Stereo)
            ->setSampleRate(sampleRate)
            ->setBufferCapacityInFrames(bufferCapacityInFrames)
            ->setPerformanceMode(oboe::PerformanceMode::LowLatency);

    oboe::Result openResult = builder.openStream(state.value().stream);

    if (openResult != oboe::Result::OK) {
        const char* errorText = oboe::convertToText(openResult);
        __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe error code: %s", errorText);
        state.reset();
        return STATUS_ERROR;
    }

    if (state.value().checkStreamParameters() == STATUS_ERROR) {
        state.value().stream->close();
        state.reset();
        return STATUS_ERROR;
    }

    return STATUS_OK;
}

extern "C" void oboe_request_start() {
    state.value().stream->requestStart();
}

/*
 * Returns STATUS_ERROR or count of frames which Oboe wrote to the current stream.
 */
extern "C" int32_t oboe_write_data(const int16_t *data,
                                   int32_t dataFrameCount) {
    auto resultFramesWritten = state.value().stream->write(data, dataFrameCount, INT64_MAX);

    if (resultFramesWritten.error() != oboe::Result::OK) {
        const char* errorText = oboe::convertToText(resultFramesWritten.error());
        __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe initial buffer write failed: %s", errorText);
        return STATUS_ERROR;
    }

    int32_t framesWritten = resultFramesWritten.value();

    if (framesWritten < 0) {
        __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe resultFramesWritten.value() is negative. resultFramesWritten: %i", framesWritten);
        return STATUS_ERROR;
    }

    return framesWritten;
}

/*
 * Returns STATUS_ERROR or XRunCount which is not negative.
 */
extern "C" int32_t oboe_get_x_run_count() {
    auto result = state.value().stream->getXRunCount();
    if (result.error() != oboe::Result::OK) {
        const char* errorText = oboe::convertToText(result.error());
        __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe stream->getXRunCount() error code: %s", errorText);
        return STATUS_ERROR;
    } else {
       int32_t count = result.value();
       if (count >= 0) {
           return count;
       } else {
           __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe stream->getXRunCount() is negative. Value: %i", count);
           return STATUS_ERROR;
       }
    }
}

/*
 * Close current oboe stream.
 */
extern "C" void close_oboe() {
    int32_t underrunCount = oboe_get_x_run_count();
    __android_log_print(ANDROID_LOG_ERROR, "jonect_cpp" , "Oboe underrun count: %i", underrunCount);

    state.value().stream->close();
    state.reset();
}

