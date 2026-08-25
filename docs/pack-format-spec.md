# 包格式权威文档定位（scout PackSpec，2026-08-25）

## 权威源
- **OptiFine**: `OptiFineDoc/doc/shaders.txt` 为唯一总纲（uniform、program、buffer、format、宏）；`shaders.properties` 文档列出全部属性键。https://github.com/sp614x/optifine/tree/master/OptiFineDoc/doc
- **Iris**: 官方文档站 https://shaders.properties/ ，仓库 https://github.com/IrisShaders/docs （内容在 `src/content/docs/current/{Reference,Guides,How To}`）；社区深潜 Iris ShaderDoc（IMS）https://github.com/IrisShaders/ShaderDoc

## Uniform 目录（行号锚定）
- OptiFine shaders.txt "Uniforms" 节 **L170–249**：约 80 个 uniform（fogShape/renderStage/bossBattle/1.17+ modelViewMatrix·projectionMatrix 等）。
- 采样器→绑定表按 program 类给出（gbuffers/shadow/composite/deferred）**L256–424**：如 depthtex0=6、noisetex=15、colortex8–15=16–23。
- → M2 描述符槽计划可直接由该表确定性生成；反射结果与其交叉校验。

## 文档覆盖缺口（兼容性风险清单）
- Iris 记载了格式大部分 + 自家扩展：setup/begin pass、custom images、SSBO、tags。
- 遗留 OptiFine 行为未文档化或标记损坏/TODO：`GAUX4FORMAT`、jpg 原始纹理变体等 → M1 需按"Iris 行为为准 + 缺失处回退 OptiFine doc"双轨实现，并在失败目录中登记。
