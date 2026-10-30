# Fork 维护笔记（zhanshrd）

工程纪要：调试循环、作用域坑位、bug 修复台账、重构地图。用户侧说明见 `README.md`。

## 1. 双进程架构（理解一切的前提）

| 进程 | 职责 | 关键类 |
|---|---|---|
| `com.android.systemui` | 工作区 chrome（顶栏/侧列/格底）、副格 TaskView + leash 矩阵缩放（k≈0.247）、触摸/手势、会话状态机 | `MiStepHost` + 各协作类 |
| `system` (system_server) | **主区缩放本体**：捕获 `DisplayContent`，`applyMagnificationSpec` 缩放整个显示屏；转场重放；手势监视器几何喂给 | `HostEntry.hookSystemServer`、`ZoomDispatch` |
| `com.xiaomi.type` | MiType auto-float 小钩子 | — |

## 2. LSPosed 作用域：4 个全勾

manifest 声明：`android;com.android.systemui;com.android.settings;com.xiaomi.type`。
**坑**：本机 LSPosed 上系统框架作用域值写 `system`（写 `android` 静默无效；HMA/Hyperceiler 均为 `system`）。
缺 `system` 作用域的症状：主区内容全屏大小不缩放（缩放执行端没加载），侧格正常——别误判成代码 bug。

## 3. 调试循环（两档）

```
pwsh -File tools\mstep_deploy.ps1           # 默认：构建→root安装→路径检查→zygote软重启→等开机 (~90s)
pwsh -File tools\mstep_deploy.ps1 -Light    # 快速档：仅 su 强杀 SystemUI (~17s) —— 只准在确认
                                            #   没动 system_server 侧代码时用
```

- 模块挂了 `system` 作用域后，**APK 重装必须重启框架**才对 system_server 生效；只杀 SystemUI 会得到"改了没生效"的假象
- 冒烟手势：`su -c 'am broadcast -a com.jiusis.mistep.TOGGLE -p com.android.systemui'`（必须 su + `-p`，shell uid 广播进不了 WRITE_SECURE_SETTINGS 门控接收器）
- 定向驱动：`com.jiusis.mistep.DEBUG` 广播（extras 见 `HostEntry` 接收器）→ debugLaunch(slot,pkg) / debugSwap(slot)
- 日志：`/data/adb/lspd/log/modules_*.log`，标签 `MiStepProbe`，进程前缀 `(system)` / `(com.android.systemui)` 区分侧别
- `am force-stop com.android.systemui` 必须 su（persistent 进程 shell 杀不动）
- 卡死急救：PC 侧 `tools\mistep_rescue.ps1`（取证 + 摘除 always-on-top 任务 + 重启 SystemUI）

## 4. Bug 修复台账

| ID | 症状 | 根因 | 状态 |
|---|---|---|---|
| BUG-1 | 副格任务归还后钉死 always-on-top 置顶栈 | 归还 WCT 用 `setWindowingMode(0)`(UNDEFINED 继承置顶父根)；fire-and-forget 重试 2 次即冻结 | ✅ 改显式 `1`(FULLSCREEN)，eae2d84 |
| BUG-2 | 工作区卡住收不回（两种形态：钉死任务 / overlay 残留） | promotion/transfer 卡死时 suspend 无限排队；cancel 在 SETTLING/nativeCompleting 静默 no-op | ⚠️ 管线修复后暂不复现；8s 看门狗待加 |
| BUG-3 | 主区内容不缩放 | **配置错误**：LSPosed 作用域缺 `system`，缩放执行端未加载（非代码缺陷） | ✅ 4 作用域全勾，13:52 实测 |
| FIX-4 | SDK37 原生提交观察全失 | `addTransactionCommittedListener` 传 Runnable，SDK37 要求 `TransactionCommittedListener` 接口 | ✅ Proxy 化，eae2d84 |
| FIX-5 | 潜在：缩放永久失效无自愈 | ZoomDispatch 去重吞掉外部清 spec 后的心跳重申 | ✅ 低频读回+发散清 lastAttempt，eae2d84 |
| TODO-6 | 主框触摸偏移（如有报告） | `native raw profile unknown`——libinputflinger 指纹仅内置 17PM/xaga，无 byron | 待定（当前手势几何喂给正常，可能无需） |

## 5. 重构地图

`MiStepHost`（原 7201 行）→ 协作类：`HostStaticUtil`（纯静态）、`ZoomTransport`（缩放指令三通道）、
`HostMirrorController`（镜像层/载体）、`HostSwapController`（触摸/收起/收集/主副交换）、
`HostPromotionController`（提升完成带）。方法：断言式精确搬移 + 宿主 shim 零调用点改动 +
按需放宽 private→包私有；每批独立提交 + 真机冒烟。

## 6. 待办

