package stirling.software.SPDF.model.api;

import org.springframework.web.multipart.MultipartFile;

import lombok.Data;
import lombok.EqualsAndHashCode;

import stirling.software.common.model.api.PDFFile;

@Data
@EqualsAndHashCode(callSuper = true)
public class PdfLunnaPersonalizeRequest extends PDFFile {
    private String json;
    private MultipartFile stampImage;
    private MultipartFile watermarkImage;
}
