<div align="center">

# Galaxy Play

**面向吉利银河车机的开源 CarPlay 接收端**

[![Android 10+](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)](docs/使用说明.md)
[![L7 / L6 竖屏](https://img.shields.io/badge/适配-L7_%2F_L6_竖屏-2563EB)](#适配范围)
[![GPL-3.0](https://img.shields.io/badge/许可-GPL--3.0-64748B)](LICENSE)

[下载安装](https://github.com/Tamshen/GalaxyPlay/releases) · [使用说明](docs/使用说明.md) · [更新记录](docs/更新记录.md) · [问题反馈](https://github.com/Tamshen/GalaxyPlay/issues)

</div>

Galaxy Play 基于 [DiPlay](https://github.com/shihabal3amri/DiPlay)，将 iPhone 的 CarPlay 画面、声音与触控交互接入银河车机，针对竖屏界面、音频、连接及原车服务进行适配。不同车型与使用场景通过独立配置文件管理。

## 界面预览

Android 11 AVD 实际截图（v0.1.153，1440×1920）。

| 主界面 | 设置界面 | 常用设置 |
| :---: | :---: | :---: |
| [![主界面](docs/images/home.png)](docs/images/home.png) | [![设置界面](docs/images/settings.png)](docs/images/settings.png) | [![常用设置](docs/images/preferences.png)](docs/images/preferences.png) |

## 功能

- **无线与有线**：Android 原生热点、Wi-Fi Direct 和 USB 有线连接（目前尚未成功连接）。
- **竖屏交互**：原生界面、昼夜主题、悬浮菜单与窗口适配。
- **音频适配**：分别配置音乐、导航、Siri 和通话的路由、焦点与缓冲。
- **配置复用**：内置 L7 带头枕／无头枕及 L6 模板，支持保存、复制、二维码分享与文本导入。
- **原车接入**：媒体信息、导航信息与方向盘按键接入，效果依车型及固件验证。
- **诊断排障**：连接阶段、真实首帧与配置快照关联记录，日志可主动导出或上传。

## 适配范围

目前适配 **吉利银河 L7、L6 竖屏车机**，最低 Android 10，目标平台为高通 8155、Flyme OS / Android 11。USB 有线连接目前尚未成功；Wi-Fi Direct 的重复连接稳定性仍在排查。各项功能的实车验证状态见[开发与验证](docs/开发与验证.md#验证与验收)。

安装后，在「偏好设置 → 配置文件」选择车型模板并保存，再按连接页面引导使用。完整步骤见[使用说明](docs/使用说明.md)。

## 开发与构建

项目采用 **Docker 构建环境**，Android 编译、单元测试、lint 和 APK 打包均在容器中完成。宿主机准备 Git、Python 3 和 Docker，在仓库根目录执行：

```bash
# 在 .env 配置 DIPLAY_AUTH_ASSETS_DIR 后，自动读取证书并打包
bash scripts/build-galaxyplay-host.sh

# 构建不含预置认证身份的源码验证包
bash scripts/build-android-docker.sh --source-only
```

APK 输出至 `mobile/build/outputs/apk/debug/mobile-debug.apk`。**AVD 模拟器独立运行在宿主机**，需单独下载 Android Emulator 和系统镜像，通过 ADB 安装 APK、预览和调试；项目界面预览使用 Android 11、1440×1920 竖屏。

认证配置、完整测试和发布构建见[开发文档](docs/开发与验证.md)，贡献前请阅读[开发规范](AGENTS.md)与[测试说明](docs/测试说明.md)。

## 目录

```text
galaxy/   银河车机适配、界面、车型配置与产品资源
shared/   CarPlay 协议、网络传输、音视频与认证核心
common/   应用宿主与连接生命周期
mobile/   Android APK 入口
e2e/      单元测试、设备测试与检查脚本
scripts/  构建与维护脚本
docker/   构建镜像与工具链配置
tools/    上游同步配置与补丁
docs/     使用、开发、测试、安全、更新记录及界面截图
```

[使用说明](docs/使用说明.md) · [开发与验证](docs/开发与验证.md) · [测试说明](docs/测试说明.md) · [安全与隐私](docs/安全与隐私.md) · [更新记录](docs/更新记录.md)

## 开源与说明

感谢 [DiPlay](https://github.com/shihabal3amri/DiPlay) 提供接收核心，当前基线为 v0.2.15。项目保留 [GPL-3.0](LICENSE) 及原有源码声明，依赖与资源出处见[第三方许可](docs/第三方许可.md)。

本项目独立维护，与 Apple、吉利、ECARX 或 Flyme Auto 无隶属或背书关系。使用前请阅读[首次使用须知与风险确认协议](galaxy/common/src/main/assets/galaxyplay-first-use-agreement.md)，数据与凭据处理见[安全与隐私](docs/安全与隐私.md)。
