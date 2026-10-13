# AI_PDIA

使用 Java 开发的 Android 应用项目。当前包含基础主界面（Hello World）、应用资源和示例测试。

## 项目配置

- 应用 ID：`com.example.ai_pdia`
- 最低 Android 版本：Android 12（API 31）
- 目标 SDK：API 36；编译 SDK：API 36.1
- Android Gradle Plugin：9.0.1
- Gradle Wrapper：9.2.1
- Gradle JVM：Java 21；源码兼容级别：Java 11

## 本地运行

1. 克隆仓库并用支持上述构建配置的 Android Studio 打开项目。
2. 安装所需 Android SDK，并完成 Gradle 同步。`local.properties` 中的 SDK 路径由本机配置，不提交到仓库。
3. 选择 API 31 或更高版本的模拟器或设备，运行 `app`。

Windows 下构建调试 APK：

```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux 下构建调试 APK：

```sh
./gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/`。

## 目录

- `app/src/main/`：应用源码、布局和资源
- `app/src/test/`：本地单元测试
- `app/src/androidTest/`：设备测试
- `gradle/`：Gradle Wrapper 和依赖版本配置

构建缓存、IDE 本地设置、SDK 路径、环境变量文件及签名密钥已通过 `.gitignore` 排除。
