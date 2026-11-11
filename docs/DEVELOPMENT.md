# 开发与验证

安卓工程独立维护，Windows项目只读参考，不运行Windows构建/清理工具。

## 结构

- core：API/协议、音乐频道及指定UP轮换、字幕/队列策略、下载/MP3编码；test只用合成数据。
- app/data：加密凭据、账号隔离、SQLite记录及设置。
- app/playback、app/download：Media3服务与WorkManager任务。
- app/ui：入口协调器、AppState、首页/播放器/收藏搜索/社交/下载/账号/设置页面及通用组件/弹窗；theme保留完整12组主题。
- tools：隔离工具链、构建、清理与隐私检查。Windows工程只读参考，不参与安卓构建。

## 构建

准备工程外的JDK 21、Android SDK API 37、Build Tools 36.0.0和Gradle 9.6.0，可使用 tools/Setup-Toolchain.ps1，生成的toolchain.json与缓存/签名均在工程外。

```powershell
./tools/Build-Android.ps1 -ToolchainFile <工程外的toolchain.json> -VerifyRelease
```

运行离线回归、开发Debug/R8 Release及两种Lint，验证包身份、显示名和隐私，最后自动清理生成目录。交付时加 -Deliver，只在桌面根目录输出 Bili_Play_Android_<版本>.APK 单文件，不创建文件夹或附带文件；协议/许可保留在应用内，不自动安装。

开发构建必须显式 -PDevelopmentBuild=true，身份 io.github.sodarx.biliplay.development；正式构建必须显式 false，签名密钥在工程外。两种构建均显示Bili Play，各账号凭据/记录/下载互相隔离。禁止日用数据进入源码、测试或安装包，不输出敏感值。


## 发布

测试完成后清理生成物并检查源码/暂存内容/APK隐私。安装包只放桌面根目录：`Bili_Play_Android_<版本>.APK`，不附带文件。不自动安装，不清理用户数据。

Git Tag不带v（例如`0.2.33`）；附注Tag说明`Bili Play v0.2.33`。推送代码与Tag不自动创建GitHub Release。MIT及第三方授权必须保留，见根目录LICENSE、third_party及应用内协议/许可。
