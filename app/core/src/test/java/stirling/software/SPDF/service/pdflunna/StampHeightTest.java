package stirling.software.SPDF.service.pdflunna;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import stirling.software.SPDF.model.api.misc.AddStampRequest;

class StampHeightTest {
    @Test
    void omittedCoordinatesKeepAutomaticAlignment() {
        AddStampRequest request = new AddStampRequest();
        assertEquals(-1, request.getOverrideX());
        assertEquals(-1, request.getOverrideY());
        request.setOverrideY(16);
        assertEquals(-1, request.getOverrideX());
    }

    private AddStampRequest request(String text, float x, float y) {
        AddStampRequest request = new AddStampRequest();
        request.setFileInput(
                new MockMultipartFile("fileInput", "test.pdf", "application/pdf", new byte[0]));
        request.setStampType("text");
        request.setStampText(text);
        request.setFontSize(10);
        request.setOpacity(1);
        request.setPosition(8);
        request.setOverrideX(x);
        request.setOverrideY(y);
        request.setCustomMargin("small");
        request.setCustomColor("#ff0000");
        request.setPageNumbers("all");
        return request;
    }

    private List<TextPosition> textPositions(String text, float x, float y, PDRectangle size)
            throws Exception {
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(size));
            StampOperations.apply(document, request(text, x, y));
            List<TextPosition> positions = new ArrayList<>();
            PDFTextStripper stripper =
                    new PDFTextStripper() {
                        @Override
                        protected void processTextPosition(TextPosition position) {
                            positions.add(position);
                            super.processTextPosition(position);
                        }
                    };
            stripper.getText(document);
            return positions;
        }
    }

    @Test
    void manualHeightPreservesCenterAndLegacyCoordinates() throws Exception {
        for (PDRectangle size : List.of(PDRectangle.A4, new PDRectangle(842, 595))) {
            for (String text : List.of("Buyer", "A much longer buyer name", "Buyer\nSecond line")) {
                var auto = textPositions(text, -1, -1, size);
                var manual = textPositions(text, -1, 16, size);
                var legacy = textPositions(text, 20, 16, size);
                var onlyX = textPositions(text, 20, -1, size);
                assertEquals(auto.getFirst().getX(), manual.getFirst().getX(), 0.01);
                assertEquals(16, manual.getFirst().getTextMatrix().getTranslateY(), 0.01);
                assertEquals(20, legacy.getFirst().getX(), 0.01);
                assertEquals(auto.getFirst().getX(), onlyX.getFirst().getX(), 0.01);
                assertEquals(auto.getFirst().getY(), onlyX.getFirst().getY(), 0.01);
                assertEquals(legacy.size(), manual.size());
                for (int i = 0; i < manual.size(); i++) {
                    assertEquals(legacy.get(i).getY(), manual.get(i).getY(), 0.01);
                }
            }
        }
    }

    @Test
    void imageHeightPreservesHorizontalCenter() throws Exception {
        BufferedImage stamp = new BufferedImage(20, 10, BufferedImage.TYPE_INT_RGB);
        var graphics = stamp.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 20, 10);
        graphics.dispose();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(stamp, "png", bytes);
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage(new PDRectangle(200, 300)));
            AddStampRequest request = request("", -1, 16);
            request.setStampType("image");
            request.setStampImage(
                    new MockMultipartFile(
                            "stampImage", "stamp.png", "image/png", bytes.toByteArray()));
            StampOperations.apply(document, request);
            BufferedImage rendered = new PDFRenderer(document).renderImage(0);
            assertEquals(Color.RED.getRGB(), rendered.getRGB(100, 280));
            assertEquals(Color.WHITE.getRGB(), rendered.getRGB(85, 280));
        }
    }
}
