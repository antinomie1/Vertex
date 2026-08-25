# Vulkan 带宽 / DCC / Layout 事实（AMD RDNA 向）

## Delta Color Compression（DCC）
- DCC 是无损、块级、利用颜色目标数据连贯性的域专用压缩；GCN 1.2+ 独显/APU 全启用；**渲染目标自动经过压缩器，无需特殊设置**——"if the target surface is compressed, the rendering just goes through the compressor"。
  来源: https://gpuopen.com/learn/dcc-overview （文内注明结论对 RDNA/RDNA2 仍然成立，仅解压时机变化）
- 渲染目标带宽是 GPU 最稀缺资源的大头（高分辨率+多缓冲）；DCC 正是为读写 render target 设计。
  来源: 同上
- GCN3 起 RT 支持 DCC；普通只读非块压缩纹理（RGBA8、RGBA16F 等）也可用 DCC。
  来源: https://gpuopen.com/download/VulkanFastPaths.pdf （"Vulkan Fast Paths" 官方路径手册）
- RDNA4 Hot Chips 2025: DCC 只是压缩形式之一，压缩/解压发生在 L2 前端。
  来源: https://chipsandcheese.com/p/amds-rdna4-gpu-architecture-at-hot
- **对 Vertex 方案的含义**: colortex 链保持"可压缩友好"的 usage/layout 路径（避免触发全量解压的用法，如无谓的 GENERAL layout 往返）直接省带宽；Blaze3D 的终身 GENERAL layout 恰是反模式。

## Image Layout / 屏障认知纠偏
- GENERAL = 硬件布局允许任意用途的形态；RasterGrid 2026-03 长文系统澄清屏障与 layout 迁移的动机与真实成本（业界普遍误解重灾区）。
  来源: https://www.rastergrid.com/blog/gpu-tech/2026/03/vulkan-memory-barriers-and-image-layouts-explained
- Khronos 官方性能样例专门量化 layout transition 成本。https://docs.vulkan.org/samples/latest/samples/performance/layout_transitions/README.html
- 大型 RT 使用 dedicated allocation 可因读写字带宽获益（驱动/硬件相关）。https://zeux.io/2020/02/27/writing-an-efficient-vulkan-renderer
- Granite 作者 80/20 法则: Vulkan 里大量机会在于避免冗余操作而非炫技优化。https://themaister.net/blog/2019/04

## 待补（侦察兵在途）
- subpass/input attachment 在桌面独显上的实测收益（SyncFacts2）
- loadOp/storeOp CLEAR≈免费 的官方出处（BestPractices/SyncFacts2）
