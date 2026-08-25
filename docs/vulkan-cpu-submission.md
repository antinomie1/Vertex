# Vulkan CPU 侧提交 / 多线程录制 / 描述符事实

## 多线程录制（权威来源）
- **《Writing an efficient Vulkan renderer》**（Arseny Kapoulkine/zeux，GPU Zen 2 章节 + 博客版）: 模式 = 主线程把同 framebuffer 的 draw call 块分配给多个 worker 各自录**primary command buffer**（每块独立 BeginRenderPass），再批量 `vkQueueSubmit`；这是业界引用最多的多线程录制参考。
  来源: https://zeux.io/2020/02/27/writing-an-efficient-vulkan-renderer
- Khronos 官方性能样例专章 "Command buffer usage and multi-threaded recording"（带 CPU 计数实测方法）。https://docs.vulkan.org/samples/latest/samples/performance/command_buffer_usage/README.html
- ARM 移动最佳实践同样推荐"每核一线程并发录制"。https://developer.arm.com/community/arm-community-community-blogs/b/mobile-graphics-and-gaming-blog/posts/vulkan-mobile-best-practices-and-management
- 大 RT 用 dedicated allocation 可改善带宽表现。https://zeux.io/2020/02/27/writing-an-efficient-vulkan-renderer

## 跨设备帧转发先例
- **PrimusVK**（NVIDIA PRIME 方案）: Vulkan layer 包装一个 device、把渲染结果跨设备拷贝到另一 device 呈现——双 VkDevice 协作的生产级先例（本项目 M6 交接缝同类问题域）。
  来源: https://github.com/felixdoerre/primus_vk （架构见 nv_vulkan_wrapper.cpp / primus_vk.cpp）
- Chromium ozone 讨论: DMA-BUF 可直接导入 Vulkan，但 GL 侧导入需要 OPAQUE_FD 句柄类型——句柄类型兼容性是真实坑点。https://groups.google.com/a/chromium.org/g/ozone-reviews/c/ke0fgnjk6Bo
- 内核 dma-buf 子系统文档: 跨驱动缓冲共享+异步访问同步（fence）框架。https://docs.kernel.org/driver-api/dma-buf.html

## 待补（在途侦察兵）
- 描绘符策略基准与 RADV updateAfterBind 上限（PushDesc）
- 管线创建卡顿/GPL/fast-link（BestPractices 补充）
- 每次提交/draw 的具体微秒数（SyncFacts2 交叉验证）
