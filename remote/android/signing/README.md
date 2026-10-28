# 固定开发签名

Defold Remote 的开发 APK 在 Windows、macOS 上统一使用本目录的
`defold-remote-development.keystore`。构建脚本不会因清理 `build/` 而重新生成签名；
签名文件缺失会明确失败，请从 Git 恢复。

- 格式：PKCS12；别名：`androiddebugkey`
- 开发密码：`android`（keystore 和 key 相同）
- 证书 SHA-256：`EE:56:F5:8D:73:BA:8B:D1:E9:91:50:23:1C:85:42:A7:1B:2E:F4:7B:6B:EA:DB:4F:57:C7:E5:9A:82:47:CC:4D`
- 有效期至 2054-03-15。

这是按项目要求共享到分支的开发测试签名，不用于正式商店发行。
旧版 APK 使用另一台机器临时生成的签名时，首次切换需卸载旧版再安装，配对记录会清除；
从此版本起，同一包名与签名、递增 versionCode 的版本可直接覆盖升级。

构建继续使用 `python remote/android/build.py`。不要删除、替换或重新生成此文件；
换电脑构建时直接使用仓库副本。
