<img src="app/app/src/main/res/mipmap-xxxhdpi/ic_diary_launcher.png" alt="听歌日记图标" width="100">

# 听歌日记

把音乐留在日记里。听歌日记由 Android App、AM++ 采集插件和可选的 **Apple Music 网页采集扩展**组成，在本机展示听歌历史、统计、偏好与分享图片。

## 下载 v1.3

[![下载听歌日记 APK](docs/download-app.svg)](https://github.com/tcrrry/Tcrrry-Listening-Diary/releases/download/v1.3/Listening-Diary-v1.3.apk)
[![下载 AM++ 采集器 ZIP](docs/download-collector.svg)](https://github.com/tcrrry/Tcrrry-Listening-Diary/releases/download/v1.0/Listening-Diary-Collector-v1.0.zip)

[![下载网页采集扩展 ZIP](docs/download-web.svg)](https://github.com/tcrrry/Tcrrry-Listening-Diary/releases/download/v1.3/Listening-Diary-Web-Extension-v0.6.1.zip)

[查看发布页与更新说明](https://github.com/tcrrry/Tcrrry-Listening-Diary/releases/tag/v1.3) · [获取 AM++ 整合安装包](https://github.com/tcrrry/AMPP-Lyrics)

**手机听歌**：安装 APK，并在 AM++ 中导入采集器 ZIP；已经安装 v1.0 采集器无需重复导入。**网页听歌**：额外使用下方网页扩展，也可仅用扩展本机记录。App 要求 Android 12 或更高版本。

## v1.3 更新

- 新增 Apple Music 网页采集扩展，可查看网页播放、实际听歌时长和最近记录，弹窗打开期间实时刷新。
- 扩展通过日记多设备同步的六位配对码加入同步组，自动传送新增记录；离线期间先保存在浏览器，连接恢复后补传。
- 日记设置分别展示 Apple 历史导入与备份迁移导入记录；多设备同步显示已直接同步过的网页端。
- 保留 v1.2 的播放门槛、Apple Music 页面跳转及版本更新提醒。

## v1.2 更新

- 歌曲、专辑和歌手详情可跳转 Apple Music；歌曲及专辑页面的歌手入口仍可进入日记内的歌手详情。专辑首次匹配可能需要联网，结果会缓存在本机。
- 设置中可调整有效播放门槛为 0～60 秒，也可选择不限时长；修改后按新规则重新计算历史统计，实际收听时长不变。短歌达到时长的一半也计入。
- 设置底部可检查 GitHub 正式版更新，支持自动提醒、稍后一天提醒、忽略当前版本和关闭自动提醒；手动检查不受影响。

## 安装与更新

1. 安装或覆盖安装 `Listening-Diary-v1.3.apk`，打开日记，在设置中连接 AM++ 采集器并授权。
2. 打开 AM++ 的插件管理器，导入 `Listening-Diary-Collector-v1.0.zip` 并启用；如宿主提示重启，请重新打开 AM++。
3. 正常播放歌曲后打开日记查看。插件在 AM++ 运行时记录，App 不需要一直打开；首次同步或补传旧记录可能需要一点时间。

从预览版升级时覆盖安装 App、覆盖导入同 ID 的插件，保留已有记录和配置。不要先卸载旧 App、删除插件或清除数据。建议在日记设置中导出完整 JSON 备份。

## 网页采集安装与同步

1. 下载网页扩展 ZIP 并解压，在 Chrome 打开 `chrome://extensions`，开启“开发者模式”，点击“加载已解压的扩展程序”，选择包含 `manifest.json` 的文件夹。
2. 打开或刷新 [Apple Music 网页版](https://music.apple.com/) 并播放，点击工具栏的听歌日记图标查看记录。扩展可在扩展菜单中固定。
3. 同步到手机：打开 v1.3 日记设置 → 多设备同步 → 显示配对码，在扩展中输入日记显示的局域网 IP 和六位配对码并允许浏览器访问。配对一次后，网页播放时会在日记打开、双方网络互通时自动同步；暂时离线的记录会在连接恢复后补传。

**已安装扩展的用户更新**：把新 ZIP 解压后的文件覆盖到原扩展目录，在 `chrome://extensions` 点击该扩展的重新加载，再刷新 Apple Music。保留原目录和扩展，避免本机记录与配对丢失。

扩展先把数据保存在浏览器本机，可导出日记导入文件或网页备份；卸载扩展前请先同步或导出。网页需保持运行，网页休眠或浏览器后台限制可能造成少计。切换网络时会尝试已知地址，若无法连接可在扩展中更换连接。详见 [网页扩展说明](web-extension/README.md)。

## 主要功能

- 按日、周、月、年和全部记录浏览听歌历史、排行及收听习惯，支持设置一天的开始时间。
- 展示可取得的播放设备及音量、设备分布和音乐风格；没有记录的信息不作推测。
- 从 Apple 官方数据导入历史；可按周期导出 CSV / TXT 分享，或导出完整 JSON 用于备份和迁移。
- 基于本机记录生成分享图片。可选在线补全公开的歌曲资料、封面和歌手图片。
- 局域网多设备同步：在各设备的日记设置中通过六位配对码加入同一同步组；打开日记时自动发现并交换新增记录。虚拟网卡或跨网段设备可手动填写对方的局域网 IPv4 地址；网络和防火墙需要允许设备互访。

首次直连需要核对历史数据，记录较多时可能耗时较长；后续会按进度增量同步。同步时设置页会显示连接状态和处理进度。同步需要设备同时在线，AM++ 运行时也会尝试后台同步，但受系统进程和省电策略影响，不能保证日记被强制停止后仍能同步；需要时在各设备打开日记。建议配对前先在各设备导出完整日记备份。

App 和插件通过本机授权连接，不上传听歌历史到项目服务器。CSV、TXT 和备份包含个人听歌记录，分享前请自行检查。

## 兼容性

采集器面向 AM++ 中的 Apple Music；不是通用系统播放器监听器。针对 Apple Music 1609 和 1613 有原生读取适配；其他版本使用 MediaSession 备用方式，具体记录内容取决于系统与播放器提供的信息。无法补算过去未记录的设备或播放数据。

## 源码与构建

`app/` 是 Android App，`collector/` 是 AM++ 采集插件，`web-extension/` 是网页采集扩展（运行 `npm ci` 和 `npm test` 检查）。均使用各目录下的 Gradle Wrapper，使用 JDK 17、Android SDK 及 Android API 37。App 在 `app/` 下运行 `./gradlew :app:assembleDebug`；采集器在 `collector/` 下运行 `./gradlew pluginZip -PandroidSdk=/path/to/Android/Sdk`，也可设置 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`。公开源码不包含正式签名密钥，自己构建的 APK 无法直接覆盖由本项目签名的安装包。

源码采用 GPL-3.0，第三方许可见各工程的 `THIRD_PARTY_NOTICES.md`。
