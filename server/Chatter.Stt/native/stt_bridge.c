/* Stable C ABI in front of sherpa-onnx's offline recognizer.
 * Compiled on the server by deploy/stt-setup.sh against the headers shipped
 * with the same libsherpa-onnx-c-api.so. The big config struct is zeroed, so a
 * newer sherpa that only appends fields still loads. */
#define _POSIX_C_SOURCE 200809L

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#include "sherpa-onnx/c-api/c-api.h"

#define EXPORT __attribute__((visibility("default")))

struct SttHandle {
  const SherpaOnnxOfflineRecognizer *recognizer;
  char *model;
  char *tokens;
};

static void set_err(char *buf, int len, const char *msg) {
  if (buf != NULL && len > 0) snprintf(buf, (size_t)len, "%s", msg == NULL ? "" : msg);
  if (msg != NULL && msg[0] != '\0') fprintf(stderr, "stt_bridge: %s\n", msg);
}

EXPORT void *stt_create(const char *model, const char *tokens, int threads, char *err, int err_len) {
  struct SttHandle *handle;
  SherpaOnnxOfflineRecognizerConfig config;

  set_err(err, err_len, "");
  if (model == NULL || model[0] == '\0' || tokens == NULL || tokens[0] == '\0') {
    set_err(err, err_len, "model and tokens are required");
    return NULL;
  }

  handle = calloc(1, sizeof(*handle));
  if (handle == NULL) {
    set_err(err, err_len, "out of memory");
    return NULL;
  }
  handle->model = strdup(model);
  handle->tokens = strdup(tokens);
  if (handle->model == NULL || handle->tokens == NULL) {
    set_err(err, err_len, "out of memory");
    free(handle->model);
    free(handle->tokens);
    free(handle);
    return NULL;
  }

  memset(&config, 0, sizeof(config));
  config.feat_config.sample_rate = 16000;
  config.feat_config.feature_dim = 80;
  config.model_config.tokens = handle->tokens;
  config.model_config.num_threads = threads > 0 ? threads : 1;
  config.model_config.provider = "cpu";
  config.model_config.sense_voice.model = handle->model;
  config.model_config.sense_voice.language = "auto";
  config.model_config.sense_voice.use_itn = 1;
  config.decoding_method = "greedy_search";

  handle->recognizer = SherpaOnnxCreateOfflineRecognizer(&config);
  if (handle->recognizer == NULL) {
    set_err(err, err_len, "SherpaOnnxCreateOfflineRecognizer failed");
    free(handle->model);
    free(handle->tokens);
    free(handle);
    return NULL;
  }
  return handle;
}

EXPORT int stt_decode(void *raw, const float *samples, int n, int sample_rate, char *buf, int buf_len) {
  struct SttHandle *handle = raw;
  const SherpaOnnxOfflineStream *stream;
  const SherpaOnnxOfflineRecognizerResult *result;
  size_t len;
  int rc = 0;

  if (handle == NULL || handle->recognizer == NULL || samples == NULL || n < 0 || buf == NULL || buf_len < 2)
    return -1;
  buf[0] = '\0';

  stream = SherpaOnnxCreateOfflineStream(handle->recognizer);
  if (stream == NULL) return -1;
  SherpaOnnxAcceptWaveformOffline(stream, sample_rate, samples, n);
  SherpaOnnxDecodeOfflineStream(handle->recognizer, stream);
  result = SherpaOnnxGetOfflineStreamResult(stream);
  if (result == NULL || result->text == NULL) {
    rc = -1;
  } else {
    len = strlen(result->text);
    if ((int)len >= buf_len) {
      rc = -2;
    } else {
      memcpy(buf, result->text, len + 1);
    }
  }
  if (result != NULL) SherpaOnnxDestroyOfflineRecognizerResult(result);
  SherpaOnnxDestroyOfflineStream(stream);
  return rc;
}

EXPORT void stt_destroy(void *raw) {
  struct SttHandle *handle = raw;
  if (handle == NULL) return;
  if (handle->recognizer != NULL) SherpaOnnxDestroyOfflineRecognizer(handle->recognizer);
  free(handle->model);
  free(handle->tokens);
  free(handle);
}
