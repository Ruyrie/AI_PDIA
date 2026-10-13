# 病虫害识别助手（AI_PDIA）

面向种植户和家庭园艺用户的 Android 应用：拍下作物的病叶、果实或虫体，上传云端大模型识别病虫害名称、匹配度、严重程度和识别依据；看不懂可以接着向 AI 追问，也可以直接文字或带图提问。

《Android 企业框架开发》课程项目（第六组）。设计依据为《病虫害识别助手 UI 设计与技术准备文档》与原型图（`docs/prototype/`），工程落地约定见 [docs/开发约定.md](docs/开发约定.md)。

## 技术栈

- **Android**：Java 11（不用 Kotlin）、MVVM、Jetpack（ViewModel、LiveData、Lifecycle、DataBinding）、Dagger2、OkHttp + Retrofit + Gson、RxJava3、Glide、CameraX，组件化多模块
- **后端**（`server/`）：Spring Boot 3.3.5、MyBatis、MySQL 8+、JWT、SseEmitter 流式推送；识图模型通义千问 qwen3.8-max，问答模型 DeepSeek deepseek-flash（均支持图片输入），短信使用阿里云号码认证服务 · 短信认证

## 工程结构

| 模块 | 内容 |
|---|---|
| `app` | 壳工程：PestApp（Dagger AppComponent、组件服务注册）、MainActivity（底部导航） |
| `lib_base` | 基础类（BaseActivity / BaseFragment / BaseViewModel）、主题与样式、图标、通用 BindingAdapter、ImageCompressor |
| `lib_common` | 前后端共用 VO、常量、RouterPath、UserSession、组件间服务接口、格式化工具 |
| `lib_network` | 统一响应、错误码、RxResult、AuthInterceptor、NetworkModule |
| `module_account` | A01–A09：闪屏、引导、登录注册、完善资料、找回密码、申诉换绑 |
| `module_home` | H01 首页 |
| `module_recognize` | S01、S02、R01、R02：拍照识别、识别中、识别结果、识别异常 |
| `module_record` | R03、R04：识别记录 |
| `module_ai` | Q01、Q02：AI 问答 |
| `module_mine` | M01–M03：我的、设置、关于 |
| `server` | 后端 pestai-server（见 `server/README.md`） |

## 构建配置

- 应用 ID：`com.heban.pestai`；minSdk 31（Android 12），targetSdk 36，compileSdk 36.1
- Android Gradle Plugin 9.0.1，Gradle Wrapper 9.2.1，Gradle JVM Java 21，源码兼容级别 Java 11
- 后端地址：在 `local.properties` 中配置 `BASE_URL`（不提交到仓库），缺省为 `http://10.0.2.2:8080`（模拟器访问本机后端）。调试版允许 HTTP，正式版只允许 HTTPS。

```properties
# local.properties
sdk.dir=D\:\\androidsdk
BASE_URL=http://10.0.2.2:8080
```

## 本地运行

1. 按 `server/README.md` 建库，把数据库密码与各 Key 写入 `server/.env`（复制 `server/.env.example`，该文件不提交），然后启动后端。大模型与短信 Key 未配置时自动使用 Mock / 日志模式，验证码打印在后端日志中。
2. 用 Android Studio 打开项目并完成 Gradle 同步，选择 API 31 及以上的模拟器或设备运行 `app`。

Windows 下构建调试 APK：

```powershell
.\gradlew.bat assembleDebug
```

macOS / Linux 下构建调试 APK：

```sh
./gradlew assembleDebug
```

生成的 APK 位于 `app/build/outputs/apk/debug/`。

构建缓存、IDE 本地设置、SDK 路径、环境变量文件及签名密钥已通过 `.gitignore` 排除。
