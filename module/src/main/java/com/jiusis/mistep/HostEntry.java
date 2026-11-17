package com.jiusis.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.Binder;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Mi Step宿主模块。
 *
 * v0.1 能力探针（已完成）：核对类/方法签名，确认能拿到 TaskViewTransitions / TaskViewFactory。
 * v0.2 加窗口（已完成）：在 SystemUI 进程内用常规 WindowManager 加一个自己的窗口。
 * v0.3-v0.5（已完成）：单个 TaskView 内渲染真实应用，含 PendingIntent 与自锁修正。
 * v0.6（inc4）：4 个 TaskView（主区+3 格）+ MiStepLayout 几何 + 点击副格交换。
 * v0.7：角部内滑热区（R2 参数）+ 广播触发；首次触发再启动宿主，之后切换显示。
 * v0.8：修 inset 偏移、onTaskInfoChanged 状态、格子密度自适应、亮屏重断言。
 * v0.8.1（本版）：cutout 模式 ALWAYS（去掉 122px 状态栏偏移）、工作区不透明底、
 *                任务边界直发 WCT、热区提到宿主窗口之上。
 * 规格：research/rea分析/10-最少实现规格冻结.md、11-体验移植路线图.md；仍只启动我们自己的新任务。
 */
public class HostEntry implements IXposedHookLoadPackage {

    private static final String TAG = "MiStepProbe";
    static final String SYSTEMUI = "com.android.systemui";
    static final String DESKTOP_ICON_LAUNCH_ACTION =
            "com.jiusis.mistep.DESKTOP_ICON_LAUNCH";
    private static final long LAUNCH_DELAY_MS = 12_000L;   // 等开机稳定
    static final long AFTER_ADD_MS = 2_500L;       // 等 TaskView 表面就绪再启动

    /** 试验方框的位置与尺寸（后面要换成自适应布局）。 */
    static final int BOX_W = 600;
    static final int BOX_H = 900;
    static final int BOX_X = 40;
    static final int BOX_Y = 520;

    static final String TARGET_PKG = "com.android.settings";

    static volatile Context sContext;
    static volatile Object sTaskViewFactory;
    static volatile Object sTaskViewTransitions;
    static volatile Executor sShellExecutor;
    private static volatile boolean sStarted;

