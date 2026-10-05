# 测试代码检阅与运行

[项目入口](../README.md) · [开发与验证](../docs/开发与验证.md)

全部自动化测试代码集中到本目录，按所属模块和测试类型分类；原包名、断言和依赖保留，通过模块 Gradle 的 sourceSets 映射执行。目录名采用 e2e，现有用例的类型仍是 JUnit/Robolectric 单元及组件回归，不将它们表述为真实 iPhone 的端到端验收。

## 目录与检阅顺序

```text
e2e/
├── README.md
├── checks/check_public_tree.py            # CI 公开源码凭据检查
├── device/flyme_ui_smoke.py                # 已安装当前 APK 的 L7 AVD 交互检查
├── common/androidTest/java/com/shilapi/xcertplay/vendor/ # Android VM 薄子类最小验证
├── common/test/java/com/shilapi/xcertplay/   # 宿主、设置、诊断与 L7 隔离
└── shared/
    ├── test/java/com/shilapi/xcertplay/     # 协议、传输、认证与媒体回归
    ├── debug/java/com/shilapi/xcertplay/mfi/LocalMfiProbe.kt
    └── runtime/assets/navigation_test.pcm
```

| 优先检阅 | 覆盖内容 |
| --- | --- |
| [common 测试](common/test/java/com/shilapi/xcertplay/) | L7 认证来源/车型隔离、悬浮日志与脱敏、报告导出、设置/语言与热点迁移 |
| [shared/media](shared/test/java/com/shilapi/xcertplay/media/) | 视频队列/帧龄、硬解筛选、Surface 生命周期、裁切触控、音频路由/焦点/麦克风摘要 |
| [shared/orchestration](shared/test/java/com/shilapi/xcertplay/orchestration/) | 无线交接、手动热点、认证目标、会话证明与阶段计时 |
| [shared/airplay](shared/test/java/com/shilapi/xcertplay/airplay/) | 显示协商、HID/媒体、音频隔离、流生命周期与时序 |
| [shared/mfi](shared/test/java/com/shilapi/xcertplay/mfi/) | 本地/远程/硬件认证协议、文字材料校验与替换恢复 |
| [shared/network](shared/test/java/com/shilapi/xcertplay/network/) / [transport](shared/test/java/com/shilapi/xcertplay/transport/) / [iap2](shared/test/java/com/shilapi/xcertplay/iap2/) | 网络、USB/I²C、封包、控制超时与 iAP2 |
| [shared/adb](shared/test/java/com/shilapi/xcertplay/adb/) / [hud](shared/test/java/com/shilapi/xcertplay/hud/) | 保留的上游核心回归及车辆隔离基础；这些源码不代表 L7 启用其他车型功能 |

[AppLocaleTest](common/test/java/com/shilapi/xcertplay/AppLocaleTest.kt) 包含 Android 10 中文 Context 的日间→夜间→日间回归，检查同一个长期持有的 Context 跟随系统主题且保留中文。

[L7UiDensityTest](common/test/java/com/shilapi/xcertplay/L7UiDensityTest.kt) 检查默认中档、自定义保存、非法输入保护、窗口 dp 换算与系统/投屏配置隔离。

[L7AgreementTest](common/test/java/com/shilapi/xcertplay/L7AgreementTest.kt) 检查默认未同意、正文摘要失配需重签、撤回保留连接配置、完整协议资源、文末等待 4999/5000 毫秒边界、主动勾选、停止期间禁止确认、后台返回重计时及布局/主题刷新保持倒计时。

设置双栏检查：`python3 e2e/device/l7_floating_navigation_smoke.py --adb ../tools/scripts/adb.sh --settings-only --output-dir build/previews/settings-columns`。仅在已同意协议的 AVD 运行，检查右侧正文滚动、左栏及 Header 固定、昼夜选中态、两级返回、首页悬浮菜单恢复和按钮坐标一致；不建立连接，结束留在设置。

桌面菜单检查：`python3 e2e/device/l7_desktop_menu_smoke.py --adb ../tools/scripts/adb.sh`。仅在已同意协议的 AVD 运行，授予本应用的模拟器悬浮权限，检查前后台互斥、拖动、四项菜单与当前页选中态、返回同一等待宿主、设置开关以及退出取消/确认清理。完成后应用退出；截图在 `build/previews/desktop-menu/`。Android 11 的默认 UI 转储不包含非焦点悬浮窗，桌面操作按系统报告的窗口边界点击，桌面选中态需目视复核截图；应用内选中态由无障碍节点断言。

应用界面 DPI 检查：`python3 e2e/device/l7_ui_density_smoke.py --adb ../tools/scripts/adb.sh`，仅在已同意协议的 AVD 运行。检查三档实际缩放、自定义与非法输入、重启保存、系统 DPI 不变，以及 USB 等待期间切换后宿主和服务保留；完成后恢复中档。截图在 `build/previews/ui-density/`。

协议设备检查：`python3 e2e/device/l7_agreement_smoke.py --adb ../tools/scripts/adb.sh`。仅允许 AVD，覆盖首次门禁、拒绝退出、USB 直达拦截、模拟阅读和主动确认、重启保持、查看/取消撤回保持等待服务、确认撤回停止服务及再次拦截。脚本会模拟同意并最终撤回，保留其他配置；不代实车用户确认协议。其他界面脚本应在 AVD 已完成协议确认后运行，不自动跳过协议。

追加 `--confirmation-only` 仅检查阅读确认、设置协议入口和撤回，不建立 USB 等待会话；`--output-dir` 指定本轮截图目录。设备检查验证实际可操作状态，精确五秒边界由组件测试验证。

