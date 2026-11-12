# 刷题 App (shuati-8087)

Android 客户端，内置 Tailscale 连接支持。

## 功能

- 📱 WebView 包装 8087 刷题网页
- 🔗 支持 Tailscale 连接（跨网络访问）
- 🌐 支持局域网连接
- ⚙️ 自定义服务器地址
- 📊 下拉刷新
- 📝 崩溃日志保存到 Download 目录

## 连接方式

### 1. Tailscale 连接（推荐）
- 手机安装 Tailscale App
- 登录同一 Tailscale 网络
- 使用 Tailscale IP（100.x.x.x）连接
- 默认地址：`http://100.122.70.99:8087`

### 2. 局域网连接
- 手机和服务器在同一 WiFi
- 使用局域网 IP 连接
- 默认地址：`http://192.168.2.166:8087`

### 3. 自定义地址
- 输入完整的服务器地址
- 支持 http:// 和 https://

## 构建

项目使用 GitHub Actions 自动构建 APK。

```bash
# 本地构建（需要 Android SDK）
./gradlew assembleRelease
```

## 日志

崩溃日志保存在：
```
/sdcard/Download/ShuatiApp/log_*.log
```

## 技术栈

- Android WebView
- Material Design
- Tailscale 网络支持
- SwipeRefreshLayout（下拉刷新）

## License

MIT