- [x] BUG-2：promotion 看门狗（独立 4s Runnable，不受 scene 门禁：>8s 温和取消一次、>20s 强制 failPromotion，复位防连发）—— dd77f0d
- [ ] 上游已知：分身应用不支持进入 Mi Step
- [ ] 视用户反馈决定是否做 byron 触摸指纹（`raw_dispatch_policy.h` 加 ROM profile）
- [x] version.json / manifest 版本号 fork 化（1.1.0 / 1001000）
- [ ] 已知怪癖（疑似上游既有）：DEBUG 定向启动「设置」到副格偶发 `launch watchdog fired`（Settings 冷启动 vs TaskView 绑定竞速）；普通手势启动不受影响

## 7. R1 全量评审台账（两路独立审查 + 交叉质证，134 文件 / 25054 行）

**确认为 bug 并已修复**（R1 修复提交）：

| 项 | 根因 | 修法 |
|---|---|---|
| 看门狗强制语义失效（A4≡B2, P2） | `failPromotion` 对 nativeSwap 票据委托 `requestLiveSwapCancellation` 后直接 return，而后者被 `cancelRequested`/`nativeCompleting` 闩死时静默 no-op——>8s 起全部强制路径为空转 | 取消路径改返回 boolean；失效时落入新的 `forceAbandonLivePromotion`（零 WM 写入的保守拆除：释放 promotion/input-blocker/suspend 闩，按未确认惯例冻结格子至会话退出自愈） |
| 陈旧完成泄漏加固（B1 加固） | 三个 live finisher 的 stale 早退分支不清 `h.promotion`（交叉质证证明常规不可达：`sceneEpoch++` 仅两处且被 promotion 门挡、taskEpoch 被 promoting 门挡；作为闩释放簇的廉价加固收编） | stale 分支统一走 `forceAbandonLivePromotion`（binding 已易主则不冻结格子） |
| TOGGLE 在 RESUMING 被吞（A5, P3） | `toggleVisibility` 只处理 SUSPENDED/ACTIVE，入场动画 ~0.7s 内的关闭请求静默丢弃 | RESUMING → `suspendSession("exit")`（其 972-978 行本就实现 RESUMING 挂起闩）；SUSPENDING → 记日志 |
| 拖拽收集无投放高亮（A6, P3） | `setDropHighlight` 的 `active` 参数被无视（两分支写同值） | active 时 hint scale 1.06 |
| 缩放退出失败路径旋转锁不对称（A7, P3） | exit 路径先放 rotation hold 再 apply spec，apply 抛异常时 catch 不回补 hold | catch 对称 re-arm（`!sFullscreenRotationActive` 守卫，begin/end 均幂等） |
| liveZoomReceiver 永不注销（B3, P3） | 首次 live flip 注册后进程生命周期常驻（无运行时危害，但注册/注销不对称） | `restoreLiveSwapVisual` 尾部在 `pendingLiveZoom == null` 时注销（下次 flip 惰性重挂） |
| OwnedTask.userId 跨线程（B4, P3） | Shell 线程写 / 主线程读无同步（结构竞争最坏自愈：suspend 停止并恢复 ACTIVE，1095-1102） | `volatile`；台账保留「shell-get vs main-remove 结构竞争」为已知自愈项 |

**裁决为非 bug 的评审记录**（后续轮次按已知项对待）：

- A1/A2/A3（拒绝路径冻结全家桶）：**误报**——3 参 `MiStepLiveSwap.Result` 硬编码 `rolesConfirmed=true`（MiStepLiveSwap.java:41-43），预检拒绝走 restore/clearSlot 分支；peer 抛点（HostSwapController:643-645）在 `nativeSwap=true`（:659）之前，`failPromotion` 走非 native 汇总不冻结。**残留设计限制**：存在 parked/frozen 格时 tap-swap 整体被拒（仅日志），属既有守卫语义，改动需真机验证。
- B5：`swapPreview`/`swapPreviewStatusOwner` 永为 null，swap-preview 租约状态机退役（四处理由守卫全部冗余，statusBar.finish 仍生效，无用户可见损失）——保留不删（删除=重构风险）。
- B6：live 家族 promote 方向 WCT 用 mode 0 与 BUG-1 教训「不一致」——**语境不同**：Shell transition + 同事务 reparent，成功以观察到的解析模式为门（`info(...,1)` 失败→FAILED→recover），误解析表现为交换失败+角色还原而非钉死。**禁止未经真机测试改成显式 1**。
- B7：`HomeTransitionMagnification.appendPendingGeometry`、`HomeSurfaceTrace.install`、`NativeReturnTrace.install` 为孤儿安装（属性门控 no-op）——接线前勿假设已生效。
- A8：`setPendingMainSide` 的 ACTIVE 守卫不可达（HotZoneGesture 源头丢弃）——注释已改为真实契约；「无手势关闭」为有意设计（关闭走顶栏 ✕ / TOGGLE / Home / 息屏）。