部分用例使用本机回环 HTTP/TCP/UDP、临时目录与运行时生成的身份，覆盖传输及释放行为，不依赖真实认证文件。阅读时关注触发条件、观察结果和资源清理；L7 功能先看上述前两行，再按实际修改追踪相关核心回归。

## 上游核心回归

有线启动专项：`L7VpnConsentTest` 覆盖 VPN 已准备、缺少授权页面、服务／启动拒绝、其他调用异常、用户拒绝／取消、重复请求和销毁后的迟到回调；`L7WiredJournalTest` 覆盖关闭自动连接时的崩溃摘要、两次尝试、退出分类、旧尝试隔离、容量淘汰、主动结束及清空；`L7WiredUploadTest` 验证媒体／探测记录挤满预算时仍保留两次失败摘要与退出诊断，不包含异常消息。

`python3 e2e/device/wired_vpn_smoke.py` 仅允许已同意协议的中文 AVD 和内置认证测试 APK。临时禁用模拟器 VPN 授权页面并恢复，核对原因提示、三个操作入口、进程存活、手动重试、两次失败记录和返回设置；自动连接、无线偏好、独立诊断、VPN AppOp 与相关运行时权限在结束时恢复。不上传或导出真实认证材料，不连接真实手机。截图位于忽略目录 `build/previews/wired-vpn/`。

| 范围 | 主要用例与检阅点 |
| --- | --- |
| 会话 / 输入 | `AirPlayControlDiagnosticsTest`、`AirPlayHidInputSemanticsTest`、`CarPlayTouchMapperTest` 检查控制诊断、旋钮语义与触控映射；共享核心按官方 0.2.11 迁入，产品仍使用 L7 单路显示 |
| L7 隔离 | `L7VehicleIsolationTest` 与车辆字段存储用例检查旧偏好、探测缓存及进程重启不能启用其他车型功能；底层字段解析继续独立回归 |
| P2P 兼容 | `P2pConfigBuildDiagnosticsTest`、`P2pStartupRecoveryTest` 检查共享核心的配置和有限恢复；不改变 L7 默认 MANUAL 热点方式 |
| USB / 网络 | `UsbMuxFrameBufferTest`、`UsbMuxIssue100RegressionTest` 覆盖分片及连续帧；`CarPlayVpnScopeTest` 检查限定应用失败时清理；`AirPlayPortSelectorTest` 检查冲突回退与套接字释放；`ManualHotspotConfigTest` 检查有线无需热点 |
| 窗口 / 定位 | `CarPlayHostDisplaySizeTest` 使用 Mockito 隔离真实控制器，检查缩窗、旋转、拆除期间的最新尺寸及权限；`Iap2WirelessLinkRoleTest`、定位上报用例检查链路订阅隔离及缺失方向 |
| 诊断 / 释放 | `BoundedDiagnosticWriterTest`、`AsyncDiagnosticLogTest` 检查有界队列与所属日志；`ProcessExitDiagnosticsTest` 检查本应用退出信息边界；无线、I/O、慢读取与 codec 启动用例检查采样及异常释放 |
| 歌曲 / 封面 | `CarPlayPlaybackStatusTest`、`Iap2FileTransferReceiverTest`、`Iap2LinkEngineFileTransferTest` 检查增量信息和有界传输；`NowPlayingArtworkQueueTest` 检查过期解码；媒体会话测试捕获真实发布参数，检查进度不重发封面、首次暂停与无重复焦点 |
| 通话 | `TelephonyMicrophoneTest` 检查 AEC/NS、不可用降级、设置关闭、重叠录音、失败释放及不覆盖原车模式；与 L7 路由、焦点、蓝牙互斥测试一起执行。模拟音效不证明实车降噪效果 |

## 热点与蓝牙会话回归

`L7HotspotProbeTest` 覆盖独立读取、API 29 门槛、拒绝／服务缺失／隐藏接口、空配置／掩码／有效配置以及 JSON 脱敏；`L7ProbeRunnerTest` 另验证单项热点复查不上传、不覆盖凭据。`NativeHotspotConfigurationTest` 用 API 30 系统替身覆盖 setter 返回、不可回读、回读不一致、异常类型脱敏和已开热点不重配；`CarHotspotTetheringTest` 区分已有热点、启动请求、回调与实际状态确认。现有热点任务和无线门禁回归继续覆盖读取失败保留配置、拒绝／取消和设置恢复。

`L7BluetoothMediaGuardTest` 追加当前会话启用、等待中关闭、快速切换预算、旧确认超时、暂停与已关闭／失败保护器的回归。测试只模拟目标 A2DP，不证明 L7 蓝牙交接或厂商热点授权可用。

## 方控锚点与调试页面

`L7SteeringDiagnosticsTest` 检查同一输入关联、蓝牙与队列耗时、过滤与旧会话原因、取消和有界记录；媒体回调测试覆盖重复 DOWN、UP、未知键和迟到 OEM 主线程回调。`L7BluetoothMediaGuardTest` 检查替换、暂停和关闭取消通知不重放命令；`L7SteeringLogTest` 检查方控轮转不覆盖连接日志、清除后可继续记录。

`python3 e2e/device/steering_debug_smoke.py --adb ../tools/scripts/adb.sh` 仅在已同意协议的中文 AVD 检查入口、问题标记实际落盘、清屏保留日志、昼夜与两级返回。不建立连接或上传，不证明物理方控修复；截图留在忽略目录。

## 基础调试回归

