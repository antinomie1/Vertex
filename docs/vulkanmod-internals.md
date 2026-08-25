# VulkanMod 内部架构实录（scout VkmodInternals，dev 分支 @ MC 1.21.11 / Java 21 / LWJGL 3.3.3）

> 侦察兵注: 沙箱内 tvly CLI 不可用（child_process EPERM），改走 api.github.com/raw.githubusercontent.com 直读——与 /tree 页面同源。

## 包结构（src/main/java/net/vulkanmod）
`Initializer`、`config/`、`gl/`（VkGlBuffer/Framebuffer/Program/Shader/Texture——**GL-over-Vulkan 兼容垫片**）、`interfaces/`（mixin 访问器）、`mixin/`、`render/`、`vulkan/`。

## 设备创建（vulkan/device/DeviceManager）
- 枚举物理设备→适配性判定（队列族齐全+扩展齐+swapchain 格式非空）→配置索引或自动选（独显>集显）→ `vkCreateDevice` **VK_API_VERSION_1_2**
- 开启特性: shaderDrawParameters, hostQueryReset, samplerAnisotropy, logicOp, **multiDrawIndirect**, wideLines, samplerFilterMinmax; pNext 链 **KHR_dynamic_rendering**
- 扩展: 必需 = {VK_KHR_dynamic_rendering, VK_KHR_synchronization2, VK_KHR_swapchain}; MoltenVK 加 portability_subset; 调试加 AMD_buffer_marker / NV_device_diagnostic_checkpoints; **禁用 EXT_full_screen_exclusive**
- 实例化 Graphics/Present/Transfer/Compute 四类队列，各自 CommandPool
  来源: https://raw.githubusercontent.com/xCollateral/VulkanMod/dev/src/main/java/net/vulkanmod/vulkan/device/DeviceManager.java

## 队列与录制线程模型
- `Queue.findQueueFamilies`: graphics+present（compute 族可 present 回退）；有专用 transfer/compute 则启用
- **`beginCommands()/submitCommands()` 是 `synchronized` —— 图形提交单线程**（这正是 Vertex 可超越的点：多线程 secondary 录制）
  来源: https://raw.githubusercontent.com/xCollateral/VulkanMod/dev/src/main/java/net/vulkanmod/vulkan/queue/Queue.java
- 网格构建并行: `TaskDispatcher` 起 n=max((核数−1)/2,1) 个 "Builder-i" 线程（wait/notify、高/低优先级双队列），每线程私有 BuilderResources；产出 CPU 端 UploadBuffer 由主线程上传（GPU 拷贝走 TransferQueue/staging）
  来源: .../chunk/build/task/TaskDispatcher.java ; .../build/thread/BuilderResources.java

## Swapchain（framebuffer/SwapChain）
- minImageCount+1（夹到上限）；usage COLOR_ATTACHMENT|SAMPLED（深度也 SAMPLED）
- 格式偏好 R8G8B8A8_UNORM→B8G8R8A8_UNORM + SRGB_NONLINEAR；vsync=FIFO 否则 Immediate/Mailbox（显式处理 Wayland tearing）；graphics≠present 家族才 CONCURRENT；每 RenderPass 缓存 VkFramebuffer 数组按 acquired image 索引；零尺寸最小化拆除路径+完整 recreate()
  来源: .../framebuffer/SwapChain.java

## 区块渲染（render/chunk/）
- WorldRenderer、ChunkAreaManager→ChunkArea（AreaBuffer 按 area 子分配共享 DrawBuffers）、RenderSection、SectionGraph 遍历、FrustumOctree/VFrustum 剔除、UploadManager
- 自定义紧凑顶点格式（TerrainBufferBuilder、format/I32_SNorm）+ **IndirectBuffer 支持**；网格化用内嵌 Fabric Renderer API
  来源: api.github.com/repos/xCollateral/VulkanMod/git/trees/dev?recursive=1

## Uniform 处理（shader/Uniforms + layout/）
- 静态 fastutil map：名字→按类型的 Supplier（mat4/vec4/…/vec1i），启动时从 VRenderSystem 注入（ModelViewMat/ProjMat/MVP/fog/ScreenSize/Light0_Direction…）
- layout/ 类把 supplier 输出打进 mapped UBO 内存或 push constant（descriptor/UBO、ManualUBO、PushConstants、AlignedStruct）——与 Vertex 的"uniform heap"设计同构，可借鉴其对齐工具 AlignedStruct
  来源: .../shader/Uniforms.java

## LWJGL 工具链（build.gradle）
- LWJGL 3.3.3 固定版本；捆绑 lwjgl-vulkan + **lwjgl-vma、lwjgl-shaderc、lwjgl-spvc**（Win/Linux/mac/mac-arm64 natives + MoltenVK runtime）
- 即：VMA 分配（vmaCreateAllocator）、shaderc 运行时 GLSL→SPIR-V、**spvc 反射**三件套在 JVM 内已被生产验证
  来源: https://raw.githubusercontent.com/xCollateral/VulkanMod/dev/build.gradle ; gradle.properties（0.6.8+1.21.11-dev）
