/*
 * Spektrafilm for Android — GPU (Vulkan compute) fast-path implementation. GPLv3.
 * Film modeling powered by spektrafilm.
 *
 * Compiled only when SPK_ENABLE_VULKAN is defined (see gpu/vulkan_compute.h and
 * CMakeLists.txt). Headless compute: instance -> physical device + compute queue ->
 * host-visible storage buffer -> compute pipeline (embedded SPIR-V) -> dispatch ->
 * read back. Every failure path returns false so the caller falls back to the CPU.
 */
#include "gpu/vulkan_compute.h"

#ifndef SPK_ENABLE_VULKAN

namespace spk::gpu {
bool available() { return false; }
bool cctf_encode_srgb(float*, size_t) { return false; }
bool scan_spectral(const float*, float*, uint32_t, const float*, const float*, const float*) { return false; }
}  // namespace spk::gpu

#else

#include <vulkan/vulkan.h>

#include <cstring>
#include <vector>

#include "gpu/cctf_encode_spv.h"
#include "gpu/scan_spectral_spv.h"

namespace spk::gpu {
namespace {

// One host-visible storage buffer + its memory. Returned handles are owned by caller.
struct Buf { VkBuffer buf = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE; };

struct Ctx {
    VkInstance instance = VK_NULL_HANDLE;
    VkPhysicalDevice phys = VK_NULL_HANDLE;
    VkDevice device = VK_NULL_HANDLE;
    VkQueue queue = VK_NULL_HANDLE;
    uint32_t queueFamily = 0;
    bool ok = false;

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

    ~Ctx() {
        if (device) vkDestroyDevice(device, nullptr);
        if (instance) vkDestroyInstance(instance, nullptr);
    }

