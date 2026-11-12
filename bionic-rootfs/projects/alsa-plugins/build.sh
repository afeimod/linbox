# v3.4.7【wowbox64 无声之 alsa 补全】（用户 2026-11-11 实证反馈：
# "你用了 nopulse 但是 alsa 没有补全依赖肯定没有声音，根本没有点用
# 声音驱动，保证正常使用 pulse 或者 nopulse 使用 alsa，查看 rootfs
# 是不是没有相关编译构建"）：
#   rootfs 此前只有 alsa-lib（wine --with-alsa → winealsa.drv 能编译），
#   但 Android 上没有 /dev/snd/* —— libasound 的默认 PCM 无任何后端
#   可用 → winealsa 一落空就无声。alsa-plugins 的 pulse 后端
#   （libasound_module_pcm_pulse.so）+ /etc/asound.conf 全局默认指向
#   pulse，winealsa 的输出经 libasound → pulse 插件 → rootfs
#   PulseAudio 守护 → module-sles-sink → Android 扬声器 —— audio=alsa
#   档从"摆设"变"实路"（linbox-wine/容器 audio=alsa 同步支持）。
#   同时也是 pulse 驱动无声时的官方备选通路（wine 注册表 Drivers
#   Audio=alsa 时 wine 只用 winealsa）。
revision="1.2.12"
url="https://www.alsa-project.org/files/pub/plugins/alsa-plugins-${revision}.tar.bz2"
# alsa-project.org 历史性宕机常见；GitHub codeload 自动归档兜底
#（顶层目录 alsa-plugins-1.2.12/，框架 --strip-components=1 兼容；
#  无 configure —— 框架 autoreconf -fi 自动生成，实测 tag v1.2.12 200）。
backupUrls=(
  "https://codeload.github.com/alsa-project/alsa-plugins/tar.gz/refs/tags/v${revision}"
)
urlType="tar"
arch="aarch64 x86_64"
buildSys="autotools"
license="GPL-2.0-or-later"
# 只建 pulse 桥：NDK 交叉环境探测不到 jack/speexdsp/libsamplerate 等
# 宿主库（sysroot 隔离，自动关停）；显式 --disable-* 双保险（configure
# 对未知 --disable 仅警告忽略，零风险）。
# v3.4.8【构建修复】（用户 2026-11-12 实证：rootfs / wine x86_64 /
# wine arm64ec 三个构建全挂在 alsa-plugins 的 arcam-av 部件）：
#   arcam-av/arcam_av.c 在 bionic 严格 C99 下两处必挂 —— ① bzero()
#   未声明（glibc 旧代码习惯，bionic 需 <strings.h>）；② AF_FILE 是
#   glibc 旧别名宏，bionic 无此宏（wine 日志同期报的 "Failed to open
#   /etc/machine-id" 与此无关）。arcam-av 是 Arcam 功放控制件，与
#   LinBox 音频链路（pulse 桥）毫无关系 → 直接禁掉不编。开关名以
#   v1.2.12 configure.ac 为准：AC_ARG_ENABLE([arcamav]) →
#   **--disable-arcamav（无连字符）**；此前写 --disable-arcam-av 是
#   未知选项，configure 仅警告忽略 → 目录照编照挂（本轮拼写修正点）。
#   顺带显式禁掉 oss/libav/a52/lavrate 等 Android 无后端的部件（其余
#   依赖缺失时 configure 自动关停，显式双保险）；pre_setup 里再 sed
#   修补源码做三重保险（万一仍被编也能过）。
args="--disable-jack --disable-usbstream --disable-samplerate --disable-speexdsp --disable-arcamav --disable-oss --disable-libav --disable-a52 --disable-lavrate"
deps="alsa-lib pulseaudio"

pre_setup() {
  # arcam-av bionic 兼容修补（v3.4.8）：与 --disable-arcamav 多重保险。
  # AF_FILE（glibc 旧别名，bionic 无）→ AF_UNIX（unix domain socket
  # 同语义）；补 <strings.h> 声明 bzero。目录不存在（已禁用时 tarball
  # 可能仍在）则静默跳过。
  if [ -f arcam-av/arcam_av.c ]; then
    sed -i \
      -e 's/sun_family = AF_FILE/sun_family = AF_UNIX/g' \
      -e 's|#include <string.h>|#include <string.h>\\
#include <strings.h>|' \
      arcam-av/arcam_av.c
    echo "[pre_setup] arcam-av bionic 兼容修补完成（AF_FILE→AF_UNIX；+<strings.h>）"
  fi
}

install() {
  DESTDIR="${destDir}" PREFIX="${prefix}" make install
  # /etc/asound.conf：全局默认 PCM/Ctl 经 pulse 插件转给 PulseAudio
  # 守护。没有这份覆盖，libasound 默认 PCM 在 Android 无后端可用
  #（/dev/snd/* 不存在）—— winealsa 落空即无声的根因。侦听地址由
  # PULSE_SERVER 环境决定（linbox-wine audio=alsa 与 pulse 同源注入）。
  mkdir -p "${destDir}${prefix}/etc"
  cat > "${destDir}${prefix}/etc/asound.conf" <<'EOF'
# LinBox v3.4.7：winealsa 全局默认 → alsa-plugins pulse 桥
#（Android 无 /dev/snd，ALSA 用户态唯一现实出口是 pulse 守护；
#  守护地址随 PULSE_SERVER 环境走，与 winepulse 同一实例）。
pcm.!default {
    type pulse
    hint.description "PulseAudio via alsa-plugins (LinBox)"
}
ctl.!default {
    type pulse
}
EOF
}
