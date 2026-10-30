# Mi Step (zhanshrd fork)

Personal maintenance fork of [JIUSIS/MiStep](https://github.com/JIUSIS/MiStep) — an LSPosed module recreating Smartisan's "One Step": swipe inward from either status-bar corner to summon a multi-task workspace (one magnified main pane + three real side tasks).

This fork targets **HyperOS 4 / Android 17 (SDK 37)** on Xiaomi 17 Max, with bug fixes (task-return windowing mode, SDK 37 transaction-committed listener, zoom dispatch self-healing), an incremental decomposition of the 7k-line `MiStepHost` god class, and engineering notes in [`docs/FORK_NOTES.md`](docs/FORK_NOTES.md).

**Important when installing:** the manifest declares FOUR LSPosed scopes (`system`, `com.android.systemui`, `com.android.settings`, `com.xiaomi.type`) — enable **all four** and restart the framework, otherwise the main-pane scaling (which lives in system_server) silently never applies.

Chinese documentation: [README.md](README.md). Upstream license (MiStep NC-SA, Chinese text governs) is preserved; see `LICENSE` and `THIRD_PARTY_NOTICES.md`.