`L7PermissionProbeTest` 检查授权与有效调用分离、特殊访问覆盖普通查询、未声明待验证、保护级别解析、查询异常脱敏、版本不适用与厂商回移定义，以及目录来源关系与异常原名保留。`L7SpecialAccessProbeTest` 在 API 29/30 覆盖五项特殊访问、使用情况访问默认模式与未知模式、所有文件访问版本门槛和异常类型；测试使用系统替身，不代表实车授权。日志回归额外覆盖大证据预算下仍保留 163 项结论；`L7ProbeStoreTest` 覆盖未知值、身份/schema 隔离、进程中断恢复、保存上限及定向删除。`L7ProbeRunnerTest` 检查进入页面不扫描、不上传，显式单项检查、协议门禁、取消/超时拒收迟到结果以及单工作线程约束。`L7ProbeExporterTest` 检查 Activity 创建阶段可注册导出组件，JSON 实际写入另由 AVD 检查。`L7ProbeStatusTest` 检查颜色状态的证据边界与默认全量；`L7ProbeLogTest` 检查逐项脱敏、两批落盘、字节预算，以及缓冲清空或大量会话日志下手动上传仍保留环境和权限结果、不自动发送。测试不证明厂商接口或实车权限可用。

`L7ReportingProbeTest` 检查专项候选权限与 SDK 查询不推断业务支持、未知定义与未声明分别保留、SDK 不初始化及依赖缺失处理、QNX 下游未确认和证据导出；Runner 覆盖专项单项复查不启动全量扫描、不上传。

在中文、已同意协议且未连接手机的 AVD 覆盖安装后运行 `python3 e2e/device/debug_probe_smoke.py`。检查设置首页「调试与日志」入口、关于无重复入口和旧路由、Header/系统多级返回、显式扫描、全量紧凑表格、状态筛选、重新收集重置筛选、逐项日志落盘与两批保留、单项复查、历史列表、本地 JSON 实际写入及日志入口保留；同时核对三项上报专项结果位于顶部、QNX 未确认提示和专项权限日志，不点击上传。会新增模拟器检查报告和 Downloads/L7CarPlay 导出文件，不清除既有报告；截图及检查结果保留在忽略目录 `build/previews/debug-probe/`。追加英文表格昼夜检查后恢复中文和原昼夜模式；界面排版仍需目视检查。

`L7FactoryAudioProfileTest` 检查固件 usage 优先、模板错误回退和有线/无线输入源隔离；`TelephonyMicrophoneTest` 同时验证厂商输入源拒绝后标准源回退及音效释放。中文 AVD 运行 `python3 e2e/device/audio_profile_smoke.py`，检查升级后的导航 usage 12、电话 usage 2、焦点与高级映射默认值、试听及取消不保存；不连接手机、不上传，截图留在忽略目录。

## 启动崩溃保护

`L7StartupRecoveryTest` 在 API 29/30 验证连续三次异常熔断、Java 异常落盘、native 退出分类、正常停止排除、稳定运行复位、手动重新启用与原配置保留。运行 `python3 e2e/device/startup_recovery_smoke.py`，仅允许 AVD，模拟三次进程崩溃并检查设置可访问、USB 直达被拦截；原偏好仅在内存备份，结束恢复，不清空认证、日志或报告，不上传。此测试验证启动保护，不代表已定位实车 USB 闪退原因。

## 连接分步引导与失败提示

`L7VoiceInputTestTest`／`L7VoiceInputDebugPageTest`／`L7VoiceTraceStoreTest` 覆盖显式授权、十秒限时、重复启动、后台／关闭、迟到创建、通话接管、错误释放、技术摘要与旧会话隔离。`python3 e2e/device/voice_input_smoke.py` 仅在 AVD 检查权限拒绝、授权后不自动采集、手动停止、后台停止、限时完成、中英文昼夜与返回；本地生成摘要日志，不连接手机或上传。真实 Siri 识别与听感另行验收。

`L7VoiceInputTestTest`／`L7VoiceInputDebugPageTest`／`L7VoiceTraceStoreTest` 覆盖显式授权、十秒限时、重复启动、后台／关闭、迟到创建、通话接管、错误释放、技术摘要与旧会话隔离。`python3 e2e/device/voice_input_smoke.py` 仅在 AVD 检查权限拒绝、授权后不自动采集、手动停止、后台停止、限时完成、中英文昼夜与返回；本地生成摘要日志，不连接手机或上传。真实 Siri 识别与听感另行验收。

`L7ConnectionReconnectTest` 模拟旧会话延迟释放，检查连接设置第三项沿用当前无线／USB 传输方式、释放完成前不拉起宿主、重复点击和其他停止任务互斥、无会话／已关闭控制器禁用，以及页面结束或退出期间拒绝迟到重连。`python3 e2e/device/reconnect_settings_smoke.py` 检查第三项顺序、禁用原因、中英文昼夜和返回；仅允许未连接手机且已同意协议的 AVD，不建立连接或上传。真实手机重连结果另行验收。

`L7WirelessPrerequisitesTest` 验证蓝牙关闭与失效配对不能通过、确认前不继续连接或申请权限；`L7HotspotActionsTest` 在 API 29/30 验证自动读取不弹窗、显式操作错误保留、权限拒绝不写入/开启、配置保存但超时不报成功、真实开启确认；配合 `L7HotspotTaskTest`、`L7WirelessHotspotGateTest` 和通用任务弹窗回归。`L7WiredSettingsTest` 检查进入仅检测、缺设备明确确认、已有设备沿用核心授权、能力不足阻止连接、后台丢弃迟到结果；`L7RoutesTest` 验证两个子页返回连接方式。

中文、已同意协议的 AVD 运行 `python3 e2e/device/connection_guide_smoke.py`，检查首页/设置独立入口、三步引导、Header/系统返回、热点权限拒绝和读取失败强提示、USB 未就绪提示及中英文昼夜截图。脚本临时拒绝修改设置权限，结束恢复权限、语言与昼夜，不开启热点、不上传、不建立手机连接。`--english-only` 可只复核英文引导与失败弹窗。当前 AVD 未声明 USB Host，只验收能力不足分支；USB 等待/取消和浮动菜单等待页脚本需要支持 USB Host 的设备，不把未执行的等待场景记为通过。

