package io.github.mio.autopickupisland;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** Exact, cached, hierarchy-aware reflection. Missing/ambiguous symbols fail closed. */
final class SafeReflection {
    private static final ClassValue<ConcurrentHashMap<String, Optional<Method>>> CACHE = new ClassValue<>() {
        @Override protected ConcurrentHashMap<String, Optional<Method>> computeValue(Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };
    private SafeReflection() { }
    static Method method(Class<?> type, String name, Class<?> result, Class<?>... parameters) {
        if (type == null || name == null || result == null || parameters == null) return null;
        String key = name + Arrays.toString(parameters) + result.getName();
        try {
            return CACHE.get(type).computeIfAbsent(key, ignored -> {
                for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                    try {
                        Method method = current.getDeclaredMethod(name, parameters);
                        if (method.getReturnType() != result || Modifier.isAbstract(method.getModifiers())) return Optional.empty();
                        method.setAccessible(true);
                        return Optional.of(method);
                    } catch (NoSuchMethodException absent) { /* Try the superclass. */ }
                    catch (Throwable failure) { return Optional.empty(); }
                }
                return Optional.empty();
            }).orElse(null);
        } catch (Throwable ignored) { return null; }
    }
    static Object noArg(Object owner, String name) {
        if (owner == null || name == null) return null;
        try {
            Method cached = CACHE.get(owner.getClass()).computeIfAbsent("getter:" + name, ignored -> {
                for (Class<?> current = owner.getClass(); current != null; current = current.getSuperclass()) {
                    try {
                        Method method = current.getDeclaredMethod(name);
                        if (Modifier.isStatic(method.getModifiers()) || Modifier.isAbstract(method.getModifiers())) return Optional.empty();
                        method.setAccessible(true);
                        return Optional.of(method);
                    } catch (NoSuchMethodException absent) { }
                    catch (Throwable failure) { return Optional.empty(); }
                }
                return Optional.empty();
            }).orElse(null);
            return cached == null ? null : cached.invoke(owner);
        } catch (Throwable ignored) { }
        return null;
    }
}
