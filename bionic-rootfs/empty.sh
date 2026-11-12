url=""
urlType=""
revision=""
arch=""
buildSys=""
license=""
agreementTargetFile=""
unset args
unset fullCustomArgs
customCrossFile=""
extraMesonCrossProps=""
mesonHostSystem=""
deps=""
pkgSrcDir=""
doNotApplyPatch=""
doNotUseAutogenSh=""
argsToAutogenSh=""
forceAutoreconf=""
doNotUseNinja=""
doNotRunCMakeInstallOnCmake=""
doNotMakePackage=""
unset backupUrls
unset makeSilent
unset -f pre_setup
unset -f pre_package
unset -f custom_configure
unset -f custom_url
unset -f custom_patch
unset -f install
unset -f extra_fuction

# 清理 load_env() 导出的环境变量，防止跨包污染
unset CC CXX AR READELF STRIP RANLIB LD ASM
unset CFLAGS CXXFLAGS LDFLAGS CPPFLAGS LIBS
unset PKG_CONFIG PKG_CONFIG_LIBDIR PKG_CONFIG_SYSROOT_DIR
unset USE_CCACHE CCACHE_EXEC CCACHE_DIR