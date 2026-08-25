# 工具链 / 描述符 / 外部内存 事实

## 着色器编译链（JVM 内）
- shaderc = 官方运行时 GLSL/HLSL→SPIR-V 库（glslang 前端封装），支持 target-env vulkan1.3、运行时源码字符串编译。https://github.com/google/shaderc
- glslang 是 GLSL→SPIR-V 参考编译器；Vulkan 目标要求 Vulkan-GLSL 方言（无 attribute/varying/gl_FragData/ftransform 内建）→ 旧包必须先文本重写为现代 GLSL 再喂编译器。Vitrail 已验证此流程（"rewrites every program into Vulkan GLSL ... before it ever draws"）。https://docs.vulkan.org/guide/latest/hlsl.html ; https://github.com/avpbynf/Vitrail-Shaders
- LWJGL 捆绑三件套已被 VulkanMod 生产验证（0.6.x, LWJGL 3.3.3）: lwjgl-vma、lwjgl-shaderc、lwjgl-spvc（含全平台 natives + MoltenVK）。https://raw.githubusercontent.com/xCollateral/VulkanMod/dev/build.gradle
- kool 引擎用 LWJGL 3.4.2 同组合（Kotlin 先例）。https://github.com/kool-engine/kool/blob/main/gradle/libs.versions.toml
- SPIR-V 后处理: LunarG《SPIR-V Shader Size Reduction Using spirv-opt》（死代码消除/尺寸缩减白皮书）；re-spirv 轻量快速优化器备选。https://www.lunarg.com/wp-content/uploads/2017/12/SPIR-V-Shader-Size-Reduction-Using-spirv-opt_v1.1-1.pdf ; https://github.com/renderbag/re-spirv
- 对机器生成的 SPIR-V，spirv-opt 的常量折叠/死代码清除有确定价值；性能级收益证据弱于正确性/尺寸收益——列为可选 pass。

## 描述符策略
- NVIDIA 官方 Advanced API Performance: **优先 bindless 设计** —— 无界数组描述符指向覆盖整帧所需纹理/缓冲的大描述符表。https://developer.nvidia.com/blog/advanced-api-performance-descriptors
- descriptor indexing（bindless）已入 VK 1.2 核心。https://docs.vulkan.org/samples/latest/samples/extensions/descriptor_indexing/README.html
- VK_EXT_descriptor_buffer 为新路线（描述符即缓冲），有实践复盘文章。https://medium.com/@williscool/vulkan-descriptor-buffers-redux-603ea2be3979
- NVIDIA Dos/Don'ts 配套规则: push constant 用于每-draw 常量；动态 UBO 用于每-draw 缓冲切换；管线布局中 set 数与 set 内描述符数尽量少、绑定不留空洞。
- Vertex 决策: v1 = update-after-bind 大采样器数组（RADV 上限充裕，具体 maxPerStageDescriptorUpdateAfterBindSamplers 运行时查询）；uniform heap 单 UBO + push constants。

## 跨设备交接（M6）
- `VK_EXT_external_memory_dma_buf` + `VK_KHR_external_memory_fd` 组合官方定位："足以在 dma_buf 与 VkDeviceMemory 间导入导出"。https://registry.khronos.org/VulkanSC/specs/1.0-extensions/man/html/VK_EXT_external_memory_dma_buf.html
- RADV（Mesa）在 Linux 上完整实现 DMA-BUF 导入导出（Chromium/VAAPI 生态依赖同路径）；句柄类型坑：GL 导入需 OPAQUE_FD，Vulkan 侧用 DMA-BUF fd。https://groups.google.com/a/chromium.org/g/ozone-reviews/c/ke0fgnjk6Bo ; https://docs.mesa3d.org/drivers/radv.html
- 双 device 协作生产先例: PrimusVK（渲染在一个 device、结果跨设备拷贝呈现）。https://github.com/felixdoerre/primus_vk
- 同步跨设备: kernel dma-buf fence 框架 / external semaphore fd。https://docs.kernel.org/driver-api/dma-buf.html

## 帧率/呈现补充
- GCN/RDNA 可由硬件 compute 队列承担 present（DOOM 案例，vkBasalt issue 记录输入延迟影响）→ present 队列选择是可调项。https://github.com/DadSchoorse/vkBasalt/issues/34
