# 测试代码检阅与运行

[项目入口](../README.md) · [开发与验证](../docs/开发与验证.md)

全部自动化测试代码集中到本目录，按所属模块和测试类型分类；原包名、断言和依赖保留，通过模块 Gradle 的 sourceSets 映射执行。目录名采用 e2e，现有用例的类型仍是 JUnit/Robolectric 单元及组件回归，不将它们表述为真实 iPhone 的端到端验收。

## 目录与检阅顺序

```text
e2e/
├── README.md
├── checks/check_public_tree.py            # CI 公开源码凭据检查
├── device/flyme_ui_smoke.py                # 已安装当前 APK 的 L7 AVD 交互检查
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

## 远程日志回归

`RemoteLogTest` 使用本机回环 HTTP 模拟 OpenObserve `_json` 接口，验证认证与地址限制、脱敏和真实 JSON 字节预算、重定向拒绝、目标流及写入数量检查、部分拒收、无协议不发送、显式重试和撤回后的迟到结果隔离。不连接真实账号，也不读取实车日志。

`RemoteLogDeviceTest` 检查 Android ID 优先、序列号权限回退、随机编号持久化、同设备再生成与不同设备区分、恢复服务器配置不改变编号，以及旧编号转换为安全流名。`RemoteLogTest` 同时检查按设备替换目标流、保留服务器与组织、重试沿用目标流，以及普通/空报告均不包含 `device_id`。只使用合成标识。

没有其他构建正在运行时，可执行 `python3 e2e/checks/check_openobserve_build_defaults.py`，检查 `.env` 与环境变量优先级、常驻容器不会残留上次默认值。脚本临时写入合成配置并最终恢复原 `.env`，仅生成资源，不上传日志；需要已准备 ARM64 Docker 工具链和本地认证挂载。

使用默认日志配置为空的调试 APK，运行 `python3 e2e/device/openobserve_log_smoke.py` 检查 AVD 诊断页配置弹窗、认证输入隐藏、取消不保存、保存不上传和恢复默认值。脚本只使用合成配置，最终恢复原配置，不点击上传；截图位于忽略目录 `build/previews/openobserve/`。真实 OpenObserve 联调需使用用户提供的写入地址和凭据，不能将模拟响应测试称为云端写入通过。

用户明确授权实际上传后，可运行 `python3 e2e/device/openobserve_upload_smoke.py --send`：仅在 AVD 追加三条带独立测试标记的日志，通过界面点击上传最近日志到本机专属流，并检查完整写入确认。需要已安装包含相同服务配置的调试 APK、已同意使用协议且凭据具有该流写入权限。追加 `--verify-query` 后还会查询该设备流中的本次标记，核对报告与三条记录，检查不含 `device_id`；查询需要独立权限，HTTP 401/403 不代表写入失败。脚本不读实车、不修改服务器配置，实际上传不列入默认测试；结果和截图保留在忽略目录。

## Docker 运行

[AppLocaleTest](common/test/java/com/shilapi/xcertplay/AppLocaleTest.kt) 覆盖中英文选择、系统语言迁移、已移除语言回退与长期 Context 的昼夜更新。`python3 e2e/checks/check_english_resources.py` 检查中文默认文案的英文覆盖、选项数组和格式占位符。设备上核对语言选择器仅有跟随系统、English、简体中文；英文逐页检查首页、设置、模态框和协议，并验证关于页的版本与离线许可弹窗。

`python3 e2e/device/english_ui_smoke.py --adb ../tools/scripts/adb.sh` 仅操作 AVD，需先完成协议确认且无活动会话。脚本切到 English 并保留，检查十个页面、设置选择器、协议、离线许可模态框和 USB 等待取消；不修改认证或音频配置。原始日志、用户输入和第三方许可原文保留原语言，语言选择器的“简体中文”保留自称。截图位于忽略目录 `build/previews/english-ui/`。

追加 `--dialogs-only` 可单独检查上述弹窗、协议及 USB 取消，不重复页面扫描；同样保留英文首页。

蓝牙媒体与焦点回归：[AudioFocusCoordinatorTest](shared/test/java/com/shilapi/xcertplay/media/AudioFocusCoordinatorTest.kt) 检查原版导航不申请焦点、Siri MAY_DUCK、失焦与拒绝不静音、系统降音恢复、多流选择、明确恢复、旧监听隔离和焦点关闭；[CarPlayMediaSessionTest](common/test/java/com/shilapi/xcertplay/CarPlayMediaSessionTest.kt) 检查媒体键不另建焦点、手机暂停状态优先与关闭队列；[L7BluetoothMediaGuardTest](common/test/java/com/shilapi/xcertplay/L7BluetoothMediaGuardTest.kt) 用模拟端口覆盖目标确认、权限/请求拒绝、断开确认、超时、次数上限及迟到事件。该端口不代表已验证 L7 隐藏 API 或真实蓝牙断开；媒体服务测试核对框架保存的 PlaybackState，Robolectric 不提供完整车机媒体服务。

[CarPlayPlaybackStatusTest](shared/test/java/com/shilapi/xcertplay/media/CarPlayPlaybackStatusTest.kt) 还检查手机首次暂停必须发布、增量状态去重以及新会话重置，避免音乐流已经建立时错误发布播放状态。

[L7SteeringWheelTest](common/test/java/com/shilapi/xcertplay/L7SteeringWheelTest.kt) 用模拟广播检查长按、活动助手短按、未连接/关闭拒绝，以及广播与标准语音键去重；不证明实车广播权限或 Siri 已响应。蓝牙互斥用例同时覆盖明确播放等待断开确认、重复点击合并、暂停/关闭丢弃、超时只降级一次；媒体会话用例核对手机状态转换为明确播放/暂停及当前窗口标准媒体键，焦点用例在 API 29/30 观察实际 AudioTrack 音量，核对原版助手 MAY_DUCK 与导航不申请焦点。

设备脚本追加 `--bluetooth-only` 检查设置入口、最近状态、手动降级说明及昼夜弹窗，不打开系统设置或操作蓝牙。`CarPlayMediaCallbackTest` 保留上游切换键回归并补充 L7 明确播放/暂停，`L7AudioPreferencesTest` 覆盖原版默认值、旧选择保留与恢复范围；`L7AudioSettingsTest` 检查恢复前确认、取消不保存及界面刷新。实车需同时记录 CarPlay 播放、原车蓝牙音乐、焦点和电话，按使用说明导出同一复现时段日志。

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

组件交互回归位于 [L7ComponentsTest.kt](common/test/java/com/shilapi/xcertplay/L7ComponentsTest.kt)，覆盖整行/开关各提交一次、选择后取消再打开不残留、未修改不提交、重复确认只提交一次，以及禁用操作仍保留确认值和可读反馈。诊断组件检查导出中阻止重复请求、名称稳定、失败提示和再次重试；模态框检查内容刷新后恢复同名入口焦点。布局截图、系统返回栈、连接与服务停止仍通过 AVD 或实车检查，单测不替代这些验证。

[L7DiagnosticSettingsTest.kt](common/test/java/com/shilapi/xcertplay/L7DiagnosticSettingsTest.kt) 检查导出进行中禁用条目并拦截重复请求，完成后恢复入口与标题。

[L7ModalDialogTest.kt](common/test/java/com/shilapi/xcertplay/L7ModalDialogTest.kt) 检查所属界面主题更新后的弹窗重绘、多行输入保持最小/最大行数、调用方 dismiss 清理不被覆盖、选择仅预览/确认只提交一次，以及忙碌时关闭不能取消、关闭不触发确认。[L7RoutesTest.kt](common/test/java/com/shilapi/xcertplay/L7RoutesTest.kt) 检查配置页归属设置、旧连接/诊断/日志/关于入口迁移、返回层级与未知目的地拒绝。

AVD 交互检查使用主机 ADB，不执行 Android 编译；先以 Docker 构建并覆盖安装最新 APK，再运行：

```bash
python3 e2e/device/flyme_ui_smoke.py \
  --adb /你的工作区/tools/scripts/adb.sh --serial emulator-5556
