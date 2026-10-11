package com.deadman;

import java.lang.reflect.Proxy;
import java.util.Map;

/** Strict interface fixtures: unexpected SDK calls fail rather than silently return defaults. */
public final class RuneLiteStubs
{
    private RuneLiteStubs() {}

    public static <T> T api(Class<T> type, Map<String, Object> values)
    {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) ->
        {
            if (method.getDeclaringClass() == Object.class)
            {
                switch (method.getName())
                {
                    case "toString": return type.getSimpleName() + " fixture";
                    case "hashCode": return System.identityHashCode(proxy);
                    case "equals": return proxy == args[0];
                    default: throw new AssertionError(method);
                }
            }
            if (!values.containsKey(method.getName()))
            {
                throw new AssertionError("Unexpected SDK call: " + type.getSimpleName() + "." + method.getName());
            }
            return values.get(method.getName());
        }));
    }
}
