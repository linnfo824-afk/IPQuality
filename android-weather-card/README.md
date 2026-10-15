# 天气卡片（Android 桌面小部件）

一个轻量的 Android 桌面天气卡片：实时天气、今日最高/最低温、体感、湿度、风向风级、降水概率和未来 5 天预报。
数据来自 [Open-Meteo](https://open-meteo.com/)，免费且无需 API Key。

## 功能

- **三种尺寸自适应**
  - 2×2：城市、天气图标、温度、天气描述、最高/最低温
  - 4×2（默认）：加上体感温度、湿度、风向风级、降水概率
  - 4×3 及以上：再加上未来 5 天预报
- **背景随天气变化**：晴天、夜间、多云/雾、雨、雪、雷暴各有不同的渐变色
- **城市选择**：按名称搜索（中文、拼音、英文均可），或一键使用当前位置（只申请大致位置权限）
- **自动更新**：每 30 分钟在联网时后台刷新；点按卡片可立即刷新
- 每张卡片可以设置不同的城市，点按卡片上的城市名即可切换
- 中英文界面，跟随系统语言；支持深色模式
- 最低支持 Android 8.0（API 26）

## 获取 APK

推送到仓库后，GitHub Actions 工作流 **Android Weather Card** 会自动编译。
打开仓库的 **Actions** 页面 → 选择最新一次运行 → 在 **Artifacts** 中下载 `weather-card-apk`，解压后得到：

- `app-release.apk`：体积更小的发布版（使用调试签名，适合直接安装自用）
- `app-debug.apk`：调试版

把 APK 传到手机上安装即可（需要允许“安装未知来源应用”）。

## 使用

1. 安装后打开“天气卡片”，搜索并选择城市（或使用当前位置）。
2. 长按桌面空白处 → **小部件 / 窗口小工具** → 找到 **天气卡片**，拖到桌面。
   - Android 8.0+ 的部分桌面也可以直接在应用里点 **添加到桌面**。
3. 添加时会弹出城市选择；之后点按城市名可以更换城市，点按卡片其他位置刷新。
4. 长按卡片拖动边框可调整大小，拉到 4×3 以上会显示 5 天预报。

> 国产系统（MIUI、ColorOS、EMUI 等）可能会限制后台活动，导致卡片不自动刷新。
> 可以在系统设置中允许“天气卡片”自启动 / 后台运行，或关闭其电池优化。

## 自行编译

需要 JDK 17 和 Android SDK（API 35）：

```bash
cd android-weather-card
./gradlew assembleRelease
# 输出：app/build/outputs/apk/release/app-release.apk
```

也可以直接用 Android Studio 打开 `android-weather-card` 目录。

## 代码结构

| 文件 | 作用 |
| --- | --- |
| `WeatherWidgetProvider.kt` | 小部件入口：处理添加、删除、尺寸变化、点按刷新，安排后台任务 |
| `WeatherWorker.kt` | WorkManager 后台任务，拉取天气并缓存 |
| `WidgetRenderer.kt` | 按尺寸选择布局，把天气数据渲染到卡片上 |
| `WeatherApi.kt` | Open-Meteo 天气预报和城市搜索接口 |
| `Weather.kt` | 数据模型，以及 WMO 天气代码到图标/背景/文字的映射 |
| `Prefs.kt` | 保存每张卡片的城市和最近一次天气数据 |
| `ConfigActivity.kt` | 城市选择界面（也是桌面图标打开的页面） |

天气数据由 [Open-Meteo.com](https://open-meteo.com/) 提供，遵循 CC BY 4.0 许可。
