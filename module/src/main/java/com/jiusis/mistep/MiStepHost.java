package com.jiusis.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * v1.6.1：核对原生任务/表面绑定，确认脱离后清槽，退出恢复遗留副任务。
 * 宿主不参与显示缩放，保持正常物理坐标；主区画面与触摸均让给下层系统。
 *
 * 调试广播：com.jiusis.mistep.TOGGLE / com.jiusis.mistep.DEBUG(slot,pkg,swap)。
 */
public final class MiStepHost {
    static final String LANDSCAPE_MODE_ACTION = "com.jiusis.mistep.LANDSCAPE_MODE";

    private static final String DESKTOP_ICON_LAUNCH_ACTION =
            "com.jiusis.mistep.DESKTOP_ICON_LAUNCH";

    private static final String TAG = "MiStepProbe";
    static final int SLOT_MAIN = 0;
    static final int CELL_SLOT_COUNT = 1 + MiStepLayout.CELL_COUNT;
    static final int SLOT_TOP = CELL_SLOT_COUNT;
    static final int SLOT_COUNT = CELL_SLOT_COUNT + 1;
    static final String TOP_TASK_STATE_ACTION = "com.jiusis.mistep.TOP_TASK_STATE";
    static final long LAUNCH_TIMEOUT_MS = 4000L;
    static final long SIDE_PROMOTION_TIMEOUT_MS = 3500L;

    static volatile MiStepHost sInstance;

    final Context ctx;
    final Executor executor;
    final Object factory;
    final Object transitions;
    final Handler main = new Handler(Looper.getMainLooper());

    final Slot[] slots = new Slot[SLOT_COUNT];

    volatile MiStepLayout layout;
    FrameLayout root;
    WindowManager wm;
    WindowManager.LayoutParams lp;
    DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    volatile int displayRotation = -1;
    boolean geometryRefreshQueued;
    int geometryRefreshDeferrals;
    boolean geometryRefreshAfterLive;
    private int lpY = 0;
    boolean zoomed;
    volatile int screenW = 1080;
    volatile int screenH = 2460;
    private final Object taskUsersLock = new Object();
    final Object taskWindowStatesLock = new Object();
    final java.util.Map<Integer, TaskWindowState> taskWindowStates =
            new java.util.HashMap<>();
    private volatile java.util.Set<Integer> taskUserIds;
    private volatile long taskUserIdsReadAt;
    private volatile String taskUserIdsReadError;
    volatile boolean screenOn = true;
    Slot launchCurrentSlot;
    Promotion promotion;
    SwapPreview swapPreview;
    Object swapPreviewStatusOwner;
    final MiStepStatusBar statusBar;
    private final ZoomTransport zoomTransport;
    final HostMirrorController mirrorController;
    final HostSwapController swapController;
    final HostPromotionController promotionController;
    volatile HomeTaskReturn coveredHome;
    CellMovePreview cellMovePreview;
    Bitmap mainBackdrop;
    View topStripBacking;
    Bitmap wallpaperBackdrop;
    boolean wallpaperBackdropRequested;
    BroadcastReceiver wallpaperReceiver;
    private BroadcastReceiver desktopIconReceiver;
    private boolean mainBackdropRequested;
    /** mirror 承载时主区显示的桌面/上一主区快照（避免隐藏任务后主区全黑）。 */
    View carrierBackdrop;
    int carrierSlotIndex = -1;
    MiStepTopBar topBar;
    final HostTopTaskController topCtrl;
    final HostLifecycleController lifecycle;
    final HostAdoptionController adoption;
    final HostWindowController winCtl;
    final HostLaunchController launcher;
    final HostTaskEventController taskEvents;
    final HostChromeController chrome;
    final HostCellLayoutController cellLayout;
    final HostSurfaceController surfaceCtl;
    final HostGeometryController geometry;
    final HostDebugController debugCtrl;
    private View sideBacking;
    /** 侧栏按格分段的不透明底板；承载格需要把它藏起来让下面的真实任务画面透出来。 */
    final View[] sideBackingViews = new View[SLOT_COUNT];
    boolean mainOnRight;
    View dragGhost;
    boolean dragMoved;
    float dragStartRawX;
    float dragStartRawY;
    Slot dragTarget;
    View keyguardMask;
    boolean keyguardLocked;
    static final int ACTIVE = 0, SUSPENDING = 1, SUSPENDED = 2, RESUMING = 3;
    volatile int scene = ACTIVE;
    long sceneEpoch;
    boolean suspendRequested;
    boolean landscapeSuspended;
    boolean transferBusy;
    boolean overlayRefreshScheduled;
    static boolean boundsHooksReady;
    static boolean surfaceHooksReady;
    Object surfaceObserver;
    volatile boolean traceNextSwap;
    volatile MiStepSwapTrace swapTrace;
    boolean compactPending;
    MiStepCellMotion cellMotion;
    MiStepSideDismiss sideDismiss;
    Slot sideDismissSlot;
    static final class CellMovePlan {
        final Slot slot;
        final Rect from;
        CellMovePlan(Slot slot, Rect from) { this.slot = slot; this.from = from; }
    }
    volatile boolean liveSwapInputBlocked;
    MiStepTransitionInput transitionInput;
    final android.os.IBinder desktopSceneOwner = new android.os.Binder();
    volatile long desktopRegistrationSequence;
    final FinishTransactions<RenderBinding> finishTransactions = new FinishTransactions<>(16);
    final FinishTransactions<Long> workspaceFinishes = new FinishTransactions<>(16);

    final java.util.Map<Object, OwnedTask> ownedTasks = new java.util.HashMap<>();

    static final class Slot implements SlotPositions.Entry {
        int index;
        View view;
        View hint;
        Object controller;
        int taskId = -1;
        Object taskToken;
        int[] rect;
        int windowMode = -1;
        TaskWindowState windowState;
        boolean occupied;
        boolean taskVisible;
        boolean reassertPending;
        long modeGuardUntil;
        int modeRepairAttempts;
        boolean modeAdmissionPending;
        final VisibilityRepair visibilityRepair = new VisibilityRepair();
        // v0.9 缩放渲染：任务按满屏排版，靠矩阵缩到格子里
        Object scaleSurface;
        Object cellBackground;
        Object cellBackgroundParent;
        int cellBackgroundWidth;
        int cellBackgroundHeight;
        boolean scaleOnLeash;
        int scaleTaskId = Integer.MIN_VALUE;
        float scaleApplied = -1f;
        int scaleAttempts;
        int taskUid;
        String pkg;
        // A tap swaps; an outward swipe releases this exact task to the background.
        MotionEvent bufferedDown;
        long touchTaskEpoch, touchSceneEpoch;
        boolean injecting;
        volatile boolean promoting;
        boolean launching;
        int retiredTaskId = -1;
        long taskEpoch;
        int removalCheckTask = -1;
        volatile boolean parked;
        volatile boolean frozen;
        volatile boolean closing;
        volatile boolean nativeReleasePending;
        // fullscreen-only 应用的 mirror 承载：任务仍是满屏前台，画面被移出屏幕，
        // 副本镜像显示在格子里；点击格子把原任务移回主区。
        int carrierTaskId = -1;
        Object carrierLeash;
        Object carrierMirror;
        View carrierTapView;
        boolean carrier;
        PendingSidePromotion pendingSidePromotion;

        Slot(int index) {
            this.index = index;
        }

        public int position() { return index; }
        public void position(int value) { index = value; }
    }

    static final class PendingSidePromotion {
        final Slot slot;
        final int taskId;
        final Object token;
        final long sceneEpoch;
        final long bindingEpoch;
        final long deadline;
        final String pkg;
        boolean desktopLaunch;
        int attempts;

        PendingSidePromotion(Slot slot, long sceneEpoch, boolean desktopLaunch, long deadline) {
            this.slot = slot;
            taskId = slot.taskId;
            token = slot.taskToken;
            this.sceneEpoch = sceneEpoch;
            bindingEpoch = slot.taskEpoch;
            pkg = slot.pkg;
            this.desktopLaunch = desktopLaunch;
            this.deadline = deadline;
        }
    }

