# 阴影贴图优化事实

## 缓存/复用
- Leadwerks 引擎实践: 标记静态几何 + 动态角色分离后，可缓存静态几何的阴影贴图（10 万多边形房间+点光源案例）。https://www.leadwerks.com/community/blogs/entry/2248-shadow-caching
- 社区共识（GraphicsProgramming）: 光源不动时可缓存静态几何阴影图，动态物体单独重画叠加。https://www.reddit.com/r/GraphicsProgramming/comments/1cawxd5/shadow_performance_at_sunrisesunset
- UE5 Virtual Shadow Maps: 页缓存 + **逐图元失效粒度**（Shadow Cache Invalidation Behavior 可阻止静态 World Geometry 触发失效）；Fortnite BR 技术文详列 foliage/grass/water 等缓存考量。https://dev.epicgames.com/documentation/unreal-engine/virtual-shadow-maps-in-unreal-engine ; https://www.unrealengine.com/tech-blog/virtual-shadow-maps-in-fortnite-battle-royale-chapter-4
- MJP 经典综述: 相机移动导致光栅化采样点变化 → 静态几何阴影边爬行问题及各类缓解。https://mynameismjp.wordpress.com/2013/09/10/shadow-maps

## 硬件深度比较采样
- sampler2DShadow 深度比较滤波在硬件内完成（Imagination 文档：含阴影采样的双线性始终硬件加速）。https://docs.imgtec.com/performance-guides/graphics-recommendations/html/topics/texture-sampling.html
- PCF 手动多次采样是基线实现（LearnOpenGL）；硬件 compare 模式自 GL 1.4 起标准，浮点阴影格式常属过度配置（GPU Gems 2）。https://learnopengl.com/Advanced-Lighting/Shadows/Shadow-Mapping ; https://developer.nvidia.com/gpugems/gpugems2/part-ii-shading-lighting-and-shadows/chapter-17-efficient-soft-edged-shadows-using

## 对 Vertex 的映射（初稿）
- Minecraft 太阳方位逐帧缓变 → 方案：静态地形阴影图按"太阳角度量化步进 + section 失效集"缓存复用；实体/叶子等动态层每帧小图重画。VulkanMod 的 SectionGraph/FrustumOctree 剔除结构可直接喂给阴影视锥行走。
- 翻译层必须把包的 sampler2DShadow 用法映射为 VK compare_op 采样器（depth-compare 硬件单元），而非降级为手动 PCF。
