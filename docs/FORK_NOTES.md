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
| 陈旧完成泄漏加固（B1 加固） | 三个 live finisher 的 stale 早退分支不清 `h.promotion`（交叉质证证明常规不可达：`sceneEpoch++` 仅两处且被 promotion 门挡、taskEpoch 被 promoting 门挡；作为闩释放簇的廉价加固收编） | stale 分支统一走 `forceAbandonLivePromotion`（R3 勘误：见 §10，stale 路由一律不冻结） |
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

## 8. R2 评审台账（全量重读 + R1 改动面轰炸，134 文件 / 25107 行）

**确认为 bug 并已修复**（R2 修复提交）：

| 项 | 根因 | 修法 |
|---|---|---|
| 完成窗口内误冻结已确认重绑格（R2-A1, P3） | `failPromotion` 兜底硬编码 `keepCellUnfrozen=false`；`nativeCompleting=true` 的完成窗口内（角色已落定、槽位已 `clearSlot`+`bindTask` 重绑）到达的 failPromotion（5s 租约到期 / 折叠几何变化 / suspend / 看门狗）会把健康的新绑定一并冻结 | 判别式改 `ticket.nativeCompleting`：完成窗口不冻（交还已确认绑定），未完成楔死（`cancelRequested` 闩死）维持未知结局冻结（交叉质证修正：`bindingIntact` 在闩死场景为 true，是错误判别式） |
| RESUMING 滑动污染下次打开方向（R2-A3, P3） | `hostSceneActive()` 在 RESUMING 早期为 false，滑动通过手势门 + `setPendingMainSide` 的 ACTIVE-only 守卫，在 `consumePendingMainSide` 已消费后写入，值存活到下一次打开并覆盖朝向 | 守卫放宽为 `scene != SUSPENDED`：所有过渡期滑动不再改写下一次打开方向（残留：过渡期滑动仍会被手势源截留吃掉触摸，属既有窗口，量级 ~0.5-1s） |
| force-abandon 缺 hint/touchRegion 恢复（R2-B9, P3） | 新拆除池相对镜像约定（`completeLiveCollect`/未确认尾部）缺 `updateHint`/`updateTouchRegion`；collect 的 `prepareCollectHandoff` 在租约外置 `hint GONE`，楔死进看门狗强制路径后格子无任何示能（冻结变体下 TaskInfo 回调被 promoting 门挡住，持续到会话退出） | `forceAbandonLivePromotion` 内 `restoreLiveSwapVisual` 之后补 `h.updateHint(slot)` + `h.updateTouchRegion(slot)` |

**裁决为非 bug 的评审记录**（并入已知项）：

- R2-A2（看门狗 nudge 驱动非 native 票据走 `finishLiveSwap`）：**误报**——合成失败到达的终态（`frozen=true, promoting=true`）与 `failPromotion` 外部门分支逐字节一致；「被绕过的簿记」全部为退役空操作（swapPreview/liveLift/mainLeash/compactSnapshot 在此路径从不赋值），仅 trace/日志形状不同。属路由卫生问题，不改。
- R1 遗留说明：`forceAbandonLivePromotion` 拆除时不清 `coveredHome`——仅交换成功尾部会清；若强制拆除恰好落在成功完成窗口内，`coveredHome` 残留由下次挂起的 token 校验兜底（低概率 × 低危害，观察项）。

## 9. 运行时验证豁免（操作者在案指令）

**操作者原话（2026-10-30 会话记录）**：「我要拔掉手机了，你后续复查都自己看，别手机联调了，遇到问题你自己搜别问我。」

据此，以下改动路径**仅通过编译 + 静态断言验证，未做真机运行时验证**（接受风险）：

- R1（c7126a0）：`forceAbandonLivePromotion` 拆除池、`requestLiveSwapCancellation` 布尔契约、`toggleVisibility` RESUMING 挂起闩、`restoreLiveSwapVisual` 接收器停靠、`OwnedTask.userId` volatile、HostEntry 旋转锁 catch 回补、`setDropHighlight` scale。
- R2（258cbf2）：failPromotion 兜底 `nativeCompleting` 判别式、`setPendingMainSide` 的 `scene != SUSPENDED` 守卫、force-abandon 内 `updateHint`/`updateTouchRegion` 补齐。

**豁免理由**：JVM 单测需把纯逻辑抽成可实例化形态＝源码重构，清白轮禁止；以静态枚举证明（调用点全量清单 + 场景真值表）替代。

