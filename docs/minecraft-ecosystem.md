# Minecraft Vulkan 生态调研（截至 2026-08，来源均为本会话实际抓取）

## 官方原生 Vulkan 后端（MC 26.2）

- MC Java 26.2 起自带原生 Vulkan 渲染后端，与 OpenGL 并存，`options.txt` 中 `preferredGraphicsBackend:"vulkan"` 或游戏内视频设置切换。
  来源: https://www.neowin.net/news/minecraft-java-edition-is-upgrading-to-vulkan-but-its-not-great-news-for-mods/ ; https://beebom.com/how-to-enable-vulkan-in-minecraft/
- 动机: OpenGL 停止演进 + macOS 弃用压力 + 将 Vibrant Visuals 带入 Java 版；官方承认对使用 GL 的 mod 冲击大。
  来源: 同上 neowin。
- 游戏内嵌 glslang 前端 + SPIR-V 交叉编译器，且 device 的 precompile 入口**接受任意着色器源码**（编译、反射、绑定重映射一站式）。管线按"身份"记忆化（不含源码文本的 key → 改源必须换身份）；未预编译的管线会拿游戏默认源去编→失效。
  来源: https://github.com/avpbynf/Vitrail-Shaders/blob/main/docs/internals/game-graphics-api.md
- **Blaze3D/GpuDevice 后端结构性限制**（对性能设计是决定性事实）:
  - 每个render pass 提交结束时插入一次**全内存屏障**（all-to-all），无逐资源屏障、无 subpass、无通道重叠手段；成本随 pass 数线性涨。
  - 所有纹理终身 GENERAL layout，无最优 layout 转换。
  - 单队列语义、编码器包装器不跨实例共享守卫、按名字符匹配的绑定+反射。
  来源: 同上 game-graphics-api.md
- 世界渲染结束点（frame graph 执行完、无打开的 pass）是最廉价挂接点（NeoForge 有公共事件，Fabric 需 mixin）；几行之后主目标深度被清空。坐标约定全程保持 OpenGL 方向，仅在 blit 到 swapchain 时翻转一次——OptiFine 包无需任何 Y 翻转。
  来源: 同上 game-graphics-api.md
- Sodium 按 pipeline namespace 过滤拦截：自建 namespace 的管线不受其 layout 拦截影响（可安全自建管线）。
  来源: 同上。

## Vitrail Shaders —— 唯一能原样加载旧 OptiFine 包的 Vulkan 引擎

- 定位: 在 26.2 原生 Vulkan 后端上加载**未修改** OptiFine 格式包；一人副业项目，实验性；LGPL-3.0，包前端部分移植自 Iris（NOTICE 记录）。
  来源: https://github.com/avpbynf/Vitrail-Shaders
- 架构: 每个 GLSL 单元在绘制前一次性重写为 Vulkan GLSL → 交给游戏内嵌编译器出 SPIR-V；帧内零翻译层。选包时翻译 chain；世界/天空程序按需在首帧翻译；换维度=全量重载。管线故意每帧只编一个（暖机期画面回落到游戏自身图像）。
  来源: docs/README.md (同仓库)
- 地形几何: 不自建网格——复用 Sodium 网格，通过少量单方法 mixin 挂接（每-pass 管线编译短路、顶点格式访问器替换、开 pass 时附加包的颜色目标、绑定 uniform/sampler、方块渲染器/流体渲染器写入点、半透明排序器顶点拷贝、阴影阶段 section 状态）。
  顶点格式加宽: 追加 block id、mid texcoord、mid block、法线+切线合并字（八面体 12+11bit+手性 bit+8bit 角度）、separateAo 第二颜色字；24–40 字节/顶点；格式=包需求并集；换包可能触发世界重建。
  光照贴图坐标约定: 顶点携带 level*16 原始值，由 gl_TextureMatrix[1] 缩放平移（1/256、1/32 半纹素中心）——不能在 prologue 里直接除。
  来源: docs/internals/terrain.md (同仓库)
- **实测性能短板（关键证据）**: Issue #161（Apple Silicon, Complementary Reimagined r5.8.1 Low, 854×480）:
  - Vitrail+MoltenVK ≈57 FPS vs Iris+OpenGL ≈87 FPS（慢 34%）
  - Instruments: GPU 大部分时间空闲；CPU→GPU 平均延迟 Vitrail ≈6.74ms vs Iris ≈2.26ms（无着色器基线 ≈3ms）
  - 结论方向: 瓶颈在提交路径/CPU 侧串行化，与 Blaze3D 每-pass 全屏障模型一致 → GPU 算力并非瓶颈，自有设备路线有真实收益空间。
  来源: https://github.com/avpbynf/Vitrail-Shaders/issues/161
