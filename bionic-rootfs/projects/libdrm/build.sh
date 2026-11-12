revision="2.4.134"
url="https://dri.freedesktop.org/libdrm/libdrm-${revision}.tar.xz"
urlType="tar"
arch="aarch64 x86_64"
buildSys="meson"
# https://github.com/termux/termux-packages/blob/master/packages/libdrm/LICENSE
license='
 Copyright 2005 Adam Jackson.

 Permission is hereby granted, free of charge, to any person obtaining
 a copy of this software and associated documentation files (the
 "Software"), to deal in the Software without restriction, including
 without limitation on the rights to use, copy, modify, merge,
 publish, distribute, sub license, and/or sell copies of the Software,
 and to permit persons to whom the Software is furnished to do so,
 subject to the following conditions:

 The above copyright notice and this permission notice (including the
 next paragraph) shall be included in all copies or substantial
 portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 NON-INFRINGEMENT.  IN NO EVENT SHALL ADAM JACKSON BE LIABLE FOR ANY
 CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT,
 TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.

------------------------------------------------------------------------

 Copyright 1999 Precision Insight, Inc., Cedar Park, Texas.
 Copyright 2000 VA Linux Systems, Inc., Sunnyvale, California.
 All Rights Reserved.

 Permission is hereby granted, free of charge, to any person obtaining
 a copy of this software and associated documentation files (the
 "Software"), to deal in the Software without restriction, including
 without limitation the rights to use, copy, modify, merge, publish,
 distribute, sublicense, and/or sell copies of the Software, and to
 permit persons to whom the Software is furnished to do so, subject to
 the following conditions:

 The above copyright notice and this permission notice (including the
 next paragraph) shall be included in all copies or substantial
 portions of the Software.

 THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND,
 EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF
 MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 NONINFRINGEMENT.  IN NO EVENT SHALL PRECISION INSIGHT AND/OR ITS
 SUPPLIERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
 DEALINGS IN THE SOFTWARE.
'
args="
  -Damdgpu=enabled
  -Dcairo-tests=disabled
  -Detnaviv=disabled
  -Dfreedreno=enabled
  -Dfreedreno-kgsl=true
  -Dintel=disabled
  -Dman-pages=disabled
  -Dnouveau=disabled
  -Dradeon=disabled
  -Dtests=false
  -Dvalgrind=disabled
  -Dvc4=disabled
  -Dvmwgfx=disabled
"
# ※ amdgpu 必开：wine 10+ 的 dlls/amd_ags_x64（AMD AGS 支持，部分游戏必需）
#   无条件 #include <amdgpu.h> 并链接 libdrm_amdgpu（configure.ac:
#   WINE_PACKAGE_FLAGS(DRMAMDGPU,[libdrm_amdgpu]) → pkg-config 找
#   libdrm_amdgpu.pc 提供 -I 与 -ldrm_amdgpu）。amdgpu=disabled 时
#   amdgpu.h / libdrm_amdgpu.pc 均不生成，make 阶段必报
#   'amdgpu.h' file not found（hangover/official/proton × arm64ec/x86_64 全中）。
#   Android 上 amdgpu 模块仅是普通用户态库，无需 PCI 设备即可编译。
pre_setup() {
  CFLAGS+=" -DANDROID"
}
install() {
  DESTDIR="${destDir}" PREFIX="${prefix}" meson install -C build
  # SONAME 版本别名：本构建系统按 Termux/Android 惯例产无版本 .so（meson 的
  # android 分支同样去版本化，见 libdrm meson.build "Build an unversioned so
  # on android"），但 wine 等预编译产物的 DT_NEEDED 记录上游标准 SONAME。
  cd "${destDir}/${prefix}/lib"
  # 上游 meson.build：libdrm version '2.x.0'→.so.2；amdgpu/freedreno '1.x.0'→.so.1
  ln -sf libdrm.so libdrm.so.2
  [[ -f libdrm_amdgpu.so ]] && ln -sf libdrm_amdgpu.so libdrm_amdgpu.so.1 || true
  [[ -f libdrm_freedreno.so ]] && ln -sf libdrm_freedreno.so libdrm_freedreno.so.1 || true
}
