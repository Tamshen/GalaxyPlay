package com.shilapi.xcertplay.vendor;

import com.android.dx.Code;
import com.android.dx.DexMaker;
import com.android.dx.FieldId;
import com.android.dx.Local;
import com.android.dx.MethodId;
import com.android.dx.TypeId;
import dalvik.system.InMemoryDexClassLoader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

/** 只生成应用回调子类，不复制厂商实现，不修改隐藏 API 或服务端身份校验。 */
@SuppressWarnings({"rawtypes", "unchecked"})
public final class SdkSubclass {
    private static final java.util.Map<java.util.List<Object>, Class<?>> cache = new java.util.HashMap<>();
    private static final AtomicInteger sequence = new AtomicInteger();
    private SdkSubclass() {}

    public static synchronized Object create(Class<?> base, Method[] methods, InvocationHandler handler) throws Exception {
        if (!Modifier.isPublic(base.getModifiers()) || Modifier.isFinal(base.getModifiers())) {
            throw new IllegalArgumentException("SDK_CLASS_NOT_EXTENSIBLE");
        }
        base.getConstructor();
        java.util.List<Object> key = new java.util.ArrayList<>();
        key.add(base); key.addAll(Arrays.asList(methods));
        Class<?> cached = cache.get(key);
        if (cached != null) {
            Object instance = cached.getConstructor().newInstance();
            cached.getField("dispatch").set(instance, handler);
            return instance;
        }
        String name = "com.shilapi.xcertplay.vendor.generated.Callback" + sequence.incrementAndGet();
        TypeId type = TypeId.get("L" + name.replace('.', '/') + ";");
        TypeId superType = TypeId.get(base);
        DexMaker dex = new DexMaker();
        dex.declare(type, "L7SdkCallback", Modifier.PUBLIC, superType);
        FieldId dispatch = type.getField(TypeId.get(InvocationHandler.class), "dispatch");
        FieldId table = type.getField(TypeId.get(Method[].class), "methods");
        dex.declare(dispatch, Modifier.PUBLIC, null);
        dex.declare(table, Modifier.PUBLIC | Modifier.STATIC, null);
        Code constructor = dex.declare(type.getConstructor(), Modifier.PUBLIC);
        constructor.invokeDirect(superType.getConstructor(), null, constructor.getThis(type));
        constructor.returnVoid();
        for (int i = 0; i < methods.length; i++) {
            Method method = methods[i];
            if (!Modifier.isPublic(method.getModifiers()) || Modifier.isFinal(method.getModifiers())
                    || Modifier.isStatic(method.getModifiers())) throw new IllegalArgumentException("SDK_METHOD_NOT_OVERRIDABLE");
            override(dex, type, dispatch, table, method, i);
        }
        ClassLoader loader = new InMemoryDexClassLoader(ByteBuffer.wrap(dex.generate()), base.getClassLoader());
        Class<?> generated = loader.loadClass(name);
        Object instance = generated.getConstructor().newInstance();
        generated.getField("dispatch").set(instance, handler);
        generated.getField("methods").set(null, Arrays.copyOf(methods, methods.length));
        cache.put(key, generated);
        return instance;
    }

    private static void override(DexMaker dex, TypeId type, FieldId dispatch, FieldId table, Method method, int index) {
        Class<?>[] parameters = method.getParameterTypes();
        TypeId[] types = Arrays.stream(parameters).map(TypeId::get).toArray(TypeId[]::new);
        TypeId returns = TypeId.get(method.getReturnType());
        Code code = dex.declare(type.getMethod(returns, method.getName(), types), Modifier.PUBLIC);
        Local self = code.getThis(type);
        Local handler = code.newLocal(TypeId.get(InvocationHandler.class));
        Local methods = code.newLocal(TypeId.get(Method[].class));
        Local target = code.newLocal(TypeId.get(Method.class));
        Local args = code.newLocal(TypeId.get(Object[].class));
        Local number = code.newLocal(TypeId.INT);
        Local result = code.newLocal(TypeId.OBJECT);
        Local[] values = new Local[parameters.length];
        Local[] boxes = new Local[parameters.length];
        Local[] primitiveBoxes = new Local[parameters.length];
        for (int i = 0; i < parameters.length; i++) {
            values[i] = code.getParameter(i, types[i]);
            boxes[i] = code.newLocal(TypeId.OBJECT);
            if (parameters[i].isPrimitive()) primitiveBoxes[i] = code.newLocal(TypeId.get(wrapper(parameters[i])));
        }
        Local returnValue = method.getReturnType() == void.class ? null : code.newLocal(returns);
        Local returnBox = method.getReturnType().isPrimitive() && method.getReturnType() != void.class
                ? code.newLocal(TypeId.get(wrapper(method.getReturnType()))) : null;
        code.iget(dispatch, handler, self);
        code.sget(table, methods);
        code.loadConstant(number, index);
        code.aget(target, methods, number);
        code.loadConstant(number, parameters.length);
        code.newArray(args, number);
        for (int i = 0; i < parameters.length; i++) {
            Local value = values[i];
            Local boxed = boxes[i];
            if (parameters[i].isPrimitive()) {
                TypeId wrapper = TypeId.get(wrapper(parameters[i]));
                Local primitiveBox = primitiveBoxes[i];
                code.invokeStatic(wrapper.getMethod(wrapper, "valueOf", types[i]), primitiveBox, value);
                code.cast(boxed, primitiveBox);
            } else code.cast(boxed, value);
            code.loadConstant(number, i);
            code.aput(args, number, boxed);
        }
        MethodId invoke = TypeId.get(InvocationHandler.class).getMethod(TypeId.OBJECT, "invoke",
                TypeId.OBJECT, TypeId.get(Method.class), TypeId.get(Object[].class));
        code.invokeInterface(invoke, result, handler, self, target, args);
        if (method.getReturnType() == void.class) {
            code.returnVoid();
        } else if (method.getReturnType().isPrimitive()) {
            TypeId wrapper = TypeId.get(wrapper(method.getReturnType()));
            Local boxed = returnBox;
            Local value = returnValue;
            code.cast(boxed, result);
            code.invokeVirtual(wrapper.getMethod(returns, method.getReturnType().getName() + "Value"), value, boxed);
            code.returnValue(value);
        } else {
            Local value = returnValue;
            code.cast(value, result);
            code.returnValue(value);
        }
    }

    private static Class<?> wrapper(Class<?> primitive) {
        if (primitive == boolean.class) return Boolean.class;
        if (primitive == int.class) return Integer.class;
        if (primitive == long.class) return Long.class;
        if (primitive == float.class) return Float.class;
        if (primitive == double.class) return Double.class;
        if (primitive == short.class) return Short.class;
        if (primitive == byte.class) return Byte.class;
        if (primitive == char.class) return Character.class;
        throw new IllegalArgumentException("SDK_PRIMITIVE_NOT_SUPPORTED");
    }
}
