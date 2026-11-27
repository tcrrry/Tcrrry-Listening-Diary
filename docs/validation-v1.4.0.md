# v1.4.0 发布验证

- Android App：全套本地单元测试 478 项（含新增 `PlaybackCorrectionTest` 8 项），通过 473 项、跳过 5 项（环境外部夹具用例）、0 失败、0 错误；正式 APK `versionCode 181`、`versionName 1.4.0`（`app-release.apk`，`27,416,361` 字节，`SHA-256: b523167f1c89e345e905641319124750bae90257a8d001e71b21ee74a9ac87fe`），APK Signature Scheme v2 签名验证通过。
- 公开源码校验：`app/` 下执行 `:app:compileDebugKotlin` 编译通过，不包含正式签名密钥。
- 真机性能对照：实机（13 万+ 条记录）开启高刷让路、内存裁剪与重算节流后，对比旧版 `29.70%` 掉帧与 `237 MB` Java 堆峰值，渲染卡顿帧降至 `0.72%`（22,478 帧中 161 帧，GPU `99th = 7ms`）、Java 堆降至约 `116 MB`。
- 配套组件：AM++ 采集器（`v1.0.2`）与 Chrome 网页扩展（`v0.6.2`）源码及发布压缩包未变更，`BridgeProvider` / `CollectorLink` 协议契约与 `v1.3.1` 完全一致。
- 发布附件摘要见 `SHA256SUMS.txt`。原始个人听歌记录、设备日志与签名密钥未纳入公开源码或发布附件。
