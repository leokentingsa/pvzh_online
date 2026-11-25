#include <jni.h>
#include <android/log.h>
#include <frida-core.h>

#include <deque>
#include <mutex>
#include <string>

#define LOG_TAG "PVZH-Frida"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

/*
 * Frida Gadget in "listen" mode accepts ONE client: once that client disconnects
 * the Gadget stops listening until the host process restarts. Therefore this
 * layer keeps a single long-lived remote-device connection that L2 (probe) and
 * L3/L4 (attach + load) share, and detaching only drops the session/script,
 * never the device.
 */

namespace {

std::mutex g_lock;
std::mutex g_msg_lock;
std::deque<std::string> g_messages;
const size_t kMaxMessages = 2000;

const char *kGadgetAddress = "127.0.0.1:27042";

bool g_initialized = false;
FridaDeviceManager *g_manager = nullptr;
FridaDevice *g_device = nullptr;
FridaSession *g_session = nullptr;
FridaScript *g_script = nullptr;
guint g_target_pid = 0;

/*
 * frida-core delivers script messages (the agent's send() payloads) on the
 * GLib main context that owns the connection. Without a thread that keeps
 * iterating that context, the JS runs inside the game but nothing ever reaches
 * the app: exactly the "hooks installed, counters all zero, no log" symptom.
 * So we run a dedicated main loop for the lifetime of the process.
 */
GMainContext *g_loop_context = nullptr;
GMainLoop *g_loop = nullptr;
GThread *g_loop_thread = nullptr;

gpointer main_loop_thread(gpointer) {
    g_main_context_push_thread_default(g_loop_context);
    g_main_loop_run(g_loop);
    g_main_context_pop_thread_default(g_loop_context);
    return nullptr;
}

bool start_main_loop() {
    if (g_loop != nullptr) return true;
    g_loop_context = g_main_context_new();
    g_loop = g_main_loop_new(g_loop_context, FALSE);
    g_loop_thread = g_thread_new("pvzh-frida-loop", main_loop_thread, nullptr);
    return g_loop_thread != nullptr;
}

void push_message(const char *message) {
    if (message == nullptr) return;
    std::lock_guard<std::mutex> guard(g_msg_lock);
    if (g_messages.size() >= kMaxMessages) g_messages.pop_front();
    g_messages.emplace_back(message);
}

void push_native(const std::string &text) {
    std::string line = "{\"type\":\"native\",\"payload\":{\"text\":\"";
    for (char c : text) {
        if (c == '"' || c == '\\') { line += '\\'; line += c; }
        else if (c == '\n') { line += "\\n"; }
        else line += c;
    }
    line += "\"}}";
    push_message(line.c_str());
}

void on_script_message(FridaScript *, const gchar *message, GBytes *, gpointer) {
    LOGI("script: %s", message ? message : "(null)");
    push_message(message);
}

std::string err_text(GError *error) {
    return std::string(error != nullptr ? error->message : "unknown Frida error");
}

void release_script_and_session() {
    if (g_script != nullptr) {
        GError *error = nullptr;
        if (!frida_script_is_destroyed(g_script)) {
            frida_script_unload_sync(g_script, nullptr, &error);
            if (error != nullptr) g_error_free(error);
        }
        frida_unref(g_script);
        g_script = nullptr;
    }
    if (g_session != nullptr) {
        GError *error = nullptr;
        if (!frida_session_is_detached(g_session)) {
            frida_session_detach_sync(g_session, nullptr, &error);
            if (error != nullptr) g_error_free(error);
        }
        frida_unref(g_session);
        g_session = nullptr;
    }
    g_target_pid = 0;
}

void release_device_and_manager() {
    if (g_device != nullptr) { frida_unref(g_device); g_device = nullptr; }
    if (g_manager != nullptr) {
        GError *error = nullptr;
        frida_device_manager_close_sync(g_manager, nullptr, &error);
        if (error != nullptr) g_error_free(error);
        frida_unref(g_manager);
        g_manager = nullptr;
    }
}

void release_all() {
    release_script_and_session();
    release_device_and_manager();
}

jstring to_java(JNIEnv *env, const std::string &value) {
    return env->NewStringUTF(value.c_str());
}

std::string gadget_advice(const std::string &raw) {
    if (raw.find("Unable to connect to remote frida-server") != std::string::npos ||
        raw.find("connection timed out") != std::string::npos ||
        raw.find("Connection refused") != std::string::npos) {
        return "Gadget 不接受这次连接。原因只有两种："
               "①它的唯一一个连接位置已经被占（上一次 app 进程、上一次自检，或游戏被系统冻结后留下的死连接）；"
               "②游戏没在前台跑起来。"
               "请依次做：1) 从最近任务里划掉 PVZH（或设置→应用→强制停止），重新打开并等到主界面；"
               "2) 小米/澎湃系统把 PVZH 设为「省电策略：无限制」并锁定后台；"
               "3) 工具里立刻连点 L2→L3，中途不要切回游戏——切回去会让 PVZH 被冻结，端口就不再应答；"
               "4) 最稳的做法是把工具和 PVZH 分屏同屏显示，两边都不进后台。";
    }
    return raw;
}

/* Enumerates the Gadget's own process table. Returns nullptr on failure. */
FridaProcessList *enumerate_processes(std::string &error_out) {
    GError *error = nullptr;
    FridaProcessList *list = frida_device_enumerate_processes_sync(
        g_device, nullptr, nullptr, &error);
    if (list == nullptr) {
        error_out = err_text(error);
        if (error) g_error_free(error);
    }
    return list;
}

/* Reuses the live connection, reconnecting only when there is no device yet. */
bool ensure_device(std::string &error_out) {
    if (g_device != nullptr) {
        std::string probe_error;
        if (enumerate_processes(probe_error) != nullptr) {
            // still alive: keep it, the caller re-enumerates for its own report
            return true;
        }
        LOGI("stored Gadget device went stale (%s); dropping it", probe_error.c_str());
        release_script_and_session();
        frida_unref(g_device);
        g_device = nullptr;
    }

    if (!g_initialized) {
        frida_init();
        start_main_loop();
        g_initialized = true;
    }
    if (g_manager == nullptr) {
        // Create the manager on the loop's context so every later completion and
        // script message is dispatched by the thread we keep running.
        g_main_context_push_thread_default(g_loop_context);
        g_manager = frida_device_manager_new();
        g_main_context_pop_thread_default(g_loop_context);
    }

    GError *error = nullptr;
    for (int attempt = 1; attempt <= 3; ++attempt) {
        g_device = frida_device_manager_add_remote_device_sync(
            g_manager, kGadgetAddress, nullptr, nullptr, &error);
        if (g_device != nullptr) {
            push_native("已向 127.0.0.1:27042 发起连接（第 " + std::to_string(attempt) +
                        " 次尝试，握手待验证）");
            return true;
        }
        error_out = err_text(error);
        if (error) { g_error_free(error); error = nullptr; }
        LOGI("gadget connect attempt %d failed: %s", attempt, error_out.c_str());
        g_usleep(400 * 1000);
    }
    // A half-initialised manager can keep the dead transport around; rebuild it.
    release_device_and_manager();
    return false;
}

std::string describe_processes(FridaProcessList *list, guint &target_pid) {
    std::string text;
    target_pid = 0;
    const gint count = frida_process_list_size(list);
    for (gint i = 0; i < count; i++) {
        FridaProcess *process = frida_process_list_get(list, i);
        const gchar *name = frida_process_get_name(process);
        guint pid = frida_process_get_pid(process);
        if (!text.empty()) text += ", ";
        text += std::string(name != nullptr ? name : "?") + "#" + std::to_string(pid);
        if (g_ascii_strcasecmp(name != nullptr ? name : "", "Gadget") == 0 && target_pid == 0) {
            target_pid = pid;
        }
        if (target_pid == 0 && count == 1) target_pid = pid;
        frida_unref(process);
    }
    return text + "（共 " + std::to_string(count) + " 个）";
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_pvzh_online_FridaBridge_nativeProbe(JNIEnv *env, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    std::string error_out;
    if (!ensure_device(error_out)) {
        std::string message = "ERROR: 无法连接 Gadget " + error_out +
            " —— " + gadget_advice(error_out);
        push_native(message);
        return to_java(env, message);
    }

    FridaProcessList *list = enumerate_processes(error_out);
    if (list == nullptr) {
        // The remote device object can be created lazily, so a "successful" add followed
        // by an enumerate timeout means the transport is dead. Drop it, otherwise every
        // later attempt burns another full connect timeout on the same dead handle.
        release_all();
        std::string message = "ERROR: 无法枚举 Gadget 进程：" + error_out +
            " —— " + gadget_advice(error_out);
        push_native(message);
        return to_java(env, message);
    }
    guint pid = 0;
    std::string processes = describe_processes(list, pid);
    frida_unref(list);

    std::string result = "OK: Frida " + std::string(frida_version_string()) +
        " · " + processes + " · 可 attach pid=" + std::to_string(pid) +
        "（连接已保持，下一步直接复用，不会重连）";
    push_native(result);
    return to_java(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pvzh_online_FridaBridge_nativeAttachAndLoad(JNIEnv *env, jobject, jstring source) {
    std::lock_guard<std::mutex> guard(g_lock);
    const char *script_source = env->GetStringUTFChars(source, nullptr);
    if (script_source == nullptr) return to_java(env, "ERROR: 无法读取 Agent 源码");

    std::string result;
    std::string error_out;

    if (!ensure_device(error_out)) {
        result = "ERROR: 无法连接 Gadget：" + error_out + " —— " + gadget_advice(error_out);
        push_native(result);
        env->ReleaseStringUTFChars(source, script_source);
        return to_java(env, result);
    }

    FridaProcessList *list = enumerate_processes(error_out);
    if (list == nullptr) {
        release_all();
        result = "ERROR: 无法枚举 Gadget 进程：" + error_out + " —— " + gadget_advice(error_out);
        push_native(result);
        env->ReleaseStringUTFChars(source, script_source);
        return to_java(env, result);
    }
    guint pid = 0;
    std::string processes = describe_processes(list, pid);
    frida_unref(list);
    push_native("Gadget 进程：" + processes);
    if (pid == 0) {
        result = "ERROR: Gadget 设备里没有可 attach 的进程";
        push_native(result);
        env->ReleaseStringUTFChars(source, script_source);
        return to_java(env, result);
    }

    release_script_and_session();

    GError *error = nullptr;
    g_session = frida_device_attach_sync(g_device, pid, nullptr, nullptr, &error);
    if (g_session == nullptr) {
        result = "ERROR: attach 失败 pid=" + std::to_string(pid) + "：" + err_text(error);
        if (error) g_error_free(error);
        push_native(result);
        env->ReleaseStringUTFChars(source, script_source);
        return to_java(env, result);
    }
    g_target_pid = pid;
    push_native("已 attach pid=" + std::to_string(pid));

    g_script = frida_session_create_script_sync(g_session, script_source, nullptr, nullptr, &error);
    if (g_script == nullptr) {
        result = "ERROR: 创建脚本失败：" + err_text(error);
        if (error) g_error_free(error);
        push_native(result);
        env->ReleaseStringUTFChars(source, script_source);
        return to_java(env, result);
    }

    g_signal_connect(g_script, "message", G_CALLBACK(on_script_message), nullptr);
    frida_script_load_sync(g_script, nullptr, &error);
    if (error != nullptr) {
        result = "ERROR: 加载 Agent 失败：" + err_text(error);
        push_native(result);
        g_error_free(error);
        release_script_and_session();
    } else {
        result = "OK: Frida " + std::string(frida_version_string()) +
            " 已 attach pid=" + std::to_string(pid) + " 并加载 Agent";
        push_native(result);
    }

    env->ReleaseStringUTFChars(source, script_source);
    return to_java(env, result);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pvzh_online_FridaBridge_nativePollMessages(JNIEnv *env, jobject) {
    std::deque<std::string> drained;
    {
        std::lock_guard<std::mutex> guard(g_msg_lock);
        drained.swap(g_messages);
    }
    std::string joined;
    for (size_t i = 0; i < drained.size(); i++) {
        if (i > 0) joined += "\n";
        joined += drained[i];
    }
    return to_java(env, joined);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_pvzh_online_FridaBridge_nativeState(JNIEnv *env, jobject) {
    std::string state = g_device == nullptr ? "no-device" :
        (g_session == nullptr ? "device-only" :
            (frida_session_is_detached(g_session) ? "session-detached" : "attached"));
    return to_java(env, state + " pid=" + std::to_string(g_target_pid));
}

/* Drops the script/session but keeps the Gadget connection alive. */
extern "C" JNIEXPORT void JNICALL
Java_com_pvzh_online_FridaBridge_nativeDetach(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    release_script_and_session();
    push_native("已卸载 Agent 并保持 Gadget 连接（可再次加载）");
}

/* Full teardown, only used when the app itself is going away. */
extern "C" JNIEXPORT void JNICALL
Java_com_pvzh_online_FridaBridge_nativeShutdown(JNIEnv *, jobject) {
    std::lock_guard<std::mutex> guard(g_lock);
    release_all();
    if (g_loop != nullptr) {
        g_main_loop_quit(g_loop);
        if (g_loop_thread != nullptr) { g_thread_join(g_loop_thread); g_loop_thread = nullptr; }
        g_main_loop_unref(g_loop);
        g_loop = nullptr;
        if (g_loop_context != nullptr) { g_main_context_unref(g_loop_context); g_loop_context = nullptr; }
    }
    g_initialized = false;
}