```

脚本只允许模拟器序列号，要求 Android 11 与 1440×1920，检查设置归属/返回/子页恢复、热点/认证/语言弹窗、空输入错误、选择取消、保存参数保留等待会话和 USB 等待取消，并保存昼夜及 1.5 倍字体截图到忽略目录 `build/previews/flyme-ux/`。会临时改变主题、字号和帧率测试值，通过界面恢复原值，不读取私有认证配置；认证仅切换待选值后取消，导入使用空输入，不改变现有认证。USB 流程可能出现系统录音权限提示，仅选择本次使用；不能替代真实声音、iPhone 连接或 L7 实车验收。场景采用中文定位；若 AVD 当前跟随英文系统，脚本先经「通用设置 → 应用语言」选择简体中文并保留，其他语言请先手动改为中文。开始时需无活动会话，截图仍需人工检查排版。

仅修改弹窗主题时，可在上述命令追加 `--theme-only`，只检查切换主题后的待选值、确认按钮状态以及取消恢复；此模式不执行其余设置与连接流程。

仅检查分类层级时追加 `--navigation-only`，覆盖分类尾部不显示「进入」文字、首页无返回按钮、八类导航、子页 Header 返回、重选设置侧栏回首页、离开后恢复子页，并核对全屏、音频声道、自动连接、认证来源、连接配置、诊断日志及使用协议入口可达；入口识别同时支持文字与无障碍名称。只查看认证来源后取消，不打开含已保存令牌的远程配置；保存昼夜与大字截图供人工检阅，并在 1.5 倍字体下检查 Siri 长路由值换到名称下方、左侧对齐和完整可见（场景要求该项为内置推荐长名称）。

仅检查声道交互时追加 `--audio-only`：媒体/语音助手/导航两秒试听、立即停止、选流与保存分离、主题切换保留状态和取消恢复。不保存音频设置，不以 AVD 的 PCM 写入或输出设备报告作为实车听感验收。

[MediaCodecSupportTest.kt](shared/test/java/com/shilapi/xcertplay/media/MediaCodecSupportTest.kt) 覆盖 hvcC、混合起始码 Annex B、参数集缺失/类型错误/截断拒绝；字节夹具只检查封装，不验证实际 HEVC 硬件解码。[VideoStartupWatchdogTest.kt](shared/test/java/com/shilapi/xcertplay/media/VideoStartupWatchdogTest.kt) 检查无输入/帧不足不误报、超时只报一次、区分输出与呈现，以及 reset 后等待新输入。[L7AudioRouteDialogTest.kt](common/test/java/com/shilapi/xcertplay/L7AudioRouteDialogTest.kt) 检查内层选流不保存、外层取消恢复、外层确认只提交一次。模态框回归同时检查自定义内容中的主按钮主题和可用状态。

筛选测试后必须核对 XML 中确有该类；多个 `--tests` 条件只要有其他匹配项，整体成功也可能掩盖某一类未被发现。若已有增量编译产物缺类，针对 `:shared:compileDebugUnitTestKotlin --rerun` 重新编译，再用单个类过滤验证；不要将“没有发现测试”记为通过。

[AudioOutputPolicyTest.kt](shared/test/java/com/shilapi/xcertplay/media/AudioOutputPolicyTest.kt) 检查内置三用途、覆盖后的路由用途与协议角色/优先级分离、电话保持独立以及旧传统流编号不与新预设冲突。[L7AudioPreferencesTest.kt](common/test/java/com/shilapi/xcertplay/L7AudioPreferencesTest.kt) 检查旧值保留、助手独立保存、恢复默认、预设保存与无效值回退。`--audio-only` 同时查看三用途入口，临时选择其他用途后试听并取消，不保存音频参数。

仅检查连接等待页时追加 `--connection-only`：从模拟器无会话冷启动，检查 USB 等待页的昼夜与 1.5 倍字体、返回保留服务，以及在原等待页取消后释放服务；不接入真实 USB 设备，不替代有线 CarPlay 验收。此模式会先强制结束模拟器上的应用，以排除预览启动时的自动连接，结束时恢复字号和主题。

密度布局检查先按 [AVD 密度校准](../docs/开发与验证.md#avd-屏幕与密度校准) 配置，再追加 `--output-dir build/previews/flyme-ux/density320` 保存独立截图，避免覆盖其他密度的证据。脚本不修改密度，预览档的结果仍需与实车逻辑密度和窗口对照。


## 首页、投屏悬浮菜单与退出

[L7ProjectionNavigationTest](common/test/java/com/shilapi/xcertplay/L7ProjectionNavigationTest.kt) 检查画面/设置/车机/退出四个入口及回调、设置双栏延伸为整高左栏且保留按钮实例/位置/选中态、右侧可操作、左栏空白不透传，返回首页恢复悬浮底板、连接状态触发收起、手动展开保持、固定左侧菜单留白/裁切、拖动入口后菜单位置不变与窗口尺寸适配、全尺寸视频不随侧栏变化、触控透传与遮罩拦截、拖动边界与恢复、透明度刷新。[L7DisplayGeometryTest](common/test/java/com/shilapi/xcertplay/L7DisplayGeometryTest.kt) 检查规格换算、部分占屏、旋转、编码缩放不改变毫米尺寸，以及未测得窗口拒绝协商。几何夹具为合成数据。

设备回归执行：

```bash
python3 e2e/device/l7_floating_navigation_smoke.py --adb ../tools/scripts/adb.sh
```

[L7HomePanelTest](common/test/java/com/shilapi/xcertplay/L7HomePanelTest.kt) 覆盖首次三入口、配置后四行、各入口回调、等待/已连接状态以及无线配置完整性与 USB 快捷连接。追加 `--home-only` 仅检查首页完整窗口居中、默认收起、返回展开四项菜单、跨区域拖动图标后菜单固定左上角、设置与子页菜单坐标一致、正文滚动/昼夜切换、设置往返和点击画面收起；不启动 USB 等待会话。完整流程也会先执行这组首页检查；首页已验证后可用 `--projection-only` 继续检查投屏菜单与设置往返。

此脚本只允许 emulator 序列号，在已安装最新 APK 的 AVD 中启动 USB 等待页，检查四个菜单入口、返回车机保留等待服务、诊断与日志合并及日志查看/刷新、关于/连接设置的返回层级、返回展开、拖动、设置透明度、昼夜截图、退出取消及确认后进程/服务消失。退出检查会关闭模拟器中的本应用；完成后恢复原透明度和昼夜模式，再打开浮动入口预览。没有真实 iPhone，会话接通后自动收起由组件用例覆盖；真实连接、解码与车机生命周期仍需单独验收。截图默认保存到 `build/previews/floating-navigation/`，可用 `--output-dir` 指定独立目录，不纳入 Git。
