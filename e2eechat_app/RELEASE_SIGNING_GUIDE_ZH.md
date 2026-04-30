# Android Release 签名打包指南

这个模板已经支持通过 `keystore.properties` 配置 release 签名。

## 1. 准备 keystore

在 `e2eechat_app` 目录生成你的 release keystore：

```powershell
cd e2eechat_app
keytool -genkeypair -v `
  -keystore release-keystore.jks `
  -alias e2eechat `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000
```

建议：

- 使用强密码
- 妥善备份 `release-keystore.jks`
- 不要把 keystore 提交到 GitHub

## 2. 创建 keystore.properties

复制 `keystore.properties.example` 为 `keystore.properties`，填入真实值：

```properties
storeFile=release-keystore.jks
storePassword=your-store-password
keyAlias=e2eechat
keyPassword=your-key-password
```

`keystore.properties` 已被忽略，不应提交。

## 3. 打包 release APK

```powershell
cd e2eechat_app
.\gradlew.bat assembleRelease
```

通常输出位置：

```text
e2eechat_app\app\build\outputs\apk\release\
```

## 4. 打包 AAB

```powershell
cd e2eechat_app
.\gradlew.bat bundleRelease
```

通常输出位置：

```text
e2eechat_app\app\build\outputs\bundle\release\
```

## 5. 常见问题

### `keytool` 不是内部命令

说明本机没有把 JDK `bin` 加到 PATH。可以直接使用 JDK 目录里的 `keytool.exe`。

### `Keystore file not found`

检查 `keystore.properties` 里的 `storeFile` 路径是否正确。

### `Keystore was tampered with, or password was incorrect`

通常是 `storePassword` 错误，或者选错了 keystore 文件。

### `Cannot recover key`

通常是 `keyAlias` 或 `keyPassword` 错误。
