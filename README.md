<img src="app/app/src/main/res/mipmap-xxxhdpi/ic_diary_launcher.png" alt="听歌日记图标" width="100">

# 听歌日记

把音乐留在日记里。听歌日记由 Android App 和 **AM++ 采集插件**组成：插件在 AM++ 运行时采集 Apple Music 的播放记录，App 在本机展示听歌历史、统计、偏好与分享图片。

## 下载 v1.0

[![下载听歌日记 APK](docs/download-app.svg)](https://github.com/tcrrry/Tcrrry-Listening-Diary/releases/download/v1.0/Listening-Diary-v1.0.apk)
[![下载 AM++ 采集器 ZIP](docs/download-collector.svg)](https://github.com/tcrrry/Tcrrry-Listening-Diary/releases/download/v1.0/Listening-Diary-Collector-v1.0.zip)

[查看发布页与更新说明](https://github.com/tcrrry/Tcrrry-Listening-Diary/releases/tag/v1.0) · [获取 AM++ 整合安装包](https://github.com/tcrrry/AMPP-Lyrics)

**两份文件都需要**：APK 是独立的日记 App；ZIP 是导入 AM++ 插件管理器的采集插件，不是 APK。已安装 AM++ 的用户无需再次安装整合包。App 要求 Android 12 或更高版本。

## 安装与更新

1. 安装或覆盖安装 `Listening-Diary-v1.0.apk`，打开日记，在设置中连接 AM++ 采集器并授权。
2. 打开 AM++ 的插件管理器，导入 `Listening-Diary-Collector-v1.0.zip` 并启用；如宿主提示重启，请重新打开 AM++。
3. 正常播放歌曲后打开日记查看。插件在 AM++ 运行时记录，App 不需要一直打开；首次同步或补传旧记录可能需要一点时间。

从预览版升级时覆盖安装 App、覆盖导入同 ID 的插件，保留已有记录和配置。不要先卸载旧 App、删除插件或清除数据。建议在日记设置中导出完整 JSON 备份。

## 主要功能

- 按日、周、月、年和全部记录浏览听歌历史、排行及收听习惯，支持设置一天的开始时间。
- 展示可取得的播放设备及音量、设备分布和音乐风格；没有记录的信息不作推测。
- 从 Apple 官方数据导入历史；可按周期导出 CSV / TXT 分享，或导出完整 JSON 用于备份和迁移。
- 基于本机记录生成分享图片。可选在线补全公开的歌曲资料、封面和歌手图片。

App 和插件通过本机授权连接，不上传听歌历史到项目服务器。CSV、TXT 和备份包含个人听歌记录，分享前请自行检查。

## 兼容性

采集器面向 AM++ 中的 Apple Music；不是通用系统播放器监听器。针对 Apple Music 1609 和 1613 有原生读取适配；其他版本使用 MediaSession 备用方式，具体记录内容取决于系统与播放器提供的信息。无法补算过去未记录的设备或播放数据。

## 源码与构建

`app/` 是 Android App，`collector/` 是 AM++ 采集插件。均使用各目录下的 Gradle Wrapper，使用 JDK 17、Android SDK 及 Android API 37。App 在 `app/` 下运行 `./gradlew :app:assembleDebug`；采集器在 `collector/` 下运行 `./gradlew pluginZip -PandroidSdk=/path/to/Android/Sdk`，也可设置 `ANDROID_SDK_ROOT` 或 `ANDROID_HOME`。公开源码不包含正式签名密钥，自己构建的 APK 无法直接覆盖由本项目签名的安装包。

源码采用 GPL-3.0，第三方许可见各工程的 `THIRD_PARTY_NOTICES.md`。
