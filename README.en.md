# Mi Step (zhanshrd fork)

Personal maintenance fork of [JIUSIS/MiStep](https://github.com/JIUSIS/MiStep) — an LSPosed module recreating Smartisan's "One Step": swipe inward from either status-bar corner to summon a multi-task workspace (one magnified main pane + three real side tasks **+ one top landscape task**, as of upstream v1.0.1).

This fork carries a **semantic port of upstream v1.0.1** (top landscape task pane + two-finger flip, app-clone support, quick-app pins & glass picker, fullscreen entry rule, native icon contexts) onto a decomposed codebase, plus HyperOS 4 / Android 17 (SDK 37) fixes of its own: explicit FULLSCREEN on task return, a promotion stuck-watchdog, an SDK 37 transaction-listener proxy, and a zoom divergence self-heal.

Fork version line: **1.2.0** (tracks upstream v1.0.1, base `55172b5`). Engineering notes in [`docs/FORK_NOTES.md`](docs/FORK_NOTES.md); Chinese documentation: [README.md](README.md).

**Important when installing:** enable **all four** LSPosed scopes (system framework, SystemUI, Settings, `com.xiaomi.type`) and restart the framework — the main-pane magnification lives in system_server and silently never applies otherwise. On this device's LSPosed build the framework scope value must be `system` (`android` is ignored), which is why this fork intentionally keeps the 4-scope recommendation that upstream dropped in v1.0.1.

Upstream license (MiStep NC-SA / Source Available 1.0, Chinese text governs) is preserved; see `LICENSE` and `THIRD_PARTY_NOTICES.md`.
