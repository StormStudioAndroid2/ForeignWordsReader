#pragma once

#ifdef __cplusplus
extern "C" {
#endif

typedef struct udpipe_adapter_engine udpipe_adapter_engine;

typedef struct udpipe_adapter_result {
  char* output;
  char* error;
} udpipe_adapter_result;

udpipe_adapter_engine* udpipe_adapter_create_engine(void);
char* udpipe_adapter_load_model(udpipe_adapter_engine* engine, const char* model_path);
udpipe_adapter_result* udpipe_adapter_analyze_utf8(udpipe_adapter_engine* engine, const char* utf8_text);
const char* udpipe_adapter_version(void);
void udpipe_adapter_free_string(char* value);
void udpipe_adapter_result_free(udpipe_adapter_result* result);
void udpipe_adapter_destroy_engine(udpipe_adapter_engine* engine);

#ifdef __cplusplus
}
#endif