    static final class OwnedTask {
        final int id;
        final Object token;
        Object surface;
        Object recoveryController;
        volatile int userId = -1;
        boolean recoveryCandidate;
        OwnedTask(int id, Object token) { this.id = id; this.token = token; }
    }

    final FinishTransactions<NativeObservation> nativeObservations = new FinishTransactions<>(16);
    volatile NativeObservation pendingNativeObservation;

    /** One commit signal or the bounded watchdog starts the same state verification. */
    final class NativeObservation implements Runnable {
        final Object token;
        final Runnable check;
        final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();
        NativeObservation(Object token, Runnable check) { this.token = token; this.check = check; }
        public void run() {
            if (!consumed.compareAndSet(false, true)) return;
            main.removeCallbacks(this);
            if (pendingNativeObservation == this) pendingNativeObservation = null;
            check.run();
        }
    }

    NativeObservation observeNativeCommit(Object token, Runnable check) {
        NativeObservation observation = new NativeObservation(token, check);
        pendingNativeObservation = observation;
        main.postDelayed(observation, 350L); // Fallback if the target omits the transition/commit callback.
        return observation;
    }

    void cancelNativeObservation(NativeObservation observation) {
        if (observation == null) return;
        observation.consumed.set(true);
        main.removeCallbacks(observation);
        if (pendingNativeObservation == observation) pendingNativeObservation = null;
    }

    static final class Promotion {
        final Slot slot;
        final int taskId;
        final Object token;
        final java.util.List<Object> otherSideTokens = new java.util.ArrayList<>();
        volatile boolean cancelled;
        volatile boolean handoffAttempted;
        volatile boolean handoffComplete;
        boolean background;
        boolean externalLaunch;
        boolean nativeSwap;
        boolean nativeFlip;
        boolean nativeHome;
        boolean nativeCollect;
        Runnable notificationLaunch;
        long liveZoomGuardUntil;
        MiStepLayout previousLayout;
        MiStepLayout targetLayout;
        boolean candidateRecovery;
        boolean liveMainReveal;
        Object liveRevealSurface;
        volatile boolean nativeSubmitted;
        volatile boolean cancelRequested;
        volatile boolean nativeCompleting;
        boolean liveMainFocusPrepared;
        int liveMainUserId = -1;
        PromotionFocusTransfer.Result liveMainFocusResult;
        LiveSwapVisualLease liveVisual;
        MiStepLiveBackdrop liveBackdrop;
        final java.util.List<LiveSwapVisualLease> peerVisuals = new java.util.ArrayList<>();
        final java.util.List<LivePeerBinding> livePeers = new java.util.ArrayList<>();
        OwnedTask previousMainCandidate;
        LiveTask previousMain;
        LiveTask homeBeforeCover;
        LiveTask collectionHome;
        boolean previousMainOnRight;
        boolean targetMainOnRight;
        LiveLiftPreview liveLift;
        /** 交换时主任务（previousMain）的真实 leash：桥上给它挖洞做 view 级缩小动画。 */
        Object mainLeash;
        Bitmap compactSnapshot;
        Rect compactSnapshotRect;
        long sceneEpoch;
        long bindingEpoch;

        Promotion(Slot slot, Slot[] slots) {
            this.slot = slot;
            this.taskId = slot.taskId;
            this.token = slot.taskToken;
            for (Slot other : slots) {
                // A parked/frozen or already hidden cell is not a live peer that can
                // block promotion. Requiring it visible caused spurious rollbacks
                // after the user returned from Home with a stale side binding.
                if (other != slot && other.occupied && !other.parked && !other.frozen
                        && other.taskVisible && other.taskToken != null) {
                    otherSideTokens.add(other.taskToken);
                }
            }
        }
    }

    static final class LivePeerBinding {
        final Slot slot;
        final long epoch;
        final int id;
        final Object token, controller;
        LivePeerBinding(Slot slot) {
            this.slot = slot; epoch = slot.taskEpoch; id = slot.taskId;
            token = slot.taskToken; controller = slot.controller;
        }
    }

    /** Hide only the selected cell's Host drawings; its SurfaceView stays attached. */
    static final class LiveSwapVisualLease {
        private static final class AlphaHold {
            int count;
            float alpha;
            AlphaHold(float alpha) { this.alpha = alpha; }
        }
        private static final java.util.IdentityHashMap<View, AlphaHold> held = new java.util.IdentityHashMap<>();
        final View backing, hint;
        final float backingAlpha, hintAlpha;
        final int backingVisibility, hintVisibility;
        boolean restored;
        LiveSwapVisualLease(View backing, View hint) {
            this.backing = backing; this.hint = hint;
            backingAlpha = backing == null ? 1f : backing.getAlpha();
            hintAlpha = hint == null ? 1f : hint.getAlpha();
            backingVisibility = backing == null ? View.GONE : backing.getVisibility();
            hintVisibility = hint == null ? View.GONE : hint.getVisibility();
            acquire(backing);
            acquire(hint);
        }
        private static void acquire(View view) {
            if (view == null) return;
            AlphaHold hold = held.get(view);
            if (hold == null) { hold = new AlphaHold(view.getAlpha()); held.put(view, hold); }
            hold.count++; view.setAlpha(0f);
        }
        static void writeAlpha(View view, float alpha) {
            if (view == null) return;
            AlphaHold hold = held.get(view);
            if (hold != null) hold.alpha = alpha;
            else view.setAlpha(alpha);
        }
        private static void release(View view) {
            if (view == null) return;
            AlphaHold hold = held.get(view);
            if (hold != null && --hold.count == 0) {
                held.remove(view); view.setAlpha(hold.alpha);
            }
        }
        void restore() {
            if (restored) return;
            restored = true;
            // Nested handoffs and late fade callbacks cannot resurrect shared chrome.
            // Restore the latest requested alpha only when the last owner releases it.
            release(backing);
            release(hint);
        }
    }

    static final class LiveTask {
        final int id;
        final Object token;
        final String pkg;
        LiveTask(Object info) {
            id = XposedHelpers.getIntField(info, "taskId");
            token = XposedHelpers.getObjectField(info, "token");
            ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
            pkg = base == null ? null : base.getPackageName();
        }
        LiveTask(Slot slot) {
            this.id = slot.taskId;
            this.token = slot.taskToken;
            this.pkg = slot.pkg;
        }
        LiveTask(int id, Object token, String pkg) {
            this.id = id;
            this.token = token;
            this.pkg = pkg;
        }
        boolean matches(Object info) {
            return info != null && id == XposedHelpers.getIntField(info, "taskId")
                    && token != null && token.equals(XposedHelpers.getObjectField(info, "token"));
        }
    }