**设备回归时应补的冒烟**（4 项，对应改动路径）：① RESUMING 期间角部滑动——方向不被污染、toggle 语义正常；② promotion 完成窗口内触发 cancel（几何变化/租约到期）——已重绑格不被冻结；③ collect 楔死 >20s——看门狗强制拆除后格子恢复「+」示能；④ 常规开→侧格启动→主副交换→收起全链路 + logcat 零 FATAL。

## 11. 上游同步手册（2026-10-31 查证）

**查证结论**：上游 `JIUSIS/MiStep` 自本 fork 分叉点 `32555b4`（= v1.0.0 发布态，upstream 最后推送 2026-10-28T19:07Z）之后**零新提交、零新分支、零新 tag**（唯一分支 main @ `32555b4`，唯一 Release v1.0.0）。本 fork 严格领先上游（含重构 + BUG-1/2/3 修复 + 看门狗 + 1.1.0 版本线）。「上游有新特性」的说法与仓库事实不符——若酷安等渠道出现新特性 APK，其源码尚未按 NC-SA 要求公开，无法同步。

**未来同步姿势**（本 fork 已深度重构，禁止直接 merge——`MiStepHost` 拆分后结构不同，必然冲突）：

```powershell
git fetch upstream --prune
git log --oneline 32555b4..upstream/main   # 上游新提交清单
git diff 32555b4...upstream/main --stat    # 上游改动面
```

逐提交评估 → 语义移植到本 fork 结构（协作类已拆分，勿整文件覆盖）→ `build.ps1` 编译 + 静态断言 → 若版本吸收上游特性则 fork 版本号 +1（如 1.2.0/1002000，`HostEntry` 三处 runtimeMarker 对齐）→ 按 /goal 常设令，**移植面需重新过双路评审**。

远程：`origin` = zhanshrd/MiStep（本 fork），`upstream` = JIUSIS/MiStep（已配置，走 socks5h://127.0.0.1:10808 代理）。

## 12. v1.0.1 上游移植（2026-11-01，移植基线 55172b5）

**移植方式**：语义移植（非 merge——本 fork 的 MiStepHost 已拆分，结构不同）。
- 整取 40 个无分歧文件（逐字节对账 = 与上游一致）+ 16 个新类 + cpp（updateTopNative）+ tests + tools。
- 三方语义合并 4 个文件：MiStepHost（68 hunks）/ HostEntry（21 hunks）/ HostSwapController / HostPromotionController。
- **有意分歧**：① 不采纳上游收窄的推荐作用域与 build.ps1 断言（本机 BUG-3 实测需 `system` 值，保留 4 作用域）；② 保留我们的 F-A7 catch 回补、zoom 自愈、看门狗、force-abandon、全部闸门；③ fork 版本线 1.2.0（1002000），简洁 marker。

**逐行对账**：上游 700 个功能新增行逐行核对——HostEntry 0 未匹配；MiStepHost 首轮 86 条未匹配中 48 条为 h. 前缀/跨类归属/可见性放宽的审计局限，**5 处真漏网已补**（retained-leash 裁剪、applyFullscreenBounds、applySlotScale 的 TOP 视口适配）。

**特性清单核对**（对照官方 v1.0.1 更新说明逐项）：顶部横屏窗口 ✓ 双指翻面+控制区第三页 ✓ 快捷应用自定义（长按=毛玻璃选择框，取代旧版长按拖选；固定顺序=选择顺序）✓ 应用分身 ✓ 全屏防误触入口 ✓ 歌词译文排列 ✓ 剪贴板清空复现修复 ✓ 控制中心原生图标 ✓ 剪贴板样式 ✓。

**真机冒烟欠账**（§9 豁免延续）：TOP 窗口收放、双指翻面、分身同收、全屏防误触入口、全链路回归。

**等着的预告**（作者已宣布、尚未上传源码，2026-10-31 用户转述）：顶部横向区域（topStrip 顶条带）也将能容纳一个「框」（副格）。开源后移植时注意：该特性落在 `MiStepTopBar` / `MiStepLayout`（topStrip 布局）/ `HotZoneGesture`（顶条触摸）与我们的顶栏控制中心切换逻辑交界处，本 fork 顶栏结构已深度改造（控制中心↔音乐面板切换、状态栏租约），**不可整文件覆盖，按 §11 手册语义移植 + 重开评审**。

## 10. R3 评审台账（全量重读 + 审计点复核，134 文件 / ~25.1k 行）

**确认为 bug 并已修复**（R3 修复提交）：

