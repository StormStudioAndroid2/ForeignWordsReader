#include "udpipe_adapter.h"

#include <cstdlib>
#include <cstring>
#include <exception>
#include <memory>
#include <sstream>
#include <string>

#include "model/model.h"
#include "model/pipeline.h"
#include "version/version.h"

using ufal::udpipe::model;
using ufal::udpipe::pipeline;

struct udpipe_adapter_engine {
  std::unique_ptr<model> loaded_model;
};

namespace {

char* copy_string(const std::string& value) {
  char* result = static_cast<char*>(std::malloc(value.size() + 1));
  if (result == nullptr) return nullptr;
  std::memcpy(result, value.c_str(), value.size() + 1);
  return result;
}

udpipe_adapter_result* failure(const std::string& message) {
  auto* result = new udpipe_adapter_result;
  result->output = nullptr;
  result->error = copy_string(message);
  return result;
}

}  // namespace

extern "C" udpipe_adapter_engine* udpipe_adapter_create_engine(void) {
  try {
    return new udpipe_adapter_engine;
  } catch (...) {
    return nullptr;
  }
}

extern "C" char* udpipe_adapter_load_model(
    udpipe_adapter_engine* engine,
    const char* model_path) {
  if (engine == nullptr) {
    return copy_string("UDPipe engine is null.");
  }
  if (model_path == nullptr || model_path[0] == '\0') {
    return copy_string("UDPipe model path is empty.");
  }

  try {
    std::unique_ptr<model> loaded(model::load(model_path));
    if (!loaded) {
      return copy_string("Could not load UDPipe model.");
    }
    engine->loaded_model = std::move(loaded);
    return nullptr;
  } catch (const std::exception& error) {
    return copy_string(error.what());
  } catch (...) {
    return copy_string("Unknown UDPipe model loading error.");
  }
}

extern "C" udpipe_adapter_result* udpipe_adapter_analyze_utf8(
    udpipe_adapter_engine* engine,
    const char* utf8_text) {
  if (engine == nullptr || !engine->loaded_model) {
    return failure("UDPipe model is not loaded.");
  }
  if (utf8_text == nullptr) {
    return failure("UDPipe input text is null.");
  }

  try {
    pipeline udpipe_pipeline(
        engine->loaded_model.get(),
        "tokenizer",
        pipeline::DEFAULT,
        pipeline::NONE,
        "conllu");

    std::istringstream input(utf8_text);
    std::ostringstream output;
    std::string error;

    if (!udpipe_pipeline.process(input, output, error)) {
      return failure(error.empty() ? "UDPipe processing failed." : error);
    }

    auto* result = new udpipe_adapter_result;
    result->output = copy_string(output.str());
    result->error = nullptr;
    return result;
  } catch (const std::exception& error) {
    return failure(error.what());
  } catch (...) {
    return failure("Unknown UDPipe processing error.");
  }
}

extern "C" const char* udpipe_adapter_version(void) {
  static const std::string current_version = [] {
    const ufal::udpipe::version version = ufal::udpipe::version::current();
    std::ostringstream output;
    output << version.major << "." << version.minor << "." << version.patch;
    if (!version.prerelease.empty()) {
      output << "-" << version.prerelease;
    }
    return output.str();
  }();
  return current_version.c_str();
}

extern "C" void udpipe_adapter_free_string(char* value) {
  std::free(value);
}

extern "C" void udpipe_adapter_result_free(udpipe_adapter_result* result) {
  if (result == nullptr) return;
  std::free(result->output);
  std::free(result->error);
  delete result;
}

extern "C" void udpipe_adapter_destroy_engine(udpipe_adapter_engine* engine) {
  delete engine;
}