    private MiStepHost(Context ctx, Executor executor, Object factory, Object transitions) {
        // Keep the workspace on the physical portrait window configuration while
        // native SystemUI resources follow the main application's environment.
        Display display = ((DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE))
                .getDisplay(Display.DEFAULT_DISPLAY);
        this.ctx = ctx.createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        this.executor = executor;
        this.factory = factory;
        this.transitions = transitions;
        statusBar = new MiStepStatusBar(main, MiStepHost::log);
        zoomTransport = new ZoomTransport(this);
        mirrorController = new HostMirrorController(this);
        swapController = new HostSwapController(this);
        promotionController = new HostPromotionController(this);
        topCtrl = new HostTopTaskController(this);
        lifecycle = new HostLifecycleController(this);
        adoption = new HostAdoptionController(this);
        winCtl = new HostWindowController(this);
        launcher = new HostLaunchController(this);
        taskEvents = new HostTaskEventController(this);
        chrome = new HostChromeController(this);
        cellLayout = new HostCellLayoutController(this);
        surfaceCtl = new HostSurfaceController(this);
        geometry = new HostGeometryController(this);
        debugCtrl = new HostDebugController(this);
        installLogicalBoundsHooks(ctx.getClassLoader());
        installRetainedSurfaces(ctx.getClassLoader());
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = new Slot(i);
        }
    }

    public static void start(Context ctx, Executor executor, Object factory, Object transitions) {
        if (!MiStepEntryGuard.canOpen(ctx)) { log("host start rejected while locked"); return; }
        if (sInstance != null) {
            log("host already started");
            return;
        }
        sInstance = new MiStepHost(ctx, executor, factory, transitions);
        sInstance.startInternal();
    }

    /** 首次触发启动宿主，之后在显示／隐藏间切换。 */
    public static void toggleOrStart(Context ctx, Executor executor, Object factory, Object transitions) {
        if (!MiStepEntryGuard.canOpen(ctx)) { log("host gesture rejected while locked"); return; }
        if (sInstance == null) {
            start(ctx, executor, factory, transitions);
            return;
        }
        sInstance.toggleVisibility();
    }

    /** 调试用：确保宿主已启动，但不切换显示状态。 */
    public static void toggleOrStartIfNeeded(Context ctx, Executor executor, Object factory,
            Object transitions) {
        if (!MiStepEntryGuard.canOpen(ctx)) return;
        if (sInstance == null) {
            start(ctx, executor, factory, transitions);
        }
    }

    /** 亮屏／熄屏通知（HostEntry 的屏幕广播）。 */
    public static void onScreenState(boolean on) {
        MiStepHost h = sInstance;
        if (h == null) {
            return;
        }
        h.main.post(() -> {
            h.screenOn = on;
            h.publishDesktopSideTasks();
            if (!on) {
                h.traceNextSwap = false;
                MiStepSwapTrace trace = h.swapTrace;
                if (trace != null) trace.close();
                h.closeSwapPreview();
                h.statusBar.restore();
                h.closeCellMovePreview();
                // Locking the device ends Mi Step instead of retaining a magnified lock screen.
                h.suspendSession("screen-lock");
            } else {
                h.updateTopBarState();
            }
        });
    }

    public static void onUserPresent() {
        MiStepHost h = sInstance;
        if (h != null) h.main.post(h::leaveKeyguardPrivacy);
    }

    public static void onSystemNavigation(String reason) {
        MiStepHost h = sInstance;
        if (h != null) h.main.post(() -> {
            if (NativeLandscapePolicy.shouldRestoreFromHome(
                    h.landscapeSuspended, h.scene == SUSPENDED, reason)) {
                h.resumeSession();
            } else {
                h.suspendSession(reason);
            }
        });
    }

    /** Native fullscreen uses the existing retained-task suspend/resume lifecycle. */
    public static void onLandscapeMode(boolean fullscreen) {
        MiStepHost h = sInstance;
        if (h == null) return;
        h.main.post(() -> {
            if (fullscreen) {
                h.landscapeSuspended = true;
                if (h.scene == ACTIVE) h.suspendSession("native-landscape");
            } else {
                h.landscapeSuspended = false;
                h.resumeSession();
            }
        });
    }

    /** 调试广播：向指定格启动一个包（无人值守验证用）。 */
    public static void debugLaunch(int slot, String pkg) { HostDebugController.debugLaunch(slot, pkg); }

    /** 调试广播：把指定副格与主区交换。 */
    public static void debugSwap(int slot) { HostDebugController.debugSwap(slot); }



    /** Arm passive instrumentation only; it never starts a swap or turns on a POC. */
    public static void debugTraceNextSwap() { HostDebugController.debugTraceNextSwap(); }

    void traceSwap(Promotion ticket) { debugCtrl.traceSwap(ticket); }

    void traceSwapEvent(String event) { debugCtrl.traceSwapEvent(event); }

    /** 调试广播：把主区任务 mirror 成宿主窗口里的实时画面（验证 live-surface 承载）。 */
    public static void debugMirror(int taskId, int x, int y, int w, int h, int holdMs,            int hideMode) { HostDebugController.debugMirror(taskId, x, y, w, h, holdMs, hideMode); }

    void createMirrorLayer() { mirrorController.createMirrorLayer(); }

    void setMirrorLayerVisible(boolean visible) { mirrorController.setMirrorLayerVisible(visible); }

    void startMirrorPoc(int requestedTask, Rect dest, int holdMs, int hideMode) { debugCtrl.startMirrorPoc(requestedTask, dest, holdMs, hideMode); }

    Object shellOrganizer() { return mirrorController.shellOrganizer(); }

    void reassertCarrier() { mirrorController.reassertCarrier(); }

    void restoreCarrierToMain() { mirrorController.restoreCarrierToMain(); }

    void scheduleMainCropClear(long delayMs) { mirrorController.scheduleMainCropClear(delayMs); }

    /** 打开一步时按滑动起始角决定主区在哪边：从左角滑 → 主区在左；从右角滑 → 主区在右。 */
    static volatile int pendingMainSide = -1;

    public static void setPendingMainSide(boolean mainOnRight) {
        MiStepHost host = sInstance;
        // 过渡期间（RESUMING/SUSPENDING）的滑动不得改写下一次打开的方向：
        // consumePendingMainSide 已在恢复会话时消费，晚到的写入会污染下次打开的朝向。
        // ACTIVE 时热区手势本就在 HotZoneGesture 源头被丢弃，此守卫为防御性冗余；
        // 关闭工作区走顶栏 ✕ / TOGGLE 广播 / Home、Recents / 息屏，没有“再滑一次关闭”。
        if (host != null && host.scene != SUSPENDED) return;
        pendingMainSide = mainOnRight ? 1 : 0;
    }

    void consumePendingMainSide() {
        int pending = pendingMainSide;
        if (pending < 0) return;
        pendingMainSide = -1;
        boolean right = pending == 1;
        if (right != mainOnRight) {
            log("pending main side applied: " + (right ? "right" : "left"));
            setMainSide(right);
        }
    }

    /** 调试广播：把主区任务 leash 从主区尺寸动画到目标槽（view 级交换动画的 POC）。 */
    public static void debugLeashAnim(int slotIndex, int durationMs, String pkg) {
        MiStepHost host = sInstance;
        if (host == null) {
            log("debugLeashAnim: host not started");
            return;
        }
        host.main.post(() -> host.runLeashAnimPoc(slotIndex, Math.max(150, durationMs), pkg));
    }

    volatile int leashAnimFrames;
    /** POC 期间该格的 SF 底板必须停止心跳重建，否则会盖住动画中的真实任务画面。 */
    volatile int leashPocSlot = -1;

    private void runLeashAnimPoc(int slotIndex, long durationMs, String pkg) { debugCtrl.runLeashAnimPoc(slotIndex, durationMs, pkg); }

    private void animateLeashPoc(Object leash, int[] rect, int slotIndex, int taskId,            long durationMs) { debugCtrl.animateLeashPoc(leash, rect, slotIndex, taskId, durationMs); }

    /** POC 期间隐藏目标格的宿主底板，让真实任务画面能在格子里可见（正式交换也会这么做）。 */
    private void hideSlotChromeForPoc(int slotIndex, boolean hide) { debugCtrl.hideSlotChromeForPoc(slotIndex, hide); }

    private void reverseLeashPoc(Object leash, int taskId, int slotIndex) { debugCtrl.reverseLeashPoc(leash, taskId, slotIndex); }

    /**
     * view 级主窗动画：把真实主任务 leash 在桥的洞内从主区矩形缩进目标格。
     * 与 SwapPreview 同时长、同曲线，桥只画侧栏/背景，主窗画面是真实任务表面。
     */
    private void startLiveMainShrink(Promotion ticket) { debugCtrl.startLiveMainShrink(ticket); }

    void applyLeashTransform(Object leash, float x, float y, float sx, float sy) { debugCtrl.applyLeashTransform(leash, x, y, sx, sy); }

    /** Bounds of the visible main pane while the host owns the display zoom. */
    static Rect activeMainBounds() { return HostGeometryController.activeMainBounds(); }

    /** Workspace matrix for a registered native main-task animation, never for a swap. */
    static float[] activeMainTransform() { return HostGeometryController.activeMainTransform(); }

    static Rect nativeFreeformAnimationStart(Object change) { return HostGeometryController.nativeFreeformAnimationStart(change); }

    static float[] activeStatusBarTransform() { return HostGeometryController.activeStatusBarTransform(); }

    static float[] activeIslandMonitorTransform() { return HostGeometryController.activeIslandMonitorTransform(); }

    static boolean hostSceneActive() { return HostGeometryController.hostSceneActive(); }

    static boolean shouldClampAnimationBounds(Object info, Object change) { return HostGeometryController.shouldClampAnimationBounds(info, change); }











    /** Process-wide hooks, restricted to these exact three view/controller identities. */
    static final ThreadLocal<java.util.List<MiStepSideClose>> nativeCloses = new ThreadLocal<>();
    static final ThreadLocal<Object> closeFinishTransaction = new ThreadLocal<>();
    private static void installLogicalBoundsHooks(ClassLoader cl) { HostGeometryController.installLogicalBoundsHooks(cl); }

    Rect logicalBounds() { return geometry.logicalBounds(); }
    Rect logicalBounds(Slot slot) { return geometry.logicalBounds(slot); }
    Slot slotForObject(Object object) { return geometry.slotForObject(object); }
    TopTaskGeometry topGeometry() { return winCtl.topGeometry(); }
    /** Keep app metrics in the same logical viewport as the hosted surface and input. */
    void applyTopConfiguration(Object wct, Object token, Slot slot) { winCtl.applyTopConfiguration(wct, token, slot); }
    int[] slotBounds(Slot slot) { return winCtl.slotBounds(slot); }

    java.util.Set<Integer> supportedTaskUsers() {
        long now = SystemClock.uptimeMillis();
        java.util.Set<Integer> cached = taskUserIds;
        if (cached != null && now - taskUserIdsReadAt < 5000L) return cached;
        synchronized (taskUsersLock) {
            cached = taskUserIds;
            now = SystemClock.uptimeMillis();
            if (cached != null && now - taskUserIdsReadAt < 5000L) return cached;
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            int currentUser = currentUserId();
            ids.add(currentUser);
            try {
                UserManager users = ctx.getSystemService(UserManager.class);
                if (users != null) {
                    java.util.List<UserHandle> profiles = users.getUserProfiles();
                    if (profiles != null) {
                        for (UserHandle profile : profiles) {
                            if (profile != null) {
                                ids.add((Integer) XposedHelpers.callMethod(profile, "getIdentifier"));
                            }
                        }
                    }
                }
                taskUserIdsReadError = null;
            } catch (Throwable error) {
                String message = String.valueOf(error);
                if (!message.equals(taskUserIdsReadError)) {
                    log("associated task profiles unavailable; owner only: " + message);
                    taskUserIdsReadError = message;
                }
            }
            taskUserIds = java.util.Collections.unmodifiableSet(ids);
            taskUserIdsReadAt = now;
            log("task profile scope current=" + currentUser + " associated=" + ids);
            return taskUserIds;
        }
    }

    boolean supportsTaskUser(int userId) { return winCtl.supportsTaskUser(userId); }

    int currentUserId() { return winCtl.currentUserId(); }

    boolean ownsViewOrController(Object object) {
        if (object == null) return false;
        for (Slot slot : slots)
            if (slot.index > 0 && !slot.parked && (slot.view == object || slot.controller == object)) return true;
        return false;
    }

    /** External mode1 CHANGE belongs to Launcher, not TaskView's immediate side reparent. */
    boolean leaveExternalChangeToNative(Object change) {
        if (change == null || scene != ACTIVE || !screenOn || keyguardLocked || transferBusy) return false;
        try {
            int mode = (Integer) XposedHelpers.callMethod(change, "getMode");
            if (mode != 6 && mode != 3) return false;
            Object info = XposedHelpers.callMethod(change, "getTaskInfo");
            if (!ordinaryRoot(info, 1)) return false;
            int taskId = XposedHelpers.getIntField(info, "taskId");
            Object token = XposedHelpers.getObjectField(info, "token");
            for (Slot slot : slots) {
                if (slot.index <= 0 || !isActive(slot) || !slot.occupied || slot.parked
                        || slot.frozen || slot.launching || slot.modeAdmissionPending
                        || slot.taskId != taskId || token == null || !token.equals(slot.taskToken)
                        || !controllerOwns(slot, taskId, token)) continue;
                Promotion current = promotion;
                if (current != null && (!current.externalLaunch || current.slot != slot
                        || current.sceneEpoch != sceneEpoch || current.bindingEpoch != slot.taskEpoch))
                    return false;
                Object queue = XposedHelpers.getObjectField(transitions, "mPending");
                if (!(queue instanceof Iterable<?>)) return false;
                for (Object pending : (Iterable<?>) queue)
                    if (XposedHelpers.getObjectField(pending, "mTaskView") == slot.controller) return false;
                Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
                synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                    Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                            organizer, "mTasks")).get(taskId);
                    Object canonical = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
                    if (!ordinaryRoot(canonical, 1) || !token.equals(XposedHelpers.getObjectField(canonical, "token"))
                            || XposedHelpers.getIntField(canonical, "taskId") != taskId
                            || XposedHelpers.getIntField(canonical, "userId") != XposedHelpers.getIntField(info, "userId"))
                        return false;
                }
                log("external fullscreen change kept native task=" + taskId + " slot=" + slot.index);
                return true;
            }
        } catch (Throwable unavailable) { log("external native change guard unavailable: " + unavailable); }
        return false;
    }

    java.util.List<?> rootInfos() throws Exception {
        Object service = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityTaskManager"), "getService");
        return (java.util.List<?>) XposedHelpers.callMethod(service, "getAllRootTaskInfos");
    }

    Object rootInfo(LiveTask task) throws Exception {
        for (Object info : rootInfos()) if (task.matches(info)) return info;
        return null;
    }

    boolean ordinaryRoot(Object info, int mode) {
        if (info == null || XposedHelpers.getIntField(info, "displayId") != 0
                || !supportsTaskUser(XposedHelpers.getIntField(info, "userId"))
                || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))
                || !Integer.valueOf(mode).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getActivityType"))) return false;
        ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
        return base != null && !"com.miui.home".equals(base.getPackageName());
    }

    /** Some associated-profile roots are focused while the ROM reports isVisible=false. */
    boolean mainTaskVisible(Object info) {
        return info != null && TaskUserPolicy.mayBeMainTask(
                XposedHelpers.getIntField(info, "userId"), currentUserId(), supportedTaskUsers(),
                XposedHelpers.getBooleanField(info, "isVisible"),
                XposedHelpers.getBooleanField(info, "isFocused"), false);
    }

    boolean isSideToken(Object token) {
        for (Slot slot : slots) if (!slot.parked && token != null && token.equals(slot.taskToken)) return true;
        return false;
    }

    /** 诊断：打印当前所有 root task 的判定字段（findMainTask 漏判用）。 */
    void dumpRoots(String reason) { debugCtrl.dumpRoots(reason); }

    /** 调试广播入口：打印 root 诊断。 */
    public static void debugRoots() { HostDebugController.debugRoots(); }

    LiveTask findMainTask() throws Exception { return launcher.findMainTask(); }

    LiveTask findPackageTask(String pkg) throws Exception { return launcher.findPackageTask(pkg); }

    LiveTask existingHomeTask() throws Exception { return launcher.existingHomeTask(); }

    boolean isHomeRoot(Object info) { return launcher.isHomeRoot(info); }

    /** Reveal only the same Home that this app covered; never send a HOME launch intent. */
    LiveTask existingHomeForCollection(LiveTask task) throws Exception { return launcher.existingHomeForCollection(task); }

    Object homeForCollection(LiveTask task) throws Exception { return launcher.homeForCollection(task); }



    void checkAdoption(Slot slot, LiveTask task, long epoch, int attempt,
            Runnable success, Consumer<String> failure) {
        if (!isActive(slot) || epoch != sceneEpoch) {
            adoptionFailed(slot, task, true, "scene changed during adoption", failure);
            return;
        }
        if (!executeOnShell(() -> {
            boolean ready = false;
            String detail;
            try {
                Object core = rootInfo(task);
                ready = ordinaryRoot(core, 6) && XposedHelpers.getBooleanField(core, "isVisible")
                        && controllerOwns(slot, task.id, task.token)
                        && getSurfaceField(slot.controller, "mTaskLeash") != null;
                detail = "core/controller/leash=" + ready;
            } catch (Throwable error) { detail = error.toString(); }
            final boolean confirmed = ready;
            final String result = detail;
            main.post(() -> {
                if (epoch != sceneEpoch) {
                    adoptionFailed(slot, task, true, "stale adoption completion", failure);
                } else if (confirmed) {
                    bindTask(slot, task.id, task.token);
                    slot.pkg = task.pkg;
                    slot.occupied = true;
                    slot.taskVisible = true;
                    slot.launching = false;
                    slot.windowMode = 6; // Confirmed from Core and this controller above.
                    slot.promoting = false;
                    slot.parked = false;
                    transferBusy = false;
                    updateHint(slot);
                    updateTouchRegion(slot);
                    reapplyScale(slot);
                    publishDesktopSideTasks();
                    log("adoption confirmed same task=" + task.id + " slot=" + slot.index);
                    success.run();
                    main.postDelayed(() -> {
                        compactCells();
                        // TaskView insertion can briefly hide sibling surfaces while the
                        // native transition settles. Re-show every already-owned cell
                        // without moving tasks or changing ownership.
                        main.postDelayed(() -> reassertSideTaskViews("adoption-settled"), 420L);
                    }, 120L);
                } else if (attempt < 2) {
                    main.postDelayed(() -> checkAdoption(slot, task, epoch, attempt + 1, success, failure), 500L);
                } else adoptionFailed(slot, task, true, result, failure);
            });
        })) adoptionFailed(slot, task, true, "no Shell queue for confirmation", failure);
    }







    static final class RenderBinding {
        final Slot slot;
        final int id;
        final Object token, leash, parent;
        final long scene, binding;
        RenderBinding(Slot slot, long scene, Object leash, Object parent) {
            this.slot = slot; id = slot.taskId; token = slot.taskToken;
            this.scene = scene; binding = slot.taskEpoch; this.leash = leash; this.parent = parent;
        }
    }

    private void installRetainedSurfaces(ClassLoader cl) { surfaceCtl.installRetainedSurfaces(cl); }

    private void trackRetainedSurfaces(Object info, Object finish) { surfaceCtl.trackRetainedSurfaces(info, finish); }

    /**
     * WindowManager reports this overlay visible throughout fixed-rotation launches,
     * but MIUI can leave its last buffer under the transition until the next redraw.
     * Refresh only around a transition; this does not resize or recreate the host.
     */
    private void scheduleOverlayRefresh() { surfaceCtl.scheduleOverlayRefresh(); }

    private void refreshOverlayAfterTransition() { surfaceCtl.refreshOverlayAfterTransition(); }

    /** Append after native finish operations have merged, before the very same apply. */
    void appendWorkspaceSurfaces(Object tx, java.util.List<Long> epochs) { surfaceCtl.appendWorkspaceSurfaces(tx, epochs); }

    void appendRetainedSurfaces(Object tx, java.util.List<RenderBinding> frames) { surfaceCtl.appendRetainedSurfaces(tx, frames); }


    void setZoomState(boolean on) {
        zoomed = on;
        HostEntry.setHostZoomed(on);
        MiStepStatusBarSampling.geometryChanged();
        HotZoneGesture.raiseAll(ctx);
    }

    /**
     * 写缩放触发文件（SystemUI 私有目录，system_server 侧轮询）。
     * 呼出/退出为 300ms 整屏缩放动画（system_server 侧 2.2.4+ 的 anim 命令）；
     * 动画结束后再写一次稳态续租，避免 4 秒租约把进行中的动画取消。
     */
    static final long ZOOM_ANIM_MS = 300L;
    android.animation.ValueAnimator workspaceAnimator;
        long workspaceGeneration;
    float workspaceAlpha = 1f;
    boolean workspaceEntering;




    /** 动画期间宿主不得写普通稳态（会取消动画）；心跳按此时间窗跳过。 */
    volatile long zoomAnimUntil;
    long zoomCommandGeneration;

    private String zoomOnCommand() { return zoomTransport.zoomOnCommand(); }

    void writeZoomAnimated(boolean on) { zoomTransport.writeZoomAnimated(on); }

    void writeZoom(boolean on) { zoomTransport.writeZoom(on); }

    private void writeZoomLine(String command) { zoomTransport.writeZoomLine(command); }

    private void writeZoomLine(String command, long issuedAt) { zoomTransport.writeZoomLine(command, issuedAt); }
    private void startInternal() {
        main.post(() -> {
            if (!MiStepEntryGuard.canOpen(ctx)) {
                if (sInstance == this && root == null) sInstance = null;
                log("queued host start rejected while locked");
                return;
            }
            try {
                registerDesktopIconReceiver();
                buildLayout();
                createSubViews();
                registerWallpaperReceiver();
                main.postDelayed(this::collapseStatusBarPanels, 220L);
        main.postDelayed(this::loadWallpaperBackdrop, 250L);
            } catch (Throwable t) {
                log("startInternal FAILED: " + t);
                XposedBridge.log(t);
            }
        });
    }

    private void buildLayout() { cellLayout.buildLayout(); }

    static MiStepHost current() { return sInstance; }

    static int topGestureHeight() { return HostTopTaskController.topGestureHeight(); }
    static boolean beginTopGesture() { return HostTopTaskController.beginTopGesture(); }
    static void moveTopGesture(float displacement, long time) { HostTopTaskController.moveTopGesture(displacement, time); }
    static void endTopGesture(boolean cancelled) { HostTopTaskController.endTopGesture(cancelled); }
    static void onTopTaskState(Intent intent) { HostTopTaskController.onTopTaskState(intent); }
    static boolean ownsTopNativeCaption(Object info) { return HostTopTaskController.ownsTopNativeCaption(info); }
    void resetTopForSuspend() { topCtrl.resetTopForSuspend(); }
    void createTopTaskControls() { topCtrl.createTopTaskControls(); }
    void updateTopTaskGeometry() { topCtrl.updateTopTaskGeometry(); }
    private void openTopFace(boolean manual) { topCtrl.openTopFace(manual); }
    private void releaseTopTask(boolean front, boolean dismissed, Runnable after) { topCtrl.releaseTopTask(front, dismissed, after); }

    /** BUG-2 韧性：promotion/transfer 卡死时 suspend 会无限排队、cancel 路径存在静默 no-op。
     *  本看门狗独立于 scene 门禁（覆盖 ACTIVE/SUSPENDING/RESUMING 各种卡态）：
     *  >8s 温和取消一次（requestLiveSwapCancellation），>20s 强制 failPromotion 走正规
     *  失败汇总（回滚 WCT/清理槽位/触发 finishPendingSuspend），窗口期归零防连发。 */
    private long promotionWatchdogSince;
    private boolean promotionWatchdogNudged;

    void startPromotionWatchdog() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (root == null || sInstance != MiStepHost.this) {
                    return;
                }
                if (promotion == null) {
                    promotionWatchdogSince = 0L;
                    promotionWatchdogNudged = false;
                } else {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (promotionWatchdogSince == 0L) {
                        promotionWatchdogSince = now;
                    }
                    long stuckMs = now - promotionWatchdogSince;
                    if (stuckMs > 8000L && !promotionWatchdogNudged) {
                        promotionWatchdogNudged = true;
                        log("promotion watchdog: task=" + promotion.taskId + " stuck "
                                + (stuckMs / 1000L) + "s, requesting live-swap cancellation");
                        promotionController.requestLiveSwapCancellation(promotion, "watchdog");
                    }
                    if (stuckMs > 20000L) {
                        log("promotion watchdog: task=" + promotion.taskId + " stuck "
                                + (stuckMs / 1000L) + "s, forcing failPromotion");
                        promotionWatchdogSince = now;
                        promotionWatchdogNudged = false;
                        failPromotion(promotion, "promotion watchdog deadline");
                    }
                }
                main.postDelayed(this, 4000L);
            }
        }, 4000L);
    }

    private void createSubViews() { taskEvents.createSubViews(); }

    private void createNext(final int index) { taskEvents.createNext(index); }

    private void onTaskViewCreated(int index, Object taskViewObj) { taskEvents.onTaskViewCreated(index, taskViewObj); }

    TextView makeEmptyHint() { return chrome.makeEmptyHint(); }

    void drawEmptyCellPlus(android.graphics.Canvas canvas, int width, int height,            int color, android.graphics.Paint plus) { chrome.drawEmptyCellPlus(canvas, width, height, color, plus); }

    FrameLayout.LayoutParams hintParams(Slot slot) { return chrome.hintParams(slot); }

    /** 空格子显示"+"提示；有任务时隐藏。 */
    void updateHint(Slot slot) { chrome.updateHint(slot); }

    /** An empty TaskView keeps its last resize background; hide that container, not a screenshot. */
    void updateCellSurfaceVisibility(Slot slot) { cellLayout.updateCellSurfaceVisibility(slot); }

    /** Open the destination hole while its opaque empty hint still covers it.
     * Native preparation owns show/hide; only make its eventual parent nontransparent.
     */
    void prepareCellSurface(Slot slot) { cellLayout.prepareCellSurface(slot); }

    /** A host-owned backing stays inside the cell, below the real task, even for translucent apps. */
    void ensureCellBackground(Slot slot) { cellLayout.ensureCellBackground(slot); }

    void registerDefaultDisplayListener() {
        if (displayListener != null) return;
        try {
            displayManager = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager == null) throw new IllegalStateException("DisplayManager unavailable");
            displayListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) scheduleDisplayGeometryRefresh();
                }
            };
            displayManager.registerDisplayListener(displayListener, main);
            log("default display geometry listener registered");
        } catch (Throwable error) {
            displayListener = null;
            log("default display geometry listener unavailable: " + error);
        }
    }

    void readDisplayGeometry() { geometry.readDisplayGeometry(); }

    void scheduleDisplayGeometryRefresh() { geometry.scheduleDisplayGeometryRefresh(); }

    private void refreshDisplayGeometry() { geometry.refreshDisplayGeometry(); }

    void refreshQuickApps() { chrome.refreshQuickApps(); }

    void updateTopBarState() { chrome.updateTopBarState(); }





    /** 只取桌面壁纸做高斯模糊底（不使用应用内容），供顶栏/侧栏格子的液态玻璃使用。 */
    void loadWallpaperBackdrop() { chrome.loadWallpaperBackdrop(); }

    void applyWallpaperBackdrops() { chrome.applyWallpaperBackdrops(); }

    /** 角部滑动起手在状态栏区域时 MIUI 通知栏/控制中心会被顺带拉出并盖住主区；打开一步后主动收起。 */
    void collapseStatusBarPanels() { chrome.collapseStatusBarPanels(); }

    /** 换壁纸后自动重算模糊底，避免一步里还是旧壁纸。 */
    private void registerWallpaperReceiver() { chrome.registerWallpaperReceiver(); }

    private void registerDesktopIconReceiver() {
        if (desktopIconReceiver != null) return;
        try {
            desktopIconReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (intent != null && intent.getBooleanExtra("before", false)) {
                        preannounceDesktopLaunch(intent.getBundleExtra("binding"));
                        return;
                    }
                    String pkg = intent == null ? null : intent.getStringExtra("package");
                    promoteSideTaskForDesktopLaunch(pkg, intent == null ? -1 : intent.getIntExtra("userId", -1));
                }
            };
            IntentFilter filter = new IntentFilter(DESKTOP_ICON_LAUNCH_ACTION);
            ctx.registerReceiver(desktopIconReceiver, filter,
                    "android.permission.MANAGE_ACTIVITY_TASKS", main,
                    Context.RECEIVER_EXPORTED);
            log("desktop icon launch receiver registered");
        } catch (Throwable error) {
            desktopIconReceiver = null;
            log("desktop icon launch receiver failed: " + error);
        }
    }


    /** Publish only confirmed bindings; system_server rechecks each native task before use. */
    void publishDesktopSideTasks() { launcher.publishDesktopSideTasks(); }

    private void promoteSideTaskForDesktopLaunch(String pkg, int launchUser) { launcher.promoteSideTaskForDesktopLaunch(pkg, launchUser); }







    void scheduleSideSurfaceRestore(Slot slot, int taskId, Object token,            long bindingEpoch, long epoch) { launcher.scheduleSideSurfaceRestore(slot, taskId, token, bindingEpoch, epoch); }


    MiStepLayout makeAdaptiveLayout(boolean right) { return cellLayout.makeAdaptiveLayout(right); }


    /** 调试广播入口：打印宿主 View 树（定位残带/残留视图）。 */
    public static void debugDumpViews() { HostDebugController.debugDumpViews(); }

    void dumpViewTree() { debugCtrl.dumpViewTree(); }

    private void dumpViewRecursive(View view, int depth) { debugCtrl.dumpViewRecursive(view, depth); }


    /** 交换确认后把新主任务 leash 完全复位：position/matrix/alpha/crop，避免副格残留变换把画面顶到主区上方。 */
    void resetMainTaskLeash(int taskId) { taskEvents.resetMainTaskLeash(taskId); }

    void launchQuick(QuickApp entry) { launcher.launchQuick(entry); }

    void openSettingsInMain() { launcher.openSettingsInMain(); }

    void beginWindowDrag(MotionEvent event) { winCtl.beginWindowDrag(event); }

    void updateWindowDrag(MotionEvent event) { winCtl.updateWindowDrag(event); }

    void finishWindowDrag(MotionEvent event) { winCtl.finishWindowDrag(event); }

    private void createDragGhost() { winCtl.createDragGhost(); }

    private void setDropHighlight(Slot slot, boolean active) { winCtl.setDropHighlight(slot, active); }

    /** 侧栏第 slotIndex 格的分段底板 top（与相邻格共享边界，保证列无缝）。 */
    int sideSegmentTop(int slotIndex) { return winCtl.sideSegmentTop(slotIndex); }

    /** 侧栏第 slotIndex 格的分段底板高度。 */
    int sideSegmentHeight(int slotIndex) { return winCtl.sideSegmentHeight(slotIndex); }

    private Slot slotAtPoint(float x, float y) { return winCtl.slotAtPoint(x, y); }

    int dp(float value) { return cellLayout.dp(value); }

    void removeCellBackground(Slot slot) { cellLayout.removeCellBackground(slot); }

    private void destroyCellSurface(Object background) { cellLayout.destroyCellSurface(background); }

    void closeSurfaceObject(Object object, String method) { cellLayout.closeSurfaceObject(object, method); }

    int slotIndexOfView(View view) { return winCtl.slotIndexOfView(view); }

    void applyRect(Slot slot) { cellLayout.applyRect(slot); }

    /**
     * Exchange the whole main/side orientation. The official layout keeps the two
     * windows anchored to opposite edges, so the shrinking and growing panes share a
     * moving divider instead of crossing through each other's centre.
     */
    void setMainSide(boolean right) {
        if (layout != null && mainOnRight == right) return;
        try {
            applyMainLayout(makeAdaptiveLayout(right));
            if (root != null) writeZoom(true);
        } catch (Throwable error) {
            log("main side switch failed: " + error);
        }
    }

    /** Host geometry only. A live flip sends its correlated WMS zoom separately. */
    void applyMainLayout(MiStepLayout target) { cellLayout.applyMainLayout(target); }

    /** Only the top strip / side column receive host input; transitions may cover the display. */
    void installHostTouchRegion() { winCtl.installHostTouchRegion(); }

    /** Side TaskViews return their touch areas to the host for tap/drag arbitration. */
    void updateTouchRegion(Slot slot) { winCtl.updateTouchRegion(slot); }

    // ---------------------------------------------------------------- v0.9 触摸仲裁与定向注入

    /**
     * 触摸规则（官方Mi Step语义 + 演示需要）：
     *   主区由系统处理，宿主不接收其触摸；
     *   副格有任务 → 轻点=与主区交换；滑动取消轻点，始终不操作副任务；
     *   空槽 → 不消费，交给宿主点击处理。
     */
    boolean onSlotTouch(int slotIndex, MotionEvent e) { return swapController.onSlotTouch(slotIndex, e); }

    void recycleBuffered(Slot slot) { swapController.recycleBuffered(slot); }

    private boolean followSideDismiss(Slot slot, MotionEvent e) { return swapController.followSideDismiss(slot, e); }

    private void dismissSideToBackground(Slot slot) { swapController.dismissSideToBackground(slot); }

    /** Keep tap-to-swap, transfer a drag to its real window without reinjecting coordinates. */
    private void transferTouchToTask(Slot slot, long eventTime) { swapController.transferTouchToTask(slot, eventTime); }

    private int uidOf(String pkg) { return winCtl.uidOf(pkg); }

    // ---------------------------------------------------------------- 点击 / 交换

    void adoptExisting(Slot slot, LiveTask task, Runnable success, Consumer<String> failure) { adoption.adoptExisting(slot, task, success, failure); }
    void adoptExisting(Slot slot, LiveTask task, boolean collectFromMain,
            Runnable success, Consumer<String> failure) { adoption.adoptExisting(slot, task, collectFromMain, success, failure); }
    void adoptionFailed(Slot slot, LiveTask task, boolean claimed, String error, Consumer<String> failure) { adoption.adoptionFailed(slot, task, claimed, error, failure); }
    void returnToSystem(Slot slot, boolean front, Runnable success, Consumer<String> failure) { adoption.returnToSystem(slot, front, success, failure); }
    void returnToSystem(Slot slot, boolean front, boolean liveMainReveal,
            Runnable success, Consumer<String> failure) { adoption.returnToSystem(slot, front, liveMainReveal, success, failure); }
    boolean returnTicketCurrent(Promotion ticket) { return adoption.returnTicketCurrent(ticket); }
    boolean absentFromShellAndController(Slot slot, LiveTask task) { return adoption.absentFromShellAndController(slot, task); }
    void checkReturned(Slot slot, Promotion ticket, int attempt, Runnable success, Consumer<String> failure) { adoption.checkReturned(slot, ticket, attempt, success, failure); }
    void promoteExternalLaunch(Slot slot) { adoption.promoteExternalLaunch(slot); }
    void promoteUnsupportedSide(Slot slot) { adoption.promoteUnsupportedSide(slot); }
    void scheduleSideModeResolution(Slot slot, int taskId) { adoption.scheduleSideModeResolution(slot, taskId); }
    void repairSideWindowMode(Slot slot, int taskId) { adoption.repairSideWindowMode(slot, taskId); }
    void releaseNativeFreeformSide(Slot slot, int taskId, Object token) { adoption.releaseNativeFreeformSide(slot, taskId, token); }
    static boolean promoteForNotification(String pkg, int userId, Runnable dispatch) {
        MiStepHost hh = current();
        if (hh == null) return false;
        return hh.adoption.promoteForNotification(pkg, userId, dispatch);
    }
    void resumeNotificationLaunch(Promotion ticket) { adoption.resumeNotificationLaunch(ticket); }
    void queueSidePromotion(Slot slot, boolean desktopLaunch) { adoption.queueSidePromotion(slot, desktopLaunch); }
    void retrySidePromotion(PendingSidePromotion request) { adoption.retrySidePromotion(request); }
    void beginExternalPromotion(PendingSidePromotion request) { adoption.beginExternalPromotion(request); }
    boolean externalPromotionCurrent(Promotion ticket) { return adoption.externalPromotionCurrent(ticket); }
    void afterExternalTransitionIdle(Promotion ticket) { adoption.afterExternalTransitionIdle(ticket); }
    void preannounceDesktopLaunch(android.os.Bundle binding) { adoption.preannounceDesktopLaunch(binding); }
    boolean sideTaskMatchesUser(Slot slot, int user) { return adoption.sideTaskMatchesUser(slot, user); }
    void launchWhenReady(int slot, String pkg, int attempt) { lifecycle.launchWhenReady(slot, pkg, attempt); }
    void toggleVisibility() { lifecycle.toggleVisibility(); }
    void suspendSession(String reason) { lifecycle.suspendSession(reason); }
    void returnUnboundOwnedCandidates(long epoch, Runnable complete) { lifecycle.returnUnboundOwnedCandidates(epoch, complete); }
    void returnUnboundOwnedCandidate(OwnedTask candidate, long epoch, int attempt, Runnable complete) { lifecycle.returnUnboundOwnedCandidate(candidate, epoch, attempt, complete); }
    void parkNext(int index) { lifecycle.parkNext(index); }
    void resumeSession() { lifecycle.resumeSession(); }
    void showResumingSession(long epoch) { lifecycle.showResumingSession(epoch); }
    void resumeNext(int index, long epoch) { lifecycle.resumeNext(index, epoch); }
    void finishPendingSuspend() { lifecycle.finishPendingSuspend(); }
    void beginWorkspaceEntry(Runnable ready) { lifecycle.beginWorkspaceEntry(ready); }
    void awaitWorkspaceFrame(long generation, int attempt, Runnable ready) { lifecycle.awaitWorkspaceFrame(generation, attempt, ready); }
    void setWorkspaceAlpha(float value) { lifecycle.setWorkspaceAlpha(value); }
    void playWorkspaceFade(boolean entering, long generation, Runnable complete) { lifecycle.playWorkspaceFade(entering, generation, complete); }
    void enterKeyguardPrivacy() { lifecycle.enterKeyguardPrivacy(); }
    void refreshKeyguardPrivacy() { lifecycle.refreshKeyguardPrivacy(); }
    void leaveKeyguardPrivacy() { lifecycle.leaveKeyguardPrivacy(); }

    void onSlotTapped(int slotIndex) {
        if(slotIndex==SLOT_TOP) { topCtrl.releaseTopTask(true,true,null); return; }
        swapController.onSlotTapped(slotIndex);
    }

    /** Official empty-cell flow: open an app from Home, then collect that exact task. */
    void captureMainIntoEmptySlot(Slot requestedSlot) { swapController.captureMainIntoEmptySlot(requestedSlot); }

    /** Collect the actual fullscreen task; no captured app or desktop participates. */
    private void startLiveCollect(Slot slot, LiveTask task) { swapController.startLiveCollect(slot, task); }

    private void prepareCollectHandoff(Promotion ticket, Consumer<Boolean> settled) { swapController.prepareCollectHandoff(ticket, settled); }

    void finishLiveCollect(Promotion ticket, MiStepLiveSwap.Result result) { swapController.finishLiveCollect(ticket, result); }

    private void completeLiveCollect(Promotion ticket, MiStepLiveSwap.Result result) { swapController.completeLiveCollect(ticket, result); }

    /** Promote through the existing Shell queue; retain the slot until actual detachment. */
    void swapWithMain(int slotIndex) {
        if(slotIndex==SLOT_TOP) { topCtrl.releaseTopTask(true,true,null); return; }
        swapController.swapWithMain(slotIndex);
    }

    void acquireTransitionInputWindow() { swapController.acquireTransitionInputWindow(); }

    private void releaseTransitionInputWindow() { swapController.releaseTransitionInputWindow(); }

    Object liveHostRootSurface() { return swapController.liveHostRootSurface(); }

    Rect liveSideStrip(MiStepLayout geometry) { return swapController.liveSideStrip(geometry); }

    /** Paint only empty Host controls; real app buffers remain the original task surfaces. */
    void drawLiveEmptyHints(android.graphics.Canvas canvas, MiStepLayout original,            MiStepLayout target, int revealEmptySlots) { chrome.drawLiveEmptyHints(canvas, original, target, revealEmptySlots); }

    boolean holdsLiveFlipZoom() { return swapController.holdsLiveFlipZoom(); }

    void finishLiveSwap(Promotion ticket, MiStepLiveSwap.Result result) { swapController.finishLiveSwap(ticket, result); }

    void finishLiveHome(Promotion ticket, MiStepLiveSwap.Result result) { swapController.finishLiveHome(ticket, result); }

    void closeSwapPreview() { promotionController.closeSwapPreview(); }

    void closeCellMovePreview() { promotionController.closeCellMovePreview(); }

    private Object fullscreenExitTransaction(Promotion ticket) throws Exception { return promotionController.fullscreenExitTransaction(ticket); }

    /** Retire only our binding, then return its canonical task surface to the existing DA. */
    private void handoffPromotedTask(Promotion ticket) throws Throwable { promotionController.handoffPromotedTask(ticket); }

    /** At most three observations; notification or transaction submission alone is insufficient. */
    private void checkPromotion(Promotion ticket, int attempt) { promotionController.checkPromotion(ticket, attempt); }

    /** The promoted binding is already retired; move the remaining owners into its gap. */
    void completeHomePromotion(Promotion ticket) { promotionController.completeHomePromotion(ticket); }

    /** Backfill can take focus again; make the focus request after that last WCT. */
    void finishPromotionFocus(Promotion ticket, Runnable complete, int attempt) { promotionController.finishPromotionFocus(ticket, complete, attempt); }

    void failPromotion(Promotion ticket, String reason) { promotionController.failPromotion(ticket, reason); }

    void clearSlot(Slot slot) { taskEvents.clearSlot(slot); }

    boolean isActive(Slot slot) { return taskEvents.isActive(slot); }

    boolean canRender(Slot slot) { return taskEvents.canRender(slot); }

    void postForSlot(Slot slot, Consumer<Slot> callback) { taskEvents.postForSlot(slot, callback); }

    /** Reject late callbacks from a task the native controller has already relinquished. */
    boolean controllerOwns(Slot slot, int taskId, Object token) { return taskEvents.controllerOwns(slot, taskId, token); }

    void bindTask(Slot slot, int taskId, Object token) { taskEvents.bindTask(slot, taskId, token); }

    static int windowModeOf(Object info) { return HostWindowController.windowModeOf(info); }

    static int configurationOrientationOf(Object info) { return HostWindowController.configurationOrientationOf(info); }

    static int requestedOrientationOf(Object info) { return HostWindowController.requestedOrientationOf(info); }

    void updateTaskWindowState(int slotIndex, Object info, int taskId, boolean active) { winCtl.updateTaskWindowState(slotIndex, info, taskId, active); }

    void syncSlotWindowState(Slot slot) { winCtl.syncSlotWindowState(slot); }

    /**
     * TaskInfo 的多窗口能力标记：不支持的（例如相机）不是拒绝，而是走 mirror 承载。
     * 普通应用仍按原路线进 TaskView。
     */
    /** 规则：除桌面外都可进副格；系统标志不预拒绝，交给 native adoption 结果。 */
    private boolean launchActivitySupportsSide(Intent launch) { return launcher.launchActivitySupportsSide(launch); }

    private void keepUnsupportedInMain(Slot slot, String pkg, String reason) { launcher.keepUnsupportedInMain(slot, pkg, reason); }

    /**
     * TaskViewTaskController keeps mTaskNotFound after an idle task vanished without a token.
     * Reusing that controller otherwise makes the framework remove the next task immediately.
     */
    void resetIdleTaskView(Slot slot) { taskEvents.resetIdleTaskView(slot); }





    /** 与气泡同原语：IStatusBarService.hideCurrentInputMethodForBubbles(displayId)（BC L776）。 */
    void hideIme() { winCtl.hideIme(); }

    // ---------------------------------------------------------------- 启动 / 关闭任务

    void launchPackage(int slotIndex, String pkg) { launcher.launchPackage(slotIndex, pkg); }

    private void launchComponent(int slotIndex, ComponentName cn) { launcher.launchComponent(slotIndex, cn); }

    private void launchIntoSlot(final int slotIndex, Intent launch, String label) { launcher.launchIntoSlot(slotIndex, launch, label); }

    private void chooseExistingOrLaunch(Slot slot, Intent launch, String label, String pkg) { launcher.chooseExistingOrLaunch(slot, launch, label, pkg); }

    void startSlot(final Slot slot, Intent launch, String label) { taskEvents.startSlot(slot, launch, label); }

    static void callHidden(Object target, String name, Object... args) { HostLaunchController.callHidden(target, name, args); }

    static Method findStartActivity(Class<?> cls) { return HostLaunchController.findStartActivity(cls); }

    // ---------------------------------------------------------------- 任务状态 / 密度 / 可见性

    private Object makeListener(final int slotIndex) { return taskEvents.makeListener(slotIndex); }

    private void onTaskIdKnown(int slotIndex, int taskId) { taskEvents.onTaskIdKnown(slotIndex, taskId); }

    /** 目标机 TaskView 的主要状态回调：taskId / token / 可见性都从这里取。 */
    private void onTaskInfo(int slotIndex, Object info) { taskEvents.onTaskInfo(slotIndex, info); }


    private void onTaskVisibility(int slotIndex, int taskId, boolean visible) { taskEvents.onTaskVisibility(slotIndex, taskId, visible); }

    java.util.List<MiStepSideClose> closingTasks(Object info) { return taskEvents.closingTasks(info); }

    private void onTaskRemovalStarted(int slotIndex, int taskId) { taskEvents.onTaskRemovalStarted(slotIndex, taskId); }

    /**
     * v0.9.9：副格是"栈"——某个格子空了，后面的占用格子自动往上排（官方 SideAreaStackView 的紧凑排列）。
     */
    void compactCells() { cellLayout.compactCells(); }

    void requestCompaction() { cellLayout.requestCompaction(); }

    private void finishCellLayout() { cellLayout.finishCellLayout(); }

    private boolean cellMoveCurrent(Slot slot, FrameLayout movingRoot, long epoch, long binding,            int id, Object token, Object controller, View movingView, Object surface) { return cellLayout.cellMoveCurrent(slot, movingRoot, epoch, binding, id, token, controller, movingView, surface); }

    static int compactionHintMask(int initialEmptySlots, int occupiedSlots) { return HostCellLayoutController.compactionHintMask(initialEmptySlots, occupiedSlots); }

    private void animateCellMoves(java.util.List<CellMovePlan> plans, int initialEmptySlots) { cellLayout.animateCellMoves(plans, initialEmptySlots); }

    /** 诊断用：从 RunningTaskInfo 里取 appCompatTaskInfo.topActivityAppBounds（px）。 */
    static String appBoundsOf(Object info) { return HostStaticUtil.appBoundsOf(info); }

    /** An external fullscreen task can be reordered above our roots without killing them. */
    void scheduleSideVisibilityRepair(Slot slot) { taskEvents.scheduleSideVisibilityRepair(slot); }

    /** Idempotently show every live side TaskView after a task-set change. */
    void reassertSideTaskViews(String reason) { taskEvents.reassertSideTaskViews(reason); }

    private boolean visibilityRepairCurrent(Slot slot, long epoch, long binding, int id, Object token) { return taskEvents.visibilityRepairCurrent(slot, epoch, binding, id, token); }

    String describeTasks() { return winCtl.describeTasks(); }

    boolean executeOnShell(Runnable task) { return winCtl.executeOnShell(task); }

    // ---------------------------------------------------------------- v0.9 等比缩小（自绘矩阵）

    float mainScale() { return winCtl.mainScale(); }

    float cellScale() { return winCtl.cellScale(); }

    /** Only side tasks are hosted; the system handles main-screen scaling. */
    float scaleFor(Slot slot) { return winCtl.scaleFor(slot); }

    /**
     * 把任务内容按 k 等比缩放显示到槽位里（官方 DisplayContentSmtEx.applyZoomedMatrixSmt 的等价做法）。
     * 任务窗口保持满屏（应用按整屏手机排版），只对承载表面做矩阵缩放，视觉就是"整屏缩小"。
     */
    void applySlotScale(Slot slot) { winCtl.applySlotScale(slot); }

    void reapplyScale(Slot slot) { winCtl.reapplyScale(slot); }

    /** 把任务边界改回整屏（0,0,1080,2460），应用即按"整屏手机"排版。 */
    void applyFullscreenBounds(Slot slot) { winCtl.applyFullscreenBounds(slot); }

    /** 统一施加"缩放 + 归位"：leash 需要 setPosition(0,0)，容器表面不能动位置。 */
    private boolean applyScaleTransform(Slot slot, Object sc) { return winCtl.applyScaleTransform(slot, sc); }

    /** A late mode6 finish must never pull a task selected by Launcher back into a cell. */
    boolean mayWriteSideSurface(Slot slot) { return surfaceCtl.mayWriteSideSurface(slot); }

    /** 按字段名取 SurfaceControl（先本类后父类；取不到返回 null）。 */
    static Object getSurfaceField(Object target, String name) { return HostSurfaceController.getSurfaceField(target, name); }

    boolean applyWct(String what, Object wct) { return winCtl.applyWct(what, wct); }

    private Object findWctApplier(Object wct) { return winCtl.findWctApplier(wct); }

    static void log(String msg) { HostStaticUtil.log(msg); }
}