    int findMemType(uint32_t bits, VkMemoryPropertyFlags want) {
        VkPhysicalDeviceMemoryProperties mp;
        vkGetPhysicalDeviceMemoryProperties(phys, &mp);
        for (uint32_t i = 0; i < mp.memoryTypeCount; ++i)
            if ((bits & (1u << i)) && (mp.memoryTypes[i].propertyFlags & want) == want) return (int)i;
        return -1;
    }
};

// One lazily-initialised context for the process.
Ctx& ctx() { static Ctx c; static bool tried = false; if (!tried) { tried = true; c.init(); } return c; }

}  // namespace

bool available() { return ctx().ok; }

bool cctf_encode_srgb(float* data, size_t n) {
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

struct ScanPush { uint32_t npix; float m[12]; };  // std430 push constant (matches shader)

bool scan_spectral(const float* cmy, float* rgb, uint32_t npix,
                   const float* dye, const float* icmf, const float* xyz2rgb) {
    Ctx& c = ctx();
    if (!c.ok || !cmy || !rgb || npix == 0 || !dye || !icmf || !xyz2rgb) return false;
    const int NB = 81;
    const VkDeviceSize imgBytes = static_cast<VkDeviceSize>(npix) * 3u * sizeof(float);
    const VkDeviceSize tblBytes = static_cast<VkDeviceSize>(NB) * 3u * sizeof(float);
    bool ok = false;

    Buf in{}, out{}, dyeB{}, cmfB{};
    VkShaderModule shader = VK_NULL_HANDLE;
    VkDescriptorSetLayout dsl = VK_NULL_HANDLE;
    VkPipelineLayout pl = VK_NULL_HANDLE;
    VkPipeline pipe = VK_NULL_HANDLE;
    VkDescriptorPool dpool = VK_NULL_HANDLE;
    VkCommandPool cpool = VK_NULL_HANDLE;

    auto makeBuf = [&](Buf& b, VkDeviceSize bytes, const void* src) -> bool {
        VkBufferCreateInfo bci{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bci.size = bytes;
        bci.usage = VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;
        bci.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        if (vkCreateBuffer(c.device, &bci, nullptr, &b.buf) != VK_SUCCESS) return false;
        VkMemoryRequirements req;
        vkGetBufferMemoryRequirements(c.device, b.buf, &req);
        int mt = c.findMemType(req.memoryTypeBits,
                               VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);
        if (mt < 0) return false;
        VkMemoryAllocateInfo mai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        mai.allocationSize = req.size;
        mai.memoryTypeIndex = (uint32_t)mt;
        if (vkAllocateMemory(c.device, &mai, nullptr, &b.mem) != VK_SUCCESS) return false;
        vkBindBufferMemory(c.device, b.buf, b.mem, 0);
        if (src) {
            void* mapped = nullptr;
            if (vkMapMemory(c.device, b.mem, 0, bytes, 0, &mapped) != VK_SUCCESS) return false;
            std::memcpy(mapped, src, bytes);
            vkUnmapMemory(c.device, b.mem);
        }
        return true;
    };

    do {
        if (!makeBuf(in, imgBytes, cmy)) break;     // binding 0 (in)
        if (!makeBuf(out, imgBytes, nullptr)) break; // binding 1 (out)
        if (!makeBuf(dyeB, tblBytes, dye)) break;   // binding 2
        if (!makeBuf(cmfB, tblBytes, icmf)) break;  // binding 3

        VkShaderModuleCreateInfo smci{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
        smci.codeSize = sizeof(kScanSpectralSpv);
        smci.pCode = kScanSpectralSpv;
        if (vkCreateShaderModule(c.device, &smci, nullptr, &shader) != VK_SUCCESS) break;

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
        if (vkCreateDescriptorSetLayout(c.device, &dlci, nullptr, &dsl) != VK_SUCCESS) break;

        VkPushConstantRange pcr{VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(ScanPush)};
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

        VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 4};
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

        VkBuffer bufs[4] = {in.buf, out.buf, dyeB.buf, cmfB.buf};
        VkDeviceSize sizes[4] = {imgBytes, imgBytes, tblBytes, tblBytes};
        VkDescriptorBufferInfo dbi[4];
        VkWriteDescriptorSet wds[4];
        for (uint32_t i = 0; i < 4; ++i) {
            dbi[i] = VkDescriptorBufferInfo{bufs[i], 0, sizes[i]};
            wds[i] = VkWriteDescriptorSet{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
            wds[i].dstSet = dset;
            wds[i].dstBinding = i;
            wds[i].descriptorCount = 1;
            wds[i].descriptorType = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            wds[i].pBufferInfo = &dbi[i];
        }
        vkUpdateDescriptorSets(c.device, 4, wds, 0, nullptr);

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
        ScanPush push{};
        push.npix = npix;
        // Pack the row-major 3x3 XYZ->RGB into the shader's 3x4 layout (m[0..2],[4..6],[8..10]).
        push.m[0] = xyz2rgb[0]; push.m[1] = xyz2rgb[1]; push.m[2]  = xyz2rgb[2]; push.m[3]  = 0.0f;
        push.m[4] = xyz2rgb[3]; push.m[5] = xyz2rgb[4]; push.m[6]  = xyz2rgb[5]; push.m[7]  = 0.0f;
        push.m[8] = xyz2rgb[6]; push.m[9] = xyz2rgb[7]; push.m[10] = xyz2rgb[8]; push.m[11] = 0.0f;
        vkCmdPushConstants(cmd, pl, VK_SHADER_STAGE_COMPUTE_BIT, 0, sizeof(ScanPush), &push);
        vkCmdDispatch(cmd, (npix + 63u) / 64u, 1, 1);
        vkEndCommandBuffer(cmd);

        VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
        si.commandBufferCount = 1;
        si.pCommandBuffers = &cmd;
        if (vkQueueSubmit(c.queue, 1, &si, VK_NULL_HANDLE) != VK_SUCCESS) break;
        vkQueueWaitIdle(c.queue);

        void* mapped = nullptr;
        if (vkMapMemory(c.device, out.mem, 0, imgBytes, 0, &mapped) != VK_SUCCESS) break;
        std::memcpy(rgb, mapped, imgBytes);
        vkUnmapMemory(c.device, out.mem);
        ok = true;
    } while (false);

    if (cpool) vkDestroyCommandPool(c.device, cpool, nullptr);
    if (dpool) vkDestroyDescriptorPool(c.device, dpool, nullptr);
    if (pipe) vkDestroyPipeline(c.device, pipe, nullptr);
    if (pl) vkDestroyPipelineLayout(c.device, pl, nullptr);
    if (dsl) vkDestroyDescriptorSetLayout(c.device, dsl, nullptr);
    if (shader) vkDestroyShaderModule(c.device, shader, nullptr);
    for (Buf* b : {&in, &out, &dyeB, &cmfB}) {
        if (b->mem) vkFreeMemory(c.device, b->mem, nullptr);
        if (b->buf) vkDestroyBuffer(c.device, b->buf, nullptr);
    }
    return ok;
}

}  // namespace spk::gpu

#endif  // SPK_ENABLE_VULKAN
