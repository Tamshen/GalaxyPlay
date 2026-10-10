package com.shilapi.xcertplay.vendor;

import android.app.Instrumentation;
import android.os.Bundle;
import java.lang.reflect.Method;

/** 在真实 Android VM 验证生成子类、参数装箱与各实例回调隔离；不调用厂商服务。 */
public class SdkSubclassInstrumentation extends Instrumentation {
    @Override public void onCreate(Bundle arguments) { super.onCreate(arguments); start(); }
    @Override public void onStart() {
        Bundle result = new Bundle();
        try {
            testGeneratedSubclassCallbacksAndOldInstanceIsolation();
            com.shilapi.xcertplay.GalaxyOemMediaIntegrationChecks.run(getTargetContext());
            result.putString("stream", "SDK_SUBCLASS_OK OEM_MEDIA_CONTRACT_OK L6_L7_SYNTHETIC_SDK"); finish(-1, result);
        }
        catch (Throwable error) { result.putString("stream", "SDK_SUBCLASS_FAILED " + error); finish(0, result); }
    }
    private void assertEquals(Object expected, Object actual) { if (!expected.equals(actual)) throw new AssertionError(expected + " != " + actual); }
    private void assertTrue(boolean value) { if (!value) throw new AssertionError("expected true"); }
    private void assertFalse(boolean value) { if (value) throw new AssertionError("expected false"); }
    private void assertSame(Object expected, Object actual) { if (expected != actual) throw new AssertionError("not same"); }
    public static class Client {
        public Client() {}
        public boolean play() { return false; }
        public long progress() { return 0; }
        public String title(String prefix) { return null; }
        public void pause(int code) {}
    }
    public void testGeneratedSubclassCallbacksAndOldInstanceIsolation() throws Exception {
        Method[] methods = { Client.class.getMethod("play"), Client.class.getMethod("progress"),
            Client.class.getMethod("title", String.class), Client.class.getMethod("pause", int.class) };
        int[] pauses = {0};
        Client first = (Client) SdkSubclass.create(Client.class, methods, (proxy, method, args) -> {
            if (method.getName().equals("play")) return true;
            if (method.getName().equals("progress")) return 1234L;
            if (method.getName().equals("title")) return args[0] + " synthetic";
            pauses[0] = (Integer) args[0]; return null;
        });
        Client second = (Client) SdkSubclass.create(Client.class, methods, (proxy, method, args) -> {
            if (method.getName().equals("play")) return false;
            if (method.getReturnType() == long.class) return 7L;
            return null;
        });
        assertEquals(first.getClass(), second.getClass());
        assertTrue(first.play());
        assertFalse(second.play());
        assertEquals(1234L, first.progress());
        assertEquals("private synthetic", first.title("private"));
        first.pause(42);
        assertEquals(42, pauses[0]);
    }
}