## 任务弹窗与悬浮日志开关

`L7TaskProgressDialogTest` 检查返回/右上角关闭确认、继续等待、终止释放轮询、停止后的部分结果、失败明确重试及完成与关闭确认的竞态；`L7DebugTasksTest` 用本机模拟接口检查失败重开不自动发送、同一报告重试和后台停止。`L7DiagnosticSettingsTest` 检查悬浮日志只有一个开关、权限不足时回到关闭、外部状态回填不重复操作，以及移除页面重试/取消入口。

`RemoteLogHistoryTest` 检查成功记录持久保存、未上传状态、无效数据与配置独立；`RemoteLogBatchTest` 检查失败批次不记为成功，完整确认后保存实际时间与总条数。

中文 AVD 运行 `python3 e2e/device/task_dialog_smoke.py`，检查完整采集结果弹窗、上传等待/停止/返回确认/失败重试/成功、成功时间与条数重启保留、后续失败/取消不覆盖和悬浮权限取消。`--failure-only` 跳过采集及首次上传等待，复核失败重试、上传历史与开关。上传仅通过 adb reverse 到本机临时模拟接口，先核对应用实际地址再点击；脚本备份并恢复应用内日志配置、上传历史、昼夜和悬浮权限状态，不读取 `.env` 或访问真实服务。会新增一批 AVD 检查报告，截图保存在忽略目录；快速完成的扫描不人为延时，执行中停止由受控阻塞回归验证。

## 日志查看与清空

`L7LogViewModelTest` 覆盖普通文本搜索、复制范围、刷新保留搜索和 Unicode 分段无丢失；`LogMaintenanceTest` 检查清空拒绝旧写入队列且允许新日志，`L7ProbeStoreTest` 检查报告清空保留日志和无关文件。中文、已同意协议的 AVD 运行 `python3 e2e/device/log_view_smoke.py`，检查四个按钮满宽单列、搜索/刷新/复制及清空确认后取消，保存昼夜截图到忽略目录；不上传、不实际清空已有数据。

## 远程日志回归

`RemoteLogBatchTest` 检查 Unicode/JSON 的 1 MiB 总预算、256 KiB 分批、八份运行与两份采集来源、显式省略、不去重、取消阻止下一批和手动重试跳过已确认批次。`DiagnosticRedactorTest` 检查敏感值遮盖、权限/codec/BUS 等技术信息保留及重复脱敏不损坏上下文。

`RemoteLogTest` 使用本机回环 HTTP 模拟 OpenObserve `_json` 接口，验证认证与地址限制、脱敏和真实 JSON 字节预算、重定向拒绝、目标流及写入数量检查、部分拒收、无协议不发送、显式重试和撤回后的迟到结果隔离。不连接真实账号，也不读取实车日志。

`RemoteLogDeviceTest` 检查 Android ID 优先、序列号权限回退、随机编号持久化、同设备再生成与不同设备区分、恢复服务器配置不改变编号、厂商型号规范化与长度边界，以及旧编号更新前缀时保留哈希。`RemoteLogTest` 同时检查 `{HeadUnit}-{DeviceID}` / `{DeviceID}` 模板、占位符位置限制、保留服务器与组织、服务端流名规范化、重试沿用目标流，以及普通/空报告均不包含 `device_id`。只使用合成标识。

没有其他构建正在运行时，可执行 `python3 e2e/checks/check_openobserve_build_defaults.py`，检查 `.env` 与环境变量优先级、后续构建不会残留上次默认值。脚本临时写入合成配置并最终恢复原 `.env`，仅生成资源，不上传日志；需要已准备 ARM64 Docker 工具链和本地认证挂载。

使用默认日志配置为空的调试 APK，运行 `python3 e2e/device/openobserve_log_smoke.py` 检查 AVD 诊断页配置弹窗、认证输入隐藏、取消不保存、保存不上传和恢复默认值。脚本只使用合成配置，最终恢复原配置，不点击上传；截图位于忽略目录 `build/previews/openobserve/`。真实 OpenObserve 联调需使用用户提供的写入地址和凭据，不能将模拟响应测试称为云端写入通过。

用户明确授权实际上传后，可运行 `python3 e2e/device/openobserve_upload_smoke.py --send`：仅在 AVD 追加三条带独立测试标记的日志，通过界面点击上传最近日志到本机专属流，并检查完整写入确认。需要已安装包含相同服务配置的调试 APK、已同意使用协议且凭据具有该流写入权限。追加 `--verify-query` 后还会查询该设备流中的本次标记，核对报告与三条记录，检查不含 `device_id`；查询需要独立权限，HTTP 401/403 不代表写入失败。脚本不读实车、不修改服务器配置，实际上传不列入默认测试；结果和截图保留在忽略目录。

## 厂商 APK 离线定位

