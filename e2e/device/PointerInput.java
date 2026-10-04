package l7.e2e;

import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;

/** 由 AVD 的 shell 注入真实悬停/按压事件，测试代码不进入应用 APK。 */
public final class PointerInput {
    public static void main(String[] args) throws Exception {
        int action;
        boolean mouse = true;
        switch (args[0]) {
            case "hover": action = MotionEvent.ACTION_HOVER_MOVE; break;
            case "exit": action = MotionEvent.ACTION_HOVER_EXIT; break;
            case "down": action = MotionEvent.ACTION_DOWN; mouse = false; break;
            case "cancel": action = MotionEvent.ACTION_CANCEL; mouse = false; break;
            default: throw new IllegalArgumentException("未知输入动作");
        }
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, Float.parseFloat(args[1]), Float.parseFloat(args[2]), 0);
        event.setSource(mouse ? InputDevice.SOURCE_MOUSE : InputDevice.SOURCE_TOUCHSCREEN);
        Class<?> type = Class.forName("android.hardware.input.InputManager");
        Object manager = type.getMethod("getInstance").invoke(null);
        try {
            boolean accepted = (Boolean) type.getMethod("injectInputEvent", InputEvent.class, int.class).invoke(manager, event, 2);
            if (!accepted) throw new IllegalStateException("输入事件被拒绝");
        } finally {
            event.recycle();
        }
    }
}