    static View sWindowRoot;
    static Object sTaskView;
    static final MiStepKeyguardZoom sKeyguardZoom = new MiStepKeyguardZoom();
    static final MiStepRetainedWindow sRetainedWindow = new MiStepRetainedWindow();
    static final MiStepTopSystemBars sTopSystemBars = new MiStepTopSystemBars();
    static final MiStepImeViewport sInteractiveImeViewport = new MiStepImeViewport();
    static final MiStepImeTargetRefresh imeTargetRefresh = new MiStepImeTargetRefresh();
    static final MiStepTopFocusNotification topFocusNotification = new MiStepTopFocusNotification();
    static final MiStepPassBlurCoordinates sPassBlurCoordinates = new MiStepPassBlurCoordinates();
    static volatile long sUnlockPhysicalShadeGeneration, sUnlockPhysicalShadeUntil;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if ("com.android.settings".equals(lpparam.packageName)) {
            SystemSettingsEntry.install(lpparam.classLoader);
            return;
        }
        if ("android".equals(lpparam.packageName)) {
            NativeMultiWindowUnlock.install(lpparam.classLoader, true);
            hookSystemServer(lpparam.classLoader);
            return;
        }
        if ("com.xiaomi.type".equals(lpparam.packageName)) {
            // Optional keyboard policy only; ordinary app layout/input uses the system hooks.
            hookMiTypeAutoFloating(lpparam.classLoader);
            return;
        }
        if (!SYSTEMUI.equals(lpparam.packageName)) {
            // Raw correction is owned by the system publisher; never invert it twice in apps.
            return;
        }
        final ClassLoader cl = lpparam.classLoader;
        log("=== attached to " + SYSTEMUI + " build=1.4.0 release ===");
        try {
            NativeMultiWindowUnlock.install(cl, false);
            MiStepSystemActions.install(cl);
            MiStepNativeRecents.install(cl);
            MiStepNativeMedia.install(cl);
            MiStepIslandMonitorInput.install(cl, () -> sContext);
            MiStepStatusBarSampling.install(cl);
            MiStepStatusBar.install(cl);
            MiStepTopNativeCaption.install(cl);
            MiStepPanelInput.install(cl);
            MiStepBiometricWindow.install(cl);
            MiStepMainUiConfiguration.install(cl);
            MiStepNotificationLaunch.install(cl, () -> sContext);
            installRetainedWorkspaceWakeHooks(cl);
            NativeFreeformCoordinates.install(cl);
            NativeFreeformPreview.install(cl);
            hookApplication(cl);
            hookShellProviders(cl);
            hookRecentsWallpaper(cl);
            hookBackGestureDiagnostics(cl);
            hookTransitionBounds(cl);
        } catch (Throwable t) {
            log("hooks failed: " + t);
        }
    }

    /**
     * MiType independently enters a compact PopupWindow when a landscape
     * keyboard cannot fit the current task width.  That is the wrong policy
     * inside Mi Step: the main task owns the native landscape transform, so
     * the IME should keep its normal full landscape layout.  Skip only the
     * keyboard's auto-floating decision; manual floating mode remains owned
     * by the keyboard itself.
     */
    private static void hookMiTypeAutoFloating(ClassLoader cl) {
        log("=== attached to com.xiaomi.type build=1.4.0 release ===");
        try {
            Class<?> service = XposedHelpers.findClass("com.mi.ime.MiInputMethodService", cl);
            XposedHelpers.findAndHookMethod(service, "handleSpacePressureAutoFloating",
                    android.view.inputmethod.EditorInfo.class, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Context)) return;
                    Context context = (Context) param.thisObject;
                    try {
                        long now = System.currentTimeMillis();
                        String encoded = android.provider.Settings.Global.getString(
                                context.getContentResolver(), "mistep_zoom");
                        if (!MiStepImePolicy.suppressAutomaticFloating(encoded,
                                now - android.os.SystemClock.elapsedRealtime(), now)) return;
                    } catch (Throwable unavailable) {
                        // Missing/denied/stale host state must leave the keyboard alone.
                        return;
                    }
                    // The original method only replaces the normal input view
                    // with MiType's floating placeholder and PopupWindow.  A
                    // no-op keeps the IME attached to the native landscape
                    // window configuration already supplied by WM.
                    param.setResult(null);
                    log("MiType automatic landscape floating suppressed");
                }
            });
            log("MiType auto-floating decision hook installed");
        } catch (Throwable error) {
            log("MiType auto-floating hook unavailable: " + error);
        }
    }

    // ---------------------------------------------------------------- v1.1.0：系统框架整屏缩放（R2 同款）

    static volatile Object sDisplayContent;
    static volatile Object sSystemContext;
    static volatile boolean sZoomed;
    static volatile ZoomTarget sZoomTarget;
    /** 缩放动画只在 WM handler 上创建/取消；帧内矩阵仍在 mGlobalLock 内应用。 */
    static volatile ValueAnimator sZoomAnimator;
    static volatile String sZoomAnimKey;
    static volatile boolean sZoomFrameApply;
    static volatile PathInterpolator zoomAnimInterpolator;
    static volatile float sZoomScale = 1f, sZoomOffset, sZoomOffsetX;
    static final long sZoomWatcherStarted = System.currentTimeMillis();
    static final ZoomChannelState sZoomChannels = new ZoomChannelState(sZoomWatcherStarted);
    static volatile android.os.FileObserver sZoomFileObserver;
    static volatile Handler sZoomBridgeHandler;
    static volatile boolean sHostZoomed;
    static volatile String sZoomReadError;
    static volatile String sZoomLastSeen;
    static volatile String sZoomLastDispatched;
    static volatile String sZoomLastTargetNull;
    static ZoomCommit sLiveZoomCommit;
    static long sLiveZoomIssued;
    static volatile boolean sFullscreenRotationActive;
    static volatile int sHeldDisplayRotation;
    static volatile Object sMiStepFixedRotationApp;
    static volatile int sNativeLandscapeState = NativeLandscapePolicy.INACTIVE;
    /** Accessed only under the WMS global lock; these are per-token, not display launches. */
    static final java.util.Map<Object, Integer> sTaskRotations =
            new java.util.WeakHashMap<>();
    /** Only surfaces actually written by our WM magnification, under its global lock. */
    static final java.util.Map<Object, Object> sMagnifiedSurfaces =
            new java.util.WeakHashMap<>();
    static final java.util.Map<Object, Object> sMagnificationFinishReplays =
            new java.util.WeakHashMap<>();
    static final java.util.Map<Object, Object> sHomeTransitionInfos =
            new java.util.WeakHashMap<>();
    static final ThreadLocal<Boolean> sFinalMagnificationSnapshot = new ThreadLocal<>();
    static final ThreadLocal<Object> sMagnificationFinishInfo = new ThreadLocal<>();
    static final ThreadLocal<Object> sWindowFocusScanDisplay = new ThreadLocal<>();
    static final NativeFreeformStartGate sNativeFreeformStart = new NativeFreeformStartGate();
    static final NativeFreeformStartGate sLiveSwapZoomSurfaces = new NativeFreeformStartGate();
    /** Per-task state survives top-activity changes and main/side ownership swaps. */
    static final java.util.Map<Object, TaskWindowState> sTaskWindowStates =
            new java.util.WeakHashMap<>();
    static java.lang.reflect.Method sTaskRotationResolver;
    static boolean sUpdatingTaskRotation;
    static final java.util.Map<Object, String> sGestureSurfaces =
            new java.util.WeakHashMap<>();

    static void setHostZoomed(boolean zoomed) { HostEntryZoom.setHostZoomed(zoomed); }

    static final class ZoomTarget {
        final Object display;
        final Object globalLock;
        final Handler handler;
        final ZoomDispatch dispatch;

        ZoomTarget(Object display, Object globalLock, Handler handler) {
            this.display = display;
            this.globalLock = java.util.Objects.requireNonNull(globalLock);
            this.handler = handler;
            this.dispatch = new ZoomDispatch(handler::post, globalLock,
                    (identity, scale, offsetY, offsetX) -> {
                        if (sZoomTarget == identity) {
                            // 静态状态请求优先于动画：先取消动画，避免结束回调把旧目标写回去。
                            cancelZoomAnimation(this);
                            applyDisplayZoomLocked(this, scale, offsetY, offsetX);
                        }
                    }, error -> {
                        log("WM zoom request stopped; no same-state retry: " + error);
                        XposedBridge.log(error);
                    });
        }

        /** 动画命令必须回到 WM handler 上创建，与普通请求共用同一串行化路径。 */
        void submitAnim(ZoomAnimCommand.Plan plan, String key) {
            handler.post(() -> {
                if (sZoomTarget != this) return;
                startZoomAnimation(this, plan, key);
            });
        }
    }

    /**
     * 系统框架作用域（LSPosed 里要勾"系统框架"）：
     * 捕获默认显示器的 DisplayContent，收到 com.jiusis.mistep.ZOOM 广播时调用
     * DisplayContent.applyMagnificationSpec(scale/offset) —— 目标机自带的整屏缩放原语，
     * 与锤子Mi Step的 MagnificationSpecSmt / applyZoomedMatrixSmt 是同一机制的官方版本。
     */
    private void hookSystemServer(ClassLoader cl) { HostEntrySupport.INSTANCE.hookSystemServer(cl); }

    static volatile boolean sZoomBridgeArmed;
    static volatile android.content.Context sWmsContext;
    static volatile String sLastSettingsZoom;
    static boolean sZoomSettingsWatcherArmed;

    /** Settings.Global 通道：SystemUI 有 WRITE_SECURE_SETTINGS，system_server 读取不受文件权限影响。 */
    static synchronized void startZoomSettingsWatcher() { HostEntryZoom.startZoomSettingsWatcher(); }
    /** 文件通道在部分机型被 SELinux/权限挡住；再开一条 SystemUI -> system_server 的广播直投。 */
    static synchronized void armZoomBridge(Object service) { HostEntryZoom.armZoomBridge(service); }
    /** The captured transaction is the one DisplayContent applies, never a separate crop transaction. */
    static final class ZoomCommit {
        final Context context;
        final ZoomTarget target;
        final LiveSwapZoom.Zoom zoom;
        long transactionId;
        float actualScale = Float.NaN, actualX = Float.NaN, actualY = Float.NaN;
        boolean complete;
        boolean applied;

        ZoomCommit(Context context, ZoomTarget target, LiveSwapZoom.Zoom zoom) {
            this.context = context; this.target = target; this.zoom = zoom;
        }

        void finish(boolean committed, String error) {
            if (complete) return;
            Object replay = null;
            if (committed) {
                try {
                    synchronized (target.globalLock) {
                        if (target != sZoomTarget || !zoom.sameTuple(sZoomScale, sZoomOffsetX, sZoomOffset))
                            throw new IllegalStateException("zoom changed before finish snapshot");
                        replay = createMagnificationFinishReplay(target.display);
                    }
                } catch (Throwable failure) {
                    LiveSwapZoom.closeReplay(replay); replay = null;
                    committed = false; error = "WMS finish snapshot failed: " + failure.getClass().getSimpleName();
                }
            }
            complete = true;
            if (sLiveZoomCommit == this) sLiveZoomCommit = null;
            Intent ack = new Intent(LiveSwapZoomProtocol.ACK).setPackage(SYSTEMUI);
            ack.putExtra(LiveSwapZoomProtocol.GENERATION, zoom.generation);
            ack.putExtra(LiveSwapZoomProtocol.TRANSACTION, transactionId);
            ack.putExtra(LiveSwapZoomProtocol.SCALE, Float.floatToIntBits(actualScale));
            ack.putExtra(LiveSwapZoomProtocol.X, Float.floatToIntBits(actualX));
            ack.putExtra(LiveSwapZoomProtocol.Y, Float.floatToIntBits(actualY));
            ack.putExtra(LiveSwapZoomProtocol.COMMITTED, committed);
            if (replay != null) ack.putExtra(LiveSwapZoomProtocol.FINISH_REPLAY, (android.os.Parcelable) replay);
            if (error != null) ack.putExtra(LiveSwapZoomProtocol.ERROR, error);
            final Object sentReplay = replay;
            try {
                // We run inside system_server: AMS can retain the Intent locally and parcel
                // it later. Keep native operations alive until actual receiver dispatch ends.
                context.sendOrderedBroadcast(ack, LiveSwapZoomProtocol.PERMISSION,
                        new BroadcastReceiver() {
                            @Override public void onReceive(Context c, Intent result) {
                                LiveSwapZoom.closeReplay(sentReplay);
                            }
                        }, target.handler, 0, null, null);
            } catch (Throwable failure) {
                LiveSwapZoom.closeReplay(sentReplay);
                log("live zoom acknowledgement failed: " + failure);
            }
        }
    }

    /** Snapshot operations only. Shell merges and applies them with the task handoff. */
    static Object createMagnificationFinishReplay(Object display) throws Exception { return HostEntryZoom.createMagnificationFinishReplay(display); }

    static void reapplyFinalMagnification(Object display, Object transaction) { HostEntryZoom.reapplyFinalMagnification(display, transaction); }

    static void reapplyFinalMagnification(Object display, Object transaction, Object info) { HostEntryZoom.reapplyFinalMagnification(display, transaction, info); }

    static void receiveLiveZoom(Context context, Intent intent) { HostEntryZoom.receiveLiveZoom(context, intent); }
    /** Transfer the existing physical gesture; InputDispatcher maps task coordinates once. */
    static void transferSideTouch(Intent intent) { HostEntrySupport.transferSideTouch(intent); }
    /** watcher 与 FileObserver 共用：读文件、按租约解析、投递到 WM handler。 */
    static void readAndDispatchZoom(java.io.File f) { HostEntryZoom.readAndDispatchZoom(f); }

    /** Replay on target creation and restore only when the shared host lease expires. */
    static void dispatchCurrentZoom() { HostEntryZoom.dispatchCurrentZoom(); }

    /**
     * 去重可能永久吞掉与上次相同的目标值：若 WM 侧真实 spec 被外部路径清掉/改掉，
     * 相同心跳会被 dedup 拦截，工作区从此不再缩放（与动画路径的 invalidateLastAttempt
     * 同理，但外部清 spec 没有人清 lastAttempt）。这里低频（每 3 次派发约 1 次）在
     * WM 锁下读回真实 spec，发现发散就清 lastAttempt，让下一次 submit 自然生效。
     */
    static int sZoomDivergenceTick;

    static void scheduleZoomDivergenceCheck(ZoomTarget target, float[] desired) { HostEntryZoom.scheduleZoomDivergenceCheck(target, desired); }

    /** Preserve active WM magnification after Shell resets its transition targets. */
    static void hookMagnificationReplayAfterTransition(ClassLoader cl) { HostEntryZoom.INSTANCE.hookMagnificationReplayAfterTransition(cl); }

    static void finishNativeFreeformStart(ZoomTarget target, Object start) { HostEntryNativeFreeform.finishNativeFreeformStart(target, start); }

    static final MiStepSystemUiRotation sMainSystemUiRotation = new MiStepSystemUiRotation();
    static final MiStepStatusBarClip sMainStatusBarClip = new MiStepStatusBarClip();
    static final MiStepImeProcessConfiguration sMainImeProcessConfiguration =
            new MiStepImeProcessConfiguration();
    static Object sPublishedMainUiState;
    static boolean systemUiRotationFailureLogged;

    private void updateMainSystemUiRotation(Object window) { HostEntryRotation.INSTANCE.updateMainSystemUiRotation(window); }

    static void publishMainUiConfiguration(Object app) { HostEntryRotation.publishMainUiConfiguration(app); }

    static void installMainSystemUiRotation(ClassLoader cl) { HostEntryRotation.INSTANCE.installMainSystemUiRotation(cl); }

    private void installMagnificationSurfaceTracking(ClassLoader cl) { HostEntrySupport.INSTANCE.installMagnificationSurfaceTracking(cl); }

    static void rememberMagnifiedSurface(Object container, Object spec) { HostEntryZoom.rememberMagnifiedSurface(container, spec); }

    static void stagePassBlurCoordinates(Object display, Object transaction, Object spec) {
        HostEntry.ZoomTarget target = sZoomTarget;
        if (target == null || display != target.display || display != sDisplayContent
                || !Thread.holdsLock(target.globalLock)) return;
        try {
            sPassBlurCoordinates.stage(display, transaction,
                    spec == null ? 1f : XposedHelpers.getFloatField(spec, "scale"),
                    spec == null ? 0f : XposedHelpers.getFloatField(spec, "offsetX"),
                    spec == null ? 0f : XposedHelpers.getFloatField(spec, "offsetY"));
        } catch (Throwable error) {
            log("passblur coordinate publication unavailable: " + error.getClass().getSimpleName());
        }
    }

    static int restoreExitedMagnificationLocked(ZoomTarget target) { return HostEntryZoom.restoreExitedMagnificationLocked(target); }

    /** 宿主写 SystemUI 私有文件时立即处理，避免 250ms 轮询造成的桥/缩放不同帧。 */
    static void installZoomFileObserver() { HostEntryZoom.installZoomFileObserver(); }

    /** Hold the workspace portrait; app transforms must never announce display rotation. */
    static void installFixedMainRotationHooks(ClassLoader cl) { HostEntryRotation.INSTANCE.installFixedMainRotationHooks(cl); }

    /** Root-task bounds remain in display orientation while our main token is rotated. */
    static void installFixedMainTouchableCropHook(ClassLoader cl) { HostEntryRotation.INSTANCE.installFixedMainTouchableCropHook(cl); }

    static boolean cropFixedMainTouchableRegion(Object window,            android.graphics.Region region) { return HostEntryRotation.cropFixedMainTouchableRegion(window, region); }

    private void beforeApplicationOrientationRequest(Object activity, int request) { HostEntryRotation.INSTANCE.beforeApplicationOrientationRequest(activity, request); }

    private Boolean applyMiStepFixedRotationLocked(Object display, Object activity, String source) { return HostEntryRotation.INSTANCE.applyMiStepFixedRotationLocked(display, activity, source); }

    static final class RotationResolution {
        final int overrideOrientation, resolvedRotation, taskRotation;
        final boolean rotationLocked;

        RotationResolution(int overrideOrientation, int resolvedRotation, int taskRotation,
                boolean rotationLocked) {
            this.overrideOrientation = overrideOrientation;
            this.resolvedRotation = resolvedRotation;
            this.taskRotation = taskRotation;
            this.rotationLocked = rotationLocked;
        }

        boolean lands() {
            return taskRotation == 1 || taskRotation == 3;
        }
    }

    private RotationResolution resolveTaskRotationLocked(Object display, Object activity,            Object task, int windowingMode, String source) throws Throwable { return HostEntryRotation.INSTANCE.resolveTaskRotationLocked(display, activity, task, windowingMode, source); }

    private void requestNativeLandscapeLocked(Object activity, String source) { HostEntryRotation.INSTANCE.requestNativeLandscapeLocked(activity, source); }

    private void evaluateNativeLandscapeLocked(Object display, String source) { HostEntryRotation.INSTANCE.evaluateNativeLandscapeLocked(display, source); }

    private void sendNativeLandscapeMode(boolean fullscreen) { HostEntryRotation.INSTANCE.sendNativeLandscapeMode(fullscreen); }

    static boolean releaseTaskRotationLocked(Object activity, String reason) { return HostEntryRotation.releaseTaskRotationLocked(activity, reason); }

    /** Keep orientation/windowing facts with the task, rather than the current top activity. */
    static void recordTaskWindowState(Object activity, int requestedOrientation,            int resolvedRotation, int taskRotation, int fallbackWindowingMode, String source) { HostEntryRotation.recordTaskWindowState(activity, requestedOrientation, resolvedRotation, taskRotation, fallbackWindowingMode, source); }

    static final java.util.Map<Object,String> topTaskSignals=new java.util.WeakHashMap<>();
    static long topWorkspaceGeneration;

    static void sendTopTaskDirection(Object task,int id,int request,int resolved,int rotation,int mode) { HostEntryRotation.sendTopTaskDirection(task, id, request, resolved, rotation, mode); }

    /** Only our translucent mode6 role with a wide root opts out of local portrait rotation. */
    static boolean isUpperLandscapeTask(Object task) { return HostEntryRotation.isUpperLandscapeTask(task); }

    static void captureTaskWindowState(Object display, Object activity, String source) { HostEntryRotation.captureTaskWindowState(display, activity, source); }

    static void releaseAllTaskRotationsLocked(String reason) { HostEntryRotation.releaseAllTaskRotationsLocked(reason); }

    private void refreshMiStepTaskRotationLocked(Object display) { HostEntryRotation.INSTANCE.refreshMiStepTaskRotationLocked(display); }

    private boolean isSideFixedTask(Object activity) { return HostEntryRotation.INSTANCE.isSideFixedTask(activity); }

    static boolean isMiStepSideTask(Object task) { return HostEntryRotation.isMiStepSideTask(task); }

    static final ThreadLocal<Boolean> sReadingWorkspaceFocus = new ThreadLocal<>();
    static final ThreadLocal<Boolean> sWorkspaceLauncherTaskQuery = new ThreadLocal<>();

    private void installWorkspaceRunningTasks(ClassLoader cl) { HostEntrySupport.INSTANCE.installWorkspaceRunningTasks(cl); }

    private void installWorkspaceFocus(ClassLoader cl) { HostEntrySupport.INSTANCE.installWorkspaceFocus(cl); }

    static void installNativeFreeformCoexistence(ClassLoader cl) { HostEntryNativeFreeform.INSTANCE.installNativeFreeformCoexistence(cl); }

    static Object sNativeFreeformLayerAnchor;
    static Object sNativeFreeformPinManager;
    static final java.util.Map<Object, NativeHostLayer> sNativeFreeformHostLayers =
            new java.util.IdentityHashMap<>();
    static final ThreadLocal<Object> sNativeTouchExclusionDisplay = new ThreadLocal<>();
    static Object sNativeTouchExclusionManager;

    static void installNativeFreeformPin(ClassLoader cl) { HostEntryNativeFreeform.installNativeFreeformPin(cl); }

    static boolean isNativeForegroundPin(Object task) { return HostEntryNativeFreeform.isNativeForegroundPin(task); }

    static void releaseNativeForegroundPinLayers(Object manager, int taskId) { HostEntryNativeFreeform.releaseNativeForegroundPinLayers(manager, taskId); }

    static void installNativeFreeformTouchExclusion(ClassLoader cl) { HostEntryNativeFreeform.installNativeFreeformTouchExclusion(cl); }

    static final class NativeHostLayer {
        final Object surface;
        int normalLayer;
        Object normalRelativeTo;
        Object nativeAnchor;
        int nativeLayer;

        NativeHostLayer(Object surface) { this.surface = surface; }
    }

    /** Amend WM's own layer transaction; never reparent or transform an OEM task. */
    static void placeHostBelowNativeFreeforms(Object display, Object transaction) { HostEntryNativeFreeform.placeHostBelowNativeFreeforms(display, transaction); }

    static void rememberNativeHostNormalLayer(Object host, NativeHostLayer state) { HostEntryNativeFreeform.rememberNativeHostNormalLayer(host, state); }

    static void restoreNativeHostLayer(Object host, NativeHostLayer state, Object transaction) { HostEntryNativeFreeform.restoreNativeHostLayer(host, state, transaction); }

    private void installSideSystemBarPolicy(ClassLoader cl) { HostEntrySupport.INSTANCE.installSideSystemBarPolicy(cl); }

    private void installDesktopIconLaunchBridge(ClassLoader cl) { HostEntrySupport.INSTANCE.installDesktopIconLaunchBridge(cl); }

    static volatile String sLastDesktopLaunchPackage;
    static volatile int sLastDesktopLaunchUser = -1;
    static volatile long sLastDesktopLaunchAt;

    static int desktopLaunchUser(java.lang.reflect.Method method, Object[] args) { return HostEntrySupport.desktopLaunchUser(method, args); }

    static void forwardDesktopIconLaunch(Object[] args, int launchUser) { HostEntrySupport.forwardDesktopIconLaunch(args, launchUser); }

    private boolean isSystemRotationLocked(Object display) { return HostEntryRotation.INSTANCE.isSystemRotationLocked(display); }

    static void beginFixedMainRotationLocked(Object display) { HostEntryRotation.beginFixedMainRotationLocked(display); }

    static void endFixedMainRotationLocked(Object display) { HostEntryRotation.endFixedMainRotationLocked(display); }

    static void recomputeDisplayOrientationLocked(Object display, String reason) { HostEntryRotation.recomputeDisplayOrientationLocked(display, reason); }

    static void applyDisplayZoomLocked(ZoomTarget target, float scale,            float offsetY, float offsetX) throws Throwable { HostEntryZoom.INSTANCE.applyDisplayZoomLocked(target, scale, offsetY, offsetX); }

    static void applyDisplayZoomLocked(ZoomTarget target, float scale,            float offsetY, float offsetX, ZoomCommit commit) throws Throwable { HostEntryZoom.INSTANCE.applyDisplayZoomLocked(target, scale, offsetY, offsetX, commit); }

    static void applyDisplayZoomLocked(ZoomTarget target, float scale,            float offsetY, float offsetX, ZoomCommit commit, boolean nativeVisibility) throws Throwable { HostEntryZoom.INSTANCE.applyDisplayZoomLocked(target, scale, offsetY, offsetX, commit, nativeVisibility); }

    /**
     * 整屏缩放动画：从当前生效 spec 插值到目标 spec，每帧仍在 WMS.mH 上、
     * 持真实 mGlobalLock 应用（与静态缩放同一路径）。R2 的 ZoomAnimController
     * 也用 ValueAnimator + PathInterpolator(0.34,0.69,0.1,1.0) 在 WMS 动画线程上
     * 逐帧写 applyZoomedMatrixSmt；这里沿用同一曲线与时长语义。
     *
     * POC 边界：本批只验证“逐帧整屏缩放”在目标机是否平滑、无崩溃；尚未接入
     * 主副交换动画，也不与宿主桥接动画同步。
     */
    private static void startZoomAnimation(ZoomTarget target, ZoomAnimCommand.Plan plan, String key) { HostEntryZoom.INSTANCE.startZoomAnimation(target, plan, key); }

    /** 只能在 WM handler 上取消；其它线程转发过去，不做 inline 调用。 */
    static void cancelZoomAnimation(ZoomTarget target) { HostEntryZoom.INSTANCE.cancelZoomAnimation(target); }

    static void applyGestureSurfacesLocked(ZoomTarget target, float scale,
            float offsetY, float offsetX) {
        if (Looper.myLooper() != target.handler.getLooper() || !Thread.holdsLock(target.globalLock)) return;
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            int count = 0;
            synchronized (sGestureSurfaces) {
                java.util.Iterator<Object> it = sGestureSurfaces.keySet().iterator();
                while (it.hasNext()) {
                    Object surface = it.next();
                    if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) {
                        it.remove();
                        continue;
                    }
                    // Its InputWindowHandle uses replaceTouchableRegionWithCrop(null).
                    // Navigation and native freeform spies use the task's logical display space.
                    Rect bounds = (Rect) XposedHelpers.callMethod(target.display, "getBounds");
                    float monitorScale = NativeFreeformPolicy.gestureScale(
                            sNativeFreeformLayerAnchor != null, sGestureSurfaces.get(surface), scale);
                    float x = monitorScale == 1f ? 0f : offsetX;
                    float y = monitorScale == 1f ? 0f : offsetY;
                    Rect crop = monitorScale == 1f ? null : new Rect(0, 0, bounds.width(), bounds.height());
                    XposedHelpers.callMethod(tx, "setCrop", surface, crop);
                    XposedHelpers.callMethod(tx, "setMatrix", surface, monitorScale, 0f, 0f, monitorScale);
                    XposedHelpers.callMethod(tx, "setPosition", surface, x, y);
                    count++;
                }
            }
            if (count > 0) {
                XposedHelpers.callMethod(tx, "apply");
                if (!sZoomFrameApply) {
                    log("gesture geometry scale=" + scale + " offset=" + offsetX + "," + offsetY
                            + " monitors=" + count);
                }
            }
        } catch (Throwable error) { log("navigation input transform stopped: " + error); }
        finally { if (tx != null) try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {} }
    }

    private void hookApplication(ClassLoader cl) { HostEntrySupport.INSTANCE.hookApplication(cl); }

    private void hookBackGestureDiagnostics(ClassLoader cl) { HostEntrySupport.INSTANCE.hookBackGestureDiagnostics(cl); }

    /**
     * MIUI computes the app open/close animation rectangle before SurfaceControl playback.
     * Keep that temporary visual rectangle inside Mi Step's main pane; task bounds and the
     * normal full-screen launcher animation are left untouched.
     */
    private void hookTransitionBounds(ClassLoader cl) { HostEntrySupport.INSTANCE.hookTransitionBounds(cl); }


    static void logBackEvent(String source, android.view.MotionEvent event) { HostEntrySupport.logBackEvent(source, event); }

    private void hookRecentsWallpaper(ClassLoader cl) { HostEntrySupport.INSTANCE.hookRecentsWallpaper(cl); }

    private void hookShellProviders(ClassLoader cl) { HostEntrySupport.INSTANCE.hookShellProviders(cl); }

    static void scheduleStart() {
        if (sStarted) {
            return;
        }
        sStarted = true;
        new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                // 紧急停止阀：只要这个文件存在就什么都不做。
                if (new java.io.File("/data/system/mistep-halt").exists()) {
                    log("halt marker present -> skipping test");
                    return;
                }
                // 一次性自锁：本次安装只允许尝试启动一次。
                // 死在启动这Mi Step会让 SystemUI 崩溃重启，而重启后模块会再次运行；
                // 没有这道锁就会形成崩溃循环（LSPosed 安全模式是外部兜底，不该依赖）。
                // 锁文件放在 SystemUI 自己的数据目录，跨进程重启依然有效。
                log("capabilities: factory=" + sTaskViewFactory
                        + " executor=" + sShellExecutor + " transitions=" + sTaskViewTransitions);
                if (sTaskViewFactory == null || sShellExecutor == null || sTaskViewTransitions == null) {
                    log("missing capability, abort");
                    return;
                }
                // v1.2.1：不再用一次性自锁挡触发器（重启后"呼不出/退不出"的根源）。
                // 触发器本身是热区+广播，安全；真正危险的是早期自动启动应用，已删除。
                installTriggers();
            }
        }, LAUNCH_DELAY_MS);
    }

    /** v0.7.0：角部热区 + 广播触发；首次触发再启动宿主，之后切换显示。 */
    private static void installTriggers() { HostEntrySupport.INSTANCE.installTriggers(); }

    /** 锁屏保留工作区：原生亮屏/解锁事件先于 SCREEN_ON 广播到达时提前恢复工作区。 */
    private static void installRetainedWorkspaceWakeHooks(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.keyguard.KeyguardLifecyclesDispatcher$KeyguardLifecycleHandler",
                    cl, "handleMessage", android.os.Message.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            int event = ((android.os.Message) param.args[0]).what;
                            // Native SCREEN_TURNING_ON and STARTED_WAKING_UP precede
                            // SCREEN_ON broadcasts and the first visible desktop frame.
                            if (event == 0 || event == 4) MiStepHost.onNativeWake();
                        }
                    });
            log("retained workspace native wake hook armed");
        } catch (Throwable unavailable) {
            log("retained workspace native wake hook unavailable: " + unavailable);
        }
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.statusbar.phone.StatusBarKeyguardViewManager",
                    cl, "hide", long.class, long.class, new XC_MethodHook() {
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            MiStepHost.onNativeWake();
                        }
                    });
            log("retained workspace native unlock hook armed");
        } catch (Throwable unavailable) {
            log("retained workspace native unlock hook unavailable: " + unavailable);
        }
    }

    /**
     * 无人值守调试广播（v0.8.0）：
     *   am broadcast -a com.jiusis.mistep.DEBUG --ei slot 1 --es pkg com.android.settings
     *   am broadcast -a com.jiusis.mistep.DEBUG --ei slot 1 --ez swap true
     * 先确保宿主已启动，再转给 MiStepHost。
     */
    private void handleDebugBroadcast(android.content.Intent intent) { HostEntrySupport.INSTANCE.handleDebugBroadcast(intent); }

    /** 通过工厂创建一个 TaskView；回调在 Dagger/Shell 线程上，转交主线程做视图操作。 */
    private void createTaskView() throws Exception { HostEntrySupport.INSTANCE.createTaskView(); }

    /** 把 TaskView 放进我们自己的窗口，然后启动目标应用。 */
    private void attachAndLaunch() { HostEntrySupport.INSTANCE.attachAndLaunch(); }

    /** 用公开的 TaskView#startActivity 启动目标应用；ActivityOptions 的隐藏 setter 走反射。 */
    /** 一次性自锁文件：写在 SystemUI 自己的数据目录，重启进程后仍存在。 */
    private java.io.File latchFile() { return HostEntrySupport.INSTANCE.latchFile(); }

    private void armLatch() { HostEntrySupport.INSTANCE.armLatch(); }

    /** 为目标 Intent 造 PendingIntent 并调用 TaskView#startActivity。 */
    private void launchTargetApp(View taskView) { HostEntrySupport.INSTANCE.launchTargetApp(taskView); }

    /** 隐藏 API 的调用一律走反射，失败要能看见具体是哪一个。 */
    static void callHidden(Object target, String name, Object... args) { HostEntrySupport.callHidden(target, name, args); }

    /** 用动态代理实现隐藏接口 TaskView.Listener，记录每个回调。 */
    private Object makeListener(Executor executor, String ifaceName) { return HostEntrySupport.INSTANCE.makeListener(executor, ifaceName); }

    static void log(String msg) {
        try { RuntimeDiagnosticEvents.fromHostLog(msg); } catch (Throwable ignored) {}
        Log.i(TAG, msg);
        try {
            XposedBridge.log(TAG + ": " + msg);
        } catch (Throwable ignored) {
            // 至少保留 logcat
        }
    }

    // 保留：字段探查工具，后续看状态用
    @SuppressWarnings("unused")
    static Object readField(Object target, String name) { return HostEntrySupport.readField(target, name); }

    @SuppressWarnings("unused")
    static String sig(Method m) { return HostEntrySupport.sig(m); }

    @SuppressWarnings("unused")
    private static final Map<String, String> NOTES = new LinkedHashMap<>();
}




