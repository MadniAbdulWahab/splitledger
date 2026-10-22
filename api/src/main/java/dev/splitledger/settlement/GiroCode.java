package dev.splitledger.settlement;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import dev.splitledger.common.BusinessRuleException;
import dev.splitledger.common.Iban;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * GiroCode: the European Payments Council's QR code for SEPA credit transfers (EPC069-12, version
 * 002), which German banking apps scan to fill in a transfer. The payload is plain text, one field
 * per line:
 *
 * <pre>
 * BCD                 service tag
 * 002                 version (BIC optional inside the EEA)
 * 1                   character set: UTF-8
 * SCT                 SEPA credit transfer
 * COBADEFFXXX         BIC, may be empty
 * Anna Beispiel       beneficiary name, max 70 characters
 * DE89370400440532013000
 * EUR42.00            amount: EUR, dot, at most two decimals, 0.01 to 999999999.99
 *                     purpose code (empty)
 *                     structured reference (empty, because the text below is used)
 * SplitLedger ...     remittance text, max 140 characters
 * </pre>
 *
 * The whole payload may have at most 331 bytes; error correction level M, as the guideline asks.
 */
final class GiroCode {

    static final int MAX_PAYLOAD_BYTES = 331;
    static final int MAX_NAME_LENGTH = 70;
    static final int MAX_TEXT_LENGTH = 140;
    static final long MAX_AMOUNT_CENTS = 99_999_999_999L;
    private static final Pattern BIC = Pattern.compile("[A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?");
    private static final int PIXELS_PER_MODULE = 8;

    private GiroCode() {
    }

    static String payload(String beneficiary, String iban, String bic, long amountCents, String remittance) {
        if (iban == null || !Iban.isValid(iban)) {
            throw new BusinessRuleException("A GiroCode needs a valid IBAN of the receiver");
        }
        if (bic != null && !bic.isBlank() && !BIC.matcher(bic).matches()) {
            throw new BusinessRuleException("Invalid BIC: " + bic);
        }
        if (amountCents < 1 || amountCents > MAX_AMOUNT_CENTS) {
            throw new BusinessRuleException("A GiroCode amount must be between 0.01 and 999999999.99 EUR");
        }
        String name = truncate(singleLine(beneficiary), MAX_NAME_LENGTH);
        if (name.isEmpty()) {
            throw new BusinessRuleException("A GiroCode needs the receiver's name");
        }
        String text = truncate(singleLine(remittance), MAX_TEXT_LENGTH);

        String payload = build(name, Iban.normalize(iban), bic, amountCents, text);
        // Umlauts take two bytes in UTF-8: if the limit is still exceeded, shorten the free text.
        while (utf8Length(payload) > MAX_PAYLOAD_BYTES && !text.isEmpty()) {
            text = text.substring(0, text.length() - 1).strip();
            payload = build(name, Iban.normalize(iban), bic, amountCents, text);
        }
        return payload;
    }

    /** The QR code as PNG, with the four-module quiet zone scanners need around it. */
    static byte[] png(String payload) {
        try {
            BitMatrix matrix = new QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 0, 0, Map.of(
                    EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M,
                    EncodeHintType.CHARACTER_SET, StandardCharsets.UTF_8.name(),
                    EncodeHintType.MARGIN, 4));
            int size = matrix.getWidth() * PIXELS_PER_MODULE;
            BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_BYTE_BINARY);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    boolean dark = matrix.get(x / PIXELS_PER_MODULE, y / PIXELS_PER_MODULE);
                    image.setRGB(x, y, dark ? 0x000000 : 0xFFFFFF);
                }
            }
            ByteArrayOutputStream png = new ByteArrayOutputStream();
            ImageIO.write(image, "png", png);
            return png.toByteArray();
        } catch (WriterException e) {
            throw new IllegalStateException("Payload cannot be encoded as QR code", e);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String amount(long cents) {
        return "EUR%d.%02d".formatted(cents / 100, cents % 100);
    }

    private static String build(String name, String iban, String bic, long amountCents, String text) {
        return String.join("\n",
                "BCD", "002", "1", "SCT",
                bic == null ? "" : bic.strip(),
                name, iban, amount(amountCents),
                "", "", text);
    }

    private static String singleLine(String text) {
        return text == null ? "" : text.replaceAll("\\s+", " ").strip();
    }

    private static String truncate(String text, int maxLength) {
        return text.length() <= maxLength ? text : text.substring(0, maxLength).strip();
    }

    private static int utf8Length(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
