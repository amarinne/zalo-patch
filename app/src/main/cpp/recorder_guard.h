#pragma once

#include <cstddef>
#include <cstdint>
#include <cstring>

namespace zalo_patch {
// libzrtc build e6fc4d2dfe9785fd1245daf241acb137cbfaf9d0, arm64.
// The constructor initializes neither mix buffer nor its two byte counters.
constexpr std::size_t kMixStart = 0x2e28;
constexpr std::size_t kMixEnd = 0x5630; // The critical-section pointer starts here.
using Constructor = void (*)(void*, std::uint32_t, std::int32_t);

inline void constructRecorder(Constructor original, void* recorder,
                              std::uint32_t id, std::int32_t format) {
    original(recorder, id, format);
    std::memset(static_cast<char*>(recorder) + kMixStart, 0, kMixEnd - kMixStart);
}
} // namespace zalo_patch
