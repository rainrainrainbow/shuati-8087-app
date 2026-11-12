# 刷题 App (shuati-8087)

Android 客户端，内置 Tailscale 连接支持。

## 功能

- 📱 WebView 包装 8087 刷题网页
- 🔗 **打开即用** — 自动连接 Tailscale，无需配置
- 🔄 自动备用：Tailscale → 局域网 → 本机，依次尝试
- 📊 下拉刷新
- ⚙️ 可自定义服务器地址（右上角菜单）
- 📝 崩溃日志保存到 Download 目录

## 连接逻辑

App 启动后自动按以下顺序尝试连接，**用户无需任何操作**：

1. **Tailscale IP**：`http://100.122.70.99:8087`
2. **局域网 IP**：`http://192.168.2.166:8087`
3. **本机**：`http://127.0.0.1:8087`

如果所有地址都连接失败，会提示用户点击右上角设置按钮手动配置。

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
