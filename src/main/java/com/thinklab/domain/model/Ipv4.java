package com.thinklab.domain.model;

import java.util.regex.Pattern;

/**
 * IPv4 addresses as numbers (0 to 2^32 - 1), so that ranges can be compared and the next free address counted. Only the dotted-quad form is
 * read: four numbers from 0 to 255, no leading zeros (an address like {@code 010.0.0.1} means octal to some tools and decimal to others).
 */
public final class Ipv4 {

    private static final Pattern OCTET = Pattern.compile("0|[1-9][0-9]{0,2}");

    private Ipv4() {
        throw new UnsupportedOperationException("This is a utility class and cannot be instantiated");
    }

    /** The address as a number; anything else than a dotted quad is refused. */
    public static long parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("An IPv4 address is mandatory.");
        }
        String[] parts = text.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("An IPv4 address is four numbers from 0 to 255 separated by dots.");
        }
        long value = 0;
        for (String part : parts) {
            if (!OCTET.matcher(part).matches() || Integer.parseInt(part) > 255) {
                throw new IllegalArgumentException("An IPv4 address is four numbers from 0 to 255 separated by dots.");
            }
            value = (value << 8) | Integer.parseInt(part);
        }
        return value;
    }

    public static String format(long value) {
        return ((value >> 24) & 255) + "." + ((value >> 16) & 255) + "." + ((value >> 8) & 255) + "." + (value & 255);
    }
}
