// SPDX-License-Identifier: GPL-2.0

#include <jni.h>

#include <algorithm>
#include <cstdint>
#include <cstring>
#include <deque>
#include <memory>
#include <mutex>
#include <vector>

#include "api/echo_canceller3_config.h"
#include "api/echo_canceller3_factory.h"
#include "api/echo_control.h"
#include "audio_processing/audio_buffer.h"
#include "audio_processing/audio_frame.h"
#include "audio_processing/high_pass_filter.h"
#include "resampler/push_sinc_resampler.h"

namespace {

constexpr int kPcmSampleBytes = sizeof(int16_t);
constexpr int kMinimumAec3SampleRateHz = 16000;
constexpr size_t kMaximumQueuedRenderFrames = 100;

class WebRtcEchoCanceler {
 public:
  WebRtcEchoCanceler(int sample_rate_hz,
                     int residual_echo_strength_percent)
      : input_sample_rate_hz_(sample_rate_hz),
        processing_sample_rate_hz_(
            std::max(sample_rate_hz, kMinimumAec3SampleRateHz)),
        input_frame_samples_(sample_rate_hz / 100),
        processing_frame_samples_(processing_sample_rate_hz_ / 100),
        input_pcm_(std::max(input_frame_samples_, 0)),
        processing_pcm_(std::max(processing_frame_samples_, 0)) {
    if (!IsSupportedInputSampleRate(input_sample_rate_hz_)) {
      return;
    }

    if (input_sample_rate_hz_ != processing_sample_rate_hz_) {
      render_to_processing_ = std::make_unique<webrtc::PushSincResampler>(
          input_frame_samples_, processing_frame_samples_);
      capture_to_processing_ = std::make_unique<webrtc::PushSincResampler>(
          input_frame_samples_, processing_frame_samples_);
      capture_from_processing_ = std::make_unique<webrtc::PushSincResampler>(
          processing_frame_samples_, input_frame_samples_);
    }

    render_audio_ = std::make_unique<webrtc::AudioBuffer>(
        processing_sample_rate_hz_,
        1,
        processing_sample_rate_hz_,
        1,
        processing_sample_rate_hz_,
        1);
    capture_audio_ = std::make_unique<webrtc::AudioBuffer>(
        processing_sample_rate_hz_,
        1,
        processing_sample_rate_hz_,
        1,
        processing_sample_rate_hz_,
        1);
    capture_high_pass_filter_ =
        std::make_unique<webrtc::HighPassFilter>(
            processing_sample_rate_hz_, 1);

    webrtc::EchoCanceller3Config config;
    config.delay.use_external_delay_estimator = true;
    config.ep_strength.default_gain =
        static_cast<float>(residual_echo_strength_percent) / 100.0f;
    config.ep_strength.bounded_erl =
        residual_echo_strength_percent > 100;
    webrtc::EchoCanceller3Config::Validate(&config);
    webrtc::EchoCanceller3Factory factory(config);
    echo_control_ = factory.Create(processing_sample_rate_hz_, 1, 1);
  }

  bool valid() const {
    return echo_control_ != nullptr && render_audio_ != nullptr &&
           capture_audio_ != nullptr && capture_high_pass_filter_ != nullptr &&
           input_frame_samples_ > 0 && processing_frame_samples_ > 0;
  }

