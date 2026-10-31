<p align="center">
	<img src="logo.png" width="376" height="128" alt="Winlator Logo" />
</p>

# Winlator-CN

Winlator-CN为Winlator的改版，可通过 Wine 和 Box86/Box64 运行 Windows（x86_64）应用程序。
本仓库用于存放 Winlator 应用源码的最新更新内容。
如需了解更多信息及下载发行版，请访问：http://52emu.cn/wp/376.html

# 更新日志

## 11.2.cn.04

显示后端从原来的纯 Java X 服务器实现换成 termux-x11 的 libXlorie（真正的 Xorg dix_main），补齐音视频解码器，支持容器内输入法输入与剪贴板双向同步，并内置了常用运行库与中文字体。

**X 服务器后端**

- 用 termux-x11 的 `libXlorie.so` 取代纯 Java X 服务器，X 协议由真 Xorg 处理
- 新增 `LorieView` / `CmdEntryPoint` / `MainActivity` 三个 JNI 契约类，与 native 侧 28 条 `JNINativeMethod` 一一对齐
- 新增 `XlorieServerComponent` 负责启动与复用；`dix_main` 是进程级单例，切换容器时复用已在运行的服务，只重建 X 连接
- 修正 `-nolisten` 缺少前导短横导致 dix 报 `FatalError("Unrecognized option")` 的问题（native 侧把 `exit()` 换成了 `_exit()`，表现为进容器瞬间闪退、无任何堆栈）
- 内置 xkb 数据，且必须包含 `geometry/`，否则 xkbcomp 报 `Can't find file "pc" for geometry include`

**输入法与剪贴板**

- `LorieView` 实现 `onCreateInputConnection` / `onCheckIsTextEditor`，输入法的提交与退格等控制键接到 libXlorie
- 进程内装 UTF-8 locale（`setUtf8Locale`）。bionic 默认是 `"C"`，`MB_CUR_MAX` 只有 1，native 的 `sendTextEvent` 用 `mbrtowc` 解 UTF-8 时遇到 3 字节的汉字直接判为非法序列并中止，中文一个字都发不出去
- 非 ASCII 文本改走「剪贴板 + `Ctrl+V`」粘贴，与上游 winlator-cn 的 `E02_KeyInput` 一致；纯 ASCII 仍逐字符注入。原因是逐字注入要靠 X 服务器给每个新字符临时分配 keycode，而 Wine 只在启动时读一次键盘映射，超出首批字符后就不再产生文字，表现为「有些字打不进去」
- 组字（拼音）阶段不向容器输出，只在提交时发一次，避免用退格回删拼音时吃掉容器里的真实内容
- 弹软键盘时先把焦点交给 `LorieView`，焦点只在弹键盘时转移，不抢触控板的外部鼠标捕获
- 注册 `OnPrimaryClipChangedListener`，剪贴板由单向变为双向（沿用 150ms 时间戳防回声），监听不随窗口焦点摘除

**内置 WFM 1.5-3.0**

- 新增 `wfm.tzst` 资产，按版本戳覆盖 `rootfs_patches` 里的旧版本，老容器下次启动即生效，不需要重建容器

**内置运行时与中文字体**

- 新增 `container_addons.tzst`，首次进入某个容器时解压到该容器的 `drive_c`，内容包括：
  - VC++ 2015-2022 运行库（`vcruntime140` / `msvcp140` 系列 / `concrt140`，32 位与 64 位各一套）
  - `d3dx9_43` / `d3dx9_42` / `d3dcompiler_43`（32 位与 64 位）
  - OpenAL Soft 1.24.2，作为 `OpenAL32.dll` 放进 `system32` 与 `syswow64`
  - 中文字体：微软雅黑、雅黑粗体、黑体、楷体、仿宋，并写入 `...\CurrentVersion\Fonts` 注册表项，否则 GDI 不会枚举到它们
- 按版本戳控制，只解压一次；解压失败只记警告，不影响启动
- 代价是每个容器首次启动多解压约 90 MB

**内置 .NET 运行时**

- 新增 `dotnet.tzst`，同样是每个容器首次进入时解压到 `drive_c`，落地为 `C:\windows\mono` 与 `C:\windows\Microsoft.NET`
- `mono` 里有 Mono 6.13 的两个运行时（`libmono-2.0-x86.dll` / `libmono-2.0-x86_64.dll`）与完整程序集树（`2.0-api` 到 `4.8-api`、GAC、msbuild、xbuild）
- `Microsoft.NET` 里有 `Framework` / `Framework64` 两套 `v1.1.4322`→`v4.0.30319` 目录
- 作用：`mscoree.dll` 是按 `C:\windows\mono\mono-2.0\bin\libmono-2.0-x86_64.dll` 这个路径找宿主运行时的，有了这份内容，带 .NET 可执行文件或受管 DLL 的程序可以直接跑，不必先装 wine-mono
- 压缩后约 60 MB，单独成包，与 `container_addons` 各管各的版本戳

