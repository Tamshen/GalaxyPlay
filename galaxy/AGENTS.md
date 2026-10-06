# Galaxy 适配层约定

本目录继承[根目录开发约定](../AGENTS.md)。源码包名和运行时配置键保持兼容，目录隔离不要求改类名。

- `common/src/main` 放车型、Flyme 界面、系统服务、媒体／HUD、方控和诊断组件，以及产品资源、协议与清单。
- `shared/src/main/java` 放适配策略、音视频优化组件和核心接入契约，仍由 `:shared` 编译，避免依赖宿主形成环。
- `mobile/src/main` 放 GalaxyPlay 应用入口、品牌资源与产品清单；认证继续来自外部只读目录。
- `common/`、`shared/` 的核心通过参数、接口与回调调用此目录；本目录不得新增与核心同路径同名的覆盖文件。
- 已迁出独立组件、产品资源、认证准备和偏好策略；`DiPlayBootstrap`／`AirPlayPersistence` 保留兼容入口，委托 `GalaxyStartupPolicy`／`GalaxyPreferencePolicy`，身份与旧键不迁移。产品设置、媒体绑定、后台会话、控件创建和音频路由策略已有独立入口；核心通过参数和回调接入。通用 worker／协议修复由 tools/upstream-sync 分组补丁保留，更新使用 scripts/sync-upstream-core.py 生成候选快照，校验 Galaxy 摘要、冲突和核心漂移；原工作区不自动覆盖。
- 新增或修改适配逻辑优先放本目录；核心修改必须说明接入点、生命周期与默认无适配时的行为。禁止复制整份上游宿主或解码器到本目录逃避合并。
- 日志使用 `DiagnosticSink`／`DiagnosticChannel`，每个控制器独占通道，宿主通过 `GalaxyDiagnosticSink` 接入原有脱敏日志队列；不创建另一套文件、线程或上传通道。
- 只记录组件、事件、状态、耗时和数值计数，不传身份、载荷、语音或异常原文；诊断消费失败不得改变业务行为。结束请求与资源实际释放分别记录。
- 日志默认采集，界面展示开关不决定是否留存；通道关闭后拒绝迟到记录，不通过全局可替换回调向新会话转写旧日志。
- 测试仍在 `e2e/`；构建入口先运行 `check_galaxy_layout.py` 与同步配方离线 `verify`，发现新上游同名源码须明确整合，不能静默排除核心文件。
