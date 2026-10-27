# 第三方组件

根目录 MIT 许可证仅覆盖本项目自有代码与文档，不替代外部组件的许可证。

| 组件 | 本版依赖 | 上游及许可 |
| --- | --- | --- |
| AndroidX Media3 | 标准 1.10.1 / 兼容 1.6.1 | [AndroidX Media](https://github.com/androidx/media)，Apache-2.0 |
| AndroidX Annotation | 1.9.1 | [AndroidX](https://android.googlesource.com/platform/frameworks/support/)，Apache-2.0 |
| OkHttp | 5.1.0 | [OkHttp](https://github.com/square/okhttp)，Apache-2.0 |
| GeckoView | 152.0.20260713164047，仅标准版 | [Mozilla](https://maven.mozilla.org/maven2/org/mozilla/geckoview/geckoview/152.0.20260713164047/)，MPL-2.0 及组件内部第三方许可证 |
| Gradle Wrapper | 8.11.1 | [Gradle](https://github.com/gradle/gradle)，Apache-2.0 |
| JUnit | 4.13.2，仅测试 | [JUnit 4](https://github.com/junit-team/junit4)，EPL-1.0 |

运行依赖还包括 Kotlin、Okio、协程、Guava 等传递组件；具体解析版本以 Gradle 依赖报告和上游组件内的 LICENSE / NOTICE 为准。测试依赖 AndroidX Test 与 UIAutomator，不放入日常应用 APK。

本项目原样使用 GeckoView 官方二进制，没有修改 Gecko 源码。对应源代码与构建资料见 [Firefox 152.0 发布源码](https://archive.mozilla.org/pub/firefox/releases/152.0/source/)和 [GeckoView 官方文档](https://firefox-source-docs.mozilla.org/mobile/android/geckoview/)。网页内核是固定版本，维护者应持续检查并更新。

本项目未复制或打包其他电视客户端的代码、频道表、网站片库或海报。仓库内的测试视频使用测试图与合成音频生成，示例海报由测试代码绘制。APK 中外部依赖已有的许可证材料应予保留；再分发者仍需检查自身发行内容的许可要求。
