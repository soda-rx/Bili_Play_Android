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

## GitHub Actions

Release policy 工作流对 main 推送、PR 校验版本来源；对新 Tag 校验附注类型、注释、源码版本和提交；对 Release published/edited 事件额外校验标题、预发布标记、日志和附件名。手动运行可留空只查源码，或指定包含新工具的标签。

权限仅 contents: read，无签名密钥，不安装软件、不上传附件、不创建/删除/覆盖 Release。CI 不下载或执行远端二进制，不能据此声称远端附件签名、隐私或内部版本已验证。

在 GitHub 网页直接发布时，published/edited 检查在操作之后发生，并非事前阻断，务必先做本地检查。可由管理员把 Release policy 设为分支必需检查；本次未修改保护设置。旧标签没有新工具，不为补工具重写旧提交，也不以新规则失败为由删除旧版本。

## Android 版本来源与构建身份

- version.properties 是唯一来源：versionName 为公开 SemVer；versionCode 是安装升级用的递增整数。
- app/build.gradle.kts 读取这两项，关于页显示 BuildConfig.VERSION_NAME。
- 每次新发行，包括 alpha、beta、rc 和正式版，versionCode 都应大于已有发行。不能把 35 当成版本号 0.2.35，也不能为了匹配标签重置它。
- 当前 0.2.33 / 35 保持原样。下一版若只有兼容修复可用 0.2.34 / 36；有兼容新功能则用 0.3.0 / 36。这些是选择示例，并未创建新版本。
- tools/Build-Android.ps1 -Deliver **仅构建并签名 development 验收包**。文件扩展名改为小写 .apk，不改变隔离身份或签名，不能当成正式 Release 上传。
- 正式构建须显式 -PDevelopmentBuild=false，使用 :app:assembleProductionRelease，身份 io.github.sodarx.biliplay。正式密钥留在工程外，保持既有正式安装包的签名，不能临时换成开发密钥。
- 正式发布前还须先核对已有远端 APK 的实际身份和签名；若开发身份已对外发行，不能为规范化而直接换身份或密钥，应另议兼容迁移。正式签名/交付流程目前不是完整自动化。本次不新建密钥、不代签、不构建 APK，也不推断既有远端 APK 的实际身份。附件应在安全签名流程完成后验证。

~~~powershell
# 干净且检出对应标签的工作区，对实际正式 APK 检查：
python -B tools/check_release.py --tag <版本> --notes <工程外的更新日志.md> --asset <Bili_Play_Android_完整版本.apk> --aapt <AndroidSDK的aapt路径>
~~~

SDK 工具读取 APK 内 applicationId、versionName、versionCode，拒绝 .development 验收包或版本不符；仅重命名不能通过。另用可信 SDK 的 apksigner 验证签名，并执行 tools/Check-Privacy.ps1 -Apk <文件>。元数据检查不能证明签名来源或 APK 不含恶意行为。

附件仅 Bili_Play_Android_<完整版本>.apk 单文件，不附私钥和账号数据，应用内许可/协议保留。用户自行安装，脚本不调用 adb install。测试/交付后继续运行本工程清理脚本。

参考：[SemVer](https://semver.org/lang/zh-CN/)、[Android 版本管理](https://developer.android.com/studio/publish/versioning)。
