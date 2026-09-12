package online.yudream.voxelith.runtime.worker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/** worker 内反射原语：按名字/签名探测，供跨版本适配器使用。 */
final class Reflect {

    static Class<?> cls(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, false, cl == null ? ClassLoader.getSystemClassLoader() : cl);
    }

    static Class<?> init(ClassLoader cl, String name) throws ClassNotFoundException {
        return Class.forName(name, true, cl == null ? ClassLoader.getSystemClassLoader() : cl);
    }

    static boolean present(ClassLoader cl, String name) {
        try {
            cls(cl, name);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    static Method named(Class<?> type, String name) {
        Method found = null;
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name)) {
                if (found != null && found.getParameterCount() != method.getParameterCount()) {
                    // 同名重载留给 named(type, name, paramCount)
                    continue;
                }
                found = method;
            }
        }
        if (found != null) {
            found.setAccessible(true);
            return found;
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException(type.getName() + " 找不到方法 " + name);
    }

    static Method named(Class<?> type, String name, int paramCount) {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                method.setAccessible(true);
                return method;
            }
        }
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name) && method.getParameterCount() == paramCount) {
                method.setAccessible(true);
                return method;
            }
        }
        throw new IllegalStateException(type.getName() + " 找不到方法 " + name + "/" + paramCount);
    }

    static Method declared(Class<?> type, String name, Class<?>... params) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, params);
        method.setAccessible(true);
        return method;
    }

    /** 先公开构造，再声明构造（含包可见），供跨版本 ZipResourcePack 等探测。 */
    static Constructor<?> ctor(Class<?> type, Class<?>... params) throws NoSuchMethodException {
        try {
            Constructor<?> constructor = type.getConstructor(params);
            constructor.setAccessible(true);
            return constructor;
        } catch (NoSuchMethodException e) {
            Constructor<?> constructor = type.getDeclaredConstructor(params);
            constructor.setAccessible(true);
            return constructor;
        }
    }

    static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getField(name);
        field.setAccessible(true);
        return field;
    }

    static Field declaredField(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    static Object construct(Class<?> type, Object... args) throws Exception {
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            types[i] = args[i].getClass();
        }
        Constructor<?> best = null;
        for (Constructor<?> ctor : type.getConstructors()) {
            if (compatible(ctor.getParameterTypes(), args)) {
                best = ctor;
                break;
            }
        }
        if (best == null) {
            for (Constructor<?> ctor : type.getDeclaredConstructors()) {
                if (compatible(ctor.getParameterTypes(), args)) {
                    ctor.setAccessible(true);
                    best = ctor;
                    break;
                }
            }
        }
        if (best == null) {
            throw new NoSuchMethodException(type.getName() + " 无匹配构造 " + java.util.Arrays.toString(types));
        }
        return best.newInstance(args);
    }

    static boolean compatible(Class<?>[] params, Object[] args) {
        if (params.length != args.length) {
            return false;
        }
        for (int i = 0; i < params.length; i++) {
            if (args[i] == null) {
                if (params[i].isPrimitive()) {
                    return false;
                }
                continue;
            }
            Class<?> arg = args[i].getClass();
            if (params[i].isPrimitive()) {
                if (params[i] == boolean.class && arg != Boolean.class) {
                    return false;
                }
                if (params[i] == int.class && arg != Integer.class) {
                    return false;
                }
                continue;
            }
            if (!params[i].isAssignableFrom(arg)) {
                return false;
            }
        }
        return true;
    }

    private Reflect() {
    }
}