  int Process(JNIEnv* env,
              jbyteArray pcm_array,
              int offset_bytes,
              int size_bytes,
              bool capture,
              int delay_ms) {
    if (!valid() || pcm_array == nullptr || offset_bytes < 0 ||
        size_bytes < 0) {
      return -1;
    }

    const jsize array_size = env->GetArrayLength(pcm_array);
    if (offset_bytes > array_size - size_bytes) {
      return -2;
    }

    const int frame_bytes = input_frame_samples_ * kPcmSampleBytes;
    const int processed_bytes = size_bytes - (size_bytes % frame_bytes);
    if (processed_bytes == 0) {
      return 0;
    }

    std::lock_guard<std::mutex> guard(lock_);
    for (int frame_offset = 0; frame_offset < processed_bytes;
         frame_offset += frame_bytes) {
      env->GetByteArrayRegion(
          pcm_array,
          offset_bytes + frame_offset,
          frame_bytes,
          reinterpret_cast<jbyte*>(input_pcm_.data()));
      if (env->ExceptionCheck()) {
        return -3;
      }

      if (capture) {
        FeedNextRenderFrame();
        if (!PrepareProcessingFrame(true)) {
          return -5;
        }
        ProcessCaptureFrame(std::max(delay_ms, 0));
        if (!RestoreInputRateCaptureFrame()) {
          return -6;
        }
        env->SetByteArrayRegion(
            pcm_array,
            offset_bytes + frame_offset,
            frame_bytes,
            reinterpret_cast<const jbyte*>(input_pcm_.data()));
        if (env->ExceptionCheck()) {
          return -4;
        }
      } else {
        if (!PrepareProcessingFrame(false)) {
          return -5;
        }
        QueueRenderFrame();
      }
    }

    return processed_bytes;
  }

  webrtc::EchoControl::Metrics GetMetrics() {
    std::lock_guard<std::mutex> guard(lock_);
    return echo_control_->GetMetrics();
  }

 private:
  static bool IsSupportedInputSampleRate(int sample_rate_hz) {
    return sample_rate_hz == 8000 || sample_rate_hz == 16000 ||
           sample_rate_hz == 32000 || sample_rate_hz == 48000;
  }

  bool PrepareProcessingFrame(bool capture) {
    if (input_sample_rate_hz_ == processing_sample_rate_hz_) {
      std::copy(
          input_pcm_.begin(), input_pcm_.end(), processing_pcm_.begin());
      return true;
    }

    webrtc::PushSincResampler* resampler =
        capture ? capture_to_processing_.get()
                : render_to_processing_.get();
    if (resampler == nullptr) {
      return false;
    }
    return resampler->Resample(
               input_pcm_.data(),
               input_pcm_.size(),
               processing_pcm_.data(),
               processing_pcm_.size()) == processing_pcm_.size();
  }

  bool RestoreInputRateCaptureFrame() {
    if (input_sample_rate_hz_ == processing_sample_rate_hz_) {
      std::copy(
          processing_pcm_.begin(), processing_pcm_.end(), input_pcm_.begin());
      return true;
    }

    if (capture_from_processing_ == nullptr) {
      return false;
    }
    return capture_from_processing_->Resample(
               processing_pcm_.data(),
               processing_pcm_.size(),
               input_pcm_.data(),
               input_pcm_.size()) == input_pcm_.size();
  }

  void QueueRenderFrame() {
    if (render_queue_.size() >= kMaximumQueuedRenderFrames) {
      render_queue_.pop_front();
    }
    render_queue_.emplace_back(processing_pcm_);
  }

  void FeedNextRenderFrame() {
    if (!render_queue_.empty()) {
      processing_pcm_ = std::move(render_queue_.front());
      render_queue_.pop_front();
      have_render_reference_ = true;
    } else {
      std::fill(processing_pcm_.begin(), processing_pcm_.end(), 0);
    }
    ProcessRenderFrame();
  }

  void ProcessRenderFrame() {
    render_frame_.UpdateFrame(
        0,
        processing_pcm_.data(),
        processing_frame_samples_,
        processing_sample_rate_hz_,
        webrtc::AudioFrame::kNormalSpeech,
        webrtc::AudioFrame::kVadActive,
        1);
    render_audio_->CopyFrom(&render_frame_);
    render_audio_->SplitIntoFrequencyBands();
    echo_control_->AnalyzeRender(render_audio_.get());
    render_audio_->MergeFrequencyBands();
    have_render_reference_ = true;
  }

