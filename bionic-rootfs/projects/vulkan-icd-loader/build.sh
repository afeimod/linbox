revision="v1.4.359"
url="https://github.com/KhronosGroup/Vulkan-Loader.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="cmake"
license="Apache-2.0"
args="
  -DVULKAN_HEADERS_INSTALL_DIR=${prefix}
  -DBUILD_WSI_XCB_SUPPORT=ON
  -DBUILD_WSI_XLIB_SUPPORT=ON
  -DBUILD_WSI_XLIB_XRANDR_SUPPORT=ON
  -DBUILD_WSI_WAYLAND_SUPPORT=OFF
"
deps="vulkan-headers xorgproto libxcb libX11 libXrandr"
install() {
  cd "${srcDir}/vulkan-icd-loader"
  DESTDIR="${destDir}" cmake --install build --prefix "${prefix}"
  cd "${destDir}/${prefix}/lib"
  ln -sf libvulkan.so libvulkan.so.1
}
