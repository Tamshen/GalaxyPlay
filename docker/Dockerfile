# 使用用户指定的基础镜像，构建脚本固定选择 Linux amd64 平台。
FROM mobiledevops/android-sdk-image:latest

USER root

# 命令行工具目录不是标准布局，因此显式指定 SDK 根目录。
# 仅补齐上游工具链，不修改仓库的 SDK、NDK 或 Gradle 版本。
RUN yes | sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" --licenses >/dev/null \
    && sdkmanager --sdk_root="${ANDROID_SDK_ROOT}" \
       "platforms;android-37.0" "build-tools;36.0.0" "ndk;28.2.13676358"

# JDK 25 由仓库 Daemon JVM 配置下载到缓存，基础 JDK 仅启动 Wrapper。
ENV GRADLE_USER_HOME=/var/cache/galaxyplay/gradle
WORKDIR /workspace
