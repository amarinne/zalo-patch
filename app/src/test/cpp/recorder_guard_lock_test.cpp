#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <future>
#include <latch>
#include <mutex>

// Exercise the production ensure()/loaded() paths with a recursive linker lock.
// Android calls the library-loaded callback while that lock is held.
#define dl_iterate_phdr testIteratePhdr
#include "../../main/cpp/recorder_guard.cpp"
#undef dl_iterate_phdr

namespace {
std::recursive_mutex linkerMutex;
std::latch linkerHeld{1};
std::latch scanEntered{1};
thread_local bool probeCaller = false;
}

int testIteratePhdr(int (*callback)(dl_phdr_info*, std::size_t, void*), void* data) {
    if (probeCaller) scanEntered.count_down();
    std::lock_guard<std::recursive_mutex> lock(linkerMutex);
    dl_phdr_info image{};
    image.dlpi_name = "libzrtc.so";
    // An unmatched build exercises the same lock order without installing a hook.
    return callback(&image, sizeof(image), data);
}

int main() {
    auto loader = std::async(std::launch::async, [] {
        std::lock_guard<std::recursive_mutex> lock(linkerMutex);
        linkerHeld.count_down();
        scanEntered.wait();
        loaded("libzrtc.so", nullptr);
    });
    linkerHeld.wait();
    auto probe = std::async(std::launch::async, [] {
        probeCaller = true;
        return ensure();
    });
    using namespace std::chrono_literals;
    if (probe.wait_for(2s) != std::future_status::ready
            || loader.wait_for(2s) != std::future_status::ready) {
        std::fputs("recorder guard deadlocked between its probe and loader callback\n", stderr);
        std::_Exit(1);
    }
    if (probe.get() != 2) return 2;
    loader.get();
}
