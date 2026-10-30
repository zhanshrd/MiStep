package com.jiusis.mistep;

import android.content.Intent;
import android.os.SystemClock;

/**
 * zoom 指令传输层（BUG-3 主框缩放的发射端）。
 * 三条独立通道把 "scale offsetY offsetX" 送达消费方：
 *  1. 私有文件 mistep-zoom（system_server 读取，需放开读权限）
 *  2. Settings.Global["mistep_zoom"]（带时间戳编码）
 *  3. 广播 com.jiusis.mistep.ZOOM（绕过文件权限/SELinux 限制）
 * 自 MiStepHost 原样搬出；宿主内保留同名委托 shim。
 * 状态字段 zoomAnimUntil / zoomCommandGeneration 留在宿主（live swap 守卫也在用），经 h.* 读写。
 */
final class ZoomTransport {
    private final MiStepHost h;
    private String lastBroadcastZoom;

    ZoomTransport(MiStepHost host) {
        h = host;
    }

    String zoomOnCommand() {
        return h.layout.mainScale + " " + h.layout.mainOffsetY + " " + h.layout.mainOffsetX;
    }

    void writeZoomAnimated(boolean on) {
        final long generation = ++h.zoomCommandGeneration;
        final String steady = on ? zoomOnCommand() : "1 0 0";
        h.zoomAnimUntil = SystemClock.uptimeMillis() + MiStepHost.ZOOM_ANIM_MS + 250L;
        writeZoomLine("anim " + steady + " " + MiStepHost.ZOOM_ANIM_MS);
        h.main.postDelayed(() -> {
            if (!ZoomLifecyclePolicy.shouldApplyDelayedWrite(generation,
                    h.zoomCommandGeneration, on, h.zoomed)) {
                MiStepHost.log("stale zoom animation completion ignored generation=" + generation);
                return;
            }
            writeZoomLine(steady);
        }, MiStepHost.ZOOM_ANIM_MS + 150L);
    }

    void writeZoom(boolean on) {
        h.zoomCommandGeneration++;
        writeZoomLine(on ? zoomOnCommand() : "1 0 0");
    }

    void writeZoomLine(String command) {
        writeZoomLine(command, System.currentTimeMillis());
    }

    void writeZoomLine(String command, long issuedAt) {
        // Independent transport: a failed private-file write must not suppress zoom/restore.
        broadcastZoom(command, issuedAt);
        try {
            java.io.File f = new java.io.File(h.ctx.getFilesDir(), "mistep-zoom");
            android.util.AtomicFile file = new android.util.AtomicFile(f);
            java.io.FileOutputStream stream = null;
            try {
                stream = file.startWrite();
                stream.write(command.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                file.finishWrite(stream);
                // system_server 需要读这个文件来应用单手模式缩放；AtomicFile 默认 0600，必须放开读权限。
                if (!f.setReadable(true, false)) {
                    MiStepHost.log("writeZoom: setReadable(false) returned false");
                }
            } catch (java.io.IOException failure) {
                if (stream != null) file.failWrite(stream);
                throw failure;
            }
            MiStepHost.log("zoom trigger written: " + command);
        } catch (Throwable t) {
            MiStepHost.log("writeZoom failed: " + t);
        }
    }

    /** 广播直投：绕过 system_server 读 SystemUI 私有文件的权限/SELinux 限制。 */
    private void broadcastZoom(String command, long issuedAt) {
        try {
            String encoded = ZoomChannelState.encode(command, issuedAt);
            String steady = command.trim();
            if (steady.startsWith("anim")) {
                String[] parts = steady.split("\\s+");
                if (parts.length < 4) return;
                steady = parts[1] + " " + parts[2] + " " + parts[3];
            }
            boolean changed = !command.equals(lastBroadcastZoom);
            try {
                // A heartbeat renews the timestamp too; rereading Settings must not renew it.
                if (!android.provider.Settings.Global.putString(h.ctx.getContentResolver(), "mistep_zoom", encoded)) {
                    MiStepHost.log("zoom settings write returned false");
                }
            } catch (Throwable settingsError) {
                MiStepHost.log("zoom settings write failed: " + settingsError);
            }
            String[] parts = steady.split("\\s+");
            float scale = Float.parseFloat(parts[0]);
            float offsetY = Float.parseFloat(parts[1]);
            float offsetX = Float.parseFloat(parts[2]);
            Intent intent = new Intent("com.jiusis.mistep.ZOOM");
            intent.putExtra("request", encoded);
            intent.putExtra("scale", scale);
            intent.putExtra("offsetY", offsetY);
            intent.putExtra("offsetX", offsetX);
            intent.addFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
            h.ctx.sendBroadcast(intent);
            if (changed) MiStepHost.log("zoom broadcast sent: " + command);
            lastBroadcastZoom = command;
        } catch (Throwable error) {
            MiStepHost.log("zoom broadcast failed: " + error);
        }
    }
}