  void ProcessCaptureFrame(int delay_ms) {
    if (!have_render_reference_) {
      return;
    }

    capture_frame_.UpdateFrame(
        0,
        processing_pcm_.data(),
        processing_frame_samples_,
        processing_sample_rate_hz_,
        webrtc::AudioFrame::kNormalSpeech,
        webrtc::AudioFrame::kVadActive,
        1);
    capture_audio_->CopyFrom(&capture_frame_);
    echo_control_->AnalyzeCapture(capture_audio_.get());
    capture_audio_->SplitIntoFrequencyBands();
    capture_high_pass_filter_->Process(capture_audio_.get(), true);
    echo_control_->SetAudioBufferDelay(delay_ms);
    echo_control_->ProcessCapture(capture_audio_.get(), false);
    capture_audio_->MergeFrequencyBands();
    capture_audio_->CopyTo(&capture_frame_);
    std::memcpy(
        processing_pcm_.data(),
        capture_frame_.data(),
        static_cast<size_t>(processing_frame_samples_) * kPcmSampleBytes);
  }

  const int input_sample_rate_hz_;
  const int processing_sample_rate_hz_;
  const int input_frame_samples_;
  const int processing_frame_samples_;
  std::unique_ptr<webrtc::EchoControl> echo_control_;
  std::unique_ptr<webrtc::AudioBuffer> render_audio_;
  std::unique_ptr<webrtc::AudioBuffer> capture_audio_;
  std::unique_ptr<webrtc::HighPassFilter> capture_high_pass_filter_;
  std::unique_ptr<webrtc::PushSincResampler> render_to_processing_;
  std::unique_ptr<webrtc::PushSincResampler> capture_to_processing_;
  std::unique_ptr<webrtc::PushSincResampler> capture_from_processing_;
  webrtc::AudioFrame render_frame_;
  webrtc::AudioFrame capture_frame_;
  std::vector<int16_t> input_pcm_;
  std::vector<int16_t> processing_pcm_;
  std::deque<std::vector<int16_t>> render_queue_;
  bool have_render_reference_ = false;
  std::mutex lock_;
};

WebRtcEchoCanceler* FromHandle(jlong handle) {
  return reinterpret_cast<WebRtcEchoCanceler*>(
      static_cast<intptr_t>(handle));
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_me_phh_sip_SipEchoCanceler_nativeCreate(
    JNIEnv* env,
    jobject thiz,
    jint sample_rate_hz,
    jint residual_echo_strength_percent) {
  (void)env;
  (void)thiz;

  auto echo_canceler = std::make_unique<WebRtcEchoCanceler>(
      sample_rate_hz,
      residual_echo_strength_percent);
  if (!echo_canceler->valid()) {
    return 0;
  }
  return static_cast<jlong>(
      reinterpret_cast<intptr_t>(echo_canceler.release()));
}

extern "C" JNIEXPORT jint JNICALL
Java_me_phh_sip_SipEchoCanceler_nativeProcess(
    JNIEnv* env,
    jobject thiz,
    jlong handle,
    jbyteArray pcm,
    jint offset_bytes,
    jint size_bytes,
    jboolean capture,
    jint delay_ms) {
  (void)thiz;

  WebRtcEchoCanceler* echo_canceler = FromHandle(handle);
  if (echo_canceler == nullptr) {
    return -1;
  }
  return echo_canceler->Process(
      env,
      pcm,
      offset_bytes,
      size_bytes,
      capture == JNI_TRUE,
      delay_ms);
}

extern "C" JNIEXPORT jdoubleArray JNICALL
Java_me_phh_sip_SipEchoCanceler_nativeGetMetrics(
    JNIEnv* env,
    jobject thiz,
    jlong handle) {
  (void)thiz;

  WebRtcEchoCanceler* echo_canceler = FromHandle(handle);
  if (echo_canceler == nullptr) {
    return nullptr;
  }

  const webrtc::EchoControl::Metrics metrics = echo_canceler->GetMetrics();
  const jdouble values[] = {
      metrics.echo_return_loss,
      metrics.echo_return_loss_enhancement,
      static_cast<jdouble>(metrics.delay_ms),
  };
  jdoubleArray result = env->NewDoubleArray(3);
  if (result == nullptr) {
    return nullptr;
  }
  env->SetDoubleArrayRegion(result, 0, 3, values);
  return env->ExceptionCheck() ? nullptr : result;
}

extern "C" JNIEXPORT void JNICALL
Java_me_phh_sip_SipEchoCanceler_nativeDestroy(
    JNIEnv* env,
    jobject thiz,
    jlong handle) {
  (void)env;
  (void)thiz;

  delete FromHandle(handle);
}
