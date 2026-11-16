#include "recorder_guard.h"
#include <array>
#include <cassert>

namespace {
int calls;
void hostConstructor(void* object, std::uint32_t id, std::int32_t format) {
    assert(id == 7 && format == 1);
    ++calls;
    // Model the exact host constructor: normal fields are initialized, appended
    // mixing storage is left poisoned, and the lock must survive the guard.
    std::memset(object, 0x11, 0x40);
    std::uint64_t lock = 0x123456789abcdef0;
    std::memcpy(static_cast<char*>(object) + 0x5630, &lock, sizeof(lock));
}
}

int main() {
    std::array<unsigned char, 0x56a0> recorder;
    recorder.fill(0xa5);
    zalo_patch::constructRecorder(hostConstructor, recorder.data(), 7, 1);
    assert(calls == 1);
    // Garbage byte counts otherwise become native copy offsets and memset sizes.
    std::int32_t in, out;
    std::memcpy(&in, recorder.data() + 0x5628, sizeof(in));
    std::memcpy(&out, recorder.data() + 0x562c, sizeof(out));
    assert(in == 0 && out == 0);
    for (std::size_t i = 0x2e28; i < 0x5628; ++i) assert(recorder[i] == 0);
    std::uint64_t lock;
    std::memcpy(&lock, recorder.data() + 0x5630, sizeof(lock));
    assert(lock == 0x123456789abcdef0);
    assert(recorder[0] == 0x11);
    assert(recorder[0x2e27] == 0xa5); // Input resampler.
    assert(recorder[0x5638] == 0xa5); // Output resampler.
    assert(recorder.back() == 0xa5);
}
