package dev.cosmos.autoaim.client.compat;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNull;

class TaczCompatTest {
    @Test
    void doesNotResolveAnyTaczClassWhenModIsAbsent() {
        ClassLoader rejectingLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.tacz.")) {
                    throw new AssertionError("TaCZ must not be resolved when absent: " + name);
                }
                return super.loadClass(name, resolve);
            }
        };
        assertNull(TaczCompat.loadApi(false, rejectingLoader));
    }

    @Test
    void incompatibleApiDisablesOnlyOptionalIntegration() {
        ClassLoader missingApiLoader = new ClassLoader(getClass().getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.startsWith("com.tacz.")) {
                    throw new ClassNotFoundException(name);
                }
                return super.loadClass(name, resolve);
            }
        };
        assertNull(TaczCompat.loadApi(true, missingApiLoader));
    }
}
