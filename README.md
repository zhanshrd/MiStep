# Mi Step · zhanshrd 维护分支

> 状态栏**双角横滑**唤出的多任务工作区：1 个主区 + 3 个真实副任务，主区整屏缩放、副格独立运行。
> 本分支在 [JIUSIS/MiStep](https://github.com/JIUSIS/MiStep)（九思ing，锤子科技 One Step 开源项目的复刻）基础上做 **HyperOS 4 / Android 17 实机适配、bug 修复与工程化重构**。

- 上游项目：https://github.com/JIUSIS/MiStep （酷安发布帖作者：九思ing）
- 本分支实测环境：**Xiaomi 17 Max (byron) · HyperOS 4.0.3.0.XAFCNXM · Android 17 (SDK 37) · Magisk 30.7 + Zygisk Next 1.5.2 + LSPosed v2.2.0**
- 上游文档与原版说明见 [上游 README](https://github.com/JIUSIS/MiStep#readme)；[English (upstream)](README.en.md)

---

## 本分支改了什么

### 修复（相对上游 1.0.0）

| 问题 | 根因 | 修复 |
|---|---|---|
| 主区内容不缩放，全屏大小塞在框里 | **LSPosed 作用域缺失**：主区缩放本体在 system_server 侧（`DisplayContent.applyMagnificationSpec`），manifest 声明 4 个作用域，只勾 2 个时缩放执行端根本不加载。**必须 4 个全勾**（见下） | 安装说明修正 + 本分支 README 强调 |
| 副格任务归还后钉死在 always-on-top 置顶栈（QQ 事件） | 归还事务用 `setWindowingMode(0)`（UNDEFINED 从父容器继承=那个置顶多窗口根），且 WCT 发射后不管、同一事务重试 2 次即冻结 | 改显式 `FULLSCREEN(1)`，与归还验证条件对齐 |
| SDK 37 上原生提交观察全部失效 | `addTransactionCommittedListener` 参数类型为 `SurfaceControl$TransactionCommittedListener`，传 `Runnable` 反射必然失配 | 改传 Proxy 实现（与 zoom 提交路径同款写法） |
| 潜在：工作区缩放永久失效且无自愈 | `ZoomDispatch` 会去重相同请求；WM 侧 spec 被外部清掉后，心跳重申永远被吞 | 低频读回真实 spec，发散即清 `lastAttempt` |

上游自述的「分身应用不支持进入 Mi Step」**尚未处理**（待办）。

### 重构（行为不变）

`MiStepHost` 从 7200+ 行逐步拆分为协作类（编译器断言式搬移 + 真机冒烟验证，每步独立提交）：

- `HostStaticUtil` —— 纯静态工具（日志/反射读取/窗口模式解析等）
- `ZoomTransport` —— 主区缩放指令三通道发射端（私有文件 / Settings.Global / 广播）
- `HostMirrorController` —— 镜像层与 fullscreen-only 应用载体
- `HostSwapController`、`HostPromotionController` —— 触摸/收起/收集、主副交换、提升完成（见提交历史）

### 工程化

- 断言式重构脚本、双档真机部署循环（见 `docs/FORK_NOTES.md`）
- GitHub Actions 未使用；本机构建脚本即上游 `build.ps1`（无 Gradle）

---

## 安装（重要：作用域必须 4 个全勾）

1. ROOT + LSPosed 环境（本分支实测 Zygisk Next 1.5.2 + LSPosed v2.2.0）
2. 安装 APK（release 附件，或自行构建后 `su -c 'pm install xxx.apk'`）
3. LSPosed 中启用 Mi Step，**作用域勾满 4 项**：
   - `系统框架 (system / android)` ← **主区缩放本体在这里，缺了主区不缩放**
   - `系统界面 (com.android.systemui)`
   - `设置 (com.android.settings)`
   - `com.xiaomi.type`
4. **重启框架**（软重启 zygote 或整机重启）

> 注意：模块更新后同样需要重启框架才对 system_server 侧生效——只杀 SystemUI 是不够的。

## 使用

- 状态栏任意一角**横向向内滑**唤出/收起工作区（横屏为下拉，防误触）
- 顶部控制中心 ↔ 音乐播放器左右滑动切换
- 副窗口：点击主副交换、向外拖出；空格位点击 `+` 直接启动应用
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

构建细节、双档调试循环、踩坑记录见 [`docs/FORK_NOTES.md`](docs/FORK_NOTES.md)。

## 许可

沿用上游 **MiStep NC-SA**（非商业 · 相同方式共享，中文 `LICENSE` 为准），版权归 九思ing 及原项目所有；
第三方组件声明见 `THIRD_PARTY_NOTICES.md` 与 `libs/licenses/`。本分支所有改动以同一许可回馈社区。
