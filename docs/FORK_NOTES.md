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
