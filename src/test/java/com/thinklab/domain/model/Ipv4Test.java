package com.thinklab.domain.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Ipv4Test {

    @Test
    @DisplayName("a dotted quad is read as a number and written back the same")
    void roundTrip() {
        assertEquals(0L, Ipv4.parse("0.0.0.0"));
        assertEquals(4294967295L, Ipv4.parse("255.255.255.255"));
        assertEquals(167772161L, Ipv4.parse("10.0.0.1"));
        assertEquals("10.0.0.1", Ipv4.format(167772161L));
        assertEquals("192.168.1.254", Ipv4.format(Ipv4.parse("192.168.1.254")));
    }

    @Test
    @DisplayName("anything but four numbers from 0 to 255 without leading zeros is refused")
    void refused() {
        for (String bad : new String[]{"10.0.0", "10.0.0.0.1", "10.0.0.256", "10.0.0.-1", "010.0.0.1", "a.b.c.d", "10..0.1", "", " 10.0.0.1", "10.0.0.1 "}) {
            assertThrows(IllegalArgumentException.class, () -> Ipv4.parse(bad), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> Ipv4.parse(null));
    }

    @Test
    @DisplayName("it is a utility class")
    void utility() throws ReflectiveOperationException {
        Constructor<Ipv4> constructor = Ipv4.class.getDeclaredConstructor();
        constructor.setAccessible(true);
        assertThrows(InvocationTargetException.class, constructor::newInstance);
    }
}
