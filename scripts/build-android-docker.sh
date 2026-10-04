#!/usr/bin/env bash
set -euo pipefail

# 按脚本位置计算路径，所有 Gradle 操作只在 Docker 中执行。
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_image="l7carplay-android:local"
build_platform=linux/amd64
cache_volume=l7carplay-gradle-amd64
warm=false
while [[ "${1:-}" == --arm64 || "${1:-}" == --warm ]]; do
    case "$1" in
        --arm64) build_platform=linux/arm64; cache_volume=l7carplay-gradle-arm64 ;;
        --warm) warm=true ;;
    esac
    shift
done
build_mode="standalone"
gradle_tasks=()

usage() {
    cat <<'EOF'
用法：bash scripts/build-android-docker.sh [选项]
  无参数          使用本地认证材料构建内置 MFi 的实车测试 APK
  --check         运行单元测试、mobile lint 和内置 MFi APK 构建
  --standalone    同默认打包；DIPLAY_AUTH_ASSETS_DIR 可覆盖本地材料路径
  --release       使用外部 Android keystore 构建带内置 MFi 的发布包
  --source-only   构建不含认证身份的源码验证 APK
  --image-only    仅准备 Docker 构建镜像
  -- TASK...      在容器中执行指定 Gradle 任务及参数
  --arm64         放在模式之前：原生 ARM64 JVM，官方 x86_64 SDK 工具通过模拟执行
  --warm          放在模式之前：复用当前项目开发容器及 Gradle Daemon（不用于 release）
  --help          显示帮助
基础镜像：mobiledevops/android-sdk-image:latest；默认 linux/amd64，--arm64 为混合构建。
默认认证目录：仓库内 .private/auth-assets/，不得提交到 Git。
EOF
}

