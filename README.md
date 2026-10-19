# 跳跳助手 (SkipApp)

原生 Kotlin + Android SDK 编写的**开屏广告自动跳过工具**。通过无障碍服务识别开屏广告里的"跳过"控件并自动点击，全程本地运行。

- 语言：Kotlin 2.3.20，无任何第三方运行时依赖
- 权限：**一个都不申请**（无 `INTERNET`、无通知、无存储）
- 平台：compileSdk 35 / targetSdk 35 / minSdk 24（Android 7.0+）
- 版本：v1.1.9（versionCode 20）
- 许可：MIT

## 它能做什么

- 冷启动 App 后自动寻找并点击开屏广告的跳过控件
- 支持顶部与底部边缘带的多种真实形态：`跳过`、`跳过 2`、`跳过 02`、`跳过 5s`、`15秒后跳过`、`Skip`、`跳过广告`、`关闭广告`、圆圈倒计时、胶囊容器、左上 / 左下 / 右下位置
- 一次跳过后不锁死：同一 App 内的第二个全屏广告、窗口切换、熄屏恢复后继续工作
- 白名单静默：桌面、系统设置、系统 UI、输入法、微信、支付宝、云闪付永不检测
- 内置**机上运行诊断**，不需要数据线就能看到"为什么没跳"

## 它做不到什么（能力边界）

诚实列出，避免期待错位：

| 做不到的事 | 原因 |
|---|---|
| 跳过 Canvas / SurfaceView 绘制的广告 | 这类广告在无障碍树里**没有节点**，技术上无法定位 |
| 跳过微信、支付宝、云闪付的开屏 | 白名单，金融安全优先的刻意取舍 |
| 跳过纯视频贴片（片头/片中）广告 | 已列入文本黑名单，避免误触播放器 |
| 自动恢复被系统撤销的无障碍授权 | 需要 `WRITE_SECURE_SETTINGS`，普通应用无权，只能人工重开 |
| 保证在国产 ROM 上永不被杀 | 厂商后台策略可 force-stop 进程，App 侧无对应 API |

## 隐私

不联网、不收集任何数据、不写入外部存储。诊断日志只写在应用私有目录（`filesDir/skip_diag.log`，上限 240 行），卸载即清除。

## 构建

**环境要求**：JDK 17、Android SDK 35、Gradle 9.1 + AGP 9.0.1。
本仓库**不包含 Gradle wrapper**（`gradlew`），需要自备 Gradle 或先执行 `gradle wrapper` 生成。

```powershell
# 1) 指向本机 Android SDK
echo "sdk.dir=<你的 Android SDK 路径>" > local.properties

# 2) 只编译校验（不产出 APK）
gradle compileReleaseKotlin

# 3) 产出 Release APK
gradle assembleRelease
# 产物：app/build/outputs/apk/release/app-release.apk
# 文件名由 app/build.gradle.kts 的 versionName 决定
```

**签名**：`app/build.gradle.kts` 从根目录 `signing.properties` 读取口令，也支持环境变量
`SKIPAPP_STORE_PASSWORD` / `SKIPAPP_KEY_PASSWORD`。`signing.properties`、`*.keystore`、
`local.properties` 均已被 `.gitignore` 排除，**不要提交**。没有密钥库时可用
`keytool -genkey` 自行生成，或去掉 `signingConfig` 后打未签名包。

## 测试

```powershell
powershell.exe -ExecutionPolicy Bypass -File .\tests\run-regression.ps1
```

它先离线编译 Release Kotlin，再用 JDK 直接执行**编译后的生产代码**，API 32 与 35 各跑一遍
（分别覆盖节点池 `obtain/recycle` 与 API 33+ 复制构造两条路径）。

| 组成 | 内容 |
|---|---|
| `tests/java/RegressionTests.java` | 89 条行为用例：调度链不死锁、生命周期恢复、误触反例、窗口抖动预算、落盘诊断 |
| `tests/java/AdCorpus.java` | 真实广告树语料：**36 个开屏/插屏形态必须命中，20 个业务控件形态必须一动不动** |
| `tests/java/android/**` | 23 个 Android/Binder 边界桩，让引擎能在电脑上被真实执行；**不进 APK** |

当前状态：`89 passed / 0 failed`（两个 API 各一遍），语料 `36/36` 命中、`20/20` 零误点。

> 这些数字证明的是"规则覆盖了已知的真实形态"，**不等于**在任何手机上都能跳过所有广告。
> 语料中只有 3 条来自真机截图，其余按公开广告 SDK 的布局命名构造。

## 运行诊断（无需 adb）

App 内：**使用指南 → 🔎 运行诊断**

- 服务状态、扫描帧数、命中候选次数、已发出点击次数（节点 / 手势分列）、系统拒收次数
- 最近一次扫描的包名与 Activity 类名
- **写着「跳过」却被拒的控件**：文本、id、类名、位置、窗口、状态位、拒绝原因（中文）
- 若扫过若干帧但树里没有任何带"跳过"字样的节点，会直接说明该广告是 Canvas 绘制、原理上无法跳过
- 「上一次进程的最后记录」：跨进程死亡落盘，用于确诊过夜失效是"我们自己崩"还是"被系统强杀"

## 目录结构

```
skipapp/
├── app/src/main/
│   ├── AndroidManifest.xml                       主界面 + 无障碍服务声明，零权限
│   ├── java/com/antigravity/skipapp/
│   │   ├── SkipAccessibilityService.kt           核心跳过引擎
│   │   ├── MainActivity.kt                       主界面与授权引导
│   │   ├── DiagActivity.kt                       机上运行诊断页
│   │   └── DiagStore.kt                          跨进程死亡的落盘诊断
│   └── res/                                      布局 / 文案 / 配色 / 图标 / 无障碍配置
├── tests/                                        离线回归与真实广告树语料（不进 APK）
├── build.gradle.kts  settings.gradle.kts  gradle.properties
├── app/build.gradle.kts  app/proguard-rules.pro
└── LICENSE
```

## 已知限制与注意事项

- **不要从多任务里划掉本应用**：部分厂商 ROM（如 ColorOS）会连带 force-stop，导致无障碍服务被系统标记"无法运行"，必须手动关闭再打开才能恢复。
- 无障碍服务由系统绑定，进程与主界面在同一进程内，进程被杀即服务停止。
- 匹配层刻意保守：宁可漏跳，也不点头像、搜索、静音、客服、会员购买、教程跳过。

## 许可

MIT。欢迎提 Issue 与 PR，特别是**新的真实广告形态**——语料越接近真机，规则越可靠。