| 项 | 根因 | 修法 |
|---|---|---|
| stale 路由判别式反相（R3-A1, P3, 【LEDGER-CHALLENGE】成立） | 三条 stale 路由给 `forceAbandonLivePromotion` 传 `bindingIntact(ticket)`，而 `keepCellUnfrozen=true` 才是不冻结——实际语义与 javadoc/R1 提交信息/本表旧表述**三处文档全部相反**（绑定未变→不冻、已易主→冻）。危害链：已易主+空槽 ⇒ promoting=true 无任何复位器（parkNext/resumeNext 跳过空槽、TaskInfo 回调被 promoting 门挡、clearSlot 不可达）⇒ 死「+」格跨挂起/恢复存活。R2 白盒两轮误读参数极性判 SOUND，R3 黑盒抓出——交叉质证机制的有效性实证 | 三条 stale 路由一律传 `true`（票据已死时，槽位/任务调和机制是唯一愈合者，冻结恰好禁用它）；删除零调用的 `bindingIntact`；javadoc 改为真实策略（冻结仅保留给 inert-cancellation 楔死场景） |
| parked「恢复」点击在 promotion 进行中不受门控（R3-B1, P3） | `onSlotTapped` 只查 `scene/transferBusy` 不查 `h.promotion`，`adoptExisting` 自身门也不查——全部兄弟入口（swapWithMain/captureMainIntoEmptySlot/dismissSideToBackground/launchIntoSlot/promoteForNotification/beginExternalPromotion）都设了 promotion 门，唯此一处遗漏；flip/home 的 promotion 早期窗口（input block 尚未上）内点 parked 格 ⇒ 并发 adoption WCT 流，最坏令新升格的格冻结到会话退出 | `onSlotTapped` 门补 `\|\| h.promotion != null`（`adoptExisting` 处加第二道 belt 冗余，不加——此分支已是唯一无门入口） |

**裁决为非 bug 的评审记录**（并入已知项）：

- R3-A2（HotZoneGesture 65ms 延迟触发 × 并发 opener 的跨源竞态：刚开的会话被延迟 toggle 关闭）：机制确认，但**无干净修法**——延迟 runnable 里加任何场景门都会同时杀掉 F-A5 的合法「RESUMING 期间再次滑动=取消」；区分两种场景需要从 HotZoneGesture 向 MiStepHost 传入打开归因状态（结构性改动，无真机验证条件）。记为 **F-A5 的已知接受代价**（65ms 窗口、可恢复、零状态损伤）。
- R3-A3（三处休眠接线）：`AppRawInputHook.install` 全包零调用（app 进程在 handleLoadPackage 提前返回，永不武装；被 `NativeRawInputBridge.install` 取代）；`MiStepWeChatNative.install/.request` 仅 tests/ 引用（生产走 `MiStepWeChatShortcut.open`，tests 保留作 WeChat 更新形状金丝雀）；`HostMirrorController.attachMirrorCarrier` private 零调用——**连带**：`slot.carrier=true` 仅在其中赋值，整套 carrier 下游（reassertCarrier/restoreCarrierToMain 的 carrier 分支/carrier 底板）当前为惰性空转。接线前勿假设生效（与 B7 同族）。
- R3-B2（接收器在 pendingLiveZoom≠null 时跳过停靠、事后无人补停）：**维持台账**——严格窄于 R1 修复前的已裁决状态（修复前永不注销），pending 窗口必有界自终止（ACK 或 ≤1.5s 超时），正确性无损；无新严重度证据，不构成 finding。
## 13. 全仓文件拆分收尾（2026-11-02，批次 F-V）

目标：所有源文件 ≤1000 行。起点 MiStepHost 4240 / HostEntry 3377 / HostSwapController 1251，终点 177 个 java 文件全部达标、0 超标。

### 批次链（每批独立提交，javac + port_retest 23 项全过后提交）
- F：debug/POC/trace → HostDebugController（批次 E 重试，掩码方案）
- G：显示几何 → HostGeometryController（旧批次 4 重试）
- H：保留表面 → HostSurfaceController
- I：格子布局/动画 → HostCellLayoutController
- J：提示条/壁纸/顶栏 → HostChromeController
- K：任务事件/slot 生命周期 → HostTaskEventController
- L：启动/桌面图标发布 → HostLaunchController
- M：拖拽/缩放/WCT/窗口状态 → HostWindowController
- N：任务用户缓存/根枚举/看门狗 → HostStateController
- P：9 个嵌套数据类提升为顶层（Slot/Promotion/LiveTask 等），全模块 MiStepHost.X 改名
- Q：live 交换收尾 → HostLiveSwapFinishController（首个 controller-of-controller 批次）
- R/S/T/U/V：HostEntry 拆为 Zoom / Rotation / NativeFreeform / Support / Launch（static-src 模式）
- MiStepHost 4240→983，HostEntry 3377→658，HostSwapController 1251→901

