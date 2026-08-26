# lightmux 在 cnb.cool 的构建环境镜像：JDK 17 + Android SDK 35。
#
# 由 .cnb.yml 的 docker.build 构建并按本文件内容缓存——只有改了这个文件才重建，
# 平时的 tag 构建直接复用。**没有 NDK**：lightmux 不含任何 native 代码（也正因如此不含 mosh）。
FROM eclipse-temurin:17-jdk

ENV ANDROID_HOME=/opt/android-sdk \
    ANDROID_SDK_ROOT=/opt/android-sdk \
    DEBIAN_FRONTEND=noninteractive

# python3 供 scripts/check_strings.py；unzip/curl 供 SDK 安装；unzip 还用于校验产物内容
RUN apt-get update && apt-get install -y --no-install-recommends \
        curl unzip git ca-certificates python3 \
    && rm -rf /var/lib/apt/lists/*

# cmdline-tools 必须落在 $ANDROID_HOME/cmdline-tools/latest，sdkmanager 才能定位 SDK 根目录
RUN mkdir -p "$ANDROID_HOME/cmdline-tools" \
    && curl -fsSL -o /tmp/cmdline.zip \
        https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip \
    && unzip -q /tmp/cmdline.zip -d "$ANDROID_HOME/cmdline-tools" \
    && mv "$ANDROID_HOME/cmdline-tools/cmdline-tools" "$ANDROID_HOME/cmdline-tools/latest" \
    && rm /tmp/cmdline.zip

ENV PATH="$PATH:/opt/android-sdk/cmdline-tools/latest/bin:/opt/android-sdk/platform-tools:/opt/android-sdk/build-tools/35.0.0"

# 版本与 app/build.gradle.kts 的 compileSdk = 35 对齐；apksigner 来自 build-tools，发布流水线要用它验签
RUN yes | sdkmanager --licenses > /dev/null 2>&1 || true \
    && sdkmanager --install \
        "platform-tools" \
        "platforms;android-35" \
        "build-tools;35.0.0" > /dev/null \
    && echo "SDK 安装完成"