case "${1:-}" in
    "") gradle_tasks=(:mobile:assembleStandaloneDebug) ;;
    --check) build_mode="check"; gradle_tasks=(:shared:testDebugUnitTest :common:testDebugUnitTest :mobile:lintDebug :mobile:assembleStandaloneDebug); shift ;;
    --standalone) build_mode="standalone"; gradle_tasks=(:mobile:assembleStandaloneDebug); shift ;;
    --release) build_mode="release"; gradle_tasks=(:mobile:assembleStandaloneRelease); shift ;;
    --source-only) build_mode="source"; gradle_tasks=(:mobile:assembleDebug); shift ;;
    --image-only) build_mode="image"; shift ;;
    --) build_mode="custom"; shift; gradle_tasks=("$@"); set --; if [[ ${#gradle_tasks[@]} -eq 0 ]]; then usage >&2; exit 2; fi ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
esac
if [[ $# -ne 0 ]]; then usage >&2; exit 2; fi

# 本机 SDK 路径在 Linux 中不可用，避免混用两套工具链。
if [[ "$build_mode" != "image" && -f "$project_dir/local.properties" ]] &&
   grep -Eq '^[[:space:]]*(sdk|ndk)\.dir[[:space:]]*=' "$project_dir/local.properties"; then
    printf '%s\n' 'local.properties 包含本机 SDK/NDK 路径，请移除这些路径后使用 Docker 构建。' >&2
    exit 2
fi

docker_args=(--mount "type=bind,source=$project_dir,target=/workspace"
    --mount "type=volume,source=$cache_volume,target=/var/cache/l7carplay/gradle"
    # 固定调试密钥，避免临时容器退出后重建密钥而无法覆盖安装。
    --mount type=volume,source=l7carplay-debug-signing,target=/root/.android)
needs_auth=false
if [[ "$build_mode" == "standalone" || "$build_mode" == "check" || "$build_mode" == "release" ]]; then
    needs_auth=true
elif [[ "$build_mode" == "custom" ]]; then
    # 显式外部输入或自定义 standalone 任务也使用相同的只读挂载。
    if [[ -n "${DIPLAY_AUTH_ASSETS_DIR:-}" ]]; then needs_auth=true; fi
    for task in "${gradle_tasks[@]}"; do
        if [[ "$task" == ':mobile:assembleStandaloneDebug' || "$task" == ':mobile:assembleStandaloneRelease' ]]; then needs_auth=true; fi
    done
fi

needs_signing=false
if [[ "$build_mode" != "image" ]]; then
    for task in "${gradle_tasks[@]}"; do
        case "$task" in :mobile:assembleRelease|:mobile:assembleStandaloneRelease) needs_signing=true ;; esac
    done
fi
if [[ "$needs_signing" == true ]]; then
    # 密码只按变量名转交容器，不拼接到命令行、不写入镜像或日志。
    for variable in ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_PASSWORD ANDROID_KEY_ALIAS ANDROID_KEY_PASSWORD; do
        if [[ -z "${!variable:-}" ]]; then
            printf '错误：发布签名缺少环境变量 %s。需要包含私钥的 keystore，APK 公开证书不能替代。\n' "$variable" >&2
            exit 2
        fi
    done
    if [[ ! -f "$ANDROID_KEYSTORE_PATH" ]]; then
        printf '错误：ANDROID_KEYSTORE_PATH 指向的 keystore 文件不存在。\n' >&2
        exit 2
    fi
    signing_dir="$(cd "$(dirname "$ANDROID_KEYSTORE_PATH")" && pwd)"
    signing_path="$signing_dir/$(basename "$ANDROID_KEYSTORE_PATH")"
    docker_args+=(--mount "type=bind,source=$signing_path,target=/run/l7-signing/release.jks,readonly")
    docker_args+=(--env ANDROID_KEYSTORE_PATH=/run/l7-signing/release.jks
        --env ANDROID_KEYSTORE_PASSWORD --env ANDROID_KEY_ALIAS --env ANDROID_KEY_PASSWORD)
fi
if [[ "$needs_auth" == true ]]; then
    auth_input="${DIPLAY_AUTH_ASSETS_DIR:-$project_dir/.private/auth-assets}"
    if [[ ! -d "$auth_input" ]]; then
        printf '%s\n' '缺少本地认证目录 .private/auth-assets/；可用 DIPLAY_AUTH_ASSETS_DIR 指定材料，或用 --source-only 构建源码验证包。' >&2
        exit 2
    fi
    auth_dir="$(cd "$auth_input" && pwd)"
    for auth_file in identity.pk8 certificate.p7b; do
        if [[ ! -s "$auth_dir/offline-mfi/$auth_file" ]]; then
            printf '%s\n' '外部认证资产不完整或为空，无法构建实车测试包。' >&2
            exit 2
        fi
    done
    docker_args+=(--mount "type=bind,source=$auth_dir,target=/run/l7-auth,readonly")
    docker_args+=(--env DIPLAY_AUTH_ASSETS_DIR=/run/l7-auth)
fi

# 镜像配方未变时直接复用，避免每次导出新镜像而丢失常驻容器。
prepare_image() {
    local tag="$1" platform="$2" dockerfile="$3" recipe="$4"
    local existing
    existing=$(docker image inspect "$tag" --format '{{index .Config.Labels "com.l7carplay.recipe"}}' 2>/dev/null || true)
    if [[ "$build_mode" == image || "$existing" != "$recipe" ]]; then
        docker build --platform "$platform" --progress plain --file "$dockerfile" \
            --label "com.l7carplay.recipe=$recipe" --tag "$tag" "$project_dir/docker"
    else
        printf '复用构建镜像：%s\n' "$tag"
    fi
}
prepare_image l7carplay-android:local linux/amd64 "$project_dir/docker/Dockerfile" "$(cksum < "$project_dir/docker/Dockerfile")"
if [[ "$build_platform" == linux/arm64 ]]; then
    sdk_image_id=$(docker image inspect l7carplay-android:local --format '{{.Id}}')
    arm_recipe=$({ cat "$project_dir/docker/Dockerfile.arm64"; printf '%s' "$sdk_image_id"; } | cksum)
    build_image=l7carplay-android:arm64
    prepare_image "$build_image" "$build_platform" "$project_dir/docker/Dockerfile.arm64" "$arm_recipe"
fi
docker image inspect "$build_image" --format '构建镜像：{{.Id}}，平台：{{.Os}}/{{.Architecture}}'
if [[ "$build_mode" == image ]]; then exit 0; fi
if [[ "$warm" == true && "$needs_signing" == true ]]; then
    printf '错误：release 使用一次性容器，不保留签名环境；请去掉 --warm。\n' >&2
    exit 2
fi

mkdir -p "$project_dir/build"
# 日志默认值也可从根目录 .env 读取；显式环境变量按变量名传递，不打印令牌。
log_exec_args=(exec)
log_run_args=(run --rm --platform "$build_platform")
for variable in L7_LOG_SERVER_URL L7_LOG_AUTHORIZATION; do
    if [[ ${!variable+x} ]]; then
        export "$variable"
        log_exec_args+=(--env "$variable")
        log_run_args+=(--env "$variable")
    fi
done
# 两个容器不能同时写同一份 Gradle 输出；锁由容器进程持有，退出自动释放。
# 中文文档会作为离线许可输入，JVM 的文件名编码必须由 UTF-8 locale 初始化。
gradle_command=(env LANG=C.UTF-8 LC_ALL=C.UTF-8 flock --nonblock --conflict-exit-code 75 /workspace/build/android-build.lock
    ./gradlew "${gradle_tasks[@]}" --console=plain)
set +e
if [[ "$warm" == true ]]; then
    project_key=$(printf '%s' "$project_dir" | cksum | awk '{print $1}')
    container_name="l7carplay-dev-${build_platform#linux/}-$project_key"
    image_id=$(docker image inspect "$build_image" --format '{{.Id}}')
    fingerprint=$(printf '%s\n' "$project_dir" "$image_id" "${auth_dir:-none}" | cksum)
    existing=$(docker container inspect "$container_name" --format '{{index .Config.Labels "com.l7carplay.runtime"}}' 2>/dev/null || true)
    if [[ -n "$existing" && "$existing" != "$fingerprint" ]]; then
        printf '镜像或挂载已变化，请先 docker rm -f %s，再重试；未关闭可能仍在运行的构建。\n' "$container_name" >&2
        exit 2
    fi
    if [[ -z "$existing" ]]; then
        docker run --detach --platform "$build_platform" --name "$container_name" \
            --label "com.l7carplay.runtime=$fingerprint" "${docker_args[@]}" \
            "$build_image" sleep infinity >/dev/null || exit $?
    else
        docker start "$container_name" >/dev/null || exit $?
    fi
    printf '复用开发容器：%s（保留 Gradle Daemon）\n' "$container_name"
    docker "${log_exec_args[@]}" "$container_name" "${gradle_command[@]}"
else
    docker "${log_run_args[@]}" "${docker_args[@]}" "$build_image" "${gradle_command[@]}"
fi
build_status=$?
set -e
if [[ "$build_status" -eq 75 ]]; then
    printf '当前项目已有构建正在运行；未并行修改同一输出目录，请等待后重试。\n' >&2
fi
exit "$build_status"
