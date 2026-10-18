APP_OPTIM := release
APP_PLATFORM := android-29
APP_ABI := armeabi-v7a arm64-v8a x86_64
# JNI_OnLoad registers the natives on com.meni.whitelistfilter.net.TProxyService
APP_CFLAGS := -O3 -DPKGNAME=com/meni/whitelistfilter/net -DCLSNAME=TProxyService
APP_SUPPORT_FLEXIBLE_PAGE_SIZES := true
NDK_TOOLCHAIN_VERSION := clang
