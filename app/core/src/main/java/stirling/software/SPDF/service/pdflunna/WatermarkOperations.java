package stirling.software.SPDF.service.pdflunna;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.multipart.MultipartFile;

import stirling.software.SPDF.model.api.security.AddWatermarkRequest;
import stirling.software.common.util.OcgUtils;
import stirling.software.common.util.RegexPatternUtils;

@lombok.experimental.UtilityClass
public class WatermarkOperations {
    private static final long MAX_MARKS_PER_PAGE = 10_000;

    public void apply(PDDocument document, AddWatermarkRequest request) throws IOException {
        MultipartFile pdfFile = request.getFileInput();
        String pdfFileName = pdfFile.getOriginalFilename();
        if (pdfFileName != null && (pdfFileName.contains("..") || pdfFileName.startsWith("/"))) {
            throw new SecurityException("Invalid file path in pdfFile");
        }
        String watermarkType = request.getWatermarkType();
        String watermarkText = request.getWatermarkText();
        MultipartFile watermarkImage = request.getWatermarkImage();
        if (watermarkImage != null) {
            String watermarkImageFileName = watermarkImage.getOriginalFilename();
            if (watermarkImageFileName != null
                    && (watermarkImageFileName.contains("..")
                            || watermarkImageFileName.startsWith("/"))) {
                throw new SecurityException("Invalid file path in watermarkImage");
            }
        }
        String alphabet = request.getAlphabet();
        float fontSize = request.getFontSize();
        float rotation = request.getRotation();
        float opacity = request.getOpacity();
        int widthSpacer = request.getWidthSpacer();
        int heightSpacer = request.getHeightSpacer();
        String customColor = request.getCustomColor();
        boolean hideOnPrint = Boolean.TRUE.equals(request.getHideOnPrint());
        PDFont font = "text".equalsIgnoreCase(watermarkType) ? loadFont(document, alphabet) : null;
        PDImageXObject xobject =
                "image".equalsIgnoreCase(watermarkType)
                        ? DocumentImageResources.loadImage(document, watermarkImage)
                        : null;
        // One OCG for the whole document keeps the layers panel tidy and lets the
        // watermark stay in the page content stream — URL auto-detection, text
        // selection and search keep working, and pre-existing link annotations are
        // not blocked (unlike a full-page rubber-stamp annotation overlay).
        PDOptionalContentGroup hideOnPrintOcg =
                hideOnPrint ? OcgUtils.createHideOnPrintOcg(document, "Watermark") : null;

        // Create a page in the document
        for (PDPage page : document.getPages()) {
            // Get the page's content stream
            try (PDPageContentStream contentStream =
                    new PDPageContentStream(
                            document, page, PDPageContentStream.AppendMode.APPEND, true, true)) {

                if (hideOnPrintOcg != null) {
                    contentStream.beginMarkedContent(COSName.OC, hideOnPrintOcg);
                }

                // Set transparency
                PDExtendedGraphicsState graphicsState = new PDExtendedGraphicsState();
                graphicsState.setNonStrokingAlphaConstant(opacity);
                contentStream.setGraphicsStateParameters(graphicsState);

                if ("text".equalsIgnoreCase(watermarkType)) {
                    addTextWatermark(
                            contentStream,
                            watermarkText,
                            document,
                            page,
                            rotation,
                            widthSpacer,
                            heightSpacer,
                            fontSize,
                            font,
                            customColor);
                } else if ("image".equalsIgnoreCase(watermarkType)) {
                    addImageWatermark(
                            contentStream,
                            xobject,
                            document,
                            page,
                            rotation,
                            widthSpacer,
                            heightSpacer,
                            fontSize);
                }

                if (hideOnPrintOcg != null) {
                    contentStream.endMarkedContent();
                }
            }
        }
    }

    private PDFont loadFont(PDDocument document, String alphabet) throws IOException {
        String resourceDir =
                switch (alphabet) {
                    case "arabic" -> "static/fonts/NotoSansArabic-Regular.ttf";
                    case "japanese" -> "static/fonts/NotoSansJP-Regular.ttf";
                    case "korean" -> "static/fonts/NotoSansKR-Regular.ttf";
                    case "chinese" -> "static/fonts/NotoSansSC-Regular.ttf";
                    case "thai" -> "static/fonts/NotoSansThai-Regular.ttf";
                    default -> "static/fonts/NotoSans-Regular.ttf";
                };

        try (InputStream input = new ClassPathResource(resourceDir).getInputStream()) {
            return PDType0Font.load(document, input);
        }
    }