- 已知限制: 部分几何族仍走游戏自身管线被全屏层合成（引擎启动日志为权威清单）；材质 ID 缺失会让分类类 pass 误读。
  来源: docs/README.md

## Beryl —— VulkanMod 生态的"内置包"

- Modrinth 描述原文: "Currently beryl has an integrated shader pack, as that allows to have full control over the rendering pipeline and to better optimize it." → **不加载外部旧包**。
- 本质是仿 Complementary/BSL 观感的固定管线替代品（社区 issue 表述: "an imitation of Complementary/BSL Shaders ... for VulkanMod users"）。
  来源: https://modrinth.com/mod/beryl ; https://github.com/modrinth/code/issues/6051

## VulkanMod —— 自建设备的先例（架构参考）

- Fabric mod，全新 Vulkan 体素渲染引擎替换默认 OpenGL 渲染器；经 LWJGL 直接建 VkInstance/device（`net.vulkanmod.vulkan.Vulkan.getRequiredExtensions` 用 `glfwGetRequiredInstanceExtensions`）。
  来源: https://github.com/xCollateral/vulkanmod ; https://github.com/NixOS/nixpkgs/issues/303765
- 依赖实证（0.5.5 日志）: `org_lwjgl_lwjgl-shaderc 3.3.3`、`org_lwjgl_lwjgl-vma 3.3.3` → JVM 内 shaderc 编译 + VMA 分配是已验证可行组合。
  来源: https://github.com/xCollateral/VulkanMod/issues/665
- 性能口碑: Hypixel 论坛用户报告比 Sodium 高 20–50%；YouTube 26.2 测试: vanilla GL≈160 / vanilla VK≈206 / Sodium GL≈280 FPS（数字随测试场景波动，仅作方向性证据）。
  来源: https://hypixel.net/threads/vulkanmod-giving-20-50-fps-compared-to-sodium.6041936 ; https://www.youtube.com/watch?v=0SGGEhi-qwI
- 与多数其它 mod 不兼容（HN 讨论）；Iris 在其上直接崩溃（Iris#1515）。
  来源: https://news.ycombinator.com/item?id=47068948 ; https://github.com/IrisShaders/Iris/issues/1515

## Sulkan —— Vulkan-first 引擎（非 OptiFine 格式）

- 官网定位: "Shader Loader: Runs external standard shader packs from zip files natively inside the engine" + "A clean, predictable API for shader pack authors"；内置 base-shader.zip 起步包（shadows/terrain/water/AO/clouds/atmosphere/bloom/exposure/FXAA + include helpers）→ 自有包方言 + 固定特性管线，**非 OptiFine 格式兼容**。
- 强制 Vulkan 后端否则 fail-fast；目标 26.2 Snapshot 3 renderer API；Fabric Loader ≥0.19.2、Fabric API ≥0.146.1+26.2、Java 25；支持游戏内热切换包与内置 profiling。
- 许可 GPLv3（Vitrail why.md 明确因许可未复用其代码；我方同样一行不看）。
  来源: https://sulkan.org ; https://sulkan.org/docs ; https://sulkan.org/features

## Aperture —— Iris 官方后继（全新 Slang 格式）

- IrisShaders 官推（2026-02-19）: "Iris will be discontinued. I have been working on a brand new shader mod for the past two years. The new mod is named Aperture, currently in private beta. It does not support old packs ... just started on the Vulkan rewrite last month."
  来源: https://x.com/IrisShaders/status/2024298162564059576
- 官方迁移教程已上线: https://shaders.properties/aperture/migration （新格式含 struct VertexData 等）；示例包仓库: https://github.com/IrisShaders/Aperture-Example-Pack
- Vitrail why.md 亦确认: Aperture 包用 Slang 写，是对 OptiFine 格式的 clean break。
  来源: https://github.com/avpbynf/Vitrail-Shaders/blob/main/docs/why.md

## 其它

- Zink(GL-on-VK)/ANGLE 类方案: 结构上在 GL 之上再叠翻译层，不可能超过原生 GL → 与"大幅超越 OpenGL"目标相悖，排除。
- Iris 对 OptiFine 包生态是事实规范（"packs are written against Iris, so Iris is the authority"，Vitrail 语）。
  来源: Vitrail why.md
