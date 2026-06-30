#pragma once

#include "onnxruntime_c_api.h"
#include <stddef.h>
#include <stdint.h>
#include <string.h>

static inline const OrtApi* NovelOrtApi(void) {
  const OrtApiBase* base = OrtGetApiBase();
  if (base == NULL || base->GetApi == NULL) return NULL;
  return base->GetApi(ORT_API_VERSION);
}

static inline const char* NovelOrtStatusMessage(OrtStatus* status) {
  static char message[1024];
  const OrtApi* api = NovelOrtApi();
  if (status == NULL) return NULL;
  if (api == NULL || api->GetErrorMessage == NULL) return "ONNX Runtime API is unavailable.";
  const char* raw = api->GetErrorMessage(status);
  strncpy(message, raw == NULL ? "ONNX Runtime call failed." : raw, sizeof(message) - 1);
  message[sizeof(message) - 1] = '\0';
  if (api->ReleaseStatus != NULL) api->ReleaseStatus(status);
  return message;
}

static inline const char* NovelOrtCreateEnv(void** out) {
  const OrtApi* api = NovelOrtApi();
  if (api == NULL || api->CreateEnv == NULL) return "ONNX Runtime CreateEnv is unavailable.";
  OrtEnv* env = NULL;
  const char* error = NovelOrtStatusMessage(api->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "KokoroIOS", &env));
  if (error != NULL) return error;
  *out = env;
  return NULL;
}

static inline const char* NovelOrtCreateSessionOptions(void** out) {
  const OrtApi* api = NovelOrtApi();
  if (api == NULL || api->CreateSessionOptions == NULL) return "ONNX Runtime CreateSessionOptions is unavailable.";
  OrtSessionOptions* options = NULL;
  const char* error = NovelOrtStatusMessage(api->CreateSessionOptions(&options));
  if (error != NULL) return error;
  *out = options;
  return NULL;
}

static inline const char* NovelOrtSetIntraOpNumThreads(void* options, int threads) {
  const OrtApi* api = NovelOrtApi();
  if (api == NULL || api->SetIntraOpNumThreads == NULL) return "ONNX Runtime SetIntraOpNumThreads is unavailable.";
  return NovelOrtStatusMessage(api->SetIntraOpNumThreads((OrtSessionOptions*)options, threads));
}

static inline const char* NovelOrtSetSessionGraphOptimizationLevel(void* options, int level) {
  const OrtApi* api = NovelOrtApi();
  if (api == NULL || api->SetSessionGraphOptimizationLevel == NULL) return "ONNX Runtime SetSessionGraphOptimizationLevel is unavailable.";
  return NovelOrtStatusMessage(api->SetSessionGraphOptimizationLevel((OrtSessionOptions*)options, (GraphOptimizationLevel)level));
}

static inline const char* NovelOrtCreateSession(void* env, const char* model_path, void* options, void** out) {
  const OrtApi* api = NovelOrtApi();
  if (api == NULL || api->CreateSession == NULL) return "ONNX Runtime CreateSession is unavailable.";
  OrtSession* session = NULL;
  const char* error = NovelOrtStatusMessage(api->CreateSession((OrtEnv*)env, model_path, (OrtSessionOptions*)options, &session));
  if (error != NULL) return error;
  *out = session;
  return NULL;
}

static inline const char* NovelOrtCreateCpuMemoryInfo(void** out) {
  const OrtApi* api = NovelOrtApi();
  if (api == NULL || api->CreateCpuMemoryInfo == NULL) return "ONNX Runtime CreateCpuMemoryInfo is unavailable.";
  OrtMemoryInfo* memory_info = NULL;
  const char* error = NovelOrtStatusMessage(api->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memory_info));
  if (error != NULL) return error;
  *out = memory_info;
  return NULL;
}

static inline const char* NovelOrtCreateTensor(void* memory_info, void* data, size_t data_len, const int64_t* shape, size_t shape_len, int type, void** out) {
  const OrtApi* api = NovelOrtApi();
  if (api == NULL || api->CreateTensorWithDataAsOrtValue == NULL) return "ONNX Runtime CreateTensorWithDataAsOrtValue is unavailable.";
  OrtValue* value = NULL;
  const char* error = NovelOrtStatusMessage(api->CreateTensorWithDataAsOrtValue(
    (const OrtMemoryInfo*)memory_info,
    data,
    data_len,
    shape,
    shape_len,
    (ONNXTensorElementDataType)type,
    &value
  ));
  if (error != NULL) return error;
  *out = value;
  return NULL;
}

static inline const char* NovelOrtRun(void* session, const char* const* input_names, void* const* input_values, size_t input_len, const char* const* output_names, size_t output_len, void** outputs) {
  const OrtApi* api = NovelOrtApi();
  if (api == NULL || api->Run == NULL) return "ONNX Runtime Run is unavailable.";
  return NovelOrtStatusMessage(api->Run(
    (OrtSession*)session,
    NULL,
    input_names,
    (const OrtValue* const*)input_values,
    input_len,
    output_names,
    output_len,
    (OrtValue**)outputs
  ));
}

static inline const char* NovelOrtGetTensorData(void* value, void** data, size_t* element_count) {
  const OrtApi* api = NovelOrtApi();
  if (api == NULL || api->GetTensorTypeAndShape == NULL || api->GetTensorShapeElementCount == NULL || api->GetTensorMutableData == NULL) {
    return "ONNX Runtime tensor data APIs are unavailable.";
  }
  OrtTensorTypeAndShapeInfo* shape_info = NULL;
  const char* error = NovelOrtStatusMessage(api->GetTensorTypeAndShape((OrtValue*)value, &shape_info));
  if (error != NULL) return error;
  error = NovelOrtStatusMessage(api->GetTensorShapeElementCount(shape_info, element_count));
  if (api->ReleaseTensorTypeAndShapeInfo != NULL) api->ReleaseTensorTypeAndShapeInfo(shape_info);
  if (error != NULL) return error;
  return NovelOrtStatusMessage(api->GetTensorMutableData((OrtValue*)value, data));
}

static inline void NovelOrtReleaseValue(void* value) {
  const OrtApi* api = NovelOrtApi();
  if (api != NULL && api->ReleaseValue != NULL) api->ReleaseValue((OrtValue*)value);
}

static inline void NovelOrtReleaseSession(void* session) {
  const OrtApi* api = NovelOrtApi();
  if (api != NULL && api->ReleaseSession != NULL) api->ReleaseSession((OrtSession*)session);
}

static inline void NovelOrtReleaseSessionOptions(void* options) {
  const OrtApi* api = NovelOrtApi();
  if (api != NULL && api->ReleaseSessionOptions != NULL) api->ReleaseSessionOptions((OrtSessionOptions*)options);
}
