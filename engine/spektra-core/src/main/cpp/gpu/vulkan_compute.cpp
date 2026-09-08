/*
 * Spektrafilm for Android — GPU (Vulkan compute) fast-path implementation. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Compiled only when SPK_ENABLE_VULKAN is defined (see gpu/vulkan_compute.h and
 * CMakeLists.txt). Headless compute: instance -> physical device + compute queue ->
 * host-visible storage buffers -> compute pipeline (embedded SPIR-V) -> dispatch ->
 * read back. Every failure path returns false so the caller falls back to the CPU.
 *
 * PERSISTENT HOST (GPU M1, #146): the scan kernel's pipeline, descriptor set,
 * command buffer and buffers are created once and reused across calls (buffers
 * grow-only), instead of being rebuilt per dispatch. The PR #145 device probe
 * measured the old per-call host at ~25-48 ms of fixed overhead per dispatch —
 * killing that cost is the preview-tier win. The vendored SPIR-V is UNCHANGED, so
 * the probe's published Tier 1 error numbers still describe the shipped binary.
 * Calls are serialized by a mutex (one queue, one command buffer); on any Vulkan
 * failure the kernel state is torn down and the call returns false (CPU fallback),
 * so a later call can retry from scratch.
 *
 * NaN GUARD (#145 caveat, mandated by #146): GLSL clamp(NaN) is implementation-
 * defined, so the engine's NaN-density -> black semantics must not rest on driver
 * behaviour. The input upload maps every non-finite density component to 1e4f
 * (10^-1e4 underflows to exactly 0 in fp32 -> zero transmittance -> black), which
 * is deterministic on every conformant driver. Finite inputs are copied verbatim,
 * so the probe's error measurements are unaffected.
 */
#include "gpu/vulkan_compute.h"

namespace spk::gpu {

PointwiseDispatchGrid plan_pointwise_dispatch(uint32_t pixel_count,
                                              uint32_t max_groups_x,
                                              uint32_t max_groups_y) noexcept {
    PointwiseDispatchGrid grid{};
    if (pixel_count == 0 || max_groups_x == 0 || max_groups_y == 0) return grid;
    constexpr uint64_t kWorkgroupSize = 64;
    constexpr uint64_t kMaxSafeGroupsX = UINT32_MAX / kWorkgroupSize;
    const uint64_t groups = (static_cast<uint64_t>(pixel_count) + kWorkgroupSize - 1) /
                            kWorkgroupSize;
    uint64_t groups_x = groups < max_groups_x ? groups : max_groups_x;
    if (groups_x > kMaxSafeGroupsX) groups_x = kMaxSafeGroupsX;
    const uint64_t groups_y = (groups + groups_x - 1) / groups_x;
    if (groups_y > max_groups_y || groups_x * groups_y > UINT32_MAX) return grid;
    grid.groups_x = static_cast<uint32_t>(groups_x);
    grid.groups_y = static_cast<uint32_t>(groups_y);
    grid.total_groups = static_cast<uint32_t>(groups);
    grid.group_row_stride_pixels = groups_x * kWorkgroupSize;
    grid.valid = true;
    return grid;
}

const char* pointwise_fallback_reason_name(PointwiseFallbackReason reason) noexcept {
    switch (reason) {
        case PointwiseFallbackReason::none: return "none";
        case PointwiseFallbackReason::vulkan_disabled: return "vulkan-disabled";
        case PointwiseFallbackReason::unavailable: return "vulkan-unavailable";
        case PointwiseFallbackReason::invalid_request: return "invalid-request";
        case PointwiseFallbackReason::request_too_large: return "request-too-large";
        case PointwiseFallbackReason::allocation_failed: return "allocation-failed";
        case PointwiseFallbackReason::pipeline_failed: return "pipeline-failed";
        case PointwiseFallbackReason::upload_failed: return "upload-failed";
        case PointwiseFallbackReason::dispatch_failed: return "dispatch-failed";
        case PointwiseFallbackReason::readback_failed: return "readback-failed";
    }
    return "unknown";
}

}  // namespace spk::gpu

#ifndef SPK_ENABLE_VULKAN

namespace spk::gpu {
bool render_pointwise_chain(const PointwiseChainRequest&, PointwiseChainOutput*,
                            PointwiseChainDiagnostics* diagnostics) noexcept {
    if (diagnostics) {
        *diagnostics = {};
        diagnostics->fallback_reason = PointwiseFallbackReason::vulkan_disabled;
    }
    return false;
}
bool available() { return false; }
bool cctf_encode_srgb(float*, size_t) { return false; }
bool scan_spectral(const float*, float*, uint32_t, const float*, const float*, const float*) { return false; }
bool scan_spectral_linear(const float*, float*, uint32_t, const float*, const float*, const float*) { return false; }
bool halation_scatter(const HalationScatterRequest&, double*,
                      HalationScatterDiagnostics* diagnostics) noexcept {
    if (diagnostics) {
        *diagnostics = {};
        diagnostics->reason = "vulkan-disabled";
    }
    return false;
}
}  // namespace spk::gpu

#else

#include <vulkan/vulkan.h>

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <mutex>
#include <new>
#include <vector>

#include "gpu/cctf_encode_spv.h"
#include "gpu/filming_spv.h"
#include "gpu/halation_scatter_spv.h"
#include "kernels/parallel.h"
#include "gpu/printing_spv.h"
#include "gpu/scan_spectral_chain_spv.h"
#include "gpu/scan_spectral_lin_spv.h"
#include "gpu/scan_spectral_spv.h"

namespace spk::gpu {
namespace {

// One host-visible storage buffer + its memory + its persistent mapping.
struct Buf {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    void* mapped = nullptr;
    VkDeviceSize cap = 0;
};

// SPDX-FileCopyrightText: 2026 Spektrafilm Android contributors
// SPDX-License-Identifier: GPL-3.0-only
// Resource lifetime, private scratch, keyed static uploads and single-command
// DAG/barrier concepts adapted from chaert-s/spektrafilm-ofx
// src/SpektraVulkanRenderer.cpp at
// 86476afc5b077de77e2278e3658d1ba9309892a1. This Android implementation is a
// new bounded three-stage API: one mapped bidirectional staging buffer, two
// device-local ping-pong buffers, and no OpenFX/desktop host integration.
struct ResidentBuf {
    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    void* mapped = nullptr;
    VkDeviceSize cap = 0;
    VkDeviceSize allocationSize = 0;
    VkMemoryPropertyFlags memoryFlags = 0;
};

enum PointwiseTable : size_t {
    kFilmTc = 0,
    kFilmDevelopAxis,
    kFilmDevelopCurve,
    kFilmDirAxis,
    kFilmDirCurve,
    kPrintDye,
    kPrintIlluminantSensitivity,
    kPrintPaperAxis,
    kPrintPaperCurve,
    kScanDye,
    kScanIlluminantCmf,
    kPointwiseTableCount,
};

struct Ctx {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice phys = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    VkPhysicalDeviceProperties properties{};
    VkPhysicalDeviceMemoryProperties memoryProperties{};
    bool ok = false;

    // Persistent per-kernel state (built lazily on the kernel's first call).
    // The full-chain (scan_spectral.comp) and linear (scan_spectral_lin.comp)
    // kernels share this shape — identical bindings + push-constant layout —
    // but keep separate pipelines and buffers.
    struct Kernel {
        VkShaderModule shader = VK_NULL_HANDLE;
        VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
        VkPipelineLayout pl = VK_NULL_HANDLE;
        VkPipeline pipe = VK_NULL_HANDLE;
        VkDescriptorPool dpool = VK_NULL_HANDLE;
        VkDescriptorSet dset = VK_NULL_HANDLE;  // freed with dpool
        VkCommandPool cpool = VK_NULL_HANDLE;
        VkCommandBuffer cmd = VK_NULL_HANDLE;   // freed with cpool
        VkFence fence = VK_NULL_HANDLE;
        Buf in, out, dyeB, cmfB;                // in/out grow-only; tables fixed
        bool pipelineReady = false;
    };
    Kernel scanFused;  // scan_spectral.comp (density -> encoded sRGB)
    Kernel scanLin;    // scan_spectral_lin.comp (density -> unclipped linear RGB)
    // halation_scatter.comp (#206): one pipeline, eleven descriptor sets (one
    // per buffer-role permutation of the op sequence), four device-local
    // whole-frame work buffers, one mapped staging band, one frame-floats
    // buffer. Grow-only like the scan kernels; torn down on failure only.
    struct HalationKernel {
        VkShaderModule shader = VK_NULL_HANDLE;
        VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
        VkPipelineLayout pl = VK_NULL_HANDLE;
        VkPipeline pipe = VK_NULL_HANDLE;
        VkDescriptorPool dpool = VK_NULL_HANDLE;
        std::array<VkDescriptorSet, 11> dsets{};
        VkCommandPool cpool = VK_NULL_HANDLE;
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        Buf staging;                       // host-visible, mapped: upload + readback band
        Buf frame;                         // FrameFloats (binding 27)
        std::array<ResidentBuf, 4> work{}; // src, sum, x, xT (device-local preferred)
        bool pipelineReady = false;
    } halation;

    struct PointwiseChain {
        std::array<VkShaderModule, 3> shaders{};
        std::array<VkDescriptorSetLayout, 3> descriptorLayouts{};
        std::array<VkPipelineLayout, 3> pipelineLayouts{};
        std::array<VkPipeline, 3> pipelines{};
        VkDescriptorPool descriptorPool = VK_NULL_HANDLE;
        std::array<VkDescriptorSet, 3> descriptorSets{};
        VkCommandPool commandPool = VK_NULL_HANDLE;
        VkCommandBuffer commandBuffer = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        ResidentBuf frameStaging;
        std::array<ResidentBuf, 2> ping{};
        ResidentBuf staticStaging;
        std::array<ResidentBuf, kPointwiseTableCount> tables{};
        std::array<VkDeviceSize, kPointwiseTableCount> cachedTableBytes{};
        uint64_t cachedTableKey = 0;
        bool pipelinesReady = false;
    } pointwise;