### 工具（tools/splitkit.py）
掩码保护改写（字符串/注释永不被改写）+ 三类白名单（实例→h.、静态/嵌套→MiStepHost.）+ 局部名遮蔽排除 + shim 按需生成（一律包私有、保留 throws 子句）。修过的坑：声明类型可含标点/纯空格、'==' 不是赋值、'return new X(' 假方法、多声明符字段、构造器被当成员、0 缩进成员、内部类 h.new X、this::/MiStepHost.this/sInstance==this 语义、checked exceptions、controller-of-controller 与 static-src 模式。

### 真机回归
每次部署后 root 广播 5 项冒烟（TOGGLE 开 / DEBUG slot+pkg 侧启 / --ez swap true 交换 / TOGGLE 关 / logcat 零 FATAL）。最终构建 SHA256=a0c1b1e6 5/5 全过。
- 准入守卫语义：熄屏/锁屏时 toggleOrStart 静默拒绝（canOpen=false）——观察端假象，不是回归。测试前 `su -c 'svc power stayon true'` + WAKEUP。
- HyperOS 拒绝 shell 身份触发 EXPORTED 接收器，广播必须走 su（已录 learning）。

### 已知行为改动（有意）
- 9 个嵌套数据类从 MiStepHost 内嵌提升为包级类；引用点全模块改写。
- LiveZoomRequest/NativeObservation 保持引用宿主，但前者改为顶层类显式传 (host, sc)。
- ZoomTarget 去掉 owner 字段（入口静态化后无需实例回调）。
- HostEntry 一批实例方法转静态（原依赖全静态状态，语义等价）。
- build.ps1 runtime-marker 校验从单文件改为模块目录计数（第三个标记随 hookSystemServer 迁移）。


### 版本提升（同日）
拆分收尾随发布提升为 **1.3.0 / 1003000**（runtimeMarker build=1.3.0 release ×3，随 hookSystemServer 分驻 HostEntry×2 + HostEntrySupport×1；build.ps1 与 port_retest F-11 均按模块目录计数）。1.2.0 构建归档于 dist/archive-1.2.0-pre-split/。

## 14. v1.0.2/v1.0.3 上游移植（2026-11-17，移植基线 55172b5 → 48e96f4，fork 1.4.0）

**移植方式**：语义移植（§11 手册；非 merge）。上游 2 个提交（62cd0f7 Release 1.0.2 / 48e96f4 Release 1.0.3），共 216 文件 +16490/−1344。

