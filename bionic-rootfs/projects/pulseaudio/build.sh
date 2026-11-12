revision="v17.0"
url="https://github.com/pulseaudio/pulseaudio.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="meson"
license="GPL-2.0"
args="
  -Ddaemon=true
  -Dclient=true
  -Dman=false
  -Dtests=false
  -Ddatabase=simple
  -Dalsa=enabled
  -Dx11=disabled
  -Dgtk=disabled
  -Ddbus=disabled
  -Ddoxygen=false
  -Dopenssl=disabled
  -Dgsettings=disabled
  -Dsystemd=disabled
  -Dudev=disabled
  -Dhal-compat=false
  -Doss-output=disabled
  -Djack=disabled
  -Dasyncns=disabled
  -Dtcpwrap=disabled
  -Dlirc=disabled
  -Davahi=disabled
  -Dbluez5=disabled
  -Dconsolekit=disabled
  -Delogind=disabled
  -Dfftw=disabled
  -Dglib=enabled
  -Dgstreamer=disabled
  -Dorc=disabled
  -Dsoxr=disabled
  -Dspeex=disabled
  -Dsamplerate=disabled
  -Dvalgrind=disabled
  -Dadrian-aec=true
  -Dwebrtc-aec=disabled
  -Dipv6=false
  -Drunning-from-build-tree=false
  -Dlegacy-database-entry-format=false
  -Dstream-restore-clear-old-devices=false
  -Denable-smoother-2=true
  -Datomic-arm-linux-helpers=true
  -Datomic-arm-memory-barrier=false
  -Dbashcompletiondir=no
  -Dzshcompletiondir=no
"
deps="alsa-lib glib libsndfile libandroid-execinfo libandroid-glob libtool libandroid-stub"
extraMesonCrossProps="has_function_iconv_open = false"

pre_setup() {
  mkdir -p "${srcDir}/pulseaudio/src/modules/sles"
  cp "${wsDir}/projects/pulseaudio/module-sles-sink.c" "${srcDir}/pulseaudio/src/modules/sles/"
  cp "${wsDir}/projects/pulseaudio/module-sles-source.c" "${srcDir}/pulseaudio/src/modules/sles/"
  mkdir -p "${srcDir}/pulseaudio/src/modules/aaudio"
  cp "${wsDir}/projects/pulseaudio/module-aaudio-sink.c" "${srcDir}/pulseaudio/src/modules/aaudio/"

  export LIBS="-landroid-glob -landroid-execinfo"
  local _libgcc="$($CC -print-libgcc-file-name)"
  LIBS+=" -L$(dirname $_libgcc) -l:$(basename $_libgcc)"

  # --undefined-version: 允许未定义版本符号（避免 NDK 链接器报错）
  # no-as-needed -lOpenSLES as-needed: 强制链接 libOpenSLES（代理库），
  #   确保 module-sles-sink 的 DT_NEEDED 包含 libOpenSLES.so
  export LDFLAGS+=" -Wl,--undefined-version,--no-as-needed,-lOpenSLES,--as-needed"
}

install() {
  DESTDIR="${destDir}" meson install -C build

  cd "${destDir}${prefix}/lib" || exit 1
  if [ ! -e "./libpulse.so.0" ]; then
    ln -sf libpulse.so libpulse.so.0
  fi
  for lib in libpulse-common.so*; do
    [ -e "$lib" ] && ln -v -s -f "$lib" "$(basename "$lib")" 2>/dev/null || true
  done

  cd "${destDir}${prefix}/lib/pulseaudio/" || exit 1
  for lib in modules/lib*.so*; do
    [ -e "$lib" ] && ln -v -s -f "$lib" "$(basename "$lib")" 2>/dev/null || true
  done

  if [ -f "${destDir}${prefix}/etc/pulse/default.pa" ]; then
    sed -i '/^load-module module-detect$/s/^/#/' "${destDir}${prefix}/etc/pulse/default.pa"
    # 通过 libandroid-stub 的平台 namespace 代理加载 libOpenSLES，
    # 解决 libwilhelm → libgui → eglDestroySyncKHR 符号找不到的问题
    echo "load-module module-sles-sink" >> "${destDir}${prefix}/etc/pulse/default.pa"
    echo "#load-module module-aaudio-sink" >> "${destDir}${prefix}/etc/pulse/default.pa"
  fi
}
