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
    // halation_scatter.comp (#206): one pipeline, ten descriptor sets (one per
    // buffer-role permutation of the op sequence), five device-local work
    // buffers sized to the slice, one mapped staging buffer, one frame-floats
    // buffer. Grow-only like the scan kernels; torn down on failure only.
    struct HalationKernel {
        VkShaderModule shader = VK_NULL_HANDLE;
        VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
        VkPipelineLayout pl = VK_NULL_HANDLE;
        VkPipeline pipe = VK_NULL_HANDLE;
        VkDescriptorPool dpool = VK_NULL_HANDLE;
        std::array<VkDescriptorSet, 14> dsets{};
        VkCommandPool cpool = VK_NULL_HANDLE;
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        Buf staging;                       // host-visible, mapped: upload + readback
        Buf frame;                         // FrameFloats (binding 27)
        std::array<ResidentBuf, 7> work{}; // src, A, B, C, D, T1, T2 (device-local preferred)
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
        bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
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
// header and docs/research/spektrafilm-ofx-port.md). The dispatch sequence and
// the buffer roles of the ten descriptor sets follow upstream's
// SpektraVulkanRenderer.cpp halation pass; the slicing, the f64 staging and the
// parameter plumbing are Android additions.
//
// Model (model/diffusion.cpp::apply_halation_um, per channel c):
//   core   = G(sigma_core[c]) * raw
//   tail   = sum_k amp_k * G(ratio_k * lambda_tail[c]) * raw    (k = 0..2)
//   raw'   = (1 - s) * raw + s * ((1 - w[c]) * core + w[c] * tail)
//   blur   = sum_k decay_k * G(sigma_h[c] * sqrt(k)) * raw'      (k = 1..N)
//   out    = raw' + a[c] * blur, / (1 + a[c]) when renormalising
//
// Slicing: rows [y0, y1) are rendered from an upload of rows
// [y0 - halo, y1 + halo) where halo is the scatter radius plus the bounce
// radius, so every output pixel sees exactly the neighbours it would see in a
// whole-image pass and the bytes do not depend on the slice height (a test
// proves this). The shader's reflect boundary therefore only ever acts at the
// true image edges.
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
constexpr uint32_t kHalOpLineYStore = 12u;       // IIR channels, one thread per column
constexpr uint32_t kHalOpLineYAccumulate = 13u;  // IIR channels, buf3 scratch
constexpr uint32_t kHalOpFirX = 14u;             // FIR channels, per pixel
constexpr uint32_t kHalOpFirYStore = 15u;
constexpr uint32_t kHalOpFirYAccumulate = 16u;
constexpr uint32_t kHalOpTranspose = 17u;        // tiled, component = channel mask
constexpr uint32_t kHalLineThreads = 256u;       // kThreadsPerGroup in the shader
constexpr uint32_t kHalTransposeTile = 32u;
constexpr uint32_t kHalOpScatterResolve = 4u;
constexpr uint32_t kHalOpBounceResolveRaw = 10u;
constexpr uint32_t kHalSigmaCore = 0u;
constexpr uint32_t kHalSigmaTail = 1u;
constexpr uint32_t kHalSigmaBounce = 2u;
// FrameFloats indices (halation_scatter.comp).
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
constexpr uint32_t kHalFrameBounceDecay = 89u;
constexpr uint32_t kHalFrameBounceWeightNorm = 90u;
constexpr uint32_t kHalFrameRenormalize = 91u;
constexpr uint32_t kHalFrameFloatCount = 96u;
constexpr int kHalMaxFirRadius = 256;            // the shader's FIR cap
constexpr uint32_t kHalMaxHalo = 1024;           // rows of halo the slicer will carry
constexpr double kHalSmallSigmaMax = 3.0;        // kSmallSigmaMaxD: IIR at and above
constexpr uint32_t kHalSetCount = 14u;
constexpr VkDeviceSize kHalPixelBytes = 16;      // vec4 f32
constexpr VkDeviceSize kHalWorkBudgetBytes = 448ull << 20;  // seven work buffers
constexpr uint32_t kHalMaxSlicePixels = 4u << 20;
constexpr uint32_t kHalGroupX = 32u, kHalGroupY = 8u;
// fast_exponential_filter's widest mixture component (kernels/exponential_filter).
constexpr double kHalTailMaxRatio = 2.7684;

// Rows of neighbourhood one Gaussian pass needs on each side. FIR (sigma < 3):
// the engine's exact radius int(3 * sigma + 0.5). IIR (sigma >= 3): the
// recursion has infinite support, so a slice cannot reproduce the
// whole-image state exactly; the slice-local initialisation leaves a
// transient that decays roughly 4.6x per 2 sigma (measured on log10:
// 4.1e-4 at a 4 sigma halo, 9.0e-5 at 6 sigma), so 10 sigma + 1 rows
// leave a residual near 1e-6, two orders inside the band the pass itself
// meets. Not byte-identical across slice heights for IIR sigmas: the
// slice height is a pure function of the image size, so one device
// stays deterministic. The host test measures the residual against a
// whole-image run.
int hal_halo(double sigma) {
    if (!(sigma > 0.0)) return 0;
    if (sigma >= kHalSmallSigmaMax) {
        const double r = std::ceil(10.0 * sigma) + 1.0;
        return r > static_cast<double>(kHalMaxHalo) ? static_cast<int>(kHalMaxHalo) + 1 : static_cast<int>(r);
    }
    const double r = std::floor(3.0 * sigma + 0.5);
    return r > static_cast<double>(kHalMaxFirRadius) ? kHalMaxFirRadius + 1 : static_cast<int>(r);
}

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

// Upstream's buffer roles per descriptor set: (in0, buf1, in2, buf3).
//   0 BlurX core      (src, A, A, A)      1 BlurYStore core   (A, B, A, B)
//   2 Clear tail sum  (src, C, A, C)      3 BlurX tail        (src, A, A, A)
//   4 BlurYAcc tail   (A, C, A, D)        5 ScatterResolve    (src, B, C, D)
//   6 Clear bounce    (bsrc, C, A, C)     7 BlurX bounce      (bsrc, A, A, A)
//   8 BlurYAcc bounce (A, C, A, B)        9 BounceResolveRaw  (bsrc, C, A, out)
// bsrc is D when the scatter step ran, else src; out is B (free after set 5).
// The accumulate sets bind a free buffer as buf3: the IIR's forward-sweep
// scratch (D is untouched until set 5; B is consumed by set 5 and rewritten
// only by set 9).
void write_halation_sets(Ctx& c, Ctx::HalationKernel& k, bool scatterActive) {
    const VkBuffer src = k.work[0].buf, A = k.work[1].buf, B = k.work[2].buf,
                   C = k.work[3].buf, D = k.work[4].buf, T1 = k.work[5].buf, T2 = k.work[6].buf;
    const VkBuffer bsrc = scatterActive ? D : src;
    // 10..13: the IIR X pass as transpose (source -> T1), column pass (T1 -> T2),
    // transpose back (T2 -> A, IIR channels only) for the scatter source and
    // the bounce source.
    const VkBuffer roles[kHalSetCount][4] = {
        {src, A, A, A}, {A, B, A, B}, {src, C, A, C}, {src, A, A, A}, {A, C, A, D},
        {src, B, C, D}, {bsrc, C, A, C}, {bsrc, A, A, A}, {A, C, A, B}, {bsrc, C, A, B},
        {src, T1, T1, T1}, {T1, T2, T1, T2}, {T2, A, A, A}, {bsrc, T1, T1, T1},
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
    }

    // Activity and radii, by the CPU pass's own rules.
    double sigmaCore[3], lambdaTail[3], aTot[3], sigmaH[3];
    bool anyScatterSigma = false, anyA = false, anySigmaH = false;
    for (int ch = 0; ch < 3; ++ch) {
        sigmaCore[ch] = r.scatter_core_um[ch] * r.scatter_spatial_scale / r.pixel_size_um;
        lambdaTail[ch] = r.scatter_tail_um[ch] * r.scatter_spatial_scale / r.pixel_size_um;
        if (sigmaCore[ch] > 0.0 || lambdaTail[ch] > 0.0) anyScatterSigma = true;
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
    int radiusScatter = 0, radiusBounce = 0;
    for (int ch = 0; ch < 3; ++ch) {
        radiusScatter = std::max(radiusScatter, hal_halo(std::max(sigmaCore[ch], 1e-6)));
        radiusScatter = std::max(radiusScatter, hal_halo(std::max(lambdaTail[ch], 1e-6) * kHalTailMaxRatio));
        radiusBounce = std::max(radiusBounce,
                                hal_halo(std::max(sigmaH[ch] * std::sqrt(static_cast<double>(std::max(bounces, 1))), 1e-6)));
    }
    if (radiusScatter > kHalMaxFirRadius && radiusScatter > static_cast<int>(kHalMaxHalo)) {
        d.reason = "sigma-too-large";
        return false;
    }
    if (radiusBounce > static_cast<int>(kHalMaxHalo)) {
        d.reason = "sigma-too-large";
        return false;
    }
    const uint32_t halo = static_cast<uint32_t>((scatterActive ? radiusScatter : 0) +
                                                (bounceActive ? radiusBounce : 0));
    d.halo_rows = halo;
    const uint32_t W = static_cast<uint32_t>(r.width), H = static_cast<uint32_t>(r.height);

    // Slice height from the work budget (five vec4 buffers of the padded slice)
    // and the per-dispatch pixel cap; the halo rows are the fixed cost.
    uint32_t rowsOut = r.slice_rows_override;
    if (rowsOut == 0) {
        const uint64_t budgetPixels = std::min<uint64_t>(
            kHalMaxSlicePixels, kHalWorkBudgetBytes / (7u * kHalPixelBytes));
        const uint64_t budgetRows = budgetPixels / W;
        rowsOut = budgetRows > 2ull * halo ? static_cast<uint32_t>(budgetRows - 2ull * halo) : 0u;
    }
    if (rowsOut == 0) { d.reason = "slice-too-small"; return false; }
    if (rowsOut > H) rowsOut = H;
    const uint32_t paddedRowsCap = std::min(H, rowsOut + 2u * halo);
    const uint64_t paddedPixelsCap = static_cast<uint64_t>(paddedRowsCap) * W;
    if (paddedPixelsCap > kHalMaxSlicePixels ||
        (std::max(paddedRowsCap, W) + kHalLineThreads - 1u) / kHalLineThreads > c.properties.limits.maxComputeWorkGroupCount[0] ||
        (std::max(paddedRowsCap, W) + kHalTransposeTile - 1u) / kHalTransposeTile > c.properties.limits.maxComputeWorkGroupCount[0] ||
        (std::max(paddedRowsCap, W) + kHalTransposeTile - 1u) / kHalTransposeTile > c.properties.limits.maxComputeWorkGroupCount[1] ||
        (paddedRowsCap + kHalGroupY - 1u) / kHalGroupY > c.properties.limits.maxComputeWorkGroupCount[1] ||
        (W + kHalGroupX - 1u) / kHalGroupX > c.properties.limits.maxComputeWorkGroupCount[0]) {
        d.reason = "slice-too-large";
        return false;
    }

    Ctx::HalationKernel& k = c.halation;
    if (!k.pipelineReady && !build_halation_pipeline(c, k)) { d.reason = "pipeline-failed"; return false; }
    const VkDeviceSize sliceBytes = static_cast<VkDeviceSize>(paddedPixelsCap) * kHalPixelBytes;
    if (!c.ensureBuf(k.staging, sliceBytes)) { d.reason = "allocation-failed"; return false; }
    if (!c.ensureBuf(k.frame, kHalFrameFloatCount * sizeof(float))) { d.reason = "allocation-failed"; return false; }
    PointwiseChainDiagnostics scratchDiagnostics{};
    for (ResidentBuf& b : k.work) {
        if (!c.ensureResidentBuf(b, sliceBytes,
                                 VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_SRC_BIT |
                                     VK_BUFFER_USAGE_TRANSFER_DST_BIT,
                                 0, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, false, scratchDiagnostics)) {
            d.reason = "allocation-failed";
            return false;
        }
    }

    // FrameFloats: the engine's HalationParams, as the shader expects them.
    {
        float* f = static_cast<float*>(k.frame.mapped);
        std::memset(f, 0, kHalFrameFloatCount * sizeof(float));
        f[kHalFramePixelSizeUm] = static_cast<float>(r.pixel_size_um);
        f[kHalFrameScatterAmount] = static_cast<float>(r.scatter_amount);
        f[kHalFrameScatterScale] = static_cast<float>(r.scatter_spatial_scale);
        f[kHalFrameHalationAmount] = static_cast<float>(r.halation_amount);
        f[kHalFrameHalationScale] = static_cast<float>(r.halation_spatial_scale);
        double norm = 0.0;
        for (int b = 0; b < std::max(bounces, 1); ++b) norm += std::pow(r.halation_bounce_decay, static_cast<double>(b));
        f[kHalFrameBounceDecay] = static_cast<float>(r.halation_bounce_decay);
        f[kHalFrameBounceWeightNorm] = static_cast<float>(norm);
        f[kHalFrameRenormalize] = r.halation_renormalize ? 1.0f : 0.0f;
        for (uint32_t ch = 0; ch < 3; ++ch) {
            f[kHalFrameStrength + ch] = static_cast<float>(r.halation_strength[ch]);
            f[kHalFrameFirstSigma + ch] = static_cast<float>(r.halation_first_sigma_um[ch]);
            f[kHalFrameCoreUm + ch] = static_cast<float>(r.scatter_core_um[ch]);
            f[kHalFrameTailUm + ch] = static_cast<float>(r.scatter_tail_um[ch]);
            f[kHalFrameTailWeight + ch] = static_cast<float>(r.scatter_tail_weight[ch]);
        }
    }
    write_halation_sets(c, k, scatterActive);

    // Which channels of a (mode, component) blur take the IIR (sigma >= 3),
    // mirroring sigmaForMode() in the shader so host and device agree on the
    // class; a mismatch would leave a channel unwritten.
    const double tailRatios[3] = {0.5360, 1.5236, 2.7684};
    auto iir_mask = [&](uint32_t sigmaMode, uint32_t component) {
        uint32_t mask = 0;
        for (uint32_t ch = 0; ch < 3; ++ch) {
            double sigma = 0.0;
            if (sigmaMode == kHalSigmaCore) sigma = std::max(r.scatter_core_um[ch], 0.0) * std::max(r.scatter_spatial_scale, 0.0) / r.pixel_size_um;
            else if (sigmaMode == kHalSigmaTail) sigma = std::max(r.scatter_tail_um[ch], 0.0) * tailRatios[component] * std::max(r.scatter_spatial_scale, 0.0) / r.pixel_size_um;
            else sigma = std::max(r.halation_first_sigma_um[ch], 0.0) * std::max(r.halation_spatial_scale, 0.0) * std::sqrt(static_cast<double>(component + 1u)) / r.pixel_size_um;
            if (static_cast<float>(sigma) >= static_cast<float>(kHalSmallSigmaMax)) mask |= 1u << ch;
        }
        return mask;
    };
    HalationPush push{};
    push.width = W;
    push.fullWidth = W;
    push.fullHeight = H;
    uint32_t dispatches = 0, slices = 0;
    auto barrier = [&](VkPipelineStageFlags srcStage, VkAccessFlags srcAccess,
                       VkPipelineStageFlags dstStage, VkAccessFlags dstAccess) {
        VkMemoryBarrier mb{VK_STRUCTURE_TYPE_MEMORY_BARRIER};
        mb.srcAccessMask = srcAccess;
        mb.dstAccessMask = dstAccess;
        vkCmdPipelineBarrier(k.cmd, srcStage, dstStage, 0, 1, &mb, 0, nullptr, 0, nullptr);
    };
    // `width`/`height` are the dims of the image the op walks: the slice, or
    // the transposed slice for the IIR column pass of an X blur.
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
        if (op == kHalOpLineYStore || op == kHalOpLineYAccumulate) {
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

    for (uint32_t y0 = 0; y0 < H; y0 += rowsOut) {
        const uint32_t y1 = std::min(H, y0 + rowsOut);
        const uint32_t top = y0 > halo ? y0 - halo : 0u;
        const uint32_t bottom = std::min(H, y1 + halo);
        const uint32_t rows = bottom - top;
        const size_t padded = static_cast<size_t>(rows) * W;
        // Upload rows [top, bottom) as vec4 f32 (alpha unused). Per-element map
        // with disjoint writes: the engine's deterministic parallel chunks.
        {
            const auto t0 = std::chrono::steady_clock::now();
            float* dst = static_cast<float*>(k.staging.mapped);
            const double* src = r.raw_rgb + static_cast<size_t>(top) * W * 3u;
            parallel_for(0, static_cast<int>(padded), [&](int lo, int hi) {
                for (int p = lo; p < hi; ++p) {
                    const size_t q = static_cast<size_t>(p);
                    dst[q * 4 + 0] = static_cast<float>(src[q * 3 + 0]);
                    dst[q * 4 + 1] = static_cast<float>(src[q * 3 + 1]);
                    dst[q * 4 + 2] = static_cast<float>(src[q * 3 + 2]);
                    dst[q * 4 + 3] = 0.0f;
                }
            });
            d.upload_ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        }
        const auto gpuStart = std::chrono::steady_clock::now();
        if (vkResetCommandBuffer(k.cmd, 0) != VK_SUCCESS) { d.reason = "dispatch-failed"; break; }
        VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
        bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        if (vkBeginCommandBuffer(k.cmd, &bi) != VK_SUCCESS) { d.reason = "dispatch-failed"; break; }
        barrier(VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        VkBufferCopy upload{0, 0, static_cast<VkDeviceSize>(padded) * kHalPixelBytes};
        vkCmdCopyBuffer(k.cmd, k.staging.buf, k.work[0].buf, 1, &upload);
        barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);
        // One separable blur = an X pass into A, then a Y pass (store or
        // accumulate). FIR-class channels: per-pixel ops. IIR-class channels:
        // column threads; the X pass transposes source -> T1, filters T1 -> T2
        // as columns of the transposed image, and transposes T2 -> A for those
        // channels only. `xSet` is the per-pixel X set, `tSet` the transpose set
        // for the pass's source, `ySet` the Y store/accumulate set.
        auto blur = [&](uint32_t sigmaMode, uint32_t component, uint32_t xSet, uint32_t tSet,
                        uint32_t ySet, bool accumulate) {
            const uint32_t iirMask = iir_mask(sigmaMode, component);
            const bool anyFir = iirMask != 7u;
            const bool anyIir = iirMask != 0u;
            if (anyFir) dispatch(xSet, kHalOpFirX, sigmaMode, component, W, rows);
            if (anyIir) {
                dispatch(tSet, kHalOpTranspose, sigmaMode, 0, W, rows);            // source -> T1
                dispatch(11, kHalOpLineYStore, sigmaMode, component, rows, W);     // columns of the transposed
                dispatch(12, kHalOpTranspose, sigmaMode, iirMask, rows, W);        // T2 -> A, IIR channels
            }
            const uint32_t firY = accumulate ? kHalOpFirYAccumulate : kHalOpFirYStore;
            const uint32_t iirY = accumulate ? kHalOpLineYAccumulate : kHalOpLineYStore;
            if (anyFir) dispatch(ySet, firY, sigmaMode, component, W, rows);
            if (anyIir) dispatch(ySet, iirY, sigmaMode, component, W, rows);
        };
        if (scatterActive) {
            blur(kHalSigmaCore, 0, 0, 10, 1, false);
            dispatch(2, kHalOpClear, 0, 0, W, rows);
            for (uint32_t comp = 0; comp < 3; ++comp) blur(kHalSigmaTail, comp, 3, 10, 4, true);
            dispatch(5, kHalOpScatterResolve, 0, 0, W, rows);
        }
        VkBuffer result = scatterActive ? k.work[4].buf : k.work[0].buf;
        if (bounceActive) {
            dispatch(6, kHalOpClear, 0, 0, W, rows);
            for (uint32_t b = 0; b < static_cast<uint32_t>(bounces); ++b) blur(kHalSigmaBounce, b, 7, 13, 8, true);
            dispatch(9, kHalOpBounceResolveRaw, 0, 0, W, rows);
            result = k.work[2].buf;
        }
        barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_ACCESS_SHADER_WRITE_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        const VkDeviceSize outOffset = static_cast<VkDeviceSize>(y0 - top) * W * kHalPixelBytes;
        VkBufferCopy readback{outOffset, outOffset, static_cast<VkDeviceSize>(y1 - y0) * W * kHalPixelBytes};
        vkCmdCopyBuffer(k.cmd, result, k.staging.buf, 1, &readback);
        barrier(VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
                VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_READ_BIT);
        if (vkEndCommandBuffer(k.cmd) != VK_SUCCESS) { d.reason = "dispatch-failed"; break; }
        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &k.cmd;
        if (vkQueueSubmit(c.queue, 1, &si, k.fence) != VK_SUCCESS) { d.reason = "dispatch-failed"; break; }
        if (vkWaitForFences(c.device, 1, &k.fence, VK_TRUE, UINT64_MAX) != VK_SUCCESS) { d.reason = "dispatch-failed"; break; }
        if (vkResetFences(c.device, 1, &k.fence) != VK_SUCCESS) { d.reason = "dispatch-failed"; break; }
        d.gpu_ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - gpuStart).count();
        // Read rows [y0, y1) back into the caller's f64 plane.
        {
            const auto t0 = std::chrono::steady_clock::now();
            const float* srcf = static_cast<const float*>(k.staging.mapped) + static_cast<size_t>(y0 - top) * W * 4u;
            double* dst = out + static_cast<size_t>(y0) * W * 3u;
            const size_t n = static_cast<size_t>(y1 - y0) * W;
            parallel_for(0, static_cast<int>(n), [&](int lo, int hi) {
                for (int p = lo; p < hi; ++p) {
                    const size_t q = static_cast<size_t>(p);
                    dst[q * 3 + 0] = static_cast<double>(srcf[q * 4 + 0]);
                    dst[q * 3 + 1] = static_cast<double>(srcf[q * 4 + 1]);
                    dst[q * 3 + 2] = static_cast<double>(srcf[q * 4 + 2]);
                }
            });
            d.readback_ms += std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - t0).count();
        }
        ++slices;
        if (y1 == H) {
            d.engaged = true;
            d.reason = "none";
            d.slices = slices;
            d.dispatches = dispatches;
            return true;
        }
    }
    d.slices = slices;
    d.dispatches = dispatches;
    return false;
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
        if (!ok) c.destroyHalation();
    } catch (...) {
        d.reason = "exception";
        ok = false;
    }
    return ok;
}

}  // namespace spk::gpu

#endif  // SPK_ENABLE_VULKAN
