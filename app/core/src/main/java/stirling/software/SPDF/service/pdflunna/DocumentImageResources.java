package stirling.software.SPDF.service.pdflunna;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;

import javax.imageio.ImageIO;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.web.multipart.MultipartFile;

import lombok.experimental.UtilityClass;

@UtilityClass
class DocumentImageResources {
    PDImageXObject loadImage(PDDocument document, MultipartFile upload) throws IOException {
        if (upload == null || upload.isEmpty()) {
            throw new IllegalArgumentException("An image file is required");
        }
        BufferedImage image;
        try (InputStream input = upload.getInputStream()) {
            image = ImageIO.read(input);
        }
        if (image == null) {
            throw new IllegalArgumentException("The uploaded file is not a supported image");
        }
        try {
            return LosslessFactory.createFromImage(document, image);
        } finally {
            image.flush();
        }
    }
}
