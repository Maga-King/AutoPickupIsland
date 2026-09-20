# AutoPickupIsland

ColorOS 自动取餐码识别与 HyperOS 4 原生超级岛适配模块。当前版本：0.11.8（30）。

## 构建

需要 JDK 17、Android SDK Platform 36，以及首次下载 Gradle/Maven 依赖的网络。
设置 `ANDROID_HOME` 指向本机 SDK，或自行创建不提交的 `local.properties`，填写 `sdk.dir`。

```powershell
.\gradlew.bat :app:assembleDebug
# 编译测试 APK：
.\gradlew.bat :testapp:assembleDebug
# 编译全部子项目：
.\gradlew.bat assembleDebug
```

Linux/macOS 使用 `./gradlew`。主模块输出为 `app/build/outputs/apk/debug/app-debug.apk`。

仓库包含全部项目源码、共享源码、Gradle Wrapper、资源、原版 PCR 插件 APK，
以及 `vendor/coloros/oplus-framework.jar`。三个相关模块默认从仓库内读取该 JAR，
不依赖原电脑的 E 盘固件路径；也可用 `-PcolorOsFrameworkJar=/absolute/path/to/oplus-framework.jar` 覆盖。
构建保留原有 SHA-256 校验：
`3b47a02bec547b3218f8a9ff94e11bb443e985b9b980314a9e0bedd82df8af78`。
原版 JAR 随主模块 APK 打包，手机端无需额外复制。

Android SDK、JDK 和可在线解析的 Maven/Gradle 缓存不纳入仓库；这不是离线工具链镜像。
个人签名私钥不提交。新电脑生成的 debug 签名可能不同，不能直接覆盖旧签名安装；
如需保持升级兼容，应自行安全迁移原签名密钥。

## 子项目

- `app`：三合一主模块。
- `shared`：共享协议和读取辅助源码。
- `collector-carrier`、`fixture-loader`：保留的独立组件/实验模块源码。
- `os4-api-stubs`：编译期系统 API 桩。
- `testapp`：合成页面与同签名实机测试入口。

真实设备日志、页面 XML、订单数据、截图、APK 回退备份、构建产物和本机配置不上传。
本仓库包含厂商二进制及资源，仅用于私有备份；未对这些第三方内容授予再分发许可。
