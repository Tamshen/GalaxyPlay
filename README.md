# L7CarPlay

基于 [DiPlay](https://github.com/shihabal3amri/DiPlay) 的吉利银河 L7 专用 CarPlay 接收端。目标为 Flyme OS / Android 11、1440×1920 竖屏，最低 Android 10，包名 `com.ecarx.carplay`。

支持车机原生热点无线连接与 USB 有线连接，认证来源包括应用内置、文字导入、USB CH341 和 Remote MFi。应用版本与核心基线分别维护于 [版本配置](gradle/l7-version.properties)。实车 HEVC、语音及蓝牙焦点仍有待验项，详见开发说明。

## 文档入口

| 文档 | 内容 |
| --- | --- |
| [使用说明](docs/使用说明.md) | 安装、连接、设置、认证、音频与排障 |
| [开发与验证](docs/开发与验证.md) | 范围、运行约束、Docker、签名、AVD、验收与上游同步 |
| [第三方许可](docs/第三方许可.md) | 源码和资源出处、许可证 |

UI 规范与离线示例位于工作区 `FlymeAutoOS/UI/l7carplay/组件示例.html`，由 FlymeAutoOS 统一维护。Android 原生组件继续位于本仓库 `common`，不依赖外部 HTML 构建。

开发规范见 [开发规范](AGENTS.md)，测试统一放在 [e2e](e2e/README.md)，凭据与报告处理见 [安全与隐私](SECURITY.md)，当前产品变化见 [更新记录](更新记录.md)。

## 快速构建

启动 OrbStack 或 Docker Desktop，准备本地认证输入后，在仓库根目录运行：

```bash
# Apple Silicon 默认 ARM64，复用开发容器和缓存。
bash scripts/build-l7-host.sh
# 打包成功后安装到 L7 AVD。
bash scripts/build-l7-host.sh --preview
# 完整测试、lint 与打包。
bash scripts/build-l7-host.sh --check
```

输出为 `mobile/build/outputs/apk/debug/mobile-debug.apk`。无认证输入可显式使用 `bash scripts/build-android-docker.sh --source-only`，安装后配置认证。

保留 [GPL-3.0](LICENSE)、DiAuto AGPL-3.0 声明和第三方资源许可。本项目与 Apple 或吉利无隶属或背书关系。
