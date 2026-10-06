#!/usr/bin/env bash
set -euo pipefail
# 仅在已安装 FFmpeg 的容器内运行；合成动态色块，不读入手机视频或音频。
output=galaxy/common/src/main/assets/codec-probe
mkdir -p "$output"
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=640x360:rate=30 -t 2 -an -pix_fmt yuv420p -c:v libx264 -profile:v baseline -preset fast -g 30 -bf 0 -movflags +faststart -y "$output/avc.mp4"
ffmpeg -hide_banner -loglevel error -f lavfi -i testsrc2=size=640x360:rate=30 -t 2 -an -pix_fmt yuv420p -c:v libx265 -x265-params pools=none:frame-threads=1:keyint=30:bframes=0 -tag:v hvc1 -movflags +faststart -y "$output/hevc.mp4"