`python3 e2e/checks/inspect_vendor_apks.py ../FlymeAutoOS/apk` 仅读取样本，报告默认写入忽略目录 `build/vendor-reference/apk-evidence.json`。检查多 DEX、最多两层嵌套 JAR／AAR／ZIP、目标 SDK 类定义与外部类型／字符串引用，以及原生库限定关键词计数；不解压到工作区、不执行 APK、不输出任意二进制字符串。DEX 表按 [AOSP 格式](https://source.android.com/docs/core/runtime/dex-format) 读取；只支持 035、037～040 的小端单 DEX 格式。损坏、超限或不支持的条目记录扫描缺口并返回非零，不能据此判断目标不存在；JAR 类路径只记未验证目录证据。`mediacenter` 名称且以 Service 结尾的类只列候选，不认定提供者或服务准入；原生库关键词命中也不证明 QNX 协议。

运行 `python3 -m unittest discover -s e2e/checks -p test_inspect_vendor_apks.py -v` 验证定义／引用隔离、完整性、截断、嵌套扫描和假阳性边界。`L7VendorServiceProbeTest` 在 API 29／30 验证媒体提供包和 SDK 旧路径候选组件独立查询、缺失／错误元数据、未声明组件权限、拒绝和异常脱敏；Context 只读取 PackageManager，不绑定服务或调用 Binder。实际安装后的组件证据随 `ENV-SDK-CONTRACT` 和 JSON／日志保留，准入仍待验证。

## Docker 运行

[AppLocaleTest](common/test/java/com/shilapi/xcertplay/AppLocaleTest.kt) 覆盖中英文选择、系统语言迁移、已移除语言回退与长期 Context 的昼夜更新。`python3 e2e/checks/check_english_resources.py` 检查中文默认文案的英文覆盖、选项数组和格式占位符。设备上核对语言选择器仅有跟随系统、English、简体中文；英文逐页检查首页、设置、模态框和协议，并验证关于页的版本与离线许可弹窗。

`python3 e2e/device/english_ui_smoke.py --adb ../tools/scripts/adb.sh` 仅操作 AVD，需先完成协议确认且无活动会话。脚本切到 English 并保留，检查 15 个页面、设置选择器、协议、离线许可模态框和 USB 等待取消；USB Host 不可用时检查未就绪提示并明确跳过等待场景，支持时取消前收起悬浮菜单，并等待未连接首页出现。不修改认证或音频配置。原始日志、用户输入和第三方许可原文保留原语言，语言选择器的“简体中文”保留自称。截图位于忽略目录 `build/previews/english-ui/`。

追加 `--dialogs-only` 可单独检查上述弹窗、协议及 USB 取消，不重复页面扫描；同样保留英文首页。

蓝牙媒体与焦点回归：[AudioFocusCoordinatorTest](shared/test/java/com/shilapi/xcertplay/media/AudioFocusCoordinatorTest.kt) 检查博越导航降音与恢复、电话 / Siri 优先级、焦点拒绝与明确播放恢复、系统降音叠乘、旧监听隔离和焦点关闭；[CarPlayMediaSessionTest](common/test/java/com/shilapi/xcertplay/CarPlayMediaSessionTest.kt) 检查媒体键不另建焦点、手机暂停状态优先与关闭队列；[L7BluetoothMediaGuardTest](common/test/java/com/shilapi/xcertplay/L7BluetoothMediaGuardTest.kt) 用模拟端口覆盖目标确认、权限/请求拒绝、断开确认、超时、次数上限及迟到事件。该端口不代表已验证 L7 隐藏 API 或真实蓝牙断开；媒体服务测试核对框架保存的 PlaybackState，Robolectric 不提供完整车机媒体服务。

[CarPlayPlaybackStatusTest](shared/test/java/com/shilapi/xcertplay/media/CarPlayPlaybackStatusTest.kt) 还检查手机首次暂停必须发布、增量状态去重以及新会话重置，避免音乐流已经建立时错误发布播放状态。

[L7SteeringWheelTest](common/test/java/com/shilapi/xcertplay/L7SteeringWheelTest.kt) 用模拟广播检查长按、活动助手短按、未连接/关闭拒绝，以及广播与标准语音键去重；不证明实车广播权限或 Siri 已响应。蓝牙互斥用例同时覆盖明确播放等待断开确认、重复点击合并、暂停/关闭丢弃、超时只降级一次；媒体会话用例核对手机状态转换为明确播放/暂停及当前窗口标准媒体键，焦点用例在 API 29/30 观察实际 AudioTrack 音量，核对博越助手和导航临时焦点及实际音量。

设备脚本追加 `--bluetooth-only` 检查设置入口、最近状态、手动降级说明及昼夜弹窗，不打开系统设置或操作蓝牙。`CarPlayMediaCallbackTest` 保留上游切换键回归并补充 L7 明确播放/暂停，`L7AudioPreferencesTest` 覆盖用途路由默认值、一次迁移后保留手动选择与恢复范围；`L7AudioSettingsTest` 检查恢复前确认、取消不保存及界面刷新。实车需同时记录 CarPlay 播放、原车蓝牙音乐、焦点和电话，按使用说明导出同一复现时段日志。

全部 Android 编译和测试在 `mobiledevops/android-sdk-image:latest` 的 Docker 工具链内执行；完整测试默认采用 `linux/amd64`。Apple Silicon 调试的 ARM64 混合构建入口见 [构建说明](../docs/开发与验证.md)，不将调试打包成功写成完整测试通过。在仓库根目录运行：

```bash
# 全部现有单元/组件测试，不需要本地认证输入。
bash scripts/build-android-docker.sh -- \
  :shared:testDebugUnitTest :common:testDebugUnitTest

# 强制重新执行两个测试任务，避免将 UP-TO-DATE 误当本轮实际执行。
bash scripts/build-android-docker.sh -- \
  :shared:testDebugUnitTest --rerun :common:testDebugUnitTest --rerun

# 只运行悬浮日志缓冲用例。
bash scripts/build-android-docker.sh -- \
  :common:testDebugUnitTest --tests com.shilapi.xcertplay.DebugLogBufferTest

# 按媒体包筛选。
bash scripts/build-android-docker.sh -- \
  :shared:testDebugUnitTest --tests 'com.shilapi.xcertplay.media.*'

# 完整单测、mobile lint 和内置认证 APK；需要本地认证输入。
bash scripts/build-android-docker.sh --check
```

Docker、缓存、签名和源码验证包的规则统一见构建说明。迁移后任务名称保持原模块路径，Android Studio 同步 Gradle 后也能从新目录运行测试。

## 报告与设备验证

报告仍由所属模块生成，不放进源码目录：

- `shared/build/reports/tests/testDebugUnitTest/index.html`
- `common/build/reports/tests/testDebugUnitTest/index.html`
- `<模块>/build/test-results/testDebugUnitTest/TEST-*.xml`
- `mobile/build/reports/lint-results-debug.html`

CI 继续执行 shared/common 的相同任务并上传原报告路径。以 XML 的 tests/failures/errors/skipped 及实际任务执行状态核对结果；不能仅看构建成功，因为无测试源也可能返回 NO-SOURCE。

目前没有 androidTest 自动化用例。四个模块均将设备测试映射到 `e2e/<模块>/androidTest/`，mobile/automotive 的普通测试也预留 `e2e/<模块>/test/`；需要实际新增文件时再创建目录。设备用例引入时同时配置 runner、依赖与设备连接环境，不能把空目录或预留映射计为设备测试通过。automotive 是保留的上游模块，不纳入 L7 产品验收。

AVD 可验证界面、授权与生命周期；真实 iPhone、USB 模块、远程认证服务、音频路由与实车长时项目按开发说明的验证章节逐项执行。

## 调试工具与运行时样本

[check_public_tree.py](checks/check_public_tree.py) 检查 Git 跟踪源码中的凭据容器和私钥块，CI 从本目录调用；本机可运行 `python3 e2e/checks/check_public_tree.py`。它属于交付检查，不计入 JUnit 用例数。扫描以 Git 跟踪文件为范围，尚未跟踪的新增文件需另外检查。

[LocalMfiProbe.kt](shared/debug/java/com/shilapi/xcertplay/mfi/LocalMfiProbe.kt) 是已有的 Android 本地加密验证工具，用于重复签名、验签和修改挑战后的拒绝检查。迁入 shared 的 debug 源集，类名保持不变，只随调试 APK 编译，不是 JUnit 用例，不计入自动化用例数；不会发起 CarPlay 会话，也不代表手机认证通过。不要在日志或命令说明中写真实身份内容。

[navigation_test.pcm](shared/runtime/assets/navigation_test.pcm) 是应用内导航音频自检使用的样本，仍通过 shared 的 main.assets 映射打包。其 APK 资产名和诊断功能不变；它不是测试凭据。运行时样本与仅用于 JUnit 的 resources 分开放置，避免误将凭据打包。

## 后续维护

新增测试放到对应模块的 test/java（保持现有包路径）；测试辅助类、资源和设备 Manifest 也放在对应 test 或 androidTest 根目录，JVM 资源用 resources/，Android 资源用 res/、assets/。不要重新创建模块 src/test 副本，否则当前映射不会发现它。

合并上游后，将新增测试同步迁到 e2e 对应路径并核对内容；不得为方便检阅删除上游回归或把同一类重复挂载到多个源集。变体专用测试或 testFixtures 需要新增源集映射后再使用。

认证测试继续在运行时生成临时身份，不保存真实认证、签名密钥或日志到本目录。当前验证结论只维护在开发说明的验证章节，日志与截图保留在忽略目录，本说明维护布局、检阅方法和运行方式。

源集配置依据：[Android sourceSets](https://developer.android.com/build/build-variants#sourcesets)；任务强制执行依据：[Gradle --rerun](https://docs.gradle.org/current/userguide/gradle_command_line.html#sec:builtin_task_options)。

组件交互回归位于 [L7ComponentsTest.kt](common/test/java/com/shilapi/xcertplay/L7ComponentsTest.kt)，覆盖整行/开关各提交一次、选择后取消再打开不残留、未修改不提交、重复确认只提交一次，以及禁用操作仍保留确认值和可读反馈。列表同时检查涟漪有界、悬停/禁用反馈和触屏点击不抢焦点。诊断组件检查导出中阻止重复请求、名称稳定、失败提示和再次重试；模态框检查键盘导航在内容刷新后恢复同名入口焦点。布局截图、系统返回栈、连接与服务停止仍通过 AVD 或实车检查，单测不替代这些验证。

列表原生反馈检查使用 [PointerInput.java](device/PointerInput.java) 向 AVD 注入鼠标悬停和触屏按压，再由 [list_feedback_smoke.py](device/list_feedback_smoke.py) 比较截图。辅助程序只通过 shell 临时运行，不进入 APK；先用 Docker 一次性容器编译（结束自动删除），再使用安装了 Pillow 的 Python 运行：

```bash
docker run --rm --platform linux/arm64 --mount "type=bind,source=$PWD,target=/workspace" \
  l7carplay-android:arm64 sh -c '
  mkdir -p /workspace/build/e2e/list-feedback/classes
  javac --release 8 -cp /opt/android-sdk-linux/platforms/android-37.0/android.jar \
    -d /workspace/build/e2e/list-feedback/classes /workspace/e2e/device/PointerInput.java
  /opt/android-sdk-linux/build-tools/36.1.0/d8 --min-api 29 \
    --output /workspace/build/e2e/list-feedback/pointer.jar \
    /workspace/build/e2e/list-feedback/classes/l7/e2e/PointerInput.class'
python3 e2e/device/list_feedback_smoke.py --adb ../tools/scripts/adb.sh
```

仅在中文、已同意协议且无活动会话的 1440×1920 AVD 运行。检查昼夜悬停整行、移出消退、按压不越界，以及取消选择弹窗后无强制焦点；不保存帧率。完成后恢复原昼夜模式并删除设备上的辅助程序。截图位于忽略目录 `build/previews/list-feedback/`，仍需人工检阅颜色和边界。

[L7DiagnosticSettingsTest.kt](common/test/java/com/shilapi/xcertplay/L7DiagnosticSettingsTest.kt) 检查导出进行中禁用条目并拦截重复请求，完成后恢复入口与标题。

[L7ModalDialogTest.kt](common/test/java/com/shilapi/xcertplay/L7ModalDialogTest.kt) 检查所属界面主题更新后的弹窗重绘、多行输入保持最小/最大行数、调用方 dismiss 清理不被覆盖、选择仅预览/确认只提交一次，以及忙碌时关闭不能取消、关闭不触发确认。[L7RoutesTest.kt](common/test/java/com/shilapi/xcertplay/L7RoutesTest.kt) 检查配置页归属设置、旧连接/诊断/日志/关于入口迁移、返回层级与未知目的地拒绝。

AVD 交互检查使用主机 ADB，不执行 Android 编译；先以 Docker 构建并覆盖安装最新 APK，再运行：

```bash
python3 e2e/device/flyme_ui_smoke.py \
  --adb /你的工作区/tools/scripts/adb.sh --serial emulator-5556
```

脚本只允许模拟器序列号，要求 Android 11 与 1440×1920，检查设置归属/返回/子页恢复、热点/认证/语言弹窗、空输入错误、选择取消、保存参数保留等待会话和 USB 等待取消，并保存昼夜及 1.5 倍字体截图到忽略目录 `build/previews/flyme-ux/`。会临时改变主题、字号和帧率测试值，通过界面恢复原值，不读取私有认证配置；认证仅切换待选值后取消，导入使用空输入，不改变现有认证。USB 流程可能出现系统录音权限提示，仅选择本次使用；不能替代真实声音、iPhone 连接或 L7 实车验收。场景采用中文定位；若 AVD 当前跟随英文系统，脚本先经「通用设置 → 应用语言」选择简体中文并保留，其他语言请先手动改为中文。开始时需无活动会话，截图仍需人工检查排版。

仅修改弹窗主题时，可在上述命令追加 `--theme-only`，只检查切换主题后的待选值、确认按钮状态以及取消恢复；此模式不执行其余设置与连接流程。

仅检查分类层级时追加 `--navigation-only`，覆盖分类尾部不显示「进入」文字、首页无返回按钮、八类导航及设置首页的「调试与日志」入口、子页 Header 返回、重选设置侧栏回首页、离开后恢复子页，并核对全屏、音频声道、自动连接、认证来源、连接配置、诊断日志及使用协议入口可达；入口识别同时支持文字与无障碍名称。只查看认证来源后取消，不打开含已保存令牌的远程配置；保存昼夜与大字截图供人工检阅，并在 1.5 倍字体下检查 Siri 长路由值换到名称下方、左侧对齐和完整可见（场景要求该项为内置推荐长名称）。

仅检查声道交互时追加 `--audio-only`：媒体/语音助手/导航两秒试听、立即停止、选流与保存分离、主题切换保留状态和取消恢复。不保存音频设置，不以 AVD 的 PCM 写入或输出设备报告作为实车听感验收。

[MediaCodecSupportTest.kt](shared/test/java/com/shilapi/xcertplay/media/MediaCodecSupportTest.kt) 覆盖 hvcC、混合起始码 Annex B、参数集缺失/类型错误/截断拒绝；字节夹具只检查封装，不验证实际 HEVC 硬件解码。[VideoStartupWatchdogTest.kt](shared/test/java/com/shilapi/xcertplay/media/VideoStartupWatchdogTest.kt) 检查无输入/帧不足不误报、超时只报一次、区分输出与呈现，以及 reset 后等待新输入。[L7AudioRouteDialogTest.kt](common/test/java/com/shilapi/xcertplay/L7AudioRouteDialogTest.kt) 检查内层选流不保存、外层取消恢复、外层确认只提交一次。模态框回归同时检查自定义内容中的主按钮主题和可用状态。

筛选测试后必须核对 XML 中确有该类；多个 `--tests` 条件只要有其他匹配项，整体成功也可能掩盖某一类未被发现。若已有增量编译产物缺类，针对 `:shared:compileDebugUnitTestKotlin --rerun` 重新编译，再用单个类过滤验证；不要将“没有发现测试”记为通过。

[AudioOutputPolicyTest.kt](shared/test/java/com/shilapi/xcertplay/media/AudioOutputPolicyTest.kt) 检查内置三用途、覆盖后的路由用途与协议角色/优先级分离、电话保持独立以及旧传统流编号不与新预设冲突。[L7AudioPreferencesTest.kt](common/test/java/com/shilapi/xcertplay/L7AudioPreferencesTest.kt) 检查旧值保留、助手独立保存、恢复默认、预设保存与无效值回退。`--audio-only` 同时查看三用途入口，临时选择其他用途后试听并取消，不保存音频参数。

仅检查连接等待页时追加 `--connection-only`：从模拟器无会话冷启动，检查 USB 等待页的昼夜与 1.5 倍字体、返回保留服务，以及在原等待页取消后释放服务；不接入真实 USB 设备，不替代有线 CarPlay 验收。此模式会先强制结束模拟器上的应用，以排除预览启动时的自动连接，结束时恢复字号和主题。

密度布局检查先按 [AVD 密度校准](../docs/开发与验证.md#avd-屏幕与密度校准) 配置，再追加 `--output-dir build/previews/flyme-ux/density320` 保存独立截图，避免覆盖其他密度的证据。脚本不修改密度，预览档的结果仍需与实车逻辑密度和窗口对照。


## 首页、投屏悬浮菜单与退出

[L7ProjectionNavigationTest](common/test/java/com/shilapi/xcertplay/L7ProjectionNavigationTest.kt) 检查画面/设置/车机/退出四个入口及回调、设置双栏延伸为整高左栏且保留按钮实例/位置/选中态、右侧可操作、左栏空白不透传，返回首页恢复悬浮底板、连接状态触发收起、手动展开保持、API 29/30 绿色状态点与当前页面选中态独立且断开复原、固定左侧菜单留白/裁切、拖动入口后菜单位置不变与窗口尺寸适配、全尺寸视频不随侧栏变化、触控透传与遮罩拦截、拖动边界与恢复、透明度刷新。[L7DisplayGeometryTest](common/test/java/com/shilapi/xcertplay/L7DisplayGeometryTest.kt) 检查规格换算、部分占屏、旋转、编码缩放不改变毫米尺寸，以及未测得窗口拒绝协商。几何夹具为合成数据。

设备回归执行：

```bash
python3 e2e/device/l7_floating_navigation_smoke.py --adb ../tools/scripts/adb.sh
```

[L7HomePanelTest](common/test/java/com/shilapi/xcertplay/L7HomePanelTest.kt) 覆盖首次三入口、配置后四行、各入口回调、等待/已连接状态以及无线配置完整性与 USB 快捷连接。追加 `--home-only` 仅检查首页完整窗口居中、默认收起、返回展开四项菜单、跨区域拖动图标后菜单固定左上角、设置与子页菜单坐标一致、正文滚动/昼夜切换、设置往返和点击画面收起；不启动 USB 等待会话。完整流程也会先执行这组首页检查；同时确认首页和设置不再显示底部连接状态。首页已验证后可用 `--projection-only` 继续检查投屏菜单与设置往返。

此脚本只允许 emulator 序列号，在已安装最新 APK 的 AVD 中启动 USB 等待页，检查四个菜单入口、返回车机保留等待服务、设置内调试日志归并及日志查看/刷新、关于/连接设置的返回层级、返回展开、拖动、设置透明度、昼夜截图、退出取消及确认后进程/服务消失。退出检查会关闭模拟器中的本应用；完成后恢复原透明度和昼夜模式，再打开浮动入口预览。没有真实 iPhone，会话接通后自动收起由组件用例覆盖；真实连接、解码与车机生命周期仍需单独验收。截图默认保存到 `build/previews/floating-navigation/`，可用 `--output-dir` 指定独立目录，不纳入 Git。

原生热点回归：[L7HotspotTaskTest.kt](common/test/java/com/shilapi/xcertplay/L7HotspotTaskTest.kt) 覆盖只读进入、权限拒绝、失败保留、运行热点不重配及取消后的旧结果；[NativeHotspotCredentialsTest.kt](shared/test/java/com/shilapi/xcertplay/network/NativeHotspotCredentialsTest.kt) 检查稳定名称、独立随机密码和掩码拒绝。`L7HotspotNavigationTest` 在 API 29/30 验证定制 Wi-Fi handler、原生热点入口失败恢复和无线设置回退；`L7HotspotActionsTest` 验证读取失败保留配置且不阻止写入。所有接口使用替身，不修改宿主或实车网络；实际权限与热点开启需上车验收。

AVD 界面回归：`python3 e2e/device/native_hotspot_log_smoke.py` 检查中英文热点引导、生成窗口、原生设置跳转和日志首屏入口，不上传、不写入热点。原生热点开启权限检查会临时将模拟器 WRITE_SETTINGS 设为拒绝，验证提示后恢复原 AppOps；中文 AVD 可用 `--gate-only` 单独检查。脚本仅接受 emulator 序列号，截图保存在忽略目录。

日志地址遮蔽：`RemoteLogEditorTest` 覆盖内置地址遮蔽、空字段保留、手动覆盖和更换地址不复用认证；中文 AVD 使用内置默认值时执行 `python3 e2e/device/log_server_mask_smoke.py`，检查列表/弹窗及保存/取消不覆盖配置，不上传或打印真实地址。

`python3 e2e/device/task_dialog_smoke.py --clear-only` 在 AVD 使用本机模拟接口验证清空后无内容不发送、删除中 HTTP 400 提示及旧重试丢弃，结束恢复服务器配置与上传历史。会清空 AVD 日志及报告，不在实车运行。`python3 e2e/device/connection_guide_smoke.py --wifi-only` 检查 Wi-Fi 跳转和读取失败恢复，不启停或写入热点。

显式远端联调可用 `python3 e2e/device/openobserve_http_probe.py --send`：读取本地配置，向当前 AVD 日志流发送两条合成记录，对照 Content-Type 并输出脱敏状态；不读取车辆日志，不属于默认回归。

### 全量重置

`L7ResetSettingsTest` 在 API 29/30 验证取消不执行、确认只执行一次、系统拒绝 / 异常恢复、清理等待上限与迟到回调。中文 AVD 可运行 `python3 e2e/device/reset_settings_smoke.py --reset-and-restore`：实际清空本应用数据并验证首次使用入口，原私有数据仅保存在脚本内存，结束后恢复数据、运行时权限和应用操作授权；不上传、不读取车机日志。勿中断脚本进程，进程退出会丢失内存备份。截图留在忽略目录。

## 原厂媒体与导航适配验证

媒体与 HUD 软件回归使用合成数据和隔离端口，检查首次暂停控制、API 就绪／失败、源拒绝、有限重注册、状态与进度独立、焦点交接、旧回调、图片读取／清理及导航变化／过期，不把替身结果写成 OEM 服务准入或实车显示通过。

真实 Android VM 薄子类验证：Docker 内 `:common:assembleDebugAndroidTest` 后在 AVD 安装 `common/build/outputs/apk/androidTest/debug/common-debug-androidTest.apk`，执行 `adb shell am instrument -w com.shilapi.xcertplay.host.test/com.shilapi.xcertplay.vendor.SdkSubclassInstrumentation`，预期 `SDK_SUBCLASS_OK`。仅检查代码生成、装箱和实例隔离，不调用原厂服务。
