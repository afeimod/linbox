revision="1.2.2"
url="https://github.com/libsndfile/libsndfile.git"
urlType="git"
arch="aarch64 x86_64"
buildSys="cmake"
license="LGPL-2.1"
args="
  -DCMAKE_POLICY_VERSION_MINIMUM=3.5
  -DBUILD_SHARED_LIBS=ON
  -DBUILD_PROGRAMS=OFF
  -DBUILD_EXAMPLES=OFF
  -DBUILD_TESTING=OFF
  -DINSTALL_MANPAGES=OFF
  -DENABLE_CPACK=OFF
"
deps="mpg123 mp3lame  libogg libvorbis libopus libflac"