# 版本与发布

Windows 与 Android 使用同一套规则，独立递增版本，不要求两端版本号相同。本文件不授权自动提交、推送、创建 Release 或上传安装包。

## 命名

| 项目 | Tag / 产品版本 | Annotated Tag 注释 / Release 标题 | 唯一发布附件 |
| --- | --- | --- | --- |
| Windows | 1.0.2 | Bili Play v1.0.2 | Bili_Play_1.0.2.zip |
| Android | 0.2.34 | Bili Play Android v0.2.34 | Bili_Play_Android_0.2.34.apk |

上表是格式示例，不代表这些版本已经发布。release-policy.json 保存平台名称和附件前缀；产品版本仍来自各平台原有版本文件，不另设第二个版本号。

- 正式版本为 MAJOR.MINOR.PATCH，不带 v、不补前导零，不含中文或产品名。
- 不兼容变更增加 MAJOR；兼容的新功能增加 MINOR、PATCH 归零；兼容修复和细节优化增加 PATCH。兼容范围包括安装升级、用户配置和保存的数据。
- 预发布统一为 X.Y.Z-alpha.N、X.Y.Z-beta.N、X.Y.Z-rc.N，N 从 1 递增。注释、标题、附件保留完整后缀，GitHub 勾选 Pre-release；正式版不勾选。
- 必须使用 git tag -a 创建附注标签，-m 内容为对应平台的 Release 标题，指向已经提交、验证且用于构建的提交。
- 不更换已发布版本的内容、不移动标签、不强推、不重新分配版本号。软件修正使用新版本；历史命名问题留档，不据此重打旧标签。
- 不为了格式一致改变包身份或签名密钥，避免破坏覆盖升级。提交代码和创建标签不等于发布 GitHub Release。

## 更新日志

使用中文 Markdown 标题“## 新增”“## 修复”“## 改进”，空分类删除。历史加粗分类仍可读取，新版本使用标准标题。参见 [模板](docs/RELEASE_NOTES_TEMPLATE.md)。

只写相对前一发布版本实际发生的变化，用用户能理解的描述，不重复、不夸大、不把计划写成成果。对应条目注明有依据的 @用户名 和 #PR编号，区分问题报告、方案建议与代码贡献。重要迁移提示可作为分类前的普通段落。

工具检查命名、结构、完全重复的条目和占位文字；无法代替人工核实真实性、贡献范围或近义重复。不能直接复制模板占位条目发布。

## 发布检查

需要 Python 3.10+ 和 Git。先获取完整标签历史，再执行只读检查：

~~~powershell
git fetch origin --tags
python -B -m unittest discover -s tools/tests -p test_release.py -v
python -B tools/check_release.py
python -B tools/check_release.py --notes <工程外的更新日志.md>
~~~

应用测试和隐私检查按项目既有构建流程执行，测试结束运行本项目清理脚本。私钥、真实凭据、用户数据始终留在工程与发布附件之外。

用户授权提交和打标签后，确认检出标签提交、工作区干净，再执行 --tag <版本>。不要在此阶段修改版本文件或源码。附件检查须同时指定 --tag、--notes、--asset；发布标题和附件名称使用工具输出的 Required release title / Required asset。通过检查之后，实际发布与上传仍须用户授权。

可准备工程外 JSON 离线核对 Release 表单：字段为 GitHub 的 tag_name、name、prerelease、body、assets（含 name）。执行 python -B tools/check_release.py --release-json <文件>，不会创建或修改远端 Release。

## 本地验证

本仓库已禁用 GitHub Actions，并移除工作流文件。版本、附注标签、更新说明、安装包身份和隐私检查均在本地执行；保留 tools/check_release.py 与合成回归测试，不依赖云端工作流。

每次提交前检查实际提交内容中的隐私数据和泄露风险，提交后清理本次工作产生的临时废弃文件。提交说明使用 `Bili Play (Android) v<版本号>: <英文提交说明>`。不因代码提交自动创建或发布 Release。

新版本更新说明在聊天中提供，按“新增”“修复”“改进”分类，有对应依据时每条末尾使用 ` (#x by @xxx)`。既有 Release Notes 和贡献者署名由用户维护，不自动修改。历史审计文档描述当时状态，不代表当前仍启用 Actions。

