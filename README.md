# 叶瞬光口袋宠物

[YeShunguangPet](https://github.com/Resker666/YeShunguangPet) 的 Android 版，适用于 Android 8.0 及以上。首版支持单个动画角色，可在应用内预览，也可开启跨应用悬浮。

## 使用

1. 安装体验 APK，打开「叶瞬光口袋宠物」。
2. 点进「开启悬浮宠物」，在系统设置允许显示在其他应用上层，再返回应用。轻点角色可互动，拖动可移动。长按角色后点「收起宠物」，也可以在应用或通知里收起。如果角色找不到，返回应用点「找回角色」，角色会移到屏幕中央。
3. 在悬浮宠物卡片里拖动「角色大小」滑块，可以实时调整到原始大小的 50% 至 666%，也可以用「−」和「+」逐次微调 1%。接近屏幕边缘时，应用会按角色的可见范围等比缩放，保留完整角色，并记住选好的大小。
4. 需要换角色时，从[社区皮肤库](https://github.com/Resker666/YeShunguangPet-Skins/releases)下载单角色 ZIP，在应用的「皮肤库」点「导入 ZIP」。

系统设置页、锁屏和禁止覆盖的应用可能隐藏悬浮窗。应用可以在拒绝悬浮权限时继续预览皮肤。APK 随附原创像素角色「小栈」。社区角色从独立皮肤包导入，素材权利以其各自的说明为准。项目为非官方同人项目。

## 皮肤兼容性

读取 Windows 版的 [schemaVersion 1](https://github.com/Resker666/YeShunguangPet/blob/main/docs/PET_FORMAT.md) 单角色 ZIP，沿用 `pet.json`、PNG 精灵图与每帧时长。首版使用待机、左右拖动、挥手、跳跃和点击台词。可选的 `behavior`、鼠标注视等数据可以随包导入。导入相同 ID、相同文件内容时沿用已安装皮肤，相同 ID 的不同版本会提示冲突。

## 构建

使用 JDK 17、Android SDK Platform 36 和 Build Tools 35.0.0。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows 可使用 `gradlew.bat`。APK 输出为 `app/build/outputs/apk/debug/app-debug.apk`。可选设置 `HUTAO_COMPAT_ZIP` 为胡桃 v1.4.0 原版 ZIP 的绝对路径，运行额外的实际皮肤兼容测试。
