package dev.splitledger.settlement;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;
import dev.splitledger.common.BusinessRuleException;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GiroCodeTest {

    static final String IBAN = "DE89370400440532013000";

    @Test
    void buildsTheEpcPayloadLineByLine() {
        String payload = GiroCode.payload("Anna Beispiel", "de89 3704 0044 0532 0130 00", null, 4200,
                "SplitLedger settle-up WG Oct 2026");

        assertThat(payload).isEqualTo(String.join("\n",
                "BCD", "002", "1", "SCT", "", "Anna Beispiel", IBAN, "EUR42.00", "", "",
                "SplitLedger settle-up WG Oct 2026"));
    }

    @Test
    void formatsAmountsWithADotAndTwoDecimals() {
        assertThat(GiroCode.amount(1)).isEqualTo("EUR0.01");
        assertThat(GiroCode.amount(2078)).isEqualTo("EUR20.78");
        assertThat(GiroCode.amount(124000)).isEqualTo("EUR1240.00");
        assertThat(GiroCode.amount(GiroCode.MAX_AMOUNT_CENTS)).isEqualTo("EUR999999999.99");
    }

    @Test
    void keepsEveryFieldWithinItsLimit() {
        String longName = "Anna ".repeat(30);
        String umlautText = "Überweisung für die Wohngemeinschaft äöü ".repeat(10);

        String payload = GiroCode.payload(longName, IBAN, "COBADEFFXXX", 1000, umlautText);
        String[] lines = payload.split("\n", -1);

        assertThat(lines).hasSize(11);
        assertThat(lines[4]).isEqualTo("COBADEFFXXX");
        assertThat(lines[5]).hasSizeLessThanOrEqualTo(GiroCode.MAX_NAME_LENGTH);
        assertThat(lines[10]).hasSizeLessThanOrEqualTo(GiroCode.MAX_TEXT_LENGTH).startsWith("Überweisung");
        assertThat(payload.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(GiroCode.MAX_PAYLOAD_BYTES);
    }

    @Test
    void removesLineBreaksThatWouldShiftTheFields() {
        String payload = GiroCode.payload("Anna\nBeispiel", IBAN, "", 100, "line one\r\nline two");

        assertThat(payload.split("\n", -1)).hasSize(11).contains("Anna Beispiel", "line one line two");
    }

    @Test
    void rejectsWhatABankWouldReject() {
        assertThatThrownBy(() -> GiroCode.payload("Anna", "DE88370400440532013000", null, 100, "x"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("IBAN");
        assertThatThrownBy(() -> GiroCode.payload("Anna", IBAN, "not a bic", 100, "x"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("BIC");
        assertThatThrownBy(() -> GiroCode.payload("Anna", IBAN, null, 0, "x"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> GiroCode.payload("Anna", IBAN, null, GiroCode.MAX_AMOUNT_CENTS + 1, "x"))
                .isInstanceOf(BusinessRuleException.class);
        assertThatThrownBy(() -> GiroCode.payload("  ", IBAN, null, 100, "x"))
                .isInstanceOf(BusinessRuleException.class).hasMessageContaining("name");
    }

    @Test
    void thePngDecodesBackToTheExactPayload() throws Exception {
        String payload = GiroCode.payload("Jörg Müller", IBAN, null, 2078, "SplitLedger settle-up Küche Oct 2026");

        byte[] png = GiroCode.png(payload);

        assertThat(png).startsWith(0x89, 'P', 'N', 'G');
        assertThat(decode(png)).isEqualTo(payload);
    }

    /** Reads the QR code like a scanner would, with ZXing's decoder. */
    static String decode(byte[] png) throws Exception {
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(png));
        int[] pixels = image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        RGBLuminanceSource source = new RGBLuminanceSource(image.getWidth(), image.getHeight(), pixels);
        return new QRCodeReader().decode(new BinaryBitmap(new HybridBinarizer(source))).getText();
    }
}
