revision="3.2.0"
url="https://github.com/libjpeg-turbo/libjpeg-turbo.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="cmake"
license="IJG, BSD 3-Clause, ZLIB"
args="
  -DENABLE_STATIC=OFF
  -DWITH_TOOLS=OFF
  -DWITH_JPEG8=ON
"
pre_setup() {
  cmakeBaseArgs=("${cmakeBaseArgs[@]/#-DCMAKE_SYSTEM_NAME=Android/-DCMAKE_SYSTEM_NAME=Linux}")
}