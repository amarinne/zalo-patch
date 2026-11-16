#include "recorder_guard.h"

#include <jni.h>
#include <link.h>
#include <atomic>
#include <mutex>
#include <string_view>

namespace {
// Public LSPosed native-hook ABI. No framework implementation is bundled.
using Hook = int (*)(void*, void*, void**);
struct NativeApi { std::uint32_t version; Hook hook; int (*unhook)(void*); };
using Loaded = void (*)(const char*, void*);
std::atomic<Hook> hookFunction{nullptr};
std::atomic<bool> installed{false};
std::atomic<int> constructions{0};
std::mutex installMutex;
zalo_patch::Constructor originalConstructor;

[[maybe_unused]] void construct(void* object, std::uint32_t id, std::int32_t format) {
    zalo_patch::constructRecorder(originalConstructor, object, id, format);
    constructions.fetch_add(1, std::memory_order_relaxed);
}

bool qualified(const dl_phdr_info* image) {
#if defined(__aarch64__)
    constexpr unsigned char buildId[] = {
        0xe6,0xfc,0x4d,0x2d,0xfe,0x97,0x85,0xfd,0x12,0x45,
        0xda,0xf2,0x41,0xac,0xb1,0x37,0xcb,0xfa,0xf9,0xd0};
    for (std::size_t i = 0; i < image->dlpi_phnum; ++i) {
        const auto& segment = image->dlpi_phdr[i];
        if (segment.p_type != PT_NOTE) continue;
        const auto* bytes = reinterpret_cast<const unsigned char*>(image->dlpi_addr + segment.p_vaddr);
        std::size_t offset = 0;
        while (offset + sizeof(ElfW(Nhdr)) <= segment.p_memsz) {
            ElfW(Nhdr) note;
            std::memcpy(&note, bytes + offset, sizeof(note));
            offset += sizeof(note);
            const std::size_t nameSize = (static_cast<std::size_t>(note.n_namesz) + 3) & ~std::size_t(3);
            const std::size_t dataSize = (static_cast<std::size_t>(note.n_descsz) + 3) & ~std::size_t(3);
            if (nameSize > segment.p_memsz - offset
                    || dataSize > segment.p_memsz - offset - nameSize) break;
            if (note.n_type == NT_GNU_BUILD_ID && note.n_namesz == 4
                    && note.n_descsz == sizeof(buildId)
                    && std::memcmp(bytes + offset, "GNU", 4) == 0
                    && std::memcmp(bytes + offset + nameSize, buildId, sizeof(buildId)) == 0) return true;
            offset += nameSize + dataSize;
        }
    }
#else
    (void) image;
#endif
    return false;
}

bool zrtcLibrary(const char* name) {
    if (name == nullptr) return false;
    std::string_view path(name);
    return path == "libzrtc.so" || path.ends_with("/libzrtc.so");
}

int inspectImage(dl_phdr_info* image, std::size_t, void* result) {
    if (!zrtcLibrary(image->dlpi_name)) return 0;
    auto& state = *static_cast<int*>(result);
    // dl_iterate_phdr and the loader callback already hold the linker lock here.
    // Always acquire the install mutex second, including calls from nativePrepare.
    std::lock_guard<std::mutex> lock(installMutex);
    if (installed.load()) { state = 1; return 1; }
    if (!qualified(image)) { state = 2; return 1; } // Other builds retain their existing path.
    state = -1;
    Hook hook = hookFunction.load();
    if (hook == nullptr) return 1;
#if defined(__aarch64__)
    constexpr std::uintptr_t constructorOffset = 0x73d318;
    // Verify the inspected entry is executable and has the expected original prologue.
    bool executable = false;
    for (std::size_t i = 0; i < image->dlpi_phnum; ++i) {
        const auto& segment = image->dlpi_phdr[i];
        if (segment.p_type == PT_LOAD && (segment.p_flags & PF_X)
                && constructorOffset >= segment.p_vaddr
                && constructorOffset + 16 <= segment.p_vaddr + segment.p_memsz) executable = true;
    }
    if (!executable) return 1;
    auto* target = reinterpret_cast<void*>(image->dlpi_addr + constructorOffset);
    constexpr std::uint32_t prologue[] = {0xa9bd7bfd,0xf9000bf5,0x910003fd,0xa9024ff4};
    if (std::memcmp(target, prologue, sizeof(prologue)) != 0) return 1;
    if (hook(target, reinterpret_cast<void*>(construct),
             reinterpret_cast<void**>(&originalConstructor)) == 0 && originalConstructor != nullptr) {
        installed.store(true);
        state = 1;
    }
#endif
    return 1;
}

int ensure() {
    if (installed.load()) return 1;
    int state = 0;
    dl_iterate_phdr(inspectImage, &state);
    return state;
}

void loaded(const char* name, void*) {
    if (zrtcLibrary(name)) ensure();
}
} // namespace

extern "C" [[gnu::visibility("default"), gnu::used]]
Loaded native_init(const NativeApi* api) {
    if (api != nullptr && api->version >= 1 && api->version <= 2) hookFunction.store(api->hook);
    return loaded;
}

extern "C" JNIEXPORT jint JNICALL
Java_com_ez_zalopatch_ZrtcRecorderGuard_nativePrepare(JNIEnv*, jclass) { return ensure(); }

extern "C" JNIEXPORT jint JNICALL
Java_com_ez_zalopatch_ZrtcRecorderGuard_nativeConstructions(JNIEnv*, jclass) {
    return constructions.load(std::memory_order_relaxed);
}
