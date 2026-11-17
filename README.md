# Mi Step · zhanshrd 维护分支

> 状态栏**双角横滑**唤出的多任务工作区：1 个主区 + 3 个副任务 + **1 个顶部横屏任务**，主区整屏缩放、副格独立运行。
> 基于 [JIUSIS/MiStep](https://github.com/JIUSIS/MiStep)（九思ing，锤子科技 One Step 开源项目的复刻）**v1.0.1 语义移植 + v1.0.2/v1.0.3 语义移植**，叠加本分支的 **HyperOS 4 / Android 17 实机适配、bug 修复与工程化重构**。当前版本 **1.4.0**。

- 上游项目：https://github.com/JIUSIS/MiStep （酷安发布帖作者：九思ing）
- 本分支实测环境：**Xiaomi 17 Max (byron) · HyperOS 4.0.3.0.XAFCNXM · Android 17 (SDK 37) · Magisk 30.7 + Zygisk Next 1.5.2 + LSPosed v2.2.0**
- 上游文档与原版说明见 [上游 README](https://github.com/JIUSIS/MiStep#readme)

---

## 与上游的关系

- **已完整同步上游 v1.0.1**（移植基线 `55172b5`，逐行对账核对）：顶部横屏任务与双指翻面、应用分身支持、快捷应用自定义（毛玻璃选择框）、全屏防误触入口规则、歌词译文排列、剪贴板修复、控制中心原生图标、剪贴板样式统一等。
- **有意分歧**（详见 [`docs/FORK_NOTES.md`](docs/FORK_NOTES.md)）：
  - 推荐作用域**保留 4 项**：本机实测 LSPosed 写入 `android` 值会被静默忽略，系统框架须以 `system` 勾选（BUG-3）；上游 1.0.1 起收窄为 2 项，本分支不跟随。
  - fork 版本线独立（`1.4.0` / `1004000`），不采用上游的特性串 marker。
  - 保留本分支全部修复与看门狗（见下）。

## 本分支的修复（相对上游）

| 问题 | 根因 | 修复 |
|---|---|---|
| 主区内容不缩放，全屏大小塞在框里 | **LSPosed 作用域缺失**：主区缩放本体在 system_server 侧（`DisplayContent.applyMagnificationSpec`），只勾 2 个时缩放执行端根本不加载。**必须 4 个全勾**（见下） | 安装说明修正 + 本分支 README 强调 |
| 副格任务归还后钉死在 always-on-top 置顶栈（QQ 事件） | 归还事务用 `setWindowingMode(0)`（UNDEFINED 从父容器继承=那个置顶多窗口根），且 WCT 发射后不管、同一事务重试 2 次即冻结 | 改显式 `FULLSCREEN(1)`，与归还验证条件对齐 |
| 工作区偶发卡死收不回（promotion/transfer 卡死） | 卡死时 suspend 无限排队、cancel 路径存在静默 no-op，上游无兜底 | **promotion 看门狗**（本分支独有）：>8s 温和取消一次，>20s 强制走正规失败汇总，全局闩全部释放 |
| SDK 37 上原生提交观察全部失效 | `addTransactionCommittedListener` 参数类型为 `SurfaceControl$TransactionCommittedListener`，传 `Runnable` 反射必然失配 | 改传 Proxy 实现（与 zoom 提交路径同款写法） |
| 潜在：工作区缩放永久失效且无自愈 | `ZoomDispatch` 会去重相同请求；WM 侧 spec 被外部清掉后，心跳重申永远被吞 | 低频读回真实 spec，发散即清 `lastAttempt` |

上游曾在说明中标注「分身应用不支持进入 Mi Step」——**1.0.1 已支持分身，本分支已同步**（本体与分身可同时收纳、分别切换，桌面打开对应各自任务）。

## 重构（行为不变，已完成）

单文件 ≤1000 行的硬性目标**已达成**：177 个 java 文件、0 超标。`MiStepHost`（原 7200+ 行）拆到 983 行，`HostEntry`（原 3376 行）拆到 658 行。全部拆分以**掩码保护的结构化改写**完成（字符串/注释永不被触碰），每批独立提交 + 23 项移植回归断言 + 真机 5 项冒烟，台账见 [`docs/FORK_NOTES.md`](docs/FORK_NOTES.md) §13。

宿主与入口现在由 16 个协作类组成：

**MiStepHost 侧（宿主状态机）**
- `HostSwapController` —— 触摸仲裁/收起/收集、主副交换、双任务交换管线
- `HostLiveSwapFinishController` —— live 交换收尾/焦点归还/缩放接收器/视觉恢复
- `HostPromotionController` —— 提升完成、失败汇总与回滚、原生观察
- `HostAdoptionController` —— 收养/归还/外部提升管线
- `HostTaskEventController` —— 任务事件回调与 slot 生命周期
- `HostLifecycleController` —— suspend/resume/park 会话生命周期
- `HostTopTaskController` —— 顶部横屏任务子系统（翻面手势、收放管线、方向观测）
- `HostCellLayoutController` —— 格子布局构建/紧缩/格移动动画
- `HostWindowController` —— 拖拽/格缩放/WCT 事务/触摸区域/窗口状态
- `HostGeometryController` —— 显示几何与逻辑边界钩子
- `HostSurfaceController` —— 保留表面重挂与覆盖层刷新
- `HostChromeController` —— 提示条/空格十字/壁纸底图/快捷应用刷新
- `HostLaunchController` —— 侧格启动/桌面图标发布/Home 任务识别
- `HostStateController` —— 任务用户缓存/原生观察/缩放状态/提升看门狗
- `HostDebugController` —— 调试广播/POC/链路追踪
- `HostStaticUtil` + `ZoomTransport` + 9 个顶层状态类（Slot、Promotion、LiveTask 等）

**HostEntry 侧（Xposed 入口）**
- `HostEntryZoom` —— 放大/显示缩放（WMS magnification 桥、live zoom、设置监听）
- `HostEntryRotation` —— 固定主区旋转与任务旋转解析
- `HostEntryNativeFreeform` —— 原生 freeform 共存（层级/触摸排除/pin）
- `HostEntrySupport` —— 系统服务钩子/触发器/调试广播分发
- `HostEntryLaunch` —— TaskView 创建/装载/启动链

### 工程化

- 断言式重构脚本、双档真机部署循环（见 `docs/FORK_NOTES.md`）
- GitHub Actions 未使用；本机构建脚本 `build.ps1`（无 Gradle，含 NDK）

---

## 安装（重要：作用域必须 4 个全勾）

1. ROOT + LSPosed 环境（本分支实测 Zygisk Next 1.5.2 + LSPosed v2.2.0）
2. 安装 APK（release 附件，或自行构建后 `su -c 'pm install xxx.apk'`）
3. LSPosed 中启用 Mi Step，**作用域勾满 4 项**：
   - `系统框架` ← **主区缩放本体在这里，缺了主区不缩放**（本机注意：作用域值须为 `system`，写 `android` 会被静默忽略）
   - `系统界面 (com.android.systemui)`
   - `设置 (com.android.settings)`
   - `com.xiaomi.type`（按需：阻止小米输入法横屏自动悬浮）
4. **重启框架**（软重启 zygote 或整机重启）

> 注意：模块更新后同样需要重启框架才对 system_server 侧生效——只杀 SystemUI 是不够的。

## 使用

- 状态栏任意一角**横向向内滑**唤出工作区；全屏应用内先拉出状态栏（通知面板不展开）再横滑——全屏防误触规则
- 收起：顶栏 `✕`、Home 手势、息屏；顶部横屏任务在位时点按可切回主屏视图
- 顶栏三个页面（左右滑动）：控制中心 ↔ 原生音乐 ↔ **横屏窗口说明页**
- **顶部横屏任务**：双指在顶部上下滑动翻面，或从控制区第三页进入；空位 `+` 收纳当前主屏应用；主区应用保留时顶部任务自动保留
- 快捷应用栏：点击启动；**长按打开自定义选择框**（搜索、多选、按选择顺序固定，配置持久化）
- 应用分身：本体与分身可同时收纳在副格，桌面打开时对应各自任务
- 调试广播（root）：`com.jiusis.mistep.TOGGLE` 开合工作区、`com.jiusis.mistep.DEBUG(slot,pkg,swap)` 定向启动/交换

---

## 自行构建

依赖：JDK 17、Android SDK（`platforms;android-37.0` + `build-tools;37.0.0` + `ndk;28.2.13676358`）、PowerShell 7。
签名用环境变量（或 `-Unsigned` 出未签名包）：

```powershell
$env:JAVA_HOME      = '<jdk-17>'
$env:ANDROID_HOME   = '<sdk>'
$env:MISTEP_KEYSTORE       = '<keystore路径>'
$env:MISTEP_KEY_ALIAS      = '<别名>'
$env:MISTEP_STORE_PASSWORD = '<口令>'
$env:MISTEP_KEY_PASSWORD   = '<口令>'
pwsh -File build.ps1     # 产物在 dist\，附 build-info.json 与 SHA256SUMS.txt
```

构建细节、双档调试循环、移植对账与踩坑记录见 [`docs/FORK_NOTES.md`](docs/FORK_NOTES.md)。

> 1.3.0 说明：v1.0.1 上游特性与重构均已在 Xiaomi 17 Max（HyperOS 4.0.3.0 / Android 17）真机回归通过（开→侧格→交换→收起全链路、顶部横屏、分身、5 项 root 广播冒烟、logcat 零 FATAL）。如在其他机型遇到异常，请反馈机型与日志。

## 许可

上游源码使用 [Mi Step 非商用共享源码许可 1.0](https://github.com/JIUSIS/MiStep/blob/v1.0.1/LICENSE)（Source Available），版权归 九思ing 及原项目所有；
第三方组件保留各自许可（见 `THIRD_PARTY_NOTICES.md` 与 `libs/licenses/`）。本分支所有改动以同一许可回馈社区。