    private void addTextWatermark(
            PDPageContentStream contentStream,
            String watermarkText,
            PDDocument document,
            PDPage page,
            float rotation,
            int widthSpacer,
            int heightSpacer,
            float fontSize,
            PDFont font,
            String colorString)
            throws IOException {
        contentStream.setFont(font, fontSize);

        Color redactColor;
        try {
            if (!colorString.startsWith("#")) {
                colorString = "#" + colorString;
            }
            redactColor = Color.decode(colorString);
        } catch (NumberFormatException e) {

            redactColor = Color.LIGHT_GRAY;
        }
        contentStream.setNonStrokingColor(redactColor);

        String[] textLines =
                RegexPatternUtils.getInstance().getEscapedNewlinePattern().split(watermarkText);
        float maxLineWidth = 0;

        for (int i = 0; i < textLines.length; ++i) {
            maxLineWidth = Math.max(maxLineWidth, font.getStringWidth(textLines[i]));
        }

        // Set size and location of text watermark
        float watermarkWidth = widthSpacer + maxLineWidth * fontSize / 1000;
        float watermarkHeight = heightSpacer + fontSize * textLines.length;
        float pageWidth = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();

        // Calculating the new width and height depending on the angle.
        float radians = (float) Math.toRadians(rotation);
        float newWatermarkWidth =
                (float)
                        (Math.abs(watermarkWidth * Math.cos(radians))
                                + Math.abs(watermarkHeight * Math.sin(radians)));
        float newWatermarkHeight =
                (float)
                        (Math.abs(watermarkWidth * Math.sin(radians))
                                + Math.abs(watermarkHeight * Math.cos(radians)));

        // Calculating the number of rows and columns.

        int watermarkRows = Math.min((int) (pageHeight / newWatermarkHeight + 1), 10_000);
        int watermarkCols = Math.min((int) (pageWidth / newWatermarkWidth + 1), 10_000);

        requireReasonableDensity((long) (watermarkRows + 1) * (watermarkCols + 1));

        // Add the text watermark
        for (int i = 0; i <= watermarkRows; i++) {
            for (int j = 0; j <= watermarkCols; j++) {
                contentStream.beginText();
                contentStream.setTextMatrix(
                        Matrix.getRotateInstance(
                                (float) Math.toRadians(rotation),
                                j * newWatermarkWidth,
                                i * newWatermarkHeight));

                for (int k = 0; k < textLines.length; ++k) {
                    contentStream.showText(textLines[k]);
                    contentStream.newLineAtOffset(0, -fontSize);
                }

                contentStream.endText();
            }
        }
    }

    private void addImageWatermark(
            PDPageContentStream contentStream,
            PDImageXObject xobject,
            PDDocument document,
            PDPage page,
            float rotation,
            int widthSpacer,
            int heightSpacer,
            float fontSize)
            throws IOException {

        float aspectRatio = (float) xobject.getWidth() / (float) xobject.getHeight();
        float desiredPhysicalHeight = fontSize;
        float desiredPhysicalWidth = desiredPhysicalHeight * aspectRatio;

        // Calculate the number of rows and columns for watermarks
        float pageWidth = page.getMediaBox().getWidth();
        float pageHeight = page.getMediaBox().getHeight();
        int watermarkRows =
                Math.min(
                        (int)
                                ((pageHeight + heightSpacer)
                                        / (desiredPhysicalHeight + heightSpacer)),
                        10_000);
        int watermarkCols =
                Math.min(
                        (int) ((pageWidth + widthSpacer) / (desiredPhysicalWidth + widthSpacer)),
                        10_000);

        requireReasonableDensity((long) watermarkRows * watermarkCols);

        for (int i = 0; i < watermarkRows; i++) {
            for (int j = 0; j < watermarkCols; j++) {
                float x = j * (desiredPhysicalWidth + widthSpacer);
                float y = i * (desiredPhysicalHeight + heightSpacer);

                // Save the graphics state
                contentStream.saveGraphicsState();

                // Create rotation matrix and rotate
                contentStream.transform(
                        Matrix.getTranslateInstance(
                                x + desiredPhysicalWidth / 2, y + desiredPhysicalHeight / 2));
                contentStream.transform(Matrix.getRotateInstance(Math.toRadians(rotation), 0, 0));
                contentStream.transform(
                        Matrix.getTranslateInstance(
                                -desiredPhysicalWidth / 2, -desiredPhysicalHeight / 2));

                // Draw the image and restore the graphics state
                contentStream.drawImage(xobject, 0, 0, desiredPhysicalWidth, desiredPhysicalHeight);
                contentStream.restoreGraphicsState();
            }
        }
    }

    private void requireReasonableDensity(long marks) {
        if (marks > MAX_MARKS_PER_PAGE) {
            throw new IllegalArgumentException(
                    "Watermark density exceeds 10000 marks per page; increase the font size or spacing");
        }
    }
}
