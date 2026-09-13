package stirling.software.SPDF.controller.api.security;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.config.swagger.StandardPdfResponse;
import stirling.software.SPDF.model.api.security.AddPasswordRequest;
import stirling.software.SPDF.model.api.security.PDFPasswordRequest;
import stirling.software.common.annotations.AutoJobPostMapping;
import stirling.software.common.annotations.api.SecurityApi;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.util.ExceptionUtils;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

@SecurityApi
@RequiredArgsConstructor
public class PasswordController {

    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final TempFileManager tempFileManager;

    @AutoJobPostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, value = "/remove-password")
    @StandardPdfResponse
    @Operation(
            summary = "Remove password from a PDF file",
            description =
                    "This endpoint removes the password from a protected PDF file. Users need to"
                            + " provide the existing password. Input:PDF Output:PDF Type:SISO")
    public ResponseEntity<Resource> removePassword(@ModelAttribute PDFPasswordRequest request)
            throws IOException {
        MultipartFile fileInput = request.getFileInput();
        String password = request.getPassword();

        try (PDDocument document = pdfDocumentFactory.load(fileInput, password)) {
            document.setAllSecurityToBeRemoved(true);
            return WebResponseUtils.pdfDocToWebResponse(
                    document,
                    GeneralUtils.generateFilename(
                            fileInput.getOriginalFilename(), "_password_removed.pdf"),
                    tempFileManager);
        } catch (IOException e) {
            // Handle password errors specifically
            if (ExceptionUtils.isPasswordError(e)) {
                throw ExceptionUtils.createPdfPasswordException(e);
            }
            ExceptionUtils.logException("password removal", e);
            throw ExceptionUtils.handlePdfException(e);
        }
    }

    @AutoJobPostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE, value = "/add-password")
    @StandardPdfResponse
    @Operation(
            summary = "Add password to a PDF file",
            description =
                    "This endpoint adds password protection to a PDF file. Users can specify a set"
                            + " of permissions that should be applied to the file. Input:PDF"
                            + " Output:PDF")
    public ResponseEntity<Resource> addPassword(@ModelAttribute AddPasswordRequest request)
            throws IOException {
        MultipartFile fileInput = request.getFileInput();
        String ownerPassword = request.getOwnerPassword();
        String password = request.getPassword();
        try (PDDocument document = pdfDocumentFactory.load(fileInput)) {
            stirling.software.SPDF.service.pdflunna.PasswordOperations.apply(document, request);
            if ((ownerPassword == null || ownerPassword.length() == 0)
                    && (password == null || password.length() == 0))
                return WebResponseUtils.pdfDocToWebResponse(
                        document,
                        GeneralUtils.generateFilename(
                                fileInput.getOriginalFilename(), "_permissions.pdf"),
                        tempFileManager);
            return WebResponseUtils.pdfDocToWebResponse(
                    document,
                    GeneralUtils.generateFilename(
                            fileInput.getOriginalFilename(), "_passworded.pdf"),
                    tempFileManager);
        }
    }
}
