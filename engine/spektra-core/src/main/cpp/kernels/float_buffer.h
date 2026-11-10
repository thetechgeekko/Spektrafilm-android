// Spektrafilm for Android -- uninitialised float buffers. GPLv3.
// Film modeling powered by spektrafilm.
#pragma once

#include <limits>
#include <memory>
#include <new>
#include <type_traits>
#include <utility>
#include <vector>

namespace spk {

// std::allocator that default-initialises instead of value-initialising, so
// resize(n) on a float vector leaves the new elements unwritten rather than
// zero-filling them. Only for buffers whose every element is written before it
// is read. Built with SPK_POISON_UNINIT, new elements are quiet NaN instead,
// so a read before the write shows up as non-finite output.
template <typename T>
struct NoInitAllocator : std::allocator<T> {
    template <typename U>
    struct rebind {
        using other = NoInitAllocator<U>;
    };
    NoInitAllocator() noexcept = default;
    template <typename U>
    NoInitAllocator(const NoInitAllocator<U>&) noexcept {}

    template <typename U>
    void construct(U* p) noexcept(std::is_nothrow_default_constructible<U>::value) {
#if defined(SPK_POISON_UNINIT)
        if constexpr (std::is_floating_point<U>::value) {
            ::new (static_cast<void*>(p)) U(std::numeric_limits<U>::quiet_NaN());
            return;
        }
#endif
        ::new (static_cast<void*>(p)) U;
    }
    template <typename U, typename... Args>
    void construct(U* p, Args&&... args) {
        ::new (static_cast<void*>(p)) U(std::forward<Args>(args)...);
    }
};

template <typename T, typename U>
bool operator==(const NoInitAllocator<T>&, const NoInitAllocator<U>&) noexcept { return true; }
template <typename T, typename U>
bool operator!=(const NoInitAllocator<T>&, const NoInitAllocator<U>&) noexcept { return false; }

using FloatBuffer = std::vector<float, NoInitAllocator<float>>;

}  // namespace spk
