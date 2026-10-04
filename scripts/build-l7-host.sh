#!/usr/bin/env bash
set -euo pipefail

# 在用户本机终端调用现有 Docker 构建入口；不更改 socket 权限或系统配置。
project_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
build_option=--standalone
build_arch=auto
restart_preview=false
preview=false
preview_density=()
usage() {
    cat <<'HELP'
用法：bash scripts/build-l7-host.sh [--check] [--arm64|--amd64] [--preview|--restart-preview] [--density 数值]
  默认          仅打包；Apple Silicon 使用 ARM64，其余使用 amd64，结束后自动删除容器
  --build-only  默认模式的旧参数别名；不代表测试或 lint 通过
  --check       完整单测、lint、打包；默认使用已验证的 amd64 路径
  --arm64       原生 ARM64 JVM + 模拟 SDK 工具，复用磁盘缓存，容器用后即删
  --amd64       使用原有一次性 amd64 容器
  --preview     复用已启动的 L7 AVD，覆盖安装并打开；不重启
  --restart-preview  需要排查模拟器问题时，显式重启再预览
  --density     预览密度覆盖值（80～640）；默认 320 为预览档，非实车实测
  --help        显示帮助
Gradle 只在 Docker 中执行；需要本机终端能够访问已启动的 Docker。
HELP
}
while [[ $# -gt 0 ]]; do
    case "$1" in
        --build-only) build_option=--standalone ;;
        --check) build_option=--check ;;
        --arm64) build_arch=arm64 ;;
        --amd64) build_arch=amd64 ;;
        --restart-preview) preview=true; restart_preview=true ;;
        --preview) preview=true ;;
        --density)
            if [[ $# -lt 2 || ! "$2" =~ ^[1-9][0-9]{1,2}$ ]] || (( $2 < 80 || $2 > 640 )); then
                printf '错误：--density 需要 80～640 的整数。\n' >&2
                exit 2
            fi
            preview_density=(--density "$2")
            shift
            ;;
        --help|-h) usage; exit 0 ;;
        *) printf '错误：未知参数 %s\n' "$1" >&2; usage >&2; exit 2 ;;
    esac
    shift
done
if [[ ${#preview_density[@]} -gt 0 && "$preview" != true ]]; then
    printf '错误：--density 需要搭配 --preview 或 --restart-preview。\n' >&2
    exit 2
fi
command -v docker >/dev/null 2>&1 || { printf '错误：终端找不到 docker，请先启动 OrbStack 或 Docker Desktop。\n' >&2; exit 1; }
if ! docker info >/dev/null 2>&1; then
    printf '错误：当前终端无法连接 Docker，请确认 OrbStack 或 Docker Desktop 已启动。\n' >&2
    exit 1
fi
if [[ "$build_arch" == auto ]]; then
    if [[ "$build_option" == --standalone && "$(uname -m)" == arm64 ]]; then build_arch=arm64
    else build_arch=amd64; fi
fi
build_args=("$build_option")
if [[ "$build_arch" == arm64 ]]; then build_args=(--arm64 "$build_option"); fi
preview_script="$project_dir/../tools/scripts/preview-l7.sh"
if [[ "$restart_preview" == true ]]; then preview_script="$project_dir/../tools/scripts/restart-l7.sh"; fi
if [[ "$preview" == true && ! -f "$preview_script" ]]; then
    printf '错误：缺少工作区 AVD 预览脚本，无法打开 AVD。\n' >&2
    exit 1
fi
mkdir -p "$project_dir/build/host-logs"
log_path="$project_dir/build/host-logs/$(date '+%Y%m%d-%H%M%S')-$$.log"
printf '构建模式：%s / %s\n日志：%s\n' "$build_option" "$build_arch" "$log_path"
# 保留构建进程及日志写入的退出码，失败时不安装输出目录中的旧包。
set +e
bash "$project_dir/scripts/build-android-docker.sh" "${build_args[@]}" 2>&1 | tee "$log_path"
pipeline_status=("${PIPESTATUS[@]}")
set -e
if [[ "${pipeline_status[0]}" -ne 0 ]]; then
    printf '构建/检查失败，未启动预览。日志：%s\n' "$log_path" >&2
    exit "${pipeline_status[0]}"
fi
if [[ "${pipeline_status[1]}" -ne 0 ]]; then
    printf '日志写入失败，停止预览。\n' >&2
    exit "${pipeline_status[1]}"
fi
apk_path="$project_dir/mobile/build/outputs/apk/debug/mobile-debug.apk"
if [[ ! -s "$apk_path" ]]; then
    printf '错误：Gradle 返回成功，但未找到 APK：%s\n' "$apk_path" >&2
    exit 1
fi
printf '\nAPK：%s\n日志：%s\n' "$apk_path" "$log_path"
if [[ "$build_option" == --standalone ]]; then
    printf '本次仅打包，未运行单元测试或 lint。\n'
fi
if [[ "$preview" == true ]]; then
    preview_args=(--apk "$apk_path")
    if [[ ${#preview_density[@]} -gt 0 ]]; then preview_args+=("${preview_density[@]}"); fi
    bash "$preview_script" "${preview_args[@]}"
fi