    bool init() {
        VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
        app.pApplicationName = "spektra";
        app.apiVersion = VK_API_VERSION_1_1;
        VkInstanceCreateInfo ici{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
        ici.pApplicationInfo = &app;
        if (vkCreateInstance(&ici, nullptr, &instance) != VK_SUCCESS) return false;

        uint32_t nphys = 0;
        vkEnumeratePhysicalDevices(instance, &nphys, nullptr);
        if (nphys == 0) return false;
        std::vector<VkPhysicalDevice> devs(nphys);
        vkEnumeratePhysicalDevices(instance, &nphys, devs.data());
        phys = devs[0];
        vkGetPhysicalDeviceProperties(phys, &properties);
        vkGetPhysicalDeviceMemoryProperties(phys, &memoryProperties);

        uint32_t nq = 0;
        vkGetPhysicalDeviceQueueFamilyProperties(phys, &nq, nullptr);
        std::vector<VkQueueFamilyProperties> qprops(nq);
        vkGetPhysicalDeviceQueueFamilyProperties(phys, &nq, qprops.data());
        bool found = false;
        for (uint32_t i = 0; i < nq; ++i) {
            if (qprops[i].queueFlags & VK_QUEUE_COMPUTE_BIT) { queueFamily = i; found = true; break; }
        }
        if (!found) return false;

        float pri = 1.0f;
        VkDeviceQueueCreateInfo qci{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
        qci.queueFamilyIndex = queueFamily;
        qci.queueCount = 1;
        qci.pQueuePriorities = &pri;
        VkDeviceCreateInfo dci{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
        dci.queueCreateInfoCount = 1;
        dci.pQueueCreateInfos = &qci;
        if (vkCreateDevice(phys, &dci, nullptr, &device) != VK_SUCCESS) return false;
        vkGetDeviceQueue(device, queueFamily, 0, &queue);
        ok = true;
        return true;
    }

    void destroyBuf(Buf& b) {
        if (b.mapped) { vkUnmapMemory(device, b.mem); b.mapped = nullptr; }
        if (b.mem) { vkFreeMemory(device, b.mem, nullptr); b.mem = VK_NULL_HANDLE; }
        if (b.buf) { vkDestroyBuffer(device, b.buf, nullptr); b.buf = VK_NULL_HANDLE; }
        b.cap = 0;
    }

    void destroyResidentBuf(ResidentBuf& b) {
        if (b.mapped) {
            vkUnmapMemory(device, b.mem);
            b.mapped = nullptr;
        }
        if (b.buf) {
            vkDestroyBuffer(device, b.buf, nullptr);
            b.buf = VK_NULL_HANDLE;
        }
        if (b.mem) {
            vkFreeMemory(device, b.mem, nullptr);
            b.mem = VK_NULL_HANDLE;
        }
        b.cap = 0;
        b.allocationSize = 0;
        b.memoryFlags = 0;
    }

    void destroyPointwise() {
        if (!device) return;
        vkDeviceWaitIdle(device);
        PointwiseChain& s = pointwise;
        if (s.fence) {
            vkDestroyFence(device, s.fence, nullptr);
            s.fence = VK_NULL_HANDLE;
        }
        if (s.commandPool) {
            vkDestroyCommandPool(device, s.commandPool, nullptr);
            s.commandPool = VK_NULL_HANDLE;
            s.commandBuffer = VK_NULL_HANDLE;
        }
        if (s.descriptorPool) {
            vkDestroyDescriptorPool(device, s.descriptorPool, nullptr);
            s.descriptorPool = VK_NULL_HANDLE;
            s.descriptorSets = {};
        }
        for (VkPipeline& pipeline : s.pipelines) {
            if (pipeline) vkDestroyPipeline(device, pipeline, nullptr);
            pipeline = VK_NULL_HANDLE;
        }
        for (VkPipelineLayout& layout : s.pipelineLayouts) {
            if (layout) vkDestroyPipelineLayout(device, layout, nullptr);
            layout = VK_NULL_HANDLE;
        }
        for (VkDescriptorSetLayout& layout : s.descriptorLayouts) {
            if (layout) vkDestroyDescriptorSetLayout(device, layout, nullptr);
            layout = VK_NULL_HANDLE;
        }
        for (VkShaderModule& shader : s.shaders) {
            if (shader) vkDestroyShaderModule(device, shader, nullptr);
            shader = VK_NULL_HANDLE;
        }
        destroyResidentBuf(s.frameStaging);
        for (ResidentBuf& b : s.ping) destroyResidentBuf(b);
        destroyResidentBuf(s.staticStaging);
        for (ResidentBuf& b : s.tables) destroyResidentBuf(b);
        s.cachedTableBytes = {};
        s.cachedTableKey = 0;
        s.pipelinesReady = false;
    }

    int findPreferredMemType(uint32_t bits, VkMemoryPropertyFlags required,
                             VkMemoryPropertyFlags preferred) const {
        for (uint32_t pass = 0; pass < 2; ++pass) {
            const VkMemoryPropertyFlags wanted = pass == 0 ? (required | preferred) : required;
            for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; ++i) {
                if ((bits & (1u << i)) &&
                    (memoryProperties.memoryTypes[i].propertyFlags & wanted) == wanted) {
                    return static_cast<int>(i);
                }
            }
        }
        return -1;
    }

    VkDeviceSize largestHeapFor(VkMemoryPropertyFlags required) const {
        VkDeviceSize largest = 0;
        for (uint32_t i = 0; i < memoryProperties.memoryTypeCount; ++i) {
            if ((memoryProperties.memoryTypes[i].propertyFlags & required) != required) continue;
            const uint32_t heap = memoryProperties.memoryTypes[i].heapIndex;
            if (heap < memoryProperties.memoryHeapCount &&
                memoryProperties.memoryHeaps[heap].size > largest) {
                largest = memoryProperties.memoryHeaps[heap].size;
            }
        }
        return largest;
    }

    bool ensureResidentBuf(ResidentBuf& b, VkDeviceSize bytes,
                           VkBufferUsageFlags usage,
                           VkMemoryPropertyFlags required,
                           VkMemoryPropertyFlags preferred,
                           bool persistentMap,
                           PointwiseChainDiagnostics& diagnostics) {
        if (bytes == 0) return false;
        if (b.buf && b.mem && b.cap >= bytes &&
            (b.memoryFlags & required) == required && (!persistentMap || b.mapped)) {
            return true;
        }

        destroyResidentBuf(b);
        VkBuffer buffer = VK_NULL_HANDLE;
        VkDeviceMemory memory = VK_NULL_HANDLE;
        VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bci.size = bytes;
        bci.usage = usage;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(device, &bci, nullptr, &buffer) != VK_SUCCESS) return false;

        VkMemoryRequirements req{};
        vkGetBufferMemoryRequirements(device, buffer, &req);
        const int memoryType = findPreferredMemType(req.memoryTypeBits, required, preferred);
        if (memoryType < 0) {
            vkDestroyBuffer(device, buffer, nullptr);
            return false;
        }
        const uint32_t heapIndex = memoryProperties.memoryTypes[memoryType].heapIndex;
        if (heapIndex >= memoryProperties.memoryHeapCount ||
            req.size > memoryProperties.memoryHeaps[heapIndex].size) {
            vkDestroyBuffer(device, buffer, nullptr);
            return false;
        }
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = static_cast<uint32_t>(memoryType);
        if (vkAllocateMemory(device, &mai, nullptr, &memory) != VK_SUCCESS) {
            vkDestroyBuffer(device, buffer, nullptr);
            return false;
        }
        if (vkBindBufferMemory(device, buffer, memory, 0) != VK_SUCCESS) {
            vkDestroyBuffer(device, buffer, nullptr);
            vkFreeMemory(device, memory, nullptr);
            return false;
        }

        void* mapped = nullptr;
        if (persistentMap &&
            vkMapMemory(device, memory, 0, VK_WHOLE_SIZE, 0, &mapped) != VK_SUCCESS) {
            vkDestroyBuffer(device, buffer, nullptr);
            vkFreeMemory(device, memory, nullptr);
            return false;
        }
        b.buf = buffer;
        b.mem = memory;
        b.mapped = mapped;
        b.cap = bytes;
        b.allocationSize = req.size;
        b.memoryFlags = memoryProperties.memoryTypes[memoryType].propertyFlags;
        ++diagnostics.buffer_allocations;
        return true;
    }

    bool flushResident(const ResidentBuf& b) const {
        if ((b.memoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0) return true;
        VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
        range.memory = b.mem;
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        return vkFlushMappedMemoryRanges(device, 1, &range) == VK_SUCCESS;
    }

    bool invalidateResident(const ResidentBuf& b) const {
        if ((b.memoryFlags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT) != 0) return true;
        VkMappedMemoryRange range{VK_STRUCTURE_TYPE_MAPPED_MEMORY_RANGE};
        range.memory = b.mem;
        range.offset = 0;
        range.size = VK_WHOLE_SIZE;
        return vkInvalidateMappedMemoryRanges(device, 1, &range) == VK_SUCCESS;
    }

    // Tear down all persistent kernel state (failure recovery only — never runs
    // at process exit, see the deliberate leak below).
    void destroyKernel(Kernel& s) {
        if (s.fence) { vkDestroyFence(device, s.fence, nullptr); s.fence = VK_NULL_HANDLE; }
        if (s.cpool) { vkDestroyCommandPool(device, s.cpool, nullptr); s.cpool = VK_NULL_HANDLE; s.cmd = VK_NULL_HANDLE; }
        if (s.dpool) { vkDestroyDescriptorPool(device, s.dpool, nullptr); s.dpool = VK_NULL_HANDLE; s.dset = VK_NULL_HANDLE; }
        if (s.pipe) { vkDestroyPipeline(device, s.pipe, nullptr); s.pipe = VK_NULL_HANDLE; }
        if (s.pl) { vkDestroyPipelineLayout(device, s.pl, nullptr); s.pl = VK_NULL_HANDLE; }
        if (s.dsl) { vkDestroyDescriptorSetLayout(device, s.dsl, nullptr); s.dsl = VK_NULL_HANDLE; }
        if (s.shader) { vkDestroyShaderModule(device, s.shader, nullptr); s.shader = VK_NULL_HANDLE; }
        destroyBuf(s.in);
        destroyBuf(s.out);
        destroyBuf(s.dyeB);
        destroyBuf(s.cmfB);
        s.pipelineReady = false;
    }

    void destroyScan() {
        if (!device) return;
        vkDeviceWaitIdle(device);
        destroyKernel(scanFused);
        destroyKernel(scanLin);
    }
    void destroyHalation() {
        if (!device) return;
        vkDeviceWaitIdle(device);
        HalationKernel& k = halation;
        if (k.fence) { vkDestroyFence(device, k.fence, nullptr); k.fence = VK_NULL_HANDLE; }
        if (k.cpool) { vkDestroyCommandPool(device, k.cpool, nullptr); k.cpool = VK_NULL_HANDLE; k.cmd = VK_NULL_HANDLE; }
        if (k.dpool) { vkDestroyDescriptorPool(device, k.dpool, nullptr); k.dpool = VK_NULL_HANDLE; k.dsets.fill(VK_NULL_HANDLE); }
        if (k.pipe) { vkDestroyPipeline(device, k.pipe, nullptr); k.pipe = VK_NULL_HANDLE; }
        if (k.pl) { vkDestroyPipelineLayout(device, k.pl, nullptr); k.pl = VK_NULL_HANDLE; }
        if (k.dsl) { vkDestroyDescriptorSetLayout(device, k.dsl, nullptr); k.dsl = VK_NULL_HANDLE; }
        if (k.shader) { vkDestroyShaderModule(device, k.shader, nullptr); k.shader = VK_NULL_HANDLE; }
        destroyBuf(k.staging);
        destroyBuf(k.frame);
        for (ResidentBuf& b : k.work) destroyResidentBuf(b);
        k.pipelineReady = false;
    }

    int findMemType(uint32_t bits, VkMemoryPropertyFlags want) {
        VkPhysicalDeviceMemoryProperties mp;
        vkGetPhysicalDeviceMemoryProperties(phys, &mp);
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
            if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return (int)i;
        return -1;
    }

    // Create (or grow) a HOST_VISIBLE|COHERENT storage buffer and keep it mapped.
    // Same memory type as the per-call host the probe validated; only the lifetime
    // changed. Returns false on any Vulkan failure.
    bool ensureBuf(Buf& b, VkDeviceSize bytes) {
        if (b.cap >= bytes && b.buf) return true;
        destroyBuf(b);
        VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bci.size = bytes;
        // TRANSFER_SRC/DST because the halation pass stages through this buffer
        // with vkCmdCopyBuffer in both directions; a copy to or from a buffer
        // created without them is invalid use, which validation layers reject
        // and a driver may honour or not.
        bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                    VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(device, &bci, nullptr, &b.buf) != VK_SUCCESS) return false;
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(device, b.buf, &req);
        int mt = findMemType(req.memoryTypeBits,
                             VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (mt < 0) return false;
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = (uint32_t)mt;
        if (vkAllocateMemory(device, &mai, nullptr, &b.mem) != VK_SUCCESS) return false;
        if (vkBindBufferMemory(device, b.buf, b.mem, 0) != VK_SUCCESS) return false;
        if (vkMapMemory(device, b.mem, 0, VK_WHOLE_SIZE, 0, &b.mapped) != VK_SUCCESS) return false;
        b.cap = bytes;
        return true;
    }
};

// One lazily-initialised context for the process, plus the lock serializing all
// GPU entry points (one queue + one reusable command buffer).
//
// The context is DELIBERATELY LEAKED (new, never deleted): running Vulkan
// teardown from a static destructor at process exit crashes on ICDs whose own
// statics unload first (observed as a SIGSEGV under SwiftShader), and Android
// kills app processes without running static destructors anyway. destroyScan()
// exists for mid-process FAILURE recovery, where the device is alive and the
// calls are safe.
std::mutex& gpu_mutex() { static std::mutex m; return m; }
Ctx& ctx() {
    static Ctx* c = nullptr;
    if (!c) { c = new Ctx(); c->init(); }
    return *c;
}

struct ScanPush { uint32_t npix; float m[12]; };  // std430 push constant (matches both shaders)

// Build one kernel's persistent pipeline objects (everything except the
// grow-only image buffers) from its SPIR-V blob. Called once per kernel; on
// failure the caller tears down via destroyScan().
bool build_scan_pipeline(Ctx& c, Ctx::Kernel& s, const uint32_t* spv, size_t spvBytes) {
    VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smci.codeSize = spvBytes;
    smci.pCode = spv;
    if (vkCreateShaderModule(c.device, &smci, nullptr, &s.shader) != VK_SUCCESS) return false;

    VkDescriptorSetLayoutBinding b[4]{};
    for (uint32_t i = 0; i < 4; ++i) {
        b[i].binding = i;
        b[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        b[i].descriptorCount = 1;
        b[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo dlci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dlci.bindingCount = 4;
    dlci.pBindings = b;
    if (vkCreateDescriptorSetLayout(c.device, &dlci, nullptr, &s.dsl) != VK_SUCCESS) return false;

    VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(ScanPush)};
    VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plci.setLayoutCount = 1;
    plci.pSetLayouts = &s.dsl;
    plci.pushConstantRangeCount = 1;
    plci.pPushConstantRanges = &pcr;
    if (vkCreatePipelineLayout(c.device, &plci, nullptr, &s.pl) != VK_SUCCESS) return false;

    VkComputePipelineCreateInfo cpci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpci.stage.module = s.shader;
    cpci.stage.pName = "main";
    cpci.layout = s.pl;
    if (vkCreateComputePipelines(c.device, VK_NULL_HANDLE, 1, &cpci, nullptr, &s.pipe) != VK_SUCCESS) return false;

    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4};
    VkDescriptorPoolCreateInfo dpci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpci.maxSets = 1;
    dpci.poolSizeCount = 1;
    dpci.pPoolSizes = &ps;
    if (vkCreateDescriptorPool(c.device, &dpci, nullptr, &s.dpool) != VK_SUCCESS) return false;
    VkDescriptorSetAllocateInfo dsai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dsai.descriptorPool = s.dpool;
    dsai.descriptorSetCount = 1;
    dsai.pSetLayouts = &s.dsl;
    if (vkAllocateDescriptorSets(c.device, &dsai, &s.dset) != VK_SUCCESS) return false;

    VkCommandPoolCreateInfo cpci2{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpci2.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cpci2.queueFamilyIndex = c.queueFamily;
    if (vkCreateCommandPool(c.device, &cpci2, nullptr, &s.cpool) != VK_SUCCESS) return false;
    VkCommandBufferAllocateInfo cbai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cbai.commandPool = s.cpool;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(c.device, &cbai, &s.cmd) != VK_SUCCESS) return false;

    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkCreateFence(c.device, &fci, nullptr, &s.fence) != VK_SUCCESS) return false;

    s.pipelineReady = true;
    return true;
}

struct PointwiseFilmPush {
    uint32_t npix;
    uint32_t groupsX;
    uint32_t totalGroups;
    int32_t edge;
    int32_t curvePoints;
    float exposureMultiplier;
    float couplerShift;
    float couplerMatrix[9];
};

struct PointwisePrintPush {
    uint32_t npix;
    uint32_t groupsX;
    uint32_t totalGroups;
    int32_t curvePoints;
    float midgray;
    float exposureMultiplier;
    float preflash[3];
};

struct PointwiseScanPush {
    uint32_t npix;
    uint32_t groupsX;
    uint32_t totalGroups;
    float matrix[12];
};

static_assert(sizeof(PointwiseFilmPush) == 64, "filming push layout drift");
static_assert(sizeof(PointwisePrintPush) == 36, "printing push layout drift");
static_assert(sizeof(PointwiseScanPush) == 60, "scan push layout drift");

struct PreparedPointwiseRequest {
    size_t componentCount = 0;
    VkDeviceSize frameBytes = 0;
    VkDeviceSize staticBytes = 0;
    std::array<PointwiseTableSpan, kPointwiseTableCount> tables{};
    std::array<VkDeviceSize, kPointwiseTableCount> tableBytes{};
};

bool finite_span(const PointwiseTableSpan& span) {
    if (!span.data || span.count == 0) return false;
    for (size_t i = 0; i < span.count; ++i) {
        if (!std::isfinite(span.data[i])) return false;
    }
    return true;
}

bool finite_values(const float* values, size_t count) {
    if (!values) return false;
    for (size_t i = 0; i < count; ++i) {
        if (!std::isfinite(values[i])) return false;
    }
    return true;
}

bool strictly_increasing_planar3(const PointwiseTableSpan& axis, uint32_t points) {
    if (!axis.data || points < 2 || axis.count != static_cast<size_t>(points) * 3u) {
        return false;
    }
    for (uint32_t row = 1; row < points; ++row) {
        for (uint32_t channel = 0; channel < 3; ++channel) {
            if (!(axis.data[static_cast<size_t>(row - 1) * 3u + channel] <
                  axis.data[static_cast<size_t>(row) * 3u + channel])) {
                return false;
            }
        }
    }
    return true;
}

bool prepare_pointwise_request(const PointwiseChainRequest& request,
                               const PointwiseChainOutput* output,
                               PreparedPointwiseRequest& prepared,
                               PointwiseFallbackReason& reason) {
    constexpr uint32_t kMaxTcEdge = 1024;
    constexpr uint32_t kMaxCurvePoints = 65536;
    constexpr uint64_t kMaxStaticBytes = UINT64_C(64) * 1024u * 1024u;

    if (!output || !output->rgb || !request.input_rgb || request.pixel_count == 0 ||
        request.static_table_key == 0) {
        reason = PointwiseFallbackReason::invalid_request;
        return false;
    }
    const uint64_t components64 = static_cast<uint64_t>(request.pixel_count) * 3u;
    const uint64_t frameBytes64 = components64 * sizeof(float);
    if (components64 > std::numeric_limits<size_t>::max() ||
        frameBytes64 > std::numeric_limits<VkDeviceSize>::max()) {
        reason = PointwiseFallbackReason::request_too_large;
        return false;
    }
    prepared.componentCount = static_cast<size_t>(components64);
    prepared.frameBytes = static_cast<VkDeviceSize>(frameBytes64);
    if (request.input_component_count < prepared.componentCount ||
        output->component_capacity < prepared.componentCount) {
        reason = PointwiseFallbackReason::invalid_request;
        return false;
    }

    const uint32_t edge = request.film.tc_edge;
    const uint32_t filmPoints = request.film.curve_points;
    const uint32_t printPoints = request.print.curve_points;
    if (edge < 2 || edge > kMaxTcEdge || filmPoints < 2 ||
        filmPoints > kMaxCurvePoints || printPoints < 2 ||
        printPoints > kMaxCurvePoints) {
        reason = PointwiseFallbackReason::invalid_request;
        return false;
    }
    const uint64_t tcCount = static_cast<uint64_t>(edge) * edge * 3u;
    const uint64_t filmCurveCount = static_cast<uint64_t>(filmPoints) * 3u;
    const uint64_t printCurveCount = static_cast<uint64_t>(printPoints) * 3u;

    prepared.tables[kFilmTc] = request.film.tc_lut;
    prepared.tables[kFilmDevelopAxis] = request.film.develop_axis;
    prepared.tables[kFilmDevelopCurve] = request.film.develop_curve;
    prepared.tables[kFilmDirAxis] = request.film.dir_axis;
    prepared.tables[kFilmDirCurve] = request.film.dir_curve;
    prepared.tables[kPrintDye] = request.print.dye;
    prepared.tables[kPrintIlluminantSensitivity] = request.print.illuminant_sensitivity;
    prepared.tables[kPrintPaperAxis] = request.print.paper_axis;
    prepared.tables[kPrintPaperCurve] = request.print.paper_curve;
    prepared.tables[kScanDye] = request.scan.dye;
    prepared.tables[kScanIlluminantCmf] = request.scan.illuminant_cmf;
    const uint64_t expected[kPointwiseTableCount] = {
        tcCount,
        filmCurveCount, filmCurveCount, filmCurveCount, filmCurveCount,
        81u * 3u, 81u * 3u,
        printCurveCount, printCurveCount,
        81u * 3u, 81u * 3u,
    };

    uint64_t staticBytes = 0;
    for (size_t i = 0; i < kPointwiseTableCount; ++i) {
        if (expected[i] > std::numeric_limits<size_t>::max() ||
            prepared.tables[i].count != static_cast<size_t>(expected[i]) ||
            !finite_span(prepared.tables[i])) {
            reason = PointwiseFallbackReason::invalid_request;
            return false;
        }
        const uint64_t bytes = expected[i] * sizeof(float);
        if (bytes > std::numeric_limits<VkDeviceSize>::max() ||
            bytes > kMaxStaticBytes ||
            staticBytes > kMaxStaticBytes - bytes) {
            reason = PointwiseFallbackReason::request_too_large;
            return false;
        }
        prepared.tableBytes[i] = static_cast<VkDeviceSize>(bytes);
        staticBytes += bytes;
    }
    prepared.staticBytes = static_cast<VkDeviceSize>(staticBytes);

    if (!strictly_increasing_planar3(request.film.develop_axis, filmPoints) ||
        !strictly_increasing_planar3(request.film.dir_axis, filmPoints) ||
        !strictly_increasing_planar3(request.print.paper_axis, printPoints) ||
        !std::isfinite(request.film.exposure_multiplier) ||
        !std::isfinite(request.film.coupler_shift) ||
        !finite_values(request.film.coupler_matrix, 9) ||
        !std::isfinite(request.print.midgray) ||
        !std::isfinite(request.print.exposure_multiplier) ||
        !finite_values(request.print.preflash, 3) ||
        !finite_values(request.scan.xyz_to_rgb, 9)) {
        reason = PointwiseFallbackReason::invalid_request;
        return false;
    }
    reason = PointwiseFallbackReason::none;
    return true;
}

bool build_pointwise_pipelines(Ctx& c, PointwiseChainDiagnostics& diagnostics) {
    Ctx::PointwiseChain& s = c.pointwise;
    if (s.pipelinesReady) return true;
    const uint32_t* spirv[3] = {
        kPointwiseFilmingSpv, kPointwisePrintingSpv, kPointwiseScanSpectralSpv,
    };
    const size_t spirvBytes[3] = {
        sizeof(kPointwiseFilmingSpv), sizeof(kPointwisePrintingSpv),
        sizeof(kPointwiseScanSpectralSpv),
    };
    const uint32_t bindingCounts[3] = {7, 6, 4};
    const uint32_t pushBytes[3] = {
        sizeof(PointwiseFilmPush), sizeof(PointwisePrintPush), sizeof(PointwiseScanPush),
    };

    for (size_t stage = 0; stage < 3; ++stage) {
        if (pushBytes[stage] > c.properties.limits.maxPushConstantsSize) return false;
        VkShaderModuleCreateInfo shaderInfo{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        shaderInfo.codeSize = spirvBytes[stage];
        shaderInfo.pCode = spirv[stage];
        if (vkCreateShaderModule(c.device, &shaderInfo, nullptr, &s.shaders[stage]) != VK_SUCCESS) {
            return false;
        }

        std::array<VkDescriptorSetLayoutBinding, 7> bindings{};
        for (uint32_t binding = 0; binding < bindingCounts[stage]; ++binding) {
            bindings[binding].binding = binding;
            bindings[binding].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            bindings[binding].descriptorCount = 1;
            bindings[binding].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        }
        VkDescriptorSetLayoutCreateInfo descriptorInfo{
            VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        descriptorInfo.bindingCount = bindingCounts[stage];
        descriptorInfo.pBindings = bindings.data();
        if (vkCreateDescriptorSetLayout(c.device, &descriptorInfo, nullptr,
                                        &s.descriptorLayouts[stage]) != VK_SUCCESS) {
            return false;
        }

        VkPushConstantRange pushRange{VK_SHADER_STAGE_COMPUTE_BIT, 0, pushBytes[stage]};
        VkPipelineLayoutCreateInfo layoutInfo{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &s.descriptorLayouts[stage];
        layoutInfo.pushConstantRangeCount = 1;
        layoutInfo.pPushConstantRanges = &pushRange;
        if (vkCreatePipelineLayout(c.device, &layoutInfo, nullptr,
                                   &s.pipelineLayouts[stage]) != VK_SUCCESS) {
            return false;
        }

        VkComputePipelineCreateInfo pipelineInfo{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        pipelineInfo.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        pipelineInfo.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        pipelineInfo.stage.module = s.shaders[stage];
        pipelineInfo.stage.pName = "main";
        pipelineInfo.layout = s.pipelineLayouts[stage];
        if (vkCreateComputePipelines(c.device, VK_NULL_HANDLE, 1, &pipelineInfo, nullptr,
                                     &s.pipelines[stage]) != VK_SUCCESS) {
            return false;
        }
        ++diagnostics.pipeline_creates;
    }

    VkDescriptorPoolSize poolSize{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 17};
    VkDescriptorPoolCreateInfo poolInfo{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    poolInfo.maxSets = 3;
    poolInfo.poolSizeCount = 1;
    poolInfo.pPoolSizes = &poolSize;
    if (vkCreateDescriptorPool(c.device, &poolInfo, nullptr, &s.descriptorPool) != VK_SUCCESS) {
        return false;
    }
    VkDescriptorSetAllocateInfo allocateSets{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    allocateSets.descriptorPool = s.descriptorPool;
    allocateSets.descriptorSetCount = 3;
    allocateSets.pSetLayouts = s.descriptorLayouts.data();
    if (vkAllocateDescriptorSets(c.device, &allocateSets, s.descriptorSets.data()) != VK_SUCCESS) {
        return false;
    }

    VkCommandPoolCreateInfo commandPoolInfo{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    commandPoolInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    commandPoolInfo.queueFamilyIndex = c.queueFamily;
    if (vkCreateCommandPool(c.device, &commandPoolInfo, nullptr, &s.commandPool) != VK_SUCCESS) {
        return false;
    }
    VkCommandBufferAllocateInfo allocateCommand{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    allocateCommand.commandPool = s.commandPool;
    allocateCommand.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    allocateCommand.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(c.device, &allocateCommand, &s.commandBuffer) != VK_SUCCESS) {
        return false;
    }
    VkFenceCreateInfo fenceInfo{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkCreateFence(c.device, &fenceInfo, nullptr, &s.fence) != VK_SUCCESS) return false;
    s.pipelinesReady = true;
    return true;
}

void update_pointwise_descriptors(Ctx& c, const PreparedPointwiseRequest& prepared) {
    Ctx::PointwiseChain& s = c.pointwise;
    std::array<VkDescriptorBufferInfo, 17> infos{};
    std::array<VkWriteDescriptorSet, 17> writes{};
    size_t count = 0;
    auto add = [&](size_t stage, uint32_t binding, const ResidentBuf& buffer,
                   VkDeviceSize range) {
        infos[count] = VkDescriptorBufferInfo{buffer.buf, 0, range};
        writes[count] = VkWriteDescriptorSet{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        writes[count].dstSet = s.descriptorSets[stage];
        writes[count].dstBinding = binding;
        writes[count].descriptorCount = 1;
        writes[count].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        writes[count].pBufferInfo = &infos[count];
        ++count;
    };

    add(0, 0, s.ping[0], prepared.frameBytes);
    add(0, 1, s.ping[1], prepared.frameBytes);
    for (size_t i = kFilmTc; i <= kFilmDirCurve; ++i) {
        add(0, static_cast<uint32_t>(i - kFilmTc + 2), s.tables[i], prepared.tableBytes[i]);
    }
    add(1, 0, s.ping[1], prepared.frameBytes);
    add(1, 1, s.ping[0], prepared.frameBytes);
    for (size_t i = kPrintDye; i <= kPrintPaperCurve; ++i) {
        add(1, static_cast<uint32_t>(i - kPrintDye + 2), s.tables[i], prepared.tableBytes[i]);
    }
    add(2, 0, s.ping[0], prepared.frameBytes);
    add(2, 1, s.ping[1], prepared.frameBytes);
    add(2, 2, s.tables[kScanDye], prepared.tableBytes[kScanDye]);
    add(2, 3, s.tables[kScanIlluminantCmf], prepared.tableBytes[kScanIlluminantCmf]);
    vkUpdateDescriptorSets(c.device, static_cast<uint32_t>(count), writes.data(), 0, nullptr);
}

}  // namespace

bool render_pointwise_chain(const PointwiseChainRequest& request,
                            PointwiseChainOutput* output,
                            PointwiseChainDiagnostics* diagnostics) noexcept {
    PointwiseChainDiagnostics localDiagnostics{};
    PointwiseChainDiagnostics& d = diagnostics ? *diagnostics : localDiagnostics;
    d = {};

    // This public seam is noexcept and fail-closed. First-use context creation
    // performs C++ allocations (including temporary Vulkan enumeration vectors),
    // so bad_alloc or another runtime exception must become an ordinary fallback
    // rather than std::terminate. Caller output is not touched until every GPU
    // operation and the finite readback validation below have completed.
    try {

    PreparedPointwiseRequest prepared{};
    PointwiseFallbackReason validationReason = PointwiseFallbackReason::none;
    if (!prepare_pointwise_request(request, output, prepared, validationReason)) {
        d.fallback_reason = validationReason;
        return false;
    }

    std::lock_guard<std::mutex> lock(gpu_mutex());
    Ctx& c = ctx();
    if (!c.ok) {
        d.fallback_reason = PointwiseFallbackReason::unavailable;
        return false;
    }
    const VkPhysicalDeviceLimits& limits = c.properties.limits;
    if (limits.maxComputeWorkGroupInvocations < 64 || limits.maxComputeWorkGroupSize[0] < 64) {
        d.fallback_reason = PointwiseFallbackReason::unavailable;
        return false;
    }
    if (limits.maxMemoryAllocationCount < 15) {
        d.fallback_reason = PointwiseFallbackReason::unavailable;
        return false;
    }
    const PointwiseDispatchGrid grid = plan_pointwise_dispatch(
        request.pixel_count, limits.maxComputeWorkGroupCount[0],
        limits.maxComputeWorkGroupCount[1]);
    if (!grid.valid || prepared.frameBytes > limits.maxStorageBufferRange) {
        d.fallback_reason = PointwiseFallbackReason::request_too_large;
        return false;
    }
    for (VkDeviceSize bytes : prepared.tableBytes) {
        if (bytes > limits.maxStorageBufferRange) {
            d.fallback_reason = PointwiseFallbackReason::request_too_large;
            return false;
        }
    }

    Ctx::PointwiseChain& s = c.pointwise;
    bool staticCacheHit = s.cachedTableKey == request.static_table_key &&
                          s.cachedTableBytes == prepared.tableBytes;
    for (size_t i = 0; i < kPointwiseTableCount && staticCacheHit; ++i) {
        staticCacheHit = s.tables[i].buf && s.tables[i].cap >= prepared.tableBytes[i];
    }

    // Conservative heap preflight when VK_EXT_memory_budget is not enabled.
    // Driver allocation results remain authoritative, but impossible whole-frame
    // requests fail before tearing down a useful warm cache.
    const uint64_t deviceBytes = static_cast<uint64_t>(prepared.frameBytes) * 2u +
                                 static_cast<uint64_t>(prepared.staticBytes);
    const uint64_t hostBytes = static_cast<uint64_t>(prepared.frameBytes) +
                               static_cast<uint64_t>(prepared.staticBytes);
    if (deviceBytes > c.largestHeapFor(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) ||
        hostBytes > c.largestHeapFor(VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)) {
        d.fallback_reason = PointwiseFallbackReason::allocation_failed;
        return false;
    }

    auto fail = [&](PointwiseFallbackReason reason) {
        d.engaged = false;
        d.fallback_reason = reason;
        c.destroyPointwise();
        return false;
    };

    if (!build_pointwise_pipelines(c, d)) {
        return fail(PointwiseFallbackReason::pipeline_failed);
    }

    constexpr VkBufferUsageFlags kFrameStagingUsage =
        VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    constexpr VkBufferUsageFlags kPingUsage =
        VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
        VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    if (!c.ensureResidentBuf(s.frameStaging, prepared.frameBytes, kFrameStagingUsage,
                             VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                             VK_MEMORY_PROPERTY_HOST_COHERENT_BIT |
                                 VK_MEMORY_PROPERTY_HOST_CACHED_BIT,
                             true, d) ||
        !c.ensureResidentBuf(s.ping[0], prepared.frameBytes, kPingUsage,
                             VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, false, d) ||
        !c.ensureResidentBuf(s.ping[1], prepared.frameBytes, kPingUsage,
                             VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, false, d)) {
        return fail(PointwiseFallbackReason::allocation_failed);
    }

    std::array<VkDeviceSize, kPointwiseTableCount> staticOffsets{};
    if (!staticCacheHit) {
        constexpr VkBufferUsageFlags kStaticUsage =
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
        if (!c.ensureResidentBuf(s.staticStaging, prepared.staticBytes,
                                 VK_BUFFER_USAGE_TRANSFER_SRC_BIT,
                                 VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT,
                                 VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                                 true, d)) {
            return fail(PointwiseFallbackReason::allocation_failed);
        }
        VkDeviceSize offset = 0;
        for (size_t i = 0; i < kPointwiseTableCount; ++i) {
            if (!c.ensureResidentBuf(s.tables[i], prepared.tableBytes[i], kStaticUsage,
                                     VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, 0, false, d)) {
                return fail(PointwiseFallbackReason::allocation_failed);
            }
            staticOffsets[i] = offset;
            std::memcpy(static_cast<unsigned char*>(s.staticStaging.mapped) + offset,
                        prepared.tables[i].data,
                        static_cast<size_t>(prepared.tableBytes[i]));
            offset += prepared.tableBytes[i];
        }
        if (!c.flushResident(s.staticStaging)) {
            return fail(PointwiseFallbackReason::upload_failed);
        }
        d.static_upload_bytes = prepared.staticBytes;
    }

    std::memcpy(s.frameStaging.mapped, request.input_rgb,
                static_cast<size_t>(prepared.frameBytes));
    if (!c.flushResident(s.frameStaging)) {
        return fail(PointwiseFallbackReason::upload_failed);
    }
    update_pointwise_descriptors(c, prepared);

    PointwiseFilmPush filmPush{};
    filmPush.npix = request.pixel_count;
    filmPush.groupsX = grid.groups_x;
    filmPush.totalGroups = grid.total_groups;
    filmPush.edge = static_cast<int32_t>(request.film.tc_edge);
    filmPush.curvePoints = static_cast<int32_t>(request.film.curve_points);
    filmPush.exposureMultiplier = request.film.exposure_multiplier;
    filmPush.couplerShift = request.film.coupler_shift;
    std::memcpy(filmPush.couplerMatrix, request.film.coupler_matrix,
                sizeof(filmPush.couplerMatrix));

    PointwisePrintPush printPush{};
    printPush.npix = request.pixel_count;
    printPush.groupsX = grid.groups_x;
    printPush.totalGroups = grid.total_groups;
    printPush.curvePoints = static_cast<int32_t>(request.print.curve_points);
    printPush.midgray = request.print.midgray;
    printPush.exposureMultiplier = request.print.exposure_multiplier;
    std::memcpy(printPush.preflash, request.print.preflash, sizeof(printPush.preflash));

    PointwiseScanPush scanPush{};
    scanPush.npix = request.pixel_count;
    scanPush.groupsX = grid.groups_x;
    scanPush.totalGroups = grid.total_groups;
    scanPush.matrix[0] = request.scan.xyz_to_rgb[0];
    scanPush.matrix[1] = request.scan.xyz_to_rgb[1];
    scanPush.matrix[2] = request.scan.xyz_to_rgb[2];
    scanPush.matrix[4] = request.scan.xyz_to_rgb[3];
    scanPush.matrix[5] = request.scan.xyz_to_rgb[4];
    scanPush.matrix[6] = request.scan.xyz_to_rgb[5];
    scanPush.matrix[8] = request.scan.xyz_to_rgb[6];
    scanPush.matrix[9] = request.scan.xyz_to_rgb[7];
    scanPush.matrix[10] = request.scan.xyz_to_rgb[8];

    if (vkResetCommandBuffer(s.commandBuffer, 0) != VK_SUCCESS) {
        return fail(PointwiseFallbackReason::dispatch_failed);
    }
    VkCommandBufferBeginInfo beginInfo{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    if (vkBeginCommandBuffer(s.commandBuffer, &beginInfo) != VK_SUCCESS) {
        return fail(PointwiseFallbackReason::dispatch_failed);
    }

    VkMemoryBarrier hostWriteBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    hostWriteBarrier.srcAccessMask = VK_ACCESS_HOST_WRITE_BIT;
    hostWriteBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(s.commandBuffer, VK_PIPELINE_STAGE_HOST_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &hostWriteBarrier,
                         0, nullptr, 0, nullptr);

    VkBufferCopy frameUpload{0, 0, prepared.frameBytes};
    vkCmdCopyBuffer(s.commandBuffer, s.frameStaging.buf, s.ping[0].buf, 1, &frameUpload);
    if (!staticCacheHit) {
        for (size_t i = 0; i < kPointwiseTableCount; ++i) {
            VkBufferCopy tableUpload{staticOffsets[i], 0, prepared.tableBytes[i]};
            vkCmdCopyBuffer(s.commandBuffer, s.staticStaging.buf, s.tables[i].buf, 1,
                            &tableUpload);
        }
    }

    VkMemoryBarrier uploadBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    uploadBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    uploadBarrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    vkCmdPipelineBarrier(s.commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &uploadBarrier,
                         0, nullptr, 0, nullptr);

    auto dispatch = [&](size_t stage, const void* push, uint32_t pushBytes) {
        vkCmdBindPipeline(s.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                          s.pipelines[stage]);
        vkCmdBindDescriptorSets(s.commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                                s.pipelineLayouts[stage], 0, 1,
                                &s.descriptorSets[stage], 0, nullptr);
        vkCmdPushConstants(s.commandBuffer, s.pipelineLayouts[stage],
                           VK_SHADER_STAGE_COMPUTE_BIT, 0, pushBytes, push);
        vkCmdDispatch(s.commandBuffer, grid.groups_x, grid.groups_y, 1);
    };
    auto computeBarrier = [&]() {
        VkMemoryBarrier barrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        barrier.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
        vkCmdPipelineBarrier(s.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                             VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, 1, &barrier,
                             0, nullptr, 0, nullptr);
    };

    dispatch(0, &filmPush, sizeof(filmPush));
    computeBarrier();
    dispatch(1, &printPush, sizeof(printPush));
    computeBarrier();
    dispatch(2, &scanPush, sizeof(scanPush));

    VkMemoryBarrier readbackBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    readbackBarrier.srcAccessMask = VK_ACCESS_SHADER_WRITE_BIT;
    readbackBarrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
    vkCmdPipelineBarrier(s.commandBuffer, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                         VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &readbackBarrier,
                         0, nullptr, 0, nullptr);
    VkBufferCopy frameReadback{0, 0, prepared.frameBytes};
    vkCmdCopyBuffer(s.commandBuffer, s.ping[1].buf, s.frameStaging.buf, 1,
                    &frameReadback);
    VkMemoryBarrier hostReadBarrier{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
    hostReadBarrier.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    hostReadBarrier.dstAccessMask = VK_ACCESS_HOST_READ_BIT;
    vkCmdPipelineBarrier(s.commandBuffer, VK_PIPELINE_STAGE_TRANSFER_BIT,
                         VK_PIPELINE_STAGE_HOST_BIT, 0, 1, &hostReadBarrier,
                         0, nullptr, 0, nullptr);
    if (vkEndCommandBuffer(s.commandBuffer) != VK_SUCCESS) {
        return fail(PointwiseFallbackReason::dispatch_failed);
    }

    VkSubmitInfo submit{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    submit.commandBufferCount = 1;
    submit.pCommandBuffers = &s.commandBuffer;
    if (vkQueueSubmit(c.queue, 1, &submit, s.fence) != VK_SUCCESS ||
        vkWaitForFences(c.device, 1, &s.fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS ||
        vkResetFences(c.device, 1, &s.fence) != VK_SUCCESS) {
        return fail(PointwiseFallbackReason::dispatch_failed);
    }
    if (!c.invalidateResident(s.frameStaging)) {
        return fail(PointwiseFallbackReason::readback_failed);
    }

    const float* mappedOutput = static_cast<const float*>(s.frameStaging.mapped);
    for (size_t i = 0; i < prepared.componentCount; ++i) {
        if (!std::isfinite(mappedOutput[i])) {
            return fail(PointwiseFallbackReason::readback_failed);
        }
    }

    std::memcpy(output->rgb, mappedOutput,
                static_cast<size_t>(prepared.frameBytes));
    // These counters describe completed frame work, not merely recorded or
    // submitted commands. Every false return therefore reports zero here.
    d.dispatches = 3;
    d.input_uploads = 1;
    d.final_readbacks = 1;
    d.interstage_host_bytes = 0;
    d.engaged = true;
    d.fallback_reason = PointwiseFallbackReason::none;
    if (!staticCacheHit) {
        s.cachedTableKey = request.static_table_key;
        s.cachedTableBytes = prepared.tableBytes;
    }
    return true;
    } catch (const std::bad_alloc&) {
        d = {};
        d.fallback_reason = PointwiseFallbackReason::allocation_failed;
        return false;
    } catch (...) {
        d = {};
        d.fallback_reason = PointwiseFallbackReason::unavailable;
        return false;
    }
}

bool available() {
    std::lock_guard<std::mutex> lk(gpu_mutex());
    return ctx().ok;
}

bool cctf_encode_srgb(float* data, size_t n) {
    std::lock_guard<std::mutex> lk(gpu_mutex());
    Ctx& c = ctx();
    if (!c.ok || data == nullptr || n == 0) return false;
    const VkDeviceSize bytes = static_cast<VkDeviceSize>(n) * sizeof(float);
    bool ok = false;

    VkBuffer buf = VK_NULL_HANDLE;
    VkDeviceMemory mem = VK_NULL_HANDLE;
    VkShaderModule shader = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
    VkPipelineLayout pl = VK_NULL_HANDLE;
    VkPipeline pipe = VK_NULL_HANDLE;
    VkDescriptorPool dpool = VK_NULL_HANDLE;
    VkCommandPool cpool = VK_NULL_HANDLE;

    do {
        VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bci.size = bytes;
        bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(c.device, &bci, nullptr, &buf) != VK_SUCCESS) break;

        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(c.device, buf, &req);
        int mt = c.findMemType(req.memoryTypeBits,
                               VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (mt < 0) break;
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = (uint32_t)mt;
        if (vkAllocateMemory(c.device, &mai, nullptr, &mem) != VK_SUCCESS) break;
        vkBindBufferMemory(c.device, buf, mem, 0);

        // Upload.
        void* mapped = nullptr;
        if (vkMapMemory(c.device, mem, 0, bytes, 0, &mapped) != VK_SUCCESS) break;
        std::memcpy(mapped, data, bytes);
        vkUnmapMemory(c.device, mem);

        VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        smci.codeSize = sizeof(kCctfEncodeSpv);
        smci.pCode = kCctfEncodeSpv;
        if (vkCreateShaderModule(c.device, &smci, nullptr, &shader) != VK_SUCCESS) break;

        VkDescriptorSetLayoutBinding b{};
        b.binding = 0;
        b.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        b.descriptorCount = 1;
        b.stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
        VkDescriptorSetLayoutCreateInfo dlci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
        dlci.bindingCount = 1;
        dlci.pBindings = &b;
        if (vkCreateDescriptorSetLayout(c.device, &dlci, nullptr, &dsl) != VK_SUCCESS) break;

        VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(uint32_t)};
        VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
        plci.setLayoutCount = 1;
        plci.pSetLayouts = &dsl;
        plci.pushConstantRangeCount = 1;
        plci.pPushConstantRanges = &pcr;
        if (vkCreatePipelineLayout(c.device, &plci, nullptr, &pl) != VK_SUCCESS) break;

        VkComputePipelineCreateInfo cpci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
        cpci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        cpci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
        cpci.stage.module = shader;
        cpci.stage.pName = "main";
        cpci.layout = pl;
        if (vkCreateComputePipelines(c.device, VK_NULL_HANDLE, 1, &cpci, nullptr, &pipe) != VK_SUCCESS) break;

        VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 1};
        VkDescriptorPoolCreateInfo dpci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
        dpci.maxSets = 1;
        dpci.poolSizeCount = 1;
        dpci.pPoolSizes = &ps;
        if (vkCreateDescriptorPool(c.device, &dpci, nullptr, &dpool) != VK_SUCCESS) break;
        VkDescriptorSetAllocateInfo dsai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
        dsai.descriptorPool = dpool;
        dsai.descriptorSetCount = 1;
        dsai.pSetLayouts = &dsl;
        VkDescriptorSet dset = VK_NULL_HANDLE;
        if (vkAllocateDescriptorSets(c.device, &dsai, &dset) != VK_SUCCESS) break;
        VkDescriptorBufferInfo dbi{buf, 0, bytes};
        VkWriteDescriptorSet wds{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
        wds.dstSet = dset;
        wds.descriptorCount = 1;
        wds.descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        wds.pBufferInfo = &dbi;
        vkUpdateDescriptorSets(c.device, 1, &wds, 0, nullptr);

        VkCommandPoolCreateInfo cpci2{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
        cpci2.queueFamilyIndex = c.queueFamily;
        if (vkCreateCommandPool(c.device, &cpci2, nullptr, &cpool) != VK_SUCCESS) break;
        VkCommandBufferAllocateInfo cbai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cbai.commandPool = cpool;
        cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cbai.commandBufferCount = 1;
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        if (vkAllocateCommandBuffers(c.device, &cbai, &cmd) != VK_SUCCESS) break;

        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(cmd, &bi);
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipe);
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pl, 0, 1, &dset, 0, nullptr);
        uint32_t count = static_cast<uint32_t>(n);
        vkCmdPushConstants(cmd, pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(uint32_t), &count);
        vkCmdDispatch(cmd, (count + 63) / 64, 1, 1);
        vkEndCommandBuffer(cmd);

        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        if (vkQueueSubmit(c.queue, 1, &si, VK_NULL_HANDLE) != VK_SUCCESS) break;
        vkQueueWaitIdle(c.queue);

        // Read back.
        if (vkMapMemory(c.device, mem, 0, bytes, 0, &mapped) != VK_SUCCESS) break;
        std::memcpy(data, mapped, bytes);
        vkUnmapMemory(c.device, mem);
        ok = true;
    } while (false);

    if (cpool) vkDestroyCommandPool(c.device, cpool, nullptr);
    if (dpool) vkDestroyDescriptorPool(c.device, dpool, nullptr);
    if (pipe) vkDestroyPipeline(c.device, pipe, nullptr);
    if (pl) vkDestroyPipelineLayout(c.device, pl, nullptr);
    if (dsl) vkDestroyDescriptorSetLayout(c.device, dsl, nullptr);
    if (shader) vkDestroyShaderModule(c.device, shader, nullptr);
    if (mem) vkFreeMemory(c.device, mem, nullptr);
    if (buf) vkDestroyBuffer(c.device, buf, nullptr);
    return ok;
}

// Shared dispatch body for the two scan kernels (mutex held by the callers).
static bool dispatch_scan(Ctx& c, Ctx::Kernel& s, const uint32_t* spv, size_t spvBytes,
                          const float* cmy, float* rgb, uint32_t npix,
                          const float* dye, const float* icmf, const float* xyz2rgb) {
    if (!c.ok || !cmy || !rgb || npix == 0 || !dye || !icmf || !xyz2rgb) return false;
    const int NB = 81;
    const VkDeviceSize tblBytes = static_cast<VkDeviceSize>(NB) * 3u * sizeof(float);
    // SLICING (GPU export, #154): a single dispatch is capped at the
    // spec-guaranteed maxComputeWorkGroupCount floor (65535 groups × 64 =
    // 4,193,280 px). Full-res exports (a 12.5 MP frame) exceed that, so the
    // image is processed in slices of at most MAX_SLICE pixels. The persistent
    // in/out buffers are sized to the SLICE, not the whole image, bounding GPU
    // memory. A preview (npix < MAX_SLICE) is exactly one slice — identical to
    // the pre-slicing single dispatch, so the PR #145 numbers still hold.
    const uint32_t MAX_SLICE = 65535u * 64u;  // 4,193,280
    const uint32_t sliceCap = npix < MAX_SLICE ? npix : MAX_SLICE;
    const VkDeviceSize sliceBytes = static_cast<VkDeviceSize>(sliceCap) * 3u * sizeof(float);
    bool ok = false;

    do {
        if (!s.pipelineReady && !build_scan_pipeline(c, s, spv, spvBytes)) break;

        // Grow-only slice buffers; fixed-size tables. Any (re)creation requires a
        // descriptor rewrite; buffers only change while the queue is idle (each
        // slice's fence is waited before the next), so rewriting descriptors
        // here is race-free under the mutex.
        const bool hadIn = s.in.cap >= sliceBytes && s.in.buf;
        const bool hadOut = s.out.cap >= sliceBytes && s.out.buf;
        if (!c.ensureBuf(s.in, sliceBytes)) break;
        if (!c.ensureBuf(s.out, sliceBytes)) break;
        if (!c.ensureBuf(s.dyeB, tblBytes)) break;
        if (!c.ensureBuf(s.cmfB, tblBytes)) break;
        if (!hadIn || !hadOut) {
            VkBuffer bufs[4] = {s.in.buf, s.out.buf, s.dyeB.buf, s.cmfB.buf};
            VkDeviceSize caps[4] = {s.in.cap, s.out.cap, s.dyeB.cap, s.cmfB.cap};
            VkDescriptorBufferInfo dbi[4];
            VkWriteDescriptorSet wds[4];
            for (uint32_t i = 0; i < 4; ++i) {
                dbi[i] = VkDescriptorBufferInfo{bufs[i], 0, caps[i]};
                wds[i] = VkWriteDescriptorSet{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
                wds[i].dstSet = s.dset;
                wds[i].dstBinding = i;
                wds[i].descriptorCount = 1;
                wds[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                wds[i].pBufferInfo = &dbi[i];
            }
            vkUpdateDescriptorSets(c.device, 4, wds, 0, nullptr);
        }

        // Tables are tiny (~1 KB) and constant across slices; upload once.
        std::memcpy(s.dyeB.mapped, dye, tblBytes);
        std::memcpy(s.cmfB.mapped, icmf, tblBytes);

        ScanPush push{};
        // Pack the row-major 3x3 XYZ->RGB into the shader's 3x4 layout (m[0..2],[4..6],[8..10]).
        push.m[0] = xyz2rgb[0]; push.m[1] = xyz2rgb[1]; push.m[2]  = xyz2rgb[2]; push.m[3]  = 0.0f;
        push.m[4] = xyz2rgb[3]; push.m[5] = xyz2rgb[4]; push.m[6]  = xyz2rgb[5]; push.m[7]  = 0.0f;
        push.m[8] = xyz2rgb[6]; push.m[9] = xyz2rgb[7]; push.m[10] = xyz2rgb[8]; push.m[11] = 0.0f;

        bool slice_ok = true;
        for (uint32_t base = 0; base < npix && slice_ok; base += MAX_SLICE) {
            const uint32_t n = (npix - base) < MAX_SLICE ? (npix - base) : MAX_SLICE;
            const size_t ncomp = static_cast<size_t>(n) * 3u;

            // Upload this slice. The input copy is the NaN guard: non-finite
            // densities map to 1e4f (zero transmittance -> black) so the shader
            // never sees a NaN/Inf (clamp(NaN) is implementation-defined in
            // GLSL). Finite inputs are copied verbatim (bit-exact).
            float* dst = static_cast<float*>(s.in.mapped);
            const float* src = cmy + static_cast<size_t>(base) * 3u;
            for (size_t i = 0; i < ncomp; ++i) {
                const float v = src[i];
                dst[i] = std::isfinite(v) ? v : 1e4f;
            }

            // Record + submit. The command buffer is reused; each slice's fence
            // is waited before the buffer is reset again.
            if (vkResetCommandBuffer(s.cmd, 0) != VK_SUCCESS) { slice_ok = false; break; }
            VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
            bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            if (vkBeginCommandBuffer(s.cmd, &bi) != VK_SUCCESS) { slice_ok = false; break; }
            vkCmdBindPipeline(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s.pipe);
            vkCmdBindDescriptorSets(s.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, s.pl, 0, 1, &s.dset, 0, nullptr);
            push.npix = n;
            vkCmdPushConstants(s.cmd, s.pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(ScanPush), &push);
            vkCmdDispatch(s.cmd, (n + 63u) / 64u, 1, 1);
            if (vkEndCommandBuffer(s.cmd) != VK_SUCCESS) { slice_ok = false; break; }

            VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
            si.commandBufferCount = 1;
            si.pCommandBuffers = &s.cmd;
            if (vkQueueSubmit(c.queue, 1, &si, s.fence) != VK_SUCCESS) { slice_ok = false; break; }
            if (vkWaitForFences(c.device, 1, &s.fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS) { slice_ok = false; break; }
            if (vkResetFences(c.device, 1, &s.fence) != VK_SUCCESS) { slice_ok = false; break; }

            // Read this slice back (persistently mapped, HOST_COHERENT).
            std::memcpy(rgb + static_cast<size_t>(base) * 3u, s.out.mapped,
                        ncomp * sizeof(float));
        }
        ok = slice_ok;
    } while (false);

    // Failure recovery: tear the persistent state down so the next call rebuilds
    // from scratch (and the caller falls back to the CPU for this frame).
    if (!ok) c.destroyScan();
    return ok;
}

bool scan_spectral(const float* cmy, float* rgb, uint32_t npix,
                   const float* dye, const float* icmf, const float* xyz2rgb) {
    std::lock_guard<std::mutex> lk(gpu_mutex());
    Ctx& c = ctx();
    return dispatch_scan(c, c.scanFused, kScanSpectralSpv, sizeof(kScanSpectralSpv),
                         cmy, rgb, npix, dye, icmf, xyz2rgb);
}

bool scan_spectral_linear(const float* cmy, float* rgb, uint32_t npix,
                          const float* dye, const float* icmf, const float* xyz2rgb) {
    std::lock_guard<std::mutex> lk(gpu_mutex());
    Ctx& c = ctx();
    return dispatch_scan(c, c.scanLin, kScanSpectralLinSpv, sizeof(kScanSpectralLinSpv),
                         cmy, rgb, npix, dye, icmf, xyz2rgb);
}

// ---------------------------------------------------------------------------
// In-emulsion scatter + back-reflection halation (issue #206).
//
// Shader: gpu/halation_scatter.comp, adapted from spektrafilm OFX (see the file
// header and docs/research/spektrafilm-ofx-port.md). The dispatch sequence
// follows upstream's SpektraVulkanRenderer.cpp halation pass; the single
// weighted sum buffer, the whole-frame residency, the f64 staging and the
// parameter plumbing are Android additions.
//
// Model (model/diffusion.cpp::apply_halation_um, per channel c):
//   core   = G(sigma_core[c]) * raw
//   tail   = sum_k amp_k * G(ratio_k * lambda_tail[c]) * raw    (k = 0..2)
//   raw'   = (1 - s) * raw + s * ((1 - w[c]) * core + w[c] * tail)
//   blur   = sum_k decay_k * G(sigma_h[c] * sqrt(k)) * raw'      (k = 1..N)
//   out    = raw' + a[c] * blur, / (1 + a[c]) when renormalising
//
// Residency: the whole frame lives on the device for the pass (four packed
// f32 planes), so every blur, IIR sweeps included, sees the complete image
// exactly as the CPU pass does; the earlier row slicing with a halo left an
// IIR transient at slice joins and could not fit the DIR tail's 10-sigma halo
// at export scale. Upload and readback go through a fixed staging band of
// whole rows, which never touches the numbers (a test proves this).
// ---------------------------------------------------------------------------
namespace {

// CoreParams in halation_scatter.comp: upstream's 104-byte push block, of
// which this pass consumes width/height/operation/sigmaMode/component.
struct HalationPush {
    uint32_t width = 0, height = 0;
    float filmExposureEv = 0.f, filmGamma = 0.f;
    uint32_t exposureCount = 0;
    int32_t inputColorSpace = 0, rgbToRawMethod = 0;
    uint32_t colorSpaceCount = 0, transferLutSize = 0;
    float colorDecodeMin = 0.f, colorDecodeMax = 0.f;
    uint32_t hanatosWidth = 0, hanatosHeight = 0;
    uint32_t operation = 0, sigmaMode = 0, component = 0;
    int32_t filmPushPullMode = 0;
    float filmPushPullStops = 0.f;
    uint32_t fullWidth = 0, fullHeight = 0, tileOriginX = 0, tileOriginY = 0;
    uint32_t activeOriginX = 0, activeOriginY = 0, activeWidth = 0, activeHeight = 0;
};
static_assert(sizeof(HalationPush) == 104, "push block must match halation_scatter.comp");

constexpr uint32_t kHalOpClear = 0u;
constexpr uint32_t kHalOpScatterResolve = 4u;
constexpr uint32_t kHalOpBounceResolveRaw = 10u;
constexpr uint32_t kHalOpLineInPlace = 12u;      // IIR channels, one thread per column, in place on buf1
constexpr uint32_t kHalOpLineYAccumulate = 13u;  // IIR channels, buf1 += w * blur(in0), buf3 scratch
constexpr uint32_t kHalOpFirX = 14u;             // FIR channels, per pixel
constexpr uint32_t kHalOpFirYAccumulate = 16u;
constexpr uint32_t kHalOpTranspose = 17u;        // tiled, component = channel mask
constexpr uint32_t kHalLineThreads = 256u;       // kThreadsPerGroup in the shader
constexpr uint32_t kHalTransposeTile = 32u;
constexpr uint32_t kHalSigmaCore = 0u;
constexpr uint32_t kHalSigmaTail = 1u;
constexpr uint32_t kHalSigmaBounce = 2u;
// FrameFloats indices (binding 27), as the shader reads them.
constexpr uint32_t kHalFramePixelSizeUm = 29u;
constexpr uint32_t kHalFrameScatterAmount = 30u;
constexpr uint32_t kHalFrameScatterScale = 31u;
constexpr uint32_t kHalFrameHalationAmount = 32u;
constexpr uint32_t kHalFrameHalationScale = 33u;
constexpr uint32_t kHalFrameStrength = 34u;      // +0..2
constexpr uint32_t kHalFrameFirstSigma = 37u;    // +0..2
constexpr uint32_t kHalFrameCoreUm = 80u;        // +0..2
constexpr uint32_t kHalFrameTailUm = 83u;        // +0..2
constexpr uint32_t kHalFrameTailWeight = 86u;    // +0..2
constexpr uint32_t kHalFrameRenormalize = 91u;
// BlurTable (see the shader): per blur slot and channel, the sigma, the
// FIR/IIR class, the accumulation weight and the four Young-van Vliet
// coefficients as double-float pairs. Slot 0 is the scatter core, 1..3 the
// tail surrogates, 4.. the bounces.
constexpr uint32_t kHalFrameBlurBase = 96u;
constexpr uint32_t kHalBlurStride = 12u;
constexpr uint32_t kHalBlurFieldSigma = 0u;
constexpr uint32_t kHalBlurFieldIsIir = 1u;
constexpr uint32_t kHalBlurFieldWeight = 2u;
constexpr uint32_t kHalBlurFieldB = 3u;          // hi, lo
constexpr uint32_t kHalBlurFieldP1 = 5u;
constexpr uint32_t kHalBlurFieldP2 = 7u;
constexpr uint32_t kHalBlurFieldP3 = 9u;
constexpr uint32_t kHalBlurFieldFirRadius = 11u;
constexpr int kHalMaxFirRadius = 256;            // the shader's tap-loop bound
constexpr uint32_t kHalMaxBounces = 8u;          // the UI allows 1..5
constexpr uint32_t kHalBlurSlots = 4u + kHalMaxBounces;
constexpr uint32_t kHalFrameFloatCount = kHalFrameBlurBase + kHalBlurSlots * 3u * kHalBlurStride;
constexpr double kHalSmallSigmaMax = 3.0;        // kSmallSigmaMaxD: IIR at and above
// Largest blur sigma (px) the pass accepts. The gate measures the double-float
// recursion against the f64 CPU filter up to this value on a flat field, a
// step and the real blends (the DIR tail is 63 px at 12.5 MP, and the widest
// UI-reachable halation bounce is 157 px).
constexpr double kHalMaxSigma = 256.0;
// fast_exponential_filter's three-Gaussian surrogate of the isotropic
// exponential kernel (kernels/exponential_filter.cpp).
constexpr double kHalTailAmplitude[3] = {0.1633, 0.6496, 0.1870};
constexpr double kHalTailRatio[3] = {0.5360, 1.5236, 2.7684};
constexpr uint32_t kHalSetCount = 11u;
constexpr VkDeviceSize kHalPixelBytes = 12;      // three f32, packed
constexpr uint32_t kHalWorkBuffers = 4u;         // src, sum, x, xT
// Whole-frame residency ceiling for the four work buffers (48 B/px: ~22 MP).
// Above it the pass refuses and the CPU runs; bringing back row bands with the
// IIR state carried through host-side storage is the upgrade path if a larger
// export ever needs the GPU pass.
constexpr VkDeviceSize kHalWorkBudgetBytes = 1024ull << 20;
constexpr VkDeviceSize kHalStagingBytes = 64ull << 20;  // upload/readback band
constexpr uint32_t kHalGroupX = 32u, kHalGroupY = 8u;
constexpr uint32_t kHalSrc = 0u, kHalSum = 1u, kHalX = 2u, kHalXT = 3u;

bool build_halation_pipeline(Ctx& c, Ctx::HalationKernel& k) {
    VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    smci.codeSize = sizeof(kHalationScatterSpv);
    smci.pCode = kHalationScatterSpv;
    if (vkCreateShaderModule(c.device, &smci, nullptr, &k.shader) != VK_SUCCESS) return false;
    const uint32_t bindingIds[5] = {0u, 1u, 2u, 3u, 27u};
    VkDescriptorSetLayoutBinding b[5]{};
    for (uint32_t i = 0; i < 5; ++i) {
        b[i].binding = bindingIds[i];
        b[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
        b[i].descriptorCount = 1;
        b[i].stageFlags = VK_SHADER_STAGE_COMPUTE_BIT;
    }
    VkDescriptorSetLayoutCreateInfo dlci{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    dlci.bindingCount = 5;
    dlci.pBindings = b;
    if (vkCreateDescriptorSetLayout(c.device, &dlci, nullptr, &k.dsl) != VK_SUCCESS) return false;
    if (sizeof(HalationPush) > c.properties.limits.maxPushConstantsSize) return false;
    VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(HalationPush)};
    VkPipelineLayoutCreateInfo plci{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    plci.setLayoutCount = 1;
    plci.pSetLayouts = &k.dsl;
    plci.pushConstantRangeCount = 1;
    plci.pPushConstantRanges = &pcr;
    if (vkCreatePipelineLayout(c.device, &plci, nullptr, &k.pl) != VK_SUCCESS) return false;
    VkComputePipelineCreateInfo cpci{VK_STRUCTURE_TYPE_COMPUTE_PIPELINE_CREATE_INFO};
    cpci.stage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    cpci.stage.stage = VK_SHADER_STAGE_COMPUTE_BIT;
    cpci.stage.module = k.shader;
    cpci.stage.pName = "main";
    cpci.layout = k.pl;
    if (vkCreateComputePipelines(c.device, VK_NULL_HANDLE, 1, &cpci, nullptr, &k.pipe) != VK_SUCCESS) return false;
    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 5u * kHalSetCount};
    VkDescriptorPoolCreateInfo dpci{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    dpci.maxSets = kHalSetCount;
    dpci.poolSizeCount = 1;
    dpci.pPoolSizes = &ps;
    if (vkCreateDescriptorPool(c.device, &dpci, nullptr, &k.dpool) != VK_SUCCESS) return false;
    std::array<VkDescriptorSetLayout, kHalSetCount> layouts{};
    layouts.fill(k.dsl);
    VkDescriptorSetAllocateInfo dsai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    dsai.descriptorPool = k.dpool;
    dsai.descriptorSetCount = kHalSetCount;
    dsai.pSetLayouts = layouts.data();
    if (vkAllocateDescriptorSets(c.device, &dsai, k.dsets.data()) != VK_SUCCESS) return false;
    VkCommandPoolCreateInfo cpci2{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    cpci2.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    cpci2.queueFamilyIndex = c.queueFamily;
    if (vkCreateCommandPool(c.device, &cpci2, nullptr, &k.cpool) != VK_SUCCESS) return false;
    VkCommandBufferAllocateInfo cbai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
    cbai.commandPool = k.cpool;
    cbai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
    cbai.commandBufferCount = 1;
    if (vkAllocateCommandBuffers(c.device, &cbai, &k.cmd) != VK_SUCCESS) return false;
    VkFenceCreateInfo fci{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
    if (vkCreateFence(c.device, &fci, nullptr, &k.fence) != VK_SUCCESS) return false;
    k.pipelineReady = true;
    return true;
}

// Buffer roles per descriptor set: (in0, buf1, in2, buf3) over the four
// whole-frame work buffers S (source), SUM (weighted blur sum), X (X-blurred
// image, later the scattered image), XT (transposed scratch). One separable
// blur is: X pass S -> X (FIR channels per pixel; IIR channels transpose
// S -> XT, filter XT in place, transpose back XT -> X), then a Y pass
// SUM += w * blurY(X) (IIR channels use XT as the forward-sweep scratch).
//   0 X FIR S -> X          1 transpose S -> XT, IIR in place on XT
//   2 transpose XT -> X     3 Y accumulate X -> SUM (scratch XT)
//   4 clear SUM             5 resolve (S, SUM) -> X
// The bounce blurs after a scatter step read the scattered image in X and
// use S, now free, as their X-blurred buffer:
//   6 X FIR X -> S          7 transpose X -> XT, IIR in place on XT
//   8 transpose XT -> S     9 Y accumulate S -> SUM (scratch XT)
//  10 resolve (X, SUM) -> S
// Unused bindings of a set hold a buffer the op never touches.
void write_halation_sets(Ctx& c, Ctx::HalationKernel& k) {
    const VkBuffer S = k.work[kHalSrc].buf, SUM = k.work[kHalSum].buf,
                   X = k.work[kHalX].buf, XT = k.work[kHalXT].buf;
    const VkBuffer roles[kHalSetCount][4] = {
        {S, X, S, S},     {S, XT, S, S},    {XT, X, XT, XT}, {X, SUM, X, XT},
        {S, SUM, S, S},   {S, SUM, S, X},   {X, S, X, X},    {X, XT, X, X},
        {XT, S, XT, XT},  {S, SUM, S, XT},  {X, SUM, X, S},
    };
    std::array<VkDescriptorBufferInfo, kHalSetCount * 5> infos{};
    std::array<VkWriteDescriptorSet, kHalSetCount * 5> writes{};
    size_t n = 0;
    for (uint32_t s = 0; s < kHalSetCount; ++s) {
        const uint32_t bindingIds[5] = {0u, 1u, 2u, 3u, 27u};
        for (uint32_t i = 0; i < 5; ++i) {
            infos[n] = VkDescriptorBufferInfo{i < 4 ? roles[s][i] : k.frame.buf, 0, VK_WHOLE_SIZE};
            writes[n] = VkWriteDescriptorSet{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            writes[n].dstSet = k.dsets[s];
            writes[n].dstBinding = bindingIds[i];
            writes[n].descriptorCount = 1;
            writes[n].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            writes[n].pBufferInfo = &infos[n];
            ++n;
        }
    }
    vkUpdateDescriptorSets(c.device, static_cast<uint32_t>(n), writes.data(), 0, nullptr);
}

bool halation_scatter_locked(Ctx& c, const HalationScatterRequest& r, double* out,
                             HalationScatterDiagnostics& d) {
    d.attempted = true;
    if (!c.ok) { d.reason = "no-device"; return false; }
    if (!r.raw_rgb || !out || out == r.raw_rgb || r.width <= 0 || r.height <= 0 ||
        !(r.pixel_size_um > 0.0)) {
        d.reason = "bad-request";
        return false;
    }
    const uint64_t npix64 = static_cast<uint64_t>(r.width) * static_cast<uint64_t>(r.height);
    if (npix64 > static_cast<uint64_t>(std::numeric_limits<int32_t>::max()) / 3u) {
        d.reason = "too-many-pixels";
        return false;
    }
    const double values[] = {r.scatter_amount, r.scatter_spatial_scale, r.halation_amount,
                             r.halation_spatial_scale, r.halation_bounce_decay};
    for (double v : values) if (!std::isfinite(v)) { d.reason = "bad-request"; return false; }
    for (int ch = 0; ch < 3; ++ch) {
        const double per[] = {r.scatter_core_um[ch], r.scatter_tail_um[ch], r.scatter_tail_weight[ch],
                              r.halation_strength[ch], r.halation_first_sigma_um[ch]};
        for (double v : per) if (!std::isfinite(v)) { d.reason = "bad-request"; return false; }
        // The CPU blends (1 - w) * core + w * tail with the raw weight, and a
        // weight outside [0, 1] makes that a difference of two larger numbers.
        // The f32 sum buffer cannot hold the result inside the tolerance band
        // once it does (measured 5.3e-4 on log10(raw) at w = 1.2), so the pass
        // declines rather than returning an image it cannot bound. Presets can
        // carry such a weight: the import range check covers only the
        // resource-amplifying parameters.
        if (!(r.scatter_tail_weight[ch] >= 0.0 && r.scatter_tail_weight[ch] <= 1.0)) {
            d.reason = "tail-weight-out-of-range";
            return false;
        }
    }

    // Activity by the CPU pass's own rules.
    double sigmaCore[3], lambdaTail[3], aTot[3], sigmaH[3];
    bool anyScatterSigma = false, anyA = false, anySigmaH = false;
    bool coreNeeded = false, tailNeeded = false;
    for (int ch = 0; ch < 3; ++ch) {
        sigmaCore[ch] = r.scatter_core_um[ch] * r.scatter_spatial_scale / r.pixel_size_um;
        lambdaTail[ch] = r.scatter_tail_um[ch] * r.scatter_spatial_scale / r.pixel_size_um;
        if (sigmaCore[ch] > 0.0 || lambdaTail[ch] > 0.0) anyScatterSigma = true;
        // The CPU blend is (1 - w) * core + w * tail with the RAW w
        // (model/diffusion.cpp), and w is known to be in [0, 1] here because
        // the validation above declined anything else. A blur whose weight is
        // exactly zero for all three channels contributes nothing, so it is
        // the one case worth skipping.
        if (r.scatter_tail_weight[ch] != 1.0) coreNeeded = true;
        if (r.scatter_tail_weight[ch] != 0.0) tailNeeded = true;
        aTot[ch] = r.halation_strength[ch] * r.halation_amount;
        sigmaH[ch] = r.halation_first_sigma_um[ch] * r.halation_spatial_scale / r.pixel_size_um;
        if (aTot[ch] > 0.0) anyA = true;
        if (sigmaH[ch] > 0.0) anySigmaH = true;
    }
    const bool scatterActive = r.scatter_amount > 0.0 && anyScatterSigma;
    const int bounces = r.halation_n_bounces;
    const bool bounceActive = bounces >= 1 && anyA && anySigmaH;
    const size_t total = static_cast<size_t>(npix64) * 3u;
    if (!scatterActive && !bounceActive) {
        std::memcpy(out, r.raw_rgb, total * sizeof(double));
        d.engaged = false;
        d.reason = "nothing-to-do";
        return true;
    }
    double sigmaMax = 0.0;
    for (int ch = 0; ch < 3; ++ch) {
        if (scatterActive) {
            sigmaMax = std::max(sigmaMax, sigmaCore[ch]);
            sigmaMax = std::max(sigmaMax, lambdaTail[ch] * kHalTailRatio[2]);
        }
        if (bounceActive) {
            sigmaMax = std::max(sigmaMax, sigmaH[ch] * std::sqrt(static_cast<double>(bounces)));
        }
    }
    if (!(sigmaMax <= kHalMaxSigma)) { d.reason = "sigma-too-large"; return false; }
    if (bounceActive && static_cast<uint32_t>(bounces) > kHalMaxBounces) { d.reason = "too-many-bounces"; return false; }

    // Whole-frame residency: four packed f32 planes, each one storage range,
    // against the fixed budget and the device's largest device-local heap.
    const uint32_t W = static_cast<uint32_t>(r.width), H = static_cast<uint32_t>(r.height);
    const VkDeviceSize frameBytes = static_cast<VkDeviceSize>(npix64) * kHalPixelBytes;
    const VkDeviceSize workBytes = frameBytes * kHalWorkBuffers;
    const VkPhysicalDeviceLimits& lim = c.properties.limits;
    const uint32_t maxDim = std::max(W, H);
    if (workBytes > kHalWorkBudgetBytes || frameBytes > lim.maxStorageBufferRange ||
        workBytes > c.largestHeapFor(VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT) ||
        (maxDim + kHalLineThreads - 1u) / kHalLineThreads > lim.maxComputeWorkGroupCount[0] ||
        (maxDim + kHalTransposeTile - 1u) / kHalTransposeTile >
            std::min(lim.maxComputeWorkGroupCount[0], lim.maxComputeWorkGroupCount[1]) ||
        (W + kHalGroupX - 1u) / kHalGroupX > lim.maxComputeWorkGroupCount[0] ||
        (H + kHalGroupY - 1u) / kHalGroupY > lim.maxComputeWorkGroupCount[1]) {
        d.reason = "frame-too-large";
        return false;
    }
    // Upload/readback go through one mapped staging band of whole rows; the
    // band size never touches the numbers (a test proves it).
    const VkDeviceSize rowBytes = static_cast<VkDeviceSize>(W) * kHalPixelBytes;
    const VkDeviceSize bandCap = r.staging_bytes_override ? r.staging_bytes_override : kHalStagingBytes;
    const uint32_t bandRows = static_cast<uint32_t>(
        std::min<VkDeviceSize>(H, std::max<VkDeviceSize>(1, bandCap / rowBytes)));
    const VkDeviceSize stagingBytes = static_cast<VkDeviceSize>(bandRows) * rowBytes;

    Ctx::HalationKernel& k = c.halation;
    if (!k.pipelineReady && !build_halation_pipeline(c, k)) { d.reason = "pipeline-failed"; return false; }
    if (!c.ensureBuf(k.staging, stagingBytes)) { d.reason = "allocation-failed"; return false; }
    if (!c.ensureBuf(k.frame, kHalFrameFloatCount * sizeof(float))) { d.reason = "allocation-failed"; return false; }
    PointwiseChainDiagnostics scratchDiagnostics{};
    for (ResidentBuf& b : k.work) {
        if (!c.ensureResidentBuf(b, frameBytes,
                                 VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                                     VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                 0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, false, scratchDiagnostics)) {
            d.reason = "allocation-failed";
            return false;
        }
    }

    // Per-channel sigma in pixels of one blur, as apply_halation_um derives it
    // (model/diffusion.cpp): the scatter core, one of the three surrogate
    // Gaussians of the exponential tail, or bounce b at sigma_h * sqrt(b + 1).
    auto blur_sigma = [&](uint32_t sigmaMode, uint32_t component, uint32_t ch) {
        const double scale = sigmaMode == kHalSigmaBounce ? std::max(r.halation_spatial_scale, 0.0)
                                                          : std::max(r.scatter_spatial_scale, 0.0);
        if (sigmaMode == kHalSigmaCore) return std::max(r.scatter_core_um[ch], 0.0) * scale / r.pixel_size_um;
        if (sigmaMode == kHalSigmaTail) return std::max(r.scatter_tail_um[ch], 0.0) * kHalTailRatio[component] * scale / r.pixel_size_um;
        return std::max(r.halation_first_sigma_um[ch], 0.0) * scale *
               std::sqrt(static_cast<double>(component + 1u)) / r.pixel_size_um;
    };
    // The FIR/IIR split, in f64, exactly as gaussian_blur_plane_d dispatches it.
    // This decision is the HOST's alone: it selects which dispatches run, and
    // the shader reads it back from the table rather than recomputing it in
    // f32, where a sigma either side of 3.0 could classify the other way and
    // leave a channel of that blur unwritten.
    auto blur_is_iir = [&](uint32_t sigmaMode, uint32_t component, uint32_t ch) {
        return blur_sigma(sigmaMode, component, ch) >= kHalSmallSigmaMax;
    };
    auto iir_mask = [&](uint32_t sigmaMode, uint32_t component) {
        uint32_t mask = 0;
        for (uint32_t ch = 0; ch < 3; ++ch) if (blur_is_iir(sigmaMode, component, ch)) mask |= 1u << ch;
        return mask;
    };

    // FrameFloats: the engine's HalationParams and the blur table, as the
    // shader expects them. Everything the shader needs about a blur -- sigma,
    // class, accumulation weight, Young-van Vliet coefficients -- is computed
    // here in f64 and rounded once; the shader derives nothing.
    {
        float* f = static_cast<float*>(k.frame.mapped);
        std::memset(f, 0, kHalFrameFloatCount * sizeof(float));
        f[kHalFramePixelSizeUm] = static_cast<float>(r.pixel_size_um);
        f[kHalFrameScatterAmount] = static_cast<float>(r.scatter_amount);
        f[kHalFrameScatterScale] = static_cast<float>(r.scatter_spatial_scale);
        f[kHalFrameHalationAmount] = static_cast<float>(r.halation_amount);
        f[kHalFrameHalationScale] = static_cast<float>(r.halation_spatial_scale);
        f[kHalFrameRenormalize] = r.halation_renormalize ? 1.0f : 0.0f;
        for (uint32_t ch = 0; ch < 3; ++ch) {
            f[kHalFrameStrength + ch] = static_cast<float>(r.halation_strength[ch]);
            f[kHalFrameFirstSigma + ch] = static_cast<float>(r.halation_first_sigma_um[ch]);
            f[kHalFrameCoreUm + ch] = static_cast<float>(r.scatter_core_um[ch]);
            f[kHalFrameTailUm + ch] = static_cast<float>(r.scatter_tail_um[ch]);
            f[kHalFrameTailWeight + ch] = static_cast<float>(r.scatter_tail_weight[ch]);
        }
        // A coefficient reaches the shader as a double-float pair: the f64
        // value and the residual of its own rounding. Rounding the poles to
        // f32 alone is NOT enough -- they sit ~1/q from the unit circle, so a
        // relative perturbation of 1e-7 in a coefficient moves the pole by
        // ~q^3 in relative terms and changes the filter's shape (measured
        // against the f64 CPU filter on a unit step: 2.5e-3 at sigma 63 px,
        // 0.20 at the 256 px cap), even though the DC gain can be pinned.
        auto put_df = [&](float* dst, double v) {
            const float hi = static_cast<float>(v);
            dst[0] = hi;
            dst[1] = static_cast<float>(v - static_cast<double>(hi));
        };
        auto fill = [&](uint32_t slot, uint32_t sigmaMode, uint32_t component) {
            for (uint32_t ch = 0; ch < 3; ++ch) {
                const double sigma = blur_sigma(sigmaMode, component, ch);
                const bool isIir = blur_is_iir(sigmaMode, component, ch);
                double weight = 0.0;
                if (sigmaMode == kHalSigmaCore) {
                    weight = 1.0 - r.scatter_tail_weight[ch];
                } else if (sigmaMode == kHalSigmaTail) {
                    weight = r.scatter_tail_weight[ch] * kHalTailAmplitude[component];
                } else {
                    // pow(decay, b) / sum_b pow(decay, b), with the CPU's
                    // std::pow(0, 0) == 1 for a zero decay (which the UI
                    // allows); GLSL's pow(0, 0) is undefined, so this weight is
                    // never computed on the device.
                    double norm = 0.0;
                    for (int b = 0; b < std::max(bounces, 1); ++b) norm += std::pow(r.halation_bounce_decay, static_cast<double>(b));
                    weight = std::pow(r.halation_bounce_decay, static_cast<double>(component)) / std::max(norm, 1e-12);
                }
                float* dst = f + kHalFrameBlurBase + (slot * 3u + ch) * kHalBlurStride;
                dst[kHalBlurFieldSigma] = static_cast<float>(sigma);
                dst[kHalBlurFieldIsIir] = isIir ? 1.0f : 0.0f;
                dst[kHalBlurFieldWeight] = static_cast<float>(weight);
                if (!isIir) {
                    // gaussian_kernel_1d: radius = int(3 * sigma + 0.5) on the
                    // f64 sigma. Derived on the device from the f32 sigma it
                    // gains a tap pair wherever rounding crosses an integer.
                    const double capped = std::max(sigma, 1e-6);  // max_eps, as the CPU callers apply it
                    const double radius = std::floor(3.0 * capped + 0.5);
                    dst[kHalBlurFieldFirRadius] =
                        static_cast<float>(std::min(radius, static_cast<double>(kHalMaxFirRadius)));
                    continue;
                }
                // yvv_coeffs (kernels/exponential_filter.cpp), verbatim in f64.
                const double q = sigma >= 2.5 ? 0.98711 * sigma - 0.96330
                                              : 3.97156 - 4.14554 * std::sqrt(1.0 - 0.26891 * sigma);
                const double q2 = q * q, q3 = q2 * q;
                const double b0 = 1.57825 + 2.44413 * q + 1.4281 * q2 + 0.422205 * q3;
                const double b1 = 2.44413 * q + 2.85619 * q2 + 1.26661 * q3;
                const double b2 = -(1.4281 * q2 + 1.26661 * q3);
                const double b3 = 0.422205 * q3;
                put_df(dst + kHalBlurFieldB, 1.0 - (b1 + b2 + b3) / b0);
                put_df(dst + kHalBlurFieldP1, b1 / b0);
                put_df(dst + kHalBlurFieldP2, b2 / b0);
                put_df(dst + kHalBlurFieldP3, b3 / b0);
            }
        };
        if (scatterActive) {
            fill(0u, kHalSigmaCore, 0u);
            for (uint32_t comp = 0; comp < 3; ++comp) fill(1u + comp, kHalSigmaTail, comp);
        }
        if (bounceActive) {
            for (uint32_t b = 0; b < static_cast<uint32_t>(bounces); ++b) fill(4u + b, kHalSigmaBounce, b);
        }
    }
    write_halation_sets(c, k);
    HalationPush push{};
    push.fullWidth = W;
    push.fullHeight = H;
    uint32_t dispatches = 0, bands = 0;
    auto barrier = [&](VkPipelineStageFlags srcStage, VkAccessFlags srcAccess,
                       VkPipelineStageFlags dstStage, VkAccessFlags dstAccess) {
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = srcAccess;
        mb.dstAccessMask = dstAccess;
        vkCmdPipelineBarrier(k.cmd, srcStage, dstStage, 0, 1, &mb, 0, nullptr, 0, nullptr);
    };
    auto begin = [&]() {
        if (vkResetCommandBuffer(k.cmd, 0) != VK_SUCCESS) return false;
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(k.cmd, &bi) != VK_SUCCESS) return false;
        // Everything an earlier submission wrote (uploads, dispatches) is made
        // visible to this one.
        barrier(VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_WRITE_BIT,
                VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
        return true;
    };
    auto submit_wait = [&]() {
        if (vkEndCommandBuffer(k.cmd) != VK_SUCCESS) return false;
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &k.cmd;
        if (vkQueueSubmit(c.queue, 1, &si, k.fence) != VK_SUCCESS) return false;
        if (vkWaitForFences(c.device, 1, &k.fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS) return false;
        return vkResetFences(c.device, 1, &k.fence) == VK_SUCCESS;
    };
    // `width`/`height` are the dims of the image the op walks: the frame, or
    // the transposed frame for the IIR column pass of an X blur.
    auto dispatch = [&](uint32_t set, uint32_t op, uint32_t sigmaMode, uint32_t component,
                        uint32_t width, uint32_t height) {
        push.operation = op;
        push.sigmaMode = sigmaMode;
        push.component = component;
        push.width = width;
        push.height = height;
        vkCmdBindPipeline(k.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, k.pipe);
        vkCmdBindDescriptorSets(k.cmd, VK_PIPELINE_BIND_POINT_COMPUTE, k.pl, 0, 1, &k.dsets[set], 0, nullptr);
        vkCmdPushConstants(k.cmd, k.pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(HalationPush), &push);
        if (op == kHalOpLineInPlace || op == kHalOpLineYAccumulate) {
            vkCmdDispatch(k.cmd, (width + kHalLineThreads - 1u) / kHalLineThreads, 1, 1);
        } else if (op == kHalOpTranspose) {
            vkCmdDispatch(k.cmd, (width + kHalTransposeTile - 1u) / kHalTransposeTile,
                          (height + kHalTransposeTile - 1u) / kHalTransposeTile, 1);
        } else {
            vkCmdDispatch(k.cmd, (width + kHalGroupX - 1u) / kHalGroupX, (height + kHalGroupY - 1u) / kHalGroupY, 1);
        }
        barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT);
        ++dispatches;
    };
    // One separable blur into SUM. `fromScattered` selects the bounce sets
    // that read the scattered image in X and use S as scratch.
    auto blur = [&](uint32_t sigmaMode, uint32_t component, bool fromScattered) {
        const uint32_t xFir = fromScattered ? 6u : 0u, tFwd = fromScattered ? 7u : 1u,
                       tBack = fromScattered ? 8u : 2u, yAcc = fromScattered ? 9u : 3u;
        const uint32_t iirMask = iir_mask(sigmaMode, component);
        const bool anyFir = iirMask != 7u;
        const bool anyIir = iirMask != 0u;
        if (anyFir) dispatch(xFir, kHalOpFirX, sigmaMode, component, W, H);
        if (anyIir) {
            dispatch(tFwd, kHalOpTranspose, sigmaMode, 0, W, H);            // source -> XT
            dispatch(tFwd, kHalOpLineInPlace, sigmaMode, component, H, W);  // columns of the transposed
            dispatch(tBack, kHalOpTranspose, sigmaMode, iirMask, H, W);     // XT -> x-blurred, IIR channels
        }
        if (anyFir) dispatch(yAcc, kHalOpFirYAccumulate, sigmaMode, component, W, H);
        if (anyIir) dispatch(yAcc, kHalOpLineYAccumulate, sigmaMode, component, W, H);
    };

    // Upload: rows [y0, y1) as packed f32 (the engine's deterministic parallel
    // chunks, disjoint writes), one copy per band.
    {
        const auto t0 = std::chrono::steady_clock::now();
        for (uint32_t y0 = 0; y0 < H; y0 += bandRows) {
            const uint32_t rows = std::min(bandRows, H - y0);
            const size_t n = static_cast<size_t>(rows) * W * 3u;
            float* dst = static_cast<float*>(k.staging.mapped);
            const double* src = r.raw_rgb + static_cast<size_t>(y0) * W * 3u;
            parallel_for(0, static_cast<int>(n), [&](int lo, int hi) {
                for (int i = lo; i < hi; ++i) dst[i] = static_cast<float>(src[i]);
            });
            if (!begin()) { d.reason = "dispatch-failed"; return false; }
            barrier(VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            VkBufferCopy upload{0, static_cast<VkDeviceSize>(y0) * rowBytes, static_cast<VkDeviceSize>(rows) * rowBytes};
            vkCmdCopyBuffer(k.cmd, k.staging.buf, k.work[kHalSrc].buf, 1, &upload);
            if (!submit_wait()) { d.reason = "dispatch-failed"; return false; }
            ++bands;
        }
        d.upload_ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    }

    // The whole pass, one submission.
    const auto gpuStart = std::chrono::steady_clock::now();
    if (!begin()) { d.reason = "dispatch-failed"; return false; }
    if (scatterActive) {
        dispatch(4, kHalOpClear, 0, 0, W, H);
        if (coreNeeded) blur(kHalSigmaCore, 0, false);
        if (tailNeeded) for (uint32_t comp = 0; comp < 3; ++comp) blur(kHalSigmaTail, comp, false);
        dispatch(5, kHalOpScatterResolve, 0, 0, W, H);  // scattered -> X
    }
    if (bounceActive) {
        dispatch(4, kHalOpClear, 0, 0, W, H);
        for (uint32_t b = 0; b < static_cast<uint32_t>(bounces); ++b) blur(kHalSigmaBounce, b, scatterActive);
        dispatch(scatterActive ? 10u : 5u, kHalOpBounceResolveRaw, 0, 0, W, H);
    }
    const VkBuffer result = (scatterActive && bounceActive) ? k.work[kHalSrc].buf : k.work[kHalX].buf;
    if (!submit_wait()) { d.reason = "dispatch-failed"; return false; }
    d.gpu_ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - gpuStart).count();

    // Readback: rows [y0, y1) into the caller's f64 plane, one copy per band.
    {
        const auto t0 = std::chrono::steady_clock::now();
        for (uint32_t y0 = 0; y0 < H; y0 += bandRows) {
            const uint32_t rows = std::min(bandRows, H - y0);
            if (!begin()) { d.reason = "dispatch-failed"; return false; }
            barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                    VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            VkBufferCopy readback{static_cast<VkDeviceSize>(y0) * rowBytes, 0, static_cast<VkDeviceSize>(rows) * rowBytes};
            vkCmdCopyBuffer(k.cmd, result, k.staging.buf, 1, &readback);
            barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                    VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
            if (!submit_wait()) { d.reason = "dispatch-failed"; return false; }
            const float* srcf = static_cast<const float*>(k.staging.mapped);
            double* dst = out + static_cast<size_t>(y0) * W * 3u;
            const size_t n = static_cast<size_t>(rows) * W * 3u;
            parallel_for(0, static_cast<int>(n), [&](int lo, int hi) {
                for (int i = lo; i < hi; ++i) dst[i] = static_cast<double>(srcf[i]);
            });
        }
        d.readback_ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
    }
    d.engaged = true;
    d.reason = "none";
    d.bands = bands;
    d.dispatches = dispatches;
    return true;
}

}  // namespace

bool halation_scatter(const HalationScatterRequest& request, double* out_rgb,
                      HalationScatterDiagnostics* diagnostics) noexcept {
    HalationScatterDiagnostics local{};
    HalationScatterDiagnostics& d = diagnostics ? *diagnostics : local;
    d = {};
    bool ok = false;
    try {
        std::lock_guard<std::mutex> lk(gpu_mutex());
        Ctx& c = ctx();
        ok = halation_scatter_locked(c, request, out_rgb, d);
        // A refusal decided before any Vulkan call (bad request, sigma or slice
        // geometry) leaves the kernel state valid and warm; only a failure of the
        // device path itself tears it down so the next call rebuilds from scratch.
        if (!ok && (std::strcmp(d.reason, "pipeline-failed") == 0 ||
                    std::strcmp(d.reason, "allocation-failed") == 0 ||
                    std::strcmp(d.reason, "dispatch-failed") == 0)) {
            c.destroyHalation();
        }
    } catch (...) {
        d.reason = "exception";
        ok = false;
        try {
            std::lock_guard<std::mutex> lk(gpu_mutex());
            ctx().destroyHalation();
        } catch (...) {
        }
    }
    return ok;
}

}  // namespace spk::gpu

#endif  // SPK_ENABLE_VULKAN