## Android 版本来源与构建身份

- version.properties 是唯一来源：versionName 为公开 SemVer；versionCode 是安装升级用的递增整数。
- app/build.gradle.kts 读取这两项，关于页显示 BuildConfig.VERSION_NAME。
- 每次新发行，包括 alpha、beta、rc 和正式版，versionCode 都应大于已有发行。不能把 35 当成版本号 0.2.35，也不能为了匹配标签重置它。
- 最新已发布标签 0.2.33 / 35 保持原样；当前源码为 1.0.8 / 47。1.0.0 正式安装渠道迁移属于不兼容变更，递增 MAJOR；1.0.1～1.0.7 为兼容修订，1.0.8 为兼容的交互细节修订。versionCode 继续单调递增，不重用中间验收包的内部版本。
- tools/Build-Android.ps1 -Deliver **仅构建并签名 development 验收包**。文件扩展名改为小写 .apk，不改变隔离身份或签名，不能当成正式 Release 上传。
- 正式构建须显式 -PDevelopmentBuild=false，使用 :app:assembleProductionRelease，身份 io.github.sodarx.biliplay。正式密钥留在工程外，固定使用 release-policy.json 的 productionSignerSha256，不能临时换成开发密钥。
- 已核实用户提供的 0.2.33 APK 为 .development 身份，其旧签名固定在 developmentSignerSha256；原私钥丢失，不能覆盖升级。此前 0.3.1 / 0.4.0 开发验收包使用了另一签名，同样不能覆盖旧 0.2.33，不得作为正式包发布。
- 用户已授权制作 1.0.0 正式迁移包：使用原有 production 身份并首次建立固定正式签名。新旧应用共存，旧应用和数据不删除；正式版重新登录读取 B 站远端内容，不自动导入旧应用私有记录、队列、设置或下载索引。1.0.1 起按用户要求移除启动迁移公告，迁移说明保留在 README。今后正式版沿用此正式身份和签名。

正式交付流程（PowerShell 7，工具链与签名配置均在工程外）：

~~~powershell
tools/Build-Production.ps1 -ToolchainFile <工具链配置.json> -SigningConfigFile <正式签名目录/production.json> -Deliver
~~~

脚本先运行隔离开发验收，再构建正式 R8 Release / Lint，核对内部身份、版本、非调试标记、签名、对齐与隐私，仅复制签名后的 APK 至桌面，最后清理工作区生成物，不自动安装。

`tools/Initialize-ProductionSigning.ps1` 仅用于首次初始化，已有文件时拒绝覆盖。用户要求统一使用工程外私有目录 `~/.biliplay_android_signing/production`，仅保留一份私钥与公开证书，以及 DPAPI 凭据、签名配置和恢复密码；不再生成另一份同机密钥副本。常规构建通过当前 Windows 用户解密凭据，不把密码传在命令行中。恢复密码用于跨电脑恢复，属于敏感资料，不能删除或随 APK 分发。整个签名目录绝不能放进仓库或公开附件；单机保存不能替代加密离线备份，用户应自行妥善备份。丢失配置或密钥时停止构建，不自动重建。

~~~powershell
# 干净且检出对应标签的工作区，对实际正式 APK 检查：
python -B tools/check_release.py --tag <版本> --notes <工程外的更新日志.md> --asset <Bili_Play_Android_完整版本.apk> --aapt <AndroidSDK的aapt路径>
~~~

SDK 工具读取 APK 内 applicationId、versionName、versionCode，拒绝 .development 验收包或版本不符；仅重命名不能通过。另用可信 SDK 的 apksigner 验证签名，并执行 tools/Check-Privacy.ps1 -Apk <文件>。元数据检查不能证明签名来源或 APK 不含恶意行为。

附件仅 Bili_Play_Android_<完整版本>.apk 单文件，不附私钥和账号数据，应用内许可/协议保留。用户自行安装，脚本不调用 adb install。测试/交付后继续运行本工程清理脚本。

参考：[SemVer](https://semver.org/lang/zh-CN/)、[Android 版本管理](https://developer.android.com/studio/publish/versioning)。
