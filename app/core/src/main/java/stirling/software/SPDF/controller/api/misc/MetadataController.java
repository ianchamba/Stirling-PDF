package stirling.software.SPDF.controller.api.misc;

import java.io.IOException;
import java.util.Map;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.WebDataBinder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import io.github.pixee.security.Filenames;
import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import stirling.software.SPDF.config.swagger.StandardPdfResponse;
import stirling.software.SPDF.model.api.misc.MetadataRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.MiscApi;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;
import stirling.software.common.util.propertyeditor.StringToMapPropertyEditor;

@MiscApi
@Slf4j
@RequiredArgsConstructor
public class MetadataController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @InitBinder
    public void initBinder(WebDataBinder binder) {
        binder.registerCustomEditor(Map.class, "allRequestParams", new StringToMapPropertyEditor());
    }

    @AutoJobPostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, value = "/update-metadata")
    @StandardPdfResponse
    @Operation(
            summary = "Update metadata of a PDF file",
            description =
                    "This endpoint allows you to update the metadata of a given PDF file. You can"
                            + " add, modify, or delete standard and custom metadata fields. Input:PDF"
                            + " Output:PDF Type:SISO")
    public ResponseEntity<Resource> metadata(@ModelAttribute MetadataRequest request)
            throws IOException {

        // Extract PDF file from the request object
        MultipartFile pdfFile = request.getFileInput();

        try (PDDocument document = pdfDocumentFactory.load(pdfFile, true)) {
            stirling.software.SPDF.service.pdflunna.MetadataOperations.apply(document, request);
            return WebResponseUtils.pdfDocToWebResponse(
                    document,
                    GeneralUtils.removeExtension(
                                    Filenames.toSimpleFileName(pdfFile.getOriginalFilename()))
                            + "_metadata.pdf",
                    tempFileManager);
        }
    }
}
