package stirling.software.SPDF.model.api;

import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.media.Schema;

import lombok.Data;
import lombok.EqualsAndHashCode;

import stirling.software.common.model.api.PDFFile;

@Data
@EqualsAndHashCode(callSuper = true)
public class PdfLunnaPersonalizeRequest extends PDFFile {
    private String json;
    private MultipartFile stampImage;
    private MultipartFile watermarkImage;

    @Schema(
            description =
                    "Optional page selection for every stamp and watermark in the pipeline."
                            + " When omitted, legacy per-operation page selection is preserved.",
            allowableValues = {"all", "first", "last", "custom", "alternate"})
    private String watermarkPages;

    @Schema(
            description = "Whether custom pages are included or excluded",
            allowableValues = {"include", "exclude"},
            defaultValue = "include")
    private String watermarkPagesMode = "include";

    @Schema(description = "One-based custom pages and ascending ranges, for example 1,3-5")
    private String watermarkPagesCustom;

    @Schema(
            description = "Page parity used by alternate selection",
            allowableValues = {"odd", "even"},
            defaultValue = "odd")
    private String watermarkPagesAlternate = "odd";
}
