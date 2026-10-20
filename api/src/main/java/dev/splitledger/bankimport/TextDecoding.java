package dev.splitledger.bankimport;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/**
 * German bank exports are usually Windows-1252 (a superset of ISO-8859-1 that also has the euro
 * sign), but some tools re-save them as UTF-8. Strict UTF-8 is tried first: a Windows-1252 file
 * with umlauts is practically never valid UTF-8 (an "ä" is the single byte 0xE4), so this check
 * does not mistake one for the other. Plain ASCII decodes the same either way.
 */
final class TextDecoding {

    static final Charset WINDOWS_1252 = Charset.forName("windows-1252");
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private TextDecoding() {
    }

    static String decode(byte[] content) {
        byte[] bytes = content;
        if (bytes.length >= 3 && Arrays.equals(Arrays.copyOf(bytes, 3), UTF8_BOM)) {
            bytes = Arrays.copyOfRange(bytes, 3, bytes.length);
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException notUtf8) {
            return new String(bytes, WINDOWS_1252);
        }
    }
}
