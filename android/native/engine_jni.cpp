// The on-device engine behind the JNI bridge (spec 004 FR-012 to FR-014, T409).
//
// One Engine holds a model, its llama context and, when a projector file is given, libmtmd's vision
// context. A call renders the chat through the model's own template, evaluates the prompt (text and
// images), and samples the reply, optionally constrained to a JSON Schema by a grammar. The flow follows
// llama.cpp's tools/mtmd/mtmd-cli.cpp at the commit pinned in CMakeLists.txt.
//
// Cancelling sets a flag that llama_decode's abort callback and the generation loop watch (spike S1:
// the decode returns within milliseconds). An image encoding cannot be aborted, so the flag is checked
// between images. Nothing here logs a prompt or a reply.

#include <jni.h>

#include <atomic>
#include <memory>
#include <string>
#include <vector>

#include "arg.h"
#include "chat.h"
#include "common.h"
#include "json-schema-to-grammar.h"
#include "json.h"
#include "llama.h"
#include "mtmd-helper.h"
#include "mtmd.h"
#include "sampling.h"

namespace {

struct Engine {
    common_params params;
    common_init_result_ptr init;
    llama_model * model = nullptr;
    llama_context * lctx = nullptr;
    const llama_vocab * vocab = nullptr;
    mtmd::context_ptr vision;
    common_chat_templates_ptr tmpls;
    int n_batch = 0;
    // A cancel names the call it is for: a cancel that arrives before its call has started still stops it, and a
    // late cancel of a finished call never touches the next one. Calls are numbered from 1; no call has id -1.
    std::atomic<jlong> cancel_id{-1};
    jlong op = 0;
    bool cancelled() const { return cancel_id.load() == op; }
};

bool abort_callback(void * data) { return static_cast<Engine *>(data)->cancelled(); }

// Text crosses JNI as UTF-8 byte arrays: GetStringUTFChars gives "modified" UTF-8, which is not UTF-8 for
// characters above U+FFFF or for NUL.
std::string bytes_to_string(JNIEnv * env, jbyteArray arr) {
    if (arr == nullptr) return "";
    const jsize len = env->GetArrayLength(arr);
    std::string out((size_t) len, '\0');
    if (len > 0) env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(&out[0]));
    return out;
}

jbyteArray string_to_bytes(JNIEnv * env, const std::string & s) {
    jbyteArray arr = env->NewByteArray((jsize) s.size());
    if (arr != nullptr && !s.empty()) env->SetByteArrayRegion(arr, 0, (jsize) s.size(), reinterpret_cast<const jbyte *>(s.data()));
    return arr;
}

std::string to_string(JNIEnv * env, jstring s) {
    if (s == nullptr) return "";
    const char * c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

void throw_runtime(JNIEnv * env, const std::string & message) {
    jclass cls = env->FindClass("java/lang/RuntimeException");
    env->ThrowNew(cls, message.c_str());
}

bool ends_with(const std::string & s, const std::string & suffix) {
    return s.size() >= suffix.size() && s.compare(s.size() - suffix.size(), suffix.size(), suffix) == 0;
}

// Decodes tokens in n_batch pieces, asking for logits on the last one (no projector: the plain text path).
bool decode_text(Engine & e, const llama_tokens & tokens, llama_pos & n_past) {
    common_batch batch(e.lctx);
    for (size_t i = 0; i < tokens.size(); i += e.n_batch) {
        const size_t n = std::min<size_t>(e.n_batch, tokens.size() - i);
        batch.clear();
        for (size_t j = 0; j < n; j++) {
            batch.add(tokens[i + j], n_past + (llama_pos) j, 0, i + j == tokens.size() - 1);
        }
        if (llama_process(e.lctx, LLAMA_PROCESS_TYPE_DECODE, batch.get())) return false;
        n_past += (llama_pos) n;
    }
    return true;
}

} // namespace