**内置 GB18030 代码页**

- 新增 `nls.tzst`，把 `c_54936.nls`（GB18030）放进每个容器的 `drive_c\windows\system32`
- 起因：rootfs 只带 wine 自建的 76 个 NLS 表，其中没有 54936。汉化补丁若用 GB18030 转窗口类名，查表失败得到空串，`CreateWindowExW` 建窗失败，游戏直接退出且没有任何提示
- ntdll 的查找顺序是「先 `<wine>/share/wine/nls/c_%03u.nls`，失败再按 DOS 路径 `\??\C:\windows\system32\c_%03u.nls` 从 prefix 打开」，所以放在 `drive_c` 就能被找到
- 顺带一提：这份文件与 rootfs 里 `c_936.nls` 只差头部 4 字节的 CodePage 字段，其余完全一致

**媒体解码**

- 新增 `gst_codecs` 资产，补上 AC-3 / E-AC-3、Vorbis、Opus、FLAC、ADPCM 与 MPEG-TS 解封装。rootfs 里的 FFmpeg 是静态链接进 `libgstlibav.so` 的，构建时没开这些，含对应音轨的过场动画会直接没声音

**体验修复**

- 启动遮罩改由渲染视口回调关闭，修复容器起来后卡在「正在启动容器」且吃掉所有触摸
- 纹理滤波 `GL_NEAREST` → `GL_LINEAR`，恢复文字观感
- 双指缩放接到 libXlorie 的 `setRendererZoom` / `setZoomAnchor`
- 任务管理器在 winhandler 未就绪时回退到本地 `/proc` 进程列表
- 函数库新增 `winmm`，默认「原装先于内建」
- 移除 libXlorie 的 stderr 落盘逻辑（`Download/WinlatorCN-XServer.txt`）
- 修复新建容器时删掉的默认环境变量被迁移逻辑重新加回的问题（例如 `DXVK_HUD`）：从未启动过的容器没有记录 `appVersion`，被当成版本低于 16 的老容器而触发了默认值回填
- Box64 预设默认从「性能模式」改为「兼容模式」，性能模式关掉了 NaN/舍入的精确处理，老游戏容易算错数值或直接崩溃

**安装**

签名已更换，这一版必须卸载重装。上一版用的是另一台机器随机生成的 debug 密钥，本机已不存在、无法复现。从本版起改用仓库内固定的发布密钥（`keystore/release.jks`，RSA 4096，有效期 100 年），本地构建与 CI 产出一致，以后各版本可直接覆盖升级。

卸载会清空 `/data/data/com.winlator/files/rootfs`，容器、wine prefix、已装程序一并丢失，有要保留的先导出到手机存储。

- 包名 `com.winlator`，`versionCode 33`，`versionName 11.2.cn.04`
- 首次进入容器解压约 104 MB 资产（运行库/字体 44 MB + .NET 运行时 60 MB），一次性
- 签名指纹 SHA-256 `30909ca0af12275566620058d4285e00764d1d249ab85e8d6085b0522246830c`，可用 `apksigner verify --print-certs` 核对

**已知问题**

- 起点菜单中部分选项首次显示不全，鼠标扫过才补上。已排除渲染/上传/damage 链路，剩余怀疑在 Wine 自身的菜单绘制与裁剪，尚未解决
- 播放动画时画面上下翻转且无声音：把「函数库 → directsound」改成「内置 (Wine)」即可解决

更早的版本改动与安装包见 [Releases](https://github.com/isTurn/winlator-cn/releases)。

# 致谢与第三方项目
- 原项目 https://github.com/brunodev85/winlator
- GLIBC Patches by [Termux Pacman](https://github.com/termux-pacman/glibc-packages)
- Wine ([winehq.org](https://www.winehq.org/))
- Box86/Box64 by [ptitseb](https://github.com/ptitSeb)
- Mesa (Turnip/Zink/VirGL) ([mesa3d.org](https://www.mesa3d.org))
- DXVK ([github.com/doitsujin/dxvk](https://github.com/doitsujin/dxvk))
- VKD3D ([gitlab.winehq.org/wine/vkd3d](https://gitlab.winehq.org/wine/vkd3d))
- CNC DDraw ([github.com/FunkyFr3sh/cnc-ddraw](https://github.com/FunkyFr3sh/cnc-ddraw))
- RootFS & WFM by [Waim908](https://github.com/Waim908)
- WFM 文件管理器 [Waim908/wfm](https://github.com/Waim908/wfm)（内置版本 1.5-3.0）
- termux-x11 by [Termux](https://github.com/termux/termux-x11)（X 服务器后端 libXlorie）

特别感谢所有参与这些项目的开发者。<br>
感谢所有信任并支持本项目的人们。