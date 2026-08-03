package com.nuclyon.technicallycoded.inventoryrollback.util.serialization;

import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Version 1 is the format of every backup written before the IRP_VERSION prefix existed, and it is
 * still the branch ItemStackSerialization falls to when no prefix is found, so these payloads are
 * read long after nothing writes them.
 * <p>
 * Its base64 used to come from snakeyaml's bundled Base64Coder, which the server supplied.
 * SnakeYAML deleted that class between 2.2 and 2.6 and 26.2 ships 2.6, so the codec now lives in
 * Version1Serialization itself. The vectors below were produced by the real Base64Coder from
 * snakeyaml 2.2: they are what is actually sitting in old backup files, and they are here so that a
 * later tidy-up of the codec cannot quietly make those files unreadable.
 * <p>
 * Reached by reflection because the codec is private, matching Version2SerializationIntTest.
 */
public class Version1Base64Test {

    /** The exact 200 bytes the golden text below encodes. */
    private static byte[] payload() {
        byte[] payload = new byte[200];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) ((i * 37 + 11) & 0xFF);
        }
        return payload;
    }

    /** Emitted by snakeyaml 2.2's Base64Coder.encodeLines for payload(), one entry per line. */
    private static final String[] GOLDEN_LINES = {
            "CzBVep/E6Q4zWH2ix+wRNluApcrvFDleg6jN8hc8YYar0PUaP2SJrtP4HUJnjLHW+yBFao+02f4j",
            "SG2St9wBJktwlbrfBClOc5i94gcsUXabwOUKL1R5nsPoDTJXfKHG6xA1Wn+kye4TOF2Cp8zxFjtg",
            "harP9Bk+Y4it0vccQWaLsNX6H0RpjrPY/SJHbJG22wAlSm+Uud4DKE1yl7zhBitQdZq/5AkuU3id",
            "wucMMVZ7oMXqDzRZfqPI7RI3XIGmy/AVOl+Eqc4="
    };

    private static String encodeLines(byte[] in) throws Exception {
        Method m = Version1Serialization.class.getDeclaredMethod("encodeLines", byte[].class);
        m.setAccessible(true);
        return (String) m.invoke(null, (Object) in);
    }

    private static byte[] decodeLines(String s) throws Exception {
        Method m = Version1Serialization.class.getDeclaredMethod("decodeLines", String.class);
        m.setAccessible(true);
        try {
            return (byte[]) m.invoke(null, s);
        } catch (InvocationTargetException e) {
            // Unwrap so callers can assert on the exception the production code actually raises.
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw e;
        }
    }

    @Test
    public void readsBackupsWrittenByTheOldSnakeyamlCoder() throws Exception {
        // Old backups carry whatever line ending the server that wrote them used, and a Windows
        // host and a Linux host wrote the same format differently. Both have to read back.
        for (String sep : new String[]{"\r\n", "\n"}) {
            StringBuilder sb = new StringBuilder();
            for (String line : GOLDEN_LINES) sb.append(line).append(sep);
            assertArrayEquals(payload(), decodeLines(sb.toString()),
                    "Golden v1 payload joined with " + sep.length() + "-char separator should decode unchanged");
        }
    }

    @Test
    public void writesWhatTheOldCoderWrote() throws Exception {
        String encoded = encodeLines(payload());

        StringBuilder joined = new StringBuilder();
        int lines = 0;
        for (String line : encoded.split("\\R")) {
            assertTrue(line.length() <= 76, "Base64Coder wrapped at 76 characters, got " + line.length());
            joined.append(line);
            lines++;
        }

        assertEquals(GOLDEN_LINES.length, lines, "Wrapping should produce the same number of lines");
        assertEquals(String.join("", GOLDEN_LINES), joined.toString(),
                "Encoded characters should match snakeyaml 2.2 exactly");
        assertTrue(encoded.endsWith(System.lineSeparator()),
                "Base64Coder terminated every line including the last");
    }

    @Test
    public void roundTripsThroughItsOwnOutput() throws Exception {
        // Lengths either side of a 3-byte group boundary, so both padding cases are covered.
        for (int len : new int[]{0, 1, 2, 3, 56, 57, 58, 200, 599}) {
            byte[] data = new byte[len];
            for (int i = 0; i < len; i++) data[i] = (byte) ((i * 31 + 7) & 0xFF);
            assertArrayEquals(data, decodeLines(encodeLines(data)), "Round trip should be lossless at length " + len);
        }
    }

    @Test
    public void rejectsRatherThanGuessingAtDamagedData() {
        // stacksFromBase64 catches IllegalArgumentException and reports an unreadable backup.
        // java.util.Base64 on its own would accept "abc" as unpadded and hand back plausible
        // bytes, which is how a truncated backup turns into a silently wrong restore.
        assertThrows(IllegalArgumentException.class, () -> decodeLines("abc"));
        assertThrows(IllegalArgumentException.class, () -> decodeLines("####"));
        assertThrows(IllegalArgumentException.class, () -> decodeLines("!!!not base64!!!"));
    }

    @Test
    public void agreesWithPlainBase64OnceTheWrappingIsRemoved() throws Exception {
        byte[] data = payload();
        String unwrapped = String.join("", encodeLines(data).split("\\R"));
        assertEquals(Base64.getEncoder().encodeToString(data), unwrapped,
                "Wrapping is the only difference from standard base64");
    }
}
