# Vertex

Vertex 是一个实验性的 Fabric 客户端模组，目标是在 Minecraft 原生 Vulkan / RenderPearl 渲染器上运行 OptiFine、Iris 格式的光影包。项目目前处于 `0.1.0-alpha`，主要面向兼容性开发与验证，不代表已经兼容所有现有光影包。

## 当前能力

- 读取目录或 ZIP 格式的光影包，并支持包内绝对路径与相对路径 `#include`。
- 翻译常见 GLSL 1.20/兼容配置语法、传统内建变量、uniform、sampler 和阶段接口。
- 接入地形、水体、实体、方块实体、手持物、粒子、天空、天气、阴影以及 deferred/composite/final 屏幕通道。
- 按 `dimension.properties` 和标准 `world0` / `world-1` / `world1` 目录选择维度程序，跨维度时自动重建整套管线。
- 解析 `shaders.properties` 中的渲染目标、纹理、翻转、通道启用表达式和光影选项页面。
- 按 GPU 能力为不同程序族协商运行级别；单个程序族失败时，其余程序族仍可继续工作。
- 提供渲染缩放、阴影分辨率、GPU 时间统计、回放哈希和性能基线工具。

## 环境要求

- Minecraft `26.3-snapshot-9`（当前开发基线；模组元数据允许 `26.2+`）
- Fabric Loader `0.19.3+`
- Fabric Language Kotlin `1.13.13+kotlin.2.4.10+`
- Java 25
- 支持项目所需 Vulkan 特性的显卡与驱动

版本以 [`gradle.properties`](gradle.properties) 和 [`fabric.mod.json`](src/main/resources/fabric.mod.json) 为准。快照版本升级可能会造成 RenderPearl API 或 Mixin 注入点不兼容。

## 使用

将构建产物和依赖项放入客户端的 `mods` 目录，并把光影包目录或 ZIP 文件放入游戏目录下的 `shaderpacks`。进入游戏后按 `F7` 打开 Vertex Shader Manager，可选择、关闭或重新加载光影包，也可调整渲染比例、阴影分辨率和包自带选项。

设置保存在：

```text
config/vertex-shaders.properties
```

没有选择外部光影包时，Vertex 会生成内置的最小示例包，便于确认渲染链是否正常工作。

## 构建与开发运行

项目使用 Gradle Wrapper。只编译并打包、不执行测试：

```bash
./gradlew assemble
```

启动开发客户端：

```bash
./gradlew runClient
```

可通过 Gradle 属性直接选择包和启动参数，例如：

```bash
./gradlew runClient \
  -Pvertex.pack=/absolute/path/to/pack.zip \
  -Pvertex.options=SHADOW_QUALITY=2,CLOUDS=true \
  -Pvertex.validation=true
```

常用开发属性：

| 属性 | 用途 |
| --- | --- |
| `vertex.pack` | 光影包路径；相对路径从 `shaderpacks` 解析 |
| `vertex.options` | 逗号分隔的 `名称=值` 覆盖 |
| `vertex.dimension` | 开发验证时强制使用指定维度标识的程序 |
| `vertex.backend` | 传递 Minecraft 图形后端参数 |
| `vertex.validation=true` | 启用 Vulkan Validation |
| `vertex.quickplay` | 启动后直接进入指定单人存档 |
| `vertex.autostop` | 约定秒数后自动退出开发客户端 |
| `vertex.drawMode` | 覆盖地形绘制路径 |
| `vertex.debugReadback` | 开启指定 GPU 读回诊断 |
| `vertex.perfLogFrames` | GPU 时间统计输出间隔 |
| `vertex.perfBaseline` | 性能基线文件路径 |
| `vertex.perfUpdateBaseline=true` | 写入或更新性能基线 |
| `vertex.perfThresholdPercent` | 性能回退阈值百分比 |
| `vertex.perfGate=true` | 超过性能阈值时视为失败 |

## 代码结构

- `src/main/kotlin/dev/vertex/translate`：预处理、传统 GLSL 兼容改写和 uniform 目录。
- `src/main/kotlin/dev/vertex/runtime`：能力协商、资源预算、帧状态与性能策略。
- `src/client/kotlin/dev/vertex/frontend`：光影包发现、设置持久化与语义解析。
- `src/client/kotlin/dev/vertex/render`：地形、动态物体、阴影和屏幕通道运行时。
- `src/client/kotlin/dev/vertex/ui`：包管理器与 Iris 风格的选项界面。
- `src/client/java/dev/vertex/mixin`：Minecraft / RenderPearl 渲染接缝。

## 已知限制

- 这是兼容层而非 OpenGL 驱动模拟器；依赖厂商扩展、未映射 Iris 扩展或特殊阶段的包可能需要单独适配。
- 3D sampler 会被铺平为 2D 切片纹理；3D storage image 当前只保留二维切片语义。
- 不兼容的程序族会降级或停用，并在日志中输出 `[Vertex]` 诊断；这不一定意味着整个光影包都已停用。
- 当前开发基线是 Minecraft 快照版本，尚不提供稳定版迁移保证。

## 许可证

项目元数据声明为 [GNU Lesser General Public License v3.0 only](https://www.gnu.org/licenses/lgpl-3.0.html)（SPDX：`LGPL-3.0-only`）。
