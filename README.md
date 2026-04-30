# E2EE Chat Template

一个可上传到 GitHub 的自托管聊天模板，包含：

- `e2eechat_web`: Node.js / Express / SQLite 服务端与管理后台
- `e2eechat_app`: Android Compose 客户端
- Web 聊天页默认禁用，只保留管理后台和 App API

## 特点

- 单聊、群聊、图片、文件、语音消息
- 设备身份、公钥、prekey、群发件人密钥封装
- 已读状态、撤回、更新包上传、TOTP 管理后台
- FCM 推送与 WebRTC 语音通话

## 安全说明

这是一个 self-hosted E2EE template，目标是给个人或小范围自托管使用。
它不是经过专业审计的 Signal 级密码学产品，不应做超出这一定位的安全宣称。

## 仓库初始化

### 1. 服务端

复制 `e2eechat_web/.env.example` 为 `e2eechat_web/.env`，至少填写：

- `JWT_SECRET`
- `MANAGE_HOST`
- `MANAGE_INITIAL_USERNAME`
- `MANAGE_INITIAL_PASSWORD`

首次启动空数据库时，服务端会用这两个环境变量创建第一个管理后台账号。
默认不会再写入示例用户；只有当 `SEED_SAMPLE_USERS=true` 时，才会注入 `alice/bob/carol` 测试账号。

### 2. Android 默认服务器地址

复制 `e2eechat_app/template.properties.example` 为 `e2eechat_app/template.properties`，填写：

```properties
defaultServerUrl=https://chat.example.com
```

### 3. Firebase 可选

如果你要启用 FCM：

- 将真实的 `google-services.json` 放到 `e2eechat_app/app/google-services.json`
- 在服务端 `.env` 填入 FCM service account 相关字段

仓库不会提交真实的 Firebase 配置文件。

### 4. Release 签名可选

- 将真实签名配置写入 `e2eechat_app/keystore.properties`
- 将真实 keystore 放在本地，不要提交
- 示例见 `e2eechat_app/keystore.properties.example`

## 敏感文件策略

以下内容不应提交：

- `google-services.json`
- `keystore.properties`
- `.jks` / `.keystore`
- 服务端 `.env`
- SQLite 数据库
- `uploads/`
- `app_release/`
- Gradle 缓存、Node modules、构建产物

## 说明

- 当前模板使用现版本协议与命名，不兼容旧的 `familychat` 版本数据与密钥前缀。
- 如果你是从旧仓库直接切换，请按“全新模板 / 新部署”理解，而不是原地无感升级。