extern "C" {

JNIEXPORT jstring JNICALL Java_app_epicreplay_engine_NativeEngine_nativeMarker(JNIEnv * env, jobject) {
    return env->NewStringUTF(mtmd_default_marker());
}

JNIEXPORT jlong JNICALL Java_app_epicreplay_engine_NativeEngine_nativeLoad(
    JNIEnv * env, jobject, jstring modelPath, jstring projectorPath, jint contextTokens, jint threads, jint imageMaxTokens) {
    try {
        auto e = std::make_unique<Engine>();
        e->params.model.path = to_string(env, modelPath);
        const std::string projector = to_string(env, projectorPath);
        if (!projector.empty()) e->params.mmproj.path = projector;
        e->params.n_ctx = contextTokens;
        e->params.n_batch = 512;
        e->params.n_ubatch = 512;
        e->params.cpuparams.n_threads = threads;
        e->params.cpuparams_batch.n_threads = threads;
        postprocess_cpu_params(e->params.cpuparams);
        postprocess_cpu_params(e->params.cpuparams_batch, &e->params.cpuparams);
        e->params.mmproj_use_gpu = false; // no GPU backend is built
        e->params.warmup = false;
        e->params.image_max_tokens = imageMaxTokens;
        e->params.use_jinja = true;
        e->n_batch = e->params.n_batch;

        e->init = common_init_from_params(e->params);
        e->model = e->init->model();
        e->lctx = e->init->context();
        if (!e->model || !e->lctx) {
            throw_runtime(env, "the model could not be loaded");
            return 0;
        }
        e->vocab = llama_model_get_vocab(e->model);
        e->tmpls = common_chat_templates_init(e->model, "");
        if (!e->tmpls) {
            throw_runtime(env, "the model has no chat template");
            return 0;
        }
        if (!projector.empty()) {
            mtmd_context_params mp = mtmd_context_params_default();
            mp.use_gpu = false;
            mp.print_timings = false;
            mp.n_threads = threads;
            mp.flash_attn_type = e->params.flash_attn_type;
            mp.warmup = false;
            mp.image_min_tokens = e->params.image_min_tokens;
            mp.image_max_tokens = e->params.image_max_tokens;
            {
                const int n_ubatch = llama_n_ubatch(e->lctx);
                auto mem = mtmd_get_memory_usage(projector.c_str(), mp);
                if (mem.use_non_causal && mem.image_max_tokens > n_ubatch) {
                    mp.image_max_tokens = n_ubatch;
                    mp.image_min_tokens = std::min(mp.image_min_tokens, n_ubatch);
                }
            }
            e->vision.reset(mtmd_init_from_file(projector.c_str(), e->model, mp));
            if (!e->vision) {
                throw_runtime(env, "the image projector could not be loaded");
                return 0;
            }
        }
        return reinterpret_cast<jlong>(e.release());
    } catch (const std::exception & ex) {
        throw_runtime(env, std::string("loading failed: ") + ex.what());
        return 0;
    }
}

JNIEXPORT void JNICALL Java_app_epicreplay_engine_NativeEngine_nativeCancel(JNIEnv *, jobject, jlong handle, jlong op) {
    if (handle != 0) reinterpret_cast<Engine *>(handle)->cancel_id.store(op);
}

JNIEXPORT void JNICALL Java_app_epicreplay_engine_NativeEngine_nativeFree(JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<Engine *>(handle);
}

// stats (out): [0] prompt tokens, [1] completion tokens, [2] stop (0 end of text, 1 token limit, 2 cancelled),
//              [3] prompt ms (image encoding included), [4] generation ms, [5] image encoding ms
JNIEXPORT jbyteArray JNICALL Java_app_epicreplay_engine_NativeEngine_nativeGenerate(
    JNIEnv * env, jobject, jlong handle, jlong op, jobjectArray roles, jobjectArray contents, jobjectArray images,
    jbyteArray schema, jint maxTokens, jfloat temperature, jboolean thinking, jlongArray stats) {
    Engine & e = *reinterpret_cast<Engine *>(handle);
    jlong st[6] = {0, 0, 0, 0, 0, 0};
    std::string result;
    try {
        e.op = op; // a cancel for this id that came earlier is seen at once
        llama_set_abort_callback(e.lctx, abort_callback, &e);
        llama_memory_clear(llama_get_memory(e.lctx), true);

        // the chat, through the model's own template
        common_chat_templates_inputs inputs;
        const jsize n_msg = env->GetArrayLength(roles);
        for (jsize i = 0; i < n_msg; i++) {
            common_chat_msg m;
            auto role = (jstring) env->GetObjectArrayElement(roles, i);
            auto content = (jbyteArray) env->GetObjectArrayElement(contents, i);
            m.role = to_string(env, role);
            m.content = bytes_to_string(env, content);
            env->DeleteLocalRef(role);
            env->DeleteLocalRef(content);
            inputs.messages.push_back(std::move(m));
        }
        inputs.use_jinja = true;
        inputs.add_generation_prompt = true;
        inputs.enable_thinking = thinking;
        common_chat_params rendered = common_chat_templates_apply(e.tmpls.get(), inputs);
        std::string prompt = rendered.prompt.str();
        if (!thinking && rendered.supports_thinking && !rendered.thinking_end_tags.empty() &&
            ends_with(prompt, rendered.thinking_start_tag + "\n")) {
            // spike S1: a prompt that already holds an empty think block makes a thinking model answer at once
            prompt += "\n" + rendered.thinking_end_tags[0] + "\n\n";
        }

        // images, in the order of the markers
        const jsize n_img = images ? env->GetArrayLength(images) : 0;
        if (n_img > 0 && !e.vision) throw std::runtime_error("this model has no image projector");
        mtmd::bitmaps bitmaps;
        for (jsize i = 0; i < n_img; i++) {
            auto arr = (jbyteArray) env->GetObjectArrayElement(images, i);
            const jsize len = env->GetArrayLength(arr);
            std::vector<unsigned char> buf(len);
            env->GetByteArrayRegion(arr, 0, len, reinterpret_cast<jbyte *>(buf.data()));
            env->DeleteLocalRef(arr);
            mtmd_helper_init_opt opt = mtmd_helper_init_opt_default();
            auto res = mtmd_helper_bitmap_init_from_buf(e.vision.get(), buf.data(), buf.size(), false, opt);
            if (!res.bitmap) throw std::runtime_error("an image could not be read");
            bitmaps.entries.emplace_back(res.bitmap);
        }

        // the sampler, with the schema as a grammar when one is asked for
        common_params_sampling sp = e.params.sampling;
        sp.temp = temperature;
        const std::string schemaText = bytes_to_string(env, schema);
        if (!schemaText.empty()) {
            sp.grammar = {COMMON_GRAMMAR_TYPE_OUTPUT_FORMAT, json_schema_to_grammar(common_json::parse(schemaText))};
        }
        common_sampler * smpl = common_sampler_init(e.model, sp);
        if (!smpl) throw std::runtime_error("the sampler could not be made");
        std::unique_ptr<common_sampler, void (*)(common_sampler *)> smpl_guard(smpl, common_sampler_free);

        // the prompt
        const int64_t t_prompt = ggml_time_ms();
        llama_pos n_past = 0;
        size_t prompt_tokens = 0;
        bool ok = true;
        const llama_pos n_ctx = (llama_pos) llama_n_ctx(e.lctx);
        if (!e.vision) {
            const llama_tokens tokens = common_tokenize(e.lctx, prompt, true, true);
            prompt_tokens = tokens.size();
            if ((llama_pos) tokens.size() + maxTokens > n_ctx) throw std::runtime_error("the prompt and the reply do not fit the context");
            ok = decode_text(e, tokens, n_past);
        } else {
            const std::string marker = mtmd_default_marker();
            std::vector<std::string> segments;
            size_t start = 0, pos;
            while ((pos = prompt.find(marker, start)) != std::string::npos) {
                segments.push_back(prompt.substr(start, pos - start));
                start = pos + marker.size();
            }
            segments.push_back(prompt.substr(start));
            auto bitmaps_c = bitmaps.c_ptr();
            if (segments.size() - 1 != bitmaps_c.size()) throw std::runtime_error("the number of images does not match the prompt");
            std::vector<mtmd_input_text> texts(segments.size());
            std::vector<mtmd_input_part> parts;
            for (size_t i = 0; i < segments.size(); i++) {
                texts[i] = {segments[i].data(), segments[i].size(), false, true};
                parts.push_back({&texts[i], nullptr});
                if (i < bitmaps_c.size()) parts.push_back({nullptr, bitmaps_c[i]});
            }
            std::vector<const mtmd_input_part *> parts_ptr;
            for (const auto & p : parts) parts_ptr.push_back(&p);
            mtmd::input_chunks chunks(mtmd_input_chunks_init());
            if (mtmd_tokenize_from_parts(e.vision.get(), chunks.ptr.get(), parts_ptr.data(), parts_ptr.size(), true) != 0) {
                throw std::runtime_error("the prompt could not be tokenized");
            }
            // the positions the prompt will take (more than its tokens for M-RoPE), known before anything is evaluated
            prompt_tokens = mtmd_helper_get_n_tokens(chunks.ptr.get());
            if (mtmd_helper_get_n_pos(chunks.ptr.get()) + maxTokens > n_ctx) {
                throw std::runtime_error("the prompt and the reply do not fit the context");
            }
            const size_t n_chunks = mtmd_input_chunks_size(chunks.ptr.get());
            for (size_t i = 0; i < n_chunks && ok; i++) {
                if (e.cancelled()) break;
                auto chunk = mtmd_input_chunks_get(chunks.ptr.get(), i);
                llama_pos next = n_past;
                if (mtmd_input_chunk_get_type(chunk) == MTMD_INPUT_CHUNK_TYPE_TEXT) {
                    ok = mtmd_helper_eval_chunk_single(e.vision.get(), e.lctx, chunk, n_past, 0, e.n_batch, i == n_chunks - 1, &next) == 0;
                } else {
                    // one image at a time, so a cancel is seen between images (an encoding cannot be aborted)
                    const int64_t t_enc = ggml_time_ms();
                    mtmd::batch_ptr mb(mtmd_batch_init(e.vision.get()));
                    ok = mtmd_batch_add_chunk(mb.get(), chunk) == 0 && mtmd_batch_encode(mb.get()) == 0;
                    st[5] += ggml_time_ms() - t_enc;
                    if (ok && !e.cancelled()) {
                        float * embd = mtmd_batch_get_output_embd(mb.get(), chunk);
                        ok = embd != nullptr &&
                             mtmd_helper_decode_image_chunk(e.vision.get(), e.lctx, chunk, embd, n_past, 0, e.n_batch, &next, nullptr, nullptr) == 0;
                    }
                }
                n_past = next;
            }
        }
        st[3] = ggml_time_ms() - t_prompt;
        st[0] = (jlong) prompt_tokens;
        if (e.cancelled()) {
            st[2] = 2;
        } else if (!ok) {
            throw std::runtime_error("the prompt could not be evaluated");
        } else {
            // the reply
            const int64_t t_gen = ggml_time_ms();
            common_batch batch(e.lctx);
            int n_gen = 0;
            st[2] = 1;
            while (n_gen < maxTokens) {
                if (e.cancelled()) { st[2] = 2; break; }
                llama_token id = common_sampler_sample(smpl, e.lctx, -1);
                common_sampler_accept(smpl, id, true);
                if (llama_vocab_is_eog(e.vocab, id)) { st[2] = 0; break; }
                result += common_token_to_piece(e.lctx, id);
                n_gen++;
                batch.clear();
                batch.add(id, n_past++, 0, true);
                if (llama_process(e.lctx, LLAMA_PROCESS_TYPE_DECODE, batch.get())) {
                    if (e.cancelled()) { st[2] = 2; break; }
                    throw std::runtime_error("a token could not be decoded");
                }
            }
            st[1] = n_gen;
            st[4] = ggml_time_ms() - t_gen;
        }
        llama_set_abort_callback(e.lctx, nullptr, nullptr);
    } catch (const std::exception & ex) {
        llama_set_abort_callback(e.lctx, nullptr, nullptr);
        throw_runtime(env, ex.what());
        return nullptr;
    }
    llama_set_abort_callback(e.lctx, nullptr, nullptr);
    env->SetLongArrayRegion(stats, 0, 6, st);
    return string_to_bytes(env, result);
}

} // extern "C"
