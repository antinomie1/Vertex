# 开发环境实录（2026-08-26 建立时的关键事实与坑）

## 已锚定版本（全部经 maven/manifest 实证）
| 组件 | 版本 | 来源 |
|---|---|---|
| Minecraft | **26.2**（release；26.3-snapshot-10 为最新快照） | piston-meta manifest |
| Fabric Loader | 0.19.3 | maven.fabricmc.net |
| Fabric API | 0.158.0+26.2 | 同上 |
| fabric-language-kotlin | 1.13.13+kotlin.2.4.10 | 同上 |
| Loom | **1.17-SNAPSHOT**，插件 id 变为 `net.fabricmc.fabric-loom` | fabric-example-mod@26.2 模板 |
| Gradle | 9.7.1（wrapper 同版） | services.gradle.org/current |
| JDK | Temurin 25.0.3（系统已有） | /usr/lib/jvm |

## 无混淆纪元的关键事实（26.1+）
- piston-meta 版本 JSON 的 downloads **不再含 client_mappings** → `loom.officialMojangMappings()` 直接报 "Failed to find official mojang mappings"；Yarn 已退役（fabricmc.net/2025/10/31/obfuscation.html）。
- 正确姿势：**完全不声明 mappings**；依赖用裸 `implementation`（无 remap）；模板见 fabric-example-mod 分支名 `26.2`。
- Mixin refmap 机制随之失效/不需要——mixin 直接引用官方运行时名。

## 模板约定（fabric-example-mod@26.2）
- 插件 id：`net.fabricmc.fabric-loom`；`loom { splitEnvironmentSourceSets(); mods { ... } }`
- 双源集：src/main + src/client；两个 mixins json（client 者以 `{config, environment:"client"}` 对象形式登记）
- `minecraft": "~26.2"`、`"java": ">=25"`、compatibilityLevel JAVA_25
- Kotlin DSL 注意：动态源集用 `sourceSets["main"]` 字符串索引；split 后 processResources 用 `tasks.withType<ProcessResources>()`

## 环境坑位备忘
1. **Mixin 禁止用 Kotlin 文件写**（Fabric Wiki 明令）——Vertex 的全部 mixin 用 Java，经 @JvmStatic/Companion 调 Kotlin。
2. 本机 git 强制 GPG 签名但密钥不在 → 提交用 `-c commit.gpgsign=false`。
3. 慢网下 wrapper 二次下载发行包会 Premature EOF → 预种缓存：`~/.gradle/wrapper/dists/gradle-9.7.1-bin/<md5(url)转36>/` 放 zip+解压+.ok 标记。
4. GitHub codeload/api 在本出口不稳：tarball 用 curl -C - 续传或 jsDelivr（`cdn.jsdelivr.net/gh/<repo>@<branch>/<path>` + `data.jsdelivr.com/v1/packages/gh/<repo>@<branch>` 文件树）替代。
5. FLK 入口点：fabric.mod.json 中 `{"adapter":"kotlin","value":"dev.vertex.Vertex"}`；Kotlin object 实现 ClientModInitializer。

## 当前状态
- `./gradlew build` ✅ BUILD SUCCESSFUL → build/libs/vertex-0.1.0-alpha.jar
- 下一步 = G0 尖峰（DESIGN.md §10）：设备创建点注入 mixin（Java）+ 自有 command stream 三角形直写主目标。

## G0 切片 1 新事实（2026-08-26）
1. **WorldRenderEvents 已死**：无混淆纪元 fabric-rendering-v1(25.3.x) 改为 `net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents`，事件族 START_MAIN/AFTER_OPAQUE_TERRAIN/.../END_MAIN/BEGIN_TRANSLUCENT 等——END_MAIN 即 Vitrail 式"世界后、无打开 pass"缝，且是官方事件（Fabric 侧无需 mixin）。
2. fabric-api 聚合 jar 是零类清单壳；真实类在模块构件（fabric-rendering-v1 等），经聚合 POM 传递可达，client 源集需显式 `clientImplementation` 覆盖。
3. MC 26.2 捆绑 **LWJGL 3.4.1**：org.lwjgl.vulkan 直接可用，vkCreateDevice 等静态在 VK10；Kotlin 里与自身顶层函数偶发解析歧义 → 全限定调用最稳。
4. Window.handle 在 Mojmap 下为私有字段——干脆不取窗口句柄（G0 无 surface 需求）。