### 整取（205 个文件，逐字节等于上游 48e96f4）
- 上游改了而 fork 自分叉点从未碰过的全部文件：50 个新 Java 类（ShellCompat/WindowRootsCompat/TransitionMagnificationCompat、WorkspaceOptions/Settings 五件套+SettingsActivity、MiStepKeyguardZoom/KeyguardTransition/RetainedWindow/UnlockHandoff、RuntimeDiagnostic*/RuntimeLog*/RuntimeSystemTrace/TraceFormat、MiStepTopSystemBars/TopInsets/TopPointerFocus/TopFocusNotification/TopNativeCaption 增量、MiStepImeViewport/ImeTargetRefresh/ImeWindow、MiStepSwapOverlayBridge/Frames/Selection+SwapPanelLease、MiStepTopBack/BottomCenterSwipe/BottomReplayInput/PassiveInputChannel、MiStepWorkspaceBackdrop、MiStepSyncPreparation、MiStepLegacyMedia/NativeRecents/BiometricWindow/PassBlurCoordinates/WindowIdentity、EntryGesturePolicy/Settings、SystemSettingsEntry、WorkspaceDisplayPolicy、ControlCenterTileGeometry 等）+ 51 个 fork 未动过的修改文件（MiStepLayout/TopBar/TopRoll/TopTaskTransition/StatusBar/ImePolicy/ImeProcessConfiguration/DesktopLaunchBridge/LiveSwap 家族/Os4Controls/MediaLyric*/NativeMedia/WallpaperScale/ZoomAnimCommand/DeviceProfile 等）+ cpp（native_raw_input.cpp/raw_dispatch_policy.h）+ res（styles、ic_settings_mistep、捐赠二维码）+ tests + 44 个新 tools/*-jvm 双胞胎套件 + docs（发行说明/验证说明 1.0.2/1.0.3、澎湃OS3适配）。
- **不整取**：README/README.en/docs 使用说明（fork 自有）；fork 有意删除的 1.0.1 旧文档不复活；build.ps1/test.ps1/version.json/manifest/MiStepHost/HostEntry（语义合并）。

### 语义合并（路由到拆分后结构，宿主 shim 零整文件覆盖）
| 上游文件 | fork 落点 |
|---|---|
| MiStepHost.java（120 hunks） | MiStepHost 壳（新字段/SLOT_TOP=1+MAX_CELL_COUNT/CELL_SLOT_COUNT 转实例/静态入口 onNativeWake·handoffNativeUnlock·空主区手势/topDismiss 静态组）+ HostLifecycleController（锁屏保留全套 setKeyguardWindowHidden/retainWorkspaceForLock/leaveKeyguardPrivacy 重写、工作区壁纸底座 prepare/release/hole、入场门控）+ HostStateController（setZoomState 门禁、checkAdoption TOP caption+收集补位）+ HostGeometryController（installLogicalBoundsHooks 全量重写：OS3 startAnimation 5/6 参、closeStartTransaction、legacy onTaskInfoChanged 桥、setWindowBounds/lambda 兜底、findTaskView 守卫、MiStepKeyguardTransition.install；refreshDisplayGeometry 容量变化收会话+平板分支）+ HostSurfaceController（keyguardTransactions 接线、appendHiddenKeyguardSurfaces、installNativeStart、onTransitionReady 0x100 标记、refreshOverlay 门禁、mayWriteSideSurface roll 豁免）+ HostWindowController（tablet 几何/触摸区/interactiveBounds、executeOnShell 宿主 executor 优先、roots 数组泛化）+ HostCellLayoutController（buildLayout：workspaceSettings/MiStepLayout 5 参/topLiveClip/tablet 格底/onTaskCellsCovered；updateCellSurfaceVisibility 拆 ownsCellPresentation/cellPresentationVisible；applyRect paneLocal；compactCellsAfterCollection）+ HostTaskEventController（createNext/创建 TOP 视图 setEnableSurfaceClipping+setZOrderOnTop+topLiveClip 挂载、taskCellsCovered 点击门、legacy currentTaskInfo 桥、TOP caption 刷新、ActivityOptions 后台启动模式）+ HostChromeController（updateHint 覆盖门、applyWallpaperBackdrops 平板 mainGap/top 背板+updateWorkspaceBackdropVisibility）+ HostSwapController（onSlotTouch 覆盖吞噬、交换模式 WorkspaceOptions 选目标布局、MiStepSwapOverlayBridge.acquire 包裹 startFlip、liveSideStrip 平板）+ HostTopTaskController（**整簇重写**：live 翻面逐帧渲染取代快照、平板工具条 configureTabletTools、收集转场 setForceTranslucent+PREPARE_TOP 视口广播、顶部横滑归还 topDismiss、restoreTabletCoveredMain、TopCandidate 提为包私有）+ HostAdoptionController/HostPromotionController（ShellCompat.call/transaction、OS2 onTaskVanished 先于 tx.apply；**保留 fork BUG-1 显式 FULLSCREEN=1，上游为 0**）+ ZoomTransport（anim at= 平板延时、锁屏强制 1 0 0）+ HostStaticUtil.log（诊断事件钩）+ HostLaunchController（topInput left/top、owners 泛化） |
| HostEntry.java（50 hunks） | HostEntry 主文件（handleLoadPackage 设置入口分支+MiStepNativeRecents+MiStepBiometricWindow+installRetainedWorkspaceWakeHooks、keyguard/retained/topSystemBars/imeViewport/imeTargetRefresh/topFocusNotification/passBlur 静态实例、log 诊断钩）+ HostEntrySupport（hookSystemServer：sKeyguardZoom.install+sRetainedWindow.install、shouldMagnify 解锁 NotificationShade/IME viewport/排除策略重做、canBeImeLayeringTarget→canBeImeTarget 兼容+shouldControlIme、installWorkspaceWindowFocus/TopFrontNotifications 新装、RunningTasks focused-TOP 保留+getTasksUnlock、WorkspaceFocus isTopAppFocused 门、canAffectSystemUiFlags TOP 视口、stagePassBlurCoordinates、hookShellProviders provideRootTaskDisplayAreaOrganizer+MiStepSyncPreparation.bindProvider、hookApplication 诊断初始化、Triggers fs_gesture/recentapps、DEBUG diagnosticRequest+legacyBottomSign、newTarget 参数偏移）+ HostEntryZoom（armZoomBridge 8 个新 action+IntentFilter+BottomReplayInput.install、reapplyFinalMagnification ShellCompat.call、PHYSICAL_KEYGUARD 解锁遮罩世代、applyDisplayZoomLocked nativeVisibility 重载+sKeyguardZoom.blocked 钳制、动画 startUptimeMs、buildFinishTransaction finishDisplays）+ HostEntryRotation（installTopSystemBarReturn、InsetsPolicy 双钩、workspaceDisplayRotation、endFixed 清理、resolveTaskRotation sHeldDisplayRotation 门+参数、sendTopTaskDirection 免 TaskInfo 聚合重做、isUpperLandscapeTask→ownsTopViewport）+ HostEntryNativeFreeform（ShellCompat.call ×2） |
| AndroidManifest.xml | +WorkspaceSettingsProvider+SettingsActivity(Theme.MiStep.Settings)；minSdk 24→34；**保留 fork 4 作用域**（上游收窄为 3，BUG-3 实测需 system，有意分歧①延续）；版本 1.4.0/1004000 |
| version.json | fork 线 1.4.0/1004000，简洁 marker（上游特性串不采纳） |
| build.ps1 | 仅吸收 aapt2 --min-sdk-version 34、d8 --min-api 34；平台 jar 保持 android-37.0（本机仅此平台，-I 仅编译基座）；**不采纳上游推荐作用域断言**；fork 目录计数 marker 校验保持 |

### 有意分歧（fork 保留，勿"修复"）
① 4 作用域（android/systemui/settings/xiaomi.type）+ 无作用域断言；② failPromotion/fullscreenExitTransaction 显式 FULLSCREEN=1（BUG-1）vs 上游 0；③ 看门狗/force-abandon/R1-R3 全部闸门与修复原样保留；④ 简洁 runtimeMarker（build=1.4.0 release ×3：HostEntry×2+HostEntrySupport×1）。

### 验证（§9 豁免延续：编译+静态，未真机）
- build.ps1 -Unsigned 全链路通过：javac 177→231 文件、clang native、aapt2、d8、打包。产物 MiStep-1.4.0-unsigned.apk SHA256=7f7c8ad2…。
- marker 计数 3/3；manifest↔version.json 一致性断言过。
- tests/*.java 策略测试全过；tools/rotation-jvm 过（205+69+8 checks）；tools/*-jvm 55 套件过/42 失败。**42 个失败全部是抽取式套件按签名从 MiStepHost.java 单文件抽方法体所致的既有结构限制**：main（1.3.0）基线同失败 29 个（拆分收尾时已接受的取舍，§13 以 port_retest 代替）；本次新增 16 个失败均为上游新增同型套件，另 28 个新套件直接通过。**零回归**：main 上通过的套件在本分支全部仍通过（旧套件另有 3~4 个转好）。如需全绿，需按 controller 结构重写各 run.py 抽取器（独立任务，未列入本次）。

### 真机回归欠账（部署后 5 项冒烟 + 新特性专项）
① 全链路开→侧启→交换→收起 + logcat 零 FATAL；② 锁屏保留：锁屏→解锁工作区原地恢复（含指纹解锁 handoffNativeUnlock 路径）；③ 设置页：系统设置→Mi Step 入口、按钮显隐、双交换模式（固定主区域走一次交换）；④ 底部横滑唤出（需关系统小白条）；⑤ 平板分支（本机 17 Max 手机形态，tablet 路径预期不激活，验证不受影响即可）。

### 真机回归结果（2026-11-17，操作者实测「试了没啥问题」）
1.4.0 签名包（dist/MiStep-1.4.0.apk，SHA256 be561fae…）实机验证通过。祖先对齐 merge（1949b28）随之落库：`git merge upstream/main` 全部冲突按 ours 解决 + 还原 test.ps1 自动合并混入的平台改动，**合并树 == 合并前树（9e3c031e）逐字节一致**，仅使上游 48e96f4 成为 main 祖先——GitHub fork「behind JIUSIS:main」横幅清零。**未来同步的标准姿势 = §12/§14 语义移植 + 收尾祖先对齐 merge**（内容永远只走语义移植提交；merge 提交本身必须是零内容变更）。
## 15. v1.0.4 上游移植（2026-11-24，移植基线 48e96f4 → 7e6131c，fork 1.4.1）

**移植方式**：语义移植（§11 手册）。上游 3 个提交（56b3145/ebaca05 Release 1.0.4 + 7e6131c docs），86 文件 +6185/−719；上游 11-20 还原地更新过包（内部版本 1000257），git 最终态即移植源。

### 特性清单
「无限小窗」（手机侧栏上下滚动/平板顶部左右滚动 + 惯性 fling + 视口外行裁剪不可获焦 + 桌面点开滚动行自动滚入）、普通退出保留副窗与滚动位置（retainedOnExit，再次进入 confirmRetainedResume 淡入）、手机竖屏 TOP 本地视口旋转（MiStepTopRotation/MiStepRotationAnimation/TaskLaunchIdentity 三新类 + 共享旋转租约生命周期）、模块设置页捐赠致谢在线名单（DonorFeed，唯一网络外联：jiusis.github.io，仅设置页打开时读，空 donor_feed_url 禁用）、快捷应用栏惯性滚动、微信小程序/公众号、平板横竖屏等一批修复。

### 整取（77 个文件）
fork 自 48e96f4 未碰过的全部上游改动：4 个新 Java 类 + 19 个修改 Java（MiStepLayout/MiStepTopRoll/MiStepTopBar/ShellCompat/WorkspaceOptions 等）+ donors.xml + docs（发行/安全/验证说明 1.0.4）+ tests（TaskRotationLifecycleTest 等 10 个）+ tools 新套件（workspace-retention/phone-workspace 等）。

### 语义合并（7 文件 + 2 个新 controller）
- **HostSideScrollController（新，691 行）**：侧栏滚动整簇（上游 MiStepHost 内 ~620 行）原样搬出；Slot 持有同一 TaskView，sideOrder 逻辑行 ↔ slots[] 物理下标仅在滚动停稳时重映射（mapSideSlots）；固定显示坐标视口容器层挂 TaskView 容器表面。
- **HostEntryTopRotation（新，483 行，全静态）**：手机 TOP 本地视口旋转簇（上游 HostEntry 内 ~580 行）；共享旋转租约（sTaskRotationTransforms 弱引用放回 HostEntry 供 JVM 回归反射驱动）、closingTopRotations 过渡对账、MiStepRotationAnimation 可选加速（失败不影响配置/清理）。
- **HostLifecycle**：suspendSession 记录 pendingSuspendReason + canRetainBoundTasks→finishRetainedSuspend（退出保留，不清槽不归还）；resume 侧 releaseForegroundRetainedTasks（桌面点开的任务还主区）+ confirmRetainedResume（表面就绪后淡入）；setWorkspaceAlpha 视口整组隐形；cellPresentationVisible 纳入 sidePrepared。
- **HostAdoption**：returnToSystem 增加 nativeAlreadyFullscreen 手回（finishNativeReturnWhenIdle runOnIdle 清覆盖）与 applyRetainedDesktopReturn（等窗口绘制提交再确认）；releaseRetainedTaskToMain/finishExternalTopLaunch；preannounce 支持 retainedRoute 与滚动行 reveal；sideTaskMatchesEntry 按入口组件匹配（修微信小程序误切）。
- **HostPromotion**：fullscreenExitTransaction 拆出 appendTaskViewExitOverrides；handoff 用 revealTransition.initialRevealFrame()（平板推回从松手位置横向拉开）；交接后恢复主区不透明度等提交回调。**fork 分歧保留：显式 FULLSCREEN=1（BUG-1），上游为 0。**
- **HostTaskEvent**：createNext/onTaskViewCreated 改为按 Slot 传递（支持追加行）、SurfaceView TRANSLUCENT、sideRow 行初始 INVISIBLE、onTaskInfo 的 collected-row mode1 回声防抖 + retainedOnExit 分支 + finishExternalTopLaunch。
- **HostSwap**：onSlotTapped 先过 prepareSideRowTap；平板滚动行滑回方向改为向上；startLiveHome 枚举全部 presented side roots（SideRoot）。
- **HostWindow**：slotAtPoint 视口门 + 隐藏行不可点；scaleFor 滚动行按裁剪矩形；updateTouchRegion 视口裁剪；appendSlotViewport（leash 裁剪在未旋转的 SurfaceView 本地像素，动画之外）。
- **HostCellLayout/Chrome/Geometry/State/Launch/Surface/TopTask**：compactCells 让位滚动行补位；applyRect sideRow 分支 + 固定行显式 crop；updateHint presentsSlot 门 + 补位行隐藏；refreshEmptyHintBackdrop 增量换底；refreshDisplayGeometry 平板 SUSPENDING 停扫描 + 滚动行重映射 + 退平板恢复 topFace；desktop-icon 广播带 component；trackRetainedSurfaces 记录 CHANGE start 事务 + isBorrowingPeer 保护；releaseTopTask 先翻面再交接（topRoll.show(false, handBack)）；beginTopDismiss 平板推回起始帧。
- **HostEntry 族**：MiType 自动浮动限平板（手机恢复原生瞬态浮窗）；adjustMetrics 变体；ActivityRecord shouldRelaunch/relaunch/startFreezing/prepareSurfaces 五钩（TOP 重建布局保留真 Window）；WindowToken finishFixedRotationTransform 保留钩；dispatchProposedRotation → queueWorkspaceRotationRefresh；armZoomBridge 租约刷新重跑旋转策略；Transition finishTransition/abort/getLeashSurface 三钩（本地动画收尾交接）；desktop icon 广播带 component。
- **AndroidManifest**：+INTERNET（捐赠名单）；SettingsActivity +configChanges（横竖屏/多窗不重建）；minSdk 保持 34；**fork 4 作用域保留**。version.json 1.4.1/1004001，marker build=1.4.1 release ×3。

### 验证（§9 豁免延续：编译 + 静态，未真机）
- build.ps1 全链路过（javac 251 文件 / clang / aapt2 / d8 / 打包）；签名包 dist/MiStep-1.4.1.apk SHA256=af08af8e…（含实机调试修复）。
- tests/*.java 策略测试全过；rotation-jvm **205+24+69+8 全过——含上游新增 TaskRotationLifecycle 24 项旋转生命周期回归（驱动真实 releaseTaskRotationLocked/retain 语义，直证旋转簇移植正确）**。
- tools/*-jvm 57 过 / 44 "失败"：与 v1.4.0 基线逐一对照，**零回归**；多出的 3 个 = rotation-jvm（无 run.py 的统计误报，run.ps1 全过）+ workspace-retention/phone-workspace（上游新增抽取式套件，与既有 42 个同类结构限制）。
- 行数（如实）：MiStepHost 1184、HostEntryRotation 1119、HostEntrySupport 1279 三文件本轮超 1000（旋转族/侧栏族接入 shim 所致，与既有超标的 HostEntrySupport 同类，拆分列入待办）；HostEntry 702、HostSideScrollController 691、HostEntryTopRotation 483 达标。

### 有意分歧（延续）
① 4 作用域；② 显式 FULLSCREEN=1（BUG-1）；③ 看门狗/force-abandon/R1-R3 闸门；④ 简洁 marker；⑤ 捐赠名单保留上游 endpoint（INTERNET 权限新加，见 manifest 注释）。

### 实机调试发现的移植缺口（2026-11-25，部署后当即修复）
首版部署后「侧栏竖滑无响应」实机定位出 **9 个漏网 hunk**（逐 hunk 走查时被"无显式特性标记"的行掩盖）：①root dispatchTouchEvent 接入 trackSideScroll（致命——侧栏滚动手势唯一入口）；②root addOnAttachStateChangeListener→observeSideViewportRoot；③createSubViews 创建 sideInputViewport（致命——TaskView 全部 fallback 挂 root，视口容器层从未存在）；④TaskView surfaceCreated 钩（回收任务提前裁剪+挂视口）；⑤leaveExternalChangeToNative 保留退出分支；⑥isActive 接受滚动行（slots 数组可变长后按快照判定）；⑦canRender 要求 sidePrepared；⑧setTaskCellsCovered 遍历全部滚动行；⑨launch 重复入口按组件匹配（微信小程序）+ debugLeashAnim ShellCompat。修复后实机复测：`side arm maxOffset=1 → settled row=1`（空档滚入视口），收纳链路打通。
**教训**：语义移植的逐 hunk 对账必须包含"无日志标记的行为接线"（listener/钩子/字段声明类 hunk）；JVM 回归（rotation 24 项）只覆盖 HostEntry 族，MiStepHost 手势/滚动族无 JVM 覆盖，实机调试日志（side arm/compact rows）是有效补位手段。

### 真机回归欠账（部署后冒烟 + 新特性专项）
① 全链路 + logcat 零 FATAL；② 无限小窗：连续收 4+ 应用、上下滚动、惯性、点视口外行自动滚入、桌面点开滚动行；③ 退出重进：副窗与滚动位置保留、淡入恢复；④ 微信小程序/公众号两修复场景；⑤ 设置页捐赠名单在线加载（验证 INTERNET 权限实际放行）。
