# Kotlin/JVM 实时 Vulkan 渲染事实（侦察兵 KotlinJvm，2026-08-25）

## 内存纪律（帧循环零分配可行且有先例）
- JVM 全量 GC 暂停对低延迟负载"不可接受"；ZGC 可达亚毫秒暂停（内存/CPU 开销换）。https://www.datadoghq.com/blog/understanding-java-gc ; https://www.javacodegeeks.com/2025/12/java-memory-management-understanding-heap-stack-and-beyond.html
- 帧循环标准纪律: **每帧零堆分配 + 堆外缓冲**。JEP 454 FFM（Java 22+ stable）: `Arena` 即 SegmentAllocator，堆外段分配是 API 明确优化的热点；`Arena.ofConfined/ofShared` 提供确定性作用域生命周期、零 GC 参与。https://openjdk.org/jeps/454 ; https://www.happycoders.eu/java/foreign-function-memory-api
- 陷阱: 依赖 GC 回收堆外内存会在堆满之前耗尽 native 内存。https://blog.heaphero.io/java-off-heap-memory-leak
- LWJGL `MemoryStack` 专为每帧 scratch 设计（指针碰撞分配，帧末弹出）；官方指引"Always prefer stack allocation"。https://blog.lwjgl.org/memory-management-in-lwjgl-3
- 引擎级实践（kool）: 长命 GPU 数据用池化/shared arena，每帧命令/结构体 scratch 走 MemoryStack——热循环全程无分配。

## 先例引擎
- **kool**（de.fabmax.kool）: Kotlin 多平台引擎，Desktop-JVM **Vulkan 后端标记 Fully working**（Win/Linux x64、macOS ARM+x64），另有 OpenGL/WebGPU 后端；Apache-2.0。https://github.com/kool-engine/kool
- kool 桌面栈 = LWJGL **3.4.2** 含 lwjgl-vulkan、lwjgl-vma、lwjgl-shaderc（gradle/libs.versions.toml）。→ 本项目工具链版本直接抄作业。https://github.com/kool-engine/kool/blob/main/gradle/libs.versions.toml
- kool 最低 Java 17；其 wgpu4k 后端需 Java 22（FFM stable）。Maven Central: `de.fabmax.kool:kool-core 0.19.0`。
- LWJGL 官方维护生成式 Vulkan 绑定（lwjgl3-vulkangen 项目，活跃）。https://github.com/LWJGL/lwjgl3-vulkangen

## 并发模型（关键决策依据）
- Kotlin 协程挂起廉价但调度经 Dispatcher 工作池跳跃 → 对录制 worker 引入延迟抖动；专用 OS 线程自旋更稳。
- **虚拟线程（JEP 444/491）在执行 native 下调（JNI/FFM）时仍然 pin** —— `vkQueueSubmit`/`vkAllocateCommandBuffers` 正是此类调用 → 虚拟线程不适合提交敏感循环。https://shbhmrzd.github.io/java/concurrency/virtual-threads/2026/04/25/java-virtual-threads-pinning-and-the-deadlock-problem.html ; https://www.infoq.com/news/2024/11/java-evolves-tackle-pinning
- kotlinx.coroutines #3606 记录协程叠加虚拟线程的集成摩擦。https://github.com/Kotlin/kotlinx.coroutines/issues/3606
- 结论（有源支撑）: 录制+提交 = 固定数量的**绑核平台线程**；协程只用于资产加载/帧图编排等 IO 型工作。

## JIT 预热
- 分层编译 C1/C2: 预热完成前性能低且不稳；编译抢 CPU、deopt 造成二次尖峰。缓解: 首帧呈现前预热渲染路径、`-XX:TieredStopAtLevel` 调优、杜绝循环内分配（触发 GC+deopt）。https://docs.azul.com/prime/analyzing-tuning-warmup ; https://developers.redhat.com/articles/2023/09/29/how-we-solved-hotspot-performance-puzzle
