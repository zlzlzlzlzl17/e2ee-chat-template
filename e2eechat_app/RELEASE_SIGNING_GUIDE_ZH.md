# Android Release Signing Guide

This template already supports release signing through `keystore.properties`.

## 1. Create a Keystore

Generate your release keystore inside the `e2eechat_app` directory:

```powershell
cd e2eechat_app
keytool -genkeypair -v `
  -keystore release-keystore.jks `
  -alias e2eechat `
  -keyalg RSA `
  -keysize 2048 `
  -validity 10000
```

Recommendations:

- Use a strong password.
- Back up `release-keystore.jks` safely.
- Do not commit the keystore to GitHub.

## 2. Create `keystore.properties`

Copy `keystore.properties.example` to `keystore.properties` and fill in the real values:

```properties
storeFile=release-keystore.jks
storePassword=your-store-password
keyAlias=e2eechat
keyPassword=your-key-password
```

`keystore.properties` is already ignored and should not be committed.

## 3. Build a Release APK

```powershell
cd e2eechat_app
.\gradlew.bat assembleRelease
```

Typical output path:

```text
e2eechat_app\app\build\outputs\apk\release\
```

## 4. Build an AAB

```powershell
cd e2eechat_app
.\gradlew.bat bundleRelease
```

Typical output path:

```text
e2eechat_app\app\build\outputs\bundle\release\
```

## 5. Common Issues

### `keytool` is not recognized

Your JDK `bin` directory is probably not in `PATH`. You can also run `keytool.exe` directly from the JDK installation folder.

### `Keystore file not found`

Check whether the `storeFile` path in `keystore.properties` is correct.

### `Keystore was tampered with, or password was incorrect`

This usually means `storePassword` is wrong, or the wrong keystore file was selected.

### `Cannot recover key`

This usually means `keyAlias` or `keyPassword` is wrong.
