package stirling.software.SPDF.controller.api;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.beans.BeanWrapper;
import org.springframework.beans.BeanWrapperImpl;
import org.springframework.beans.MutablePropertyValues;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.DataBinder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.SPDF.model.PipelineConfig;
import stirling.software.SPDF.model.PipelineOperation;
import stirling.software.SPDF.model.api.PdfLunnaPersonalizeRequest;
import stirling.software.SPDF.model.api.misc.AddStampRequest;
import stirling.software.SPDF.model.api.misc.MetadataRequest;
import stirling.software.SPDF.model.api.security.AddPasswordRequest;
import stirling.software.SPDF.model.api.security.AddWatermarkRequest;
import stirling.software.SPDF.service.pdflunna.MetadataOperations;
import stirling.software.SPDF.service.pdflunna.PasswordOperations;
import stirling.software.SPDF.service.pdflunna.PdfLunnaAdmission;
import stirling.software.SPDF.service.pdflunna.StampOperations;
import stirling.software.SPDF.service.pdflunna.WatermarkOperations;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.service.PdfMetadataService;
import stirling.software.common.util.GeneralUtils;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.WebResponseUtils;

import tools.jackson.databind.ObjectMapper;

/** Optional protocol for the WordPress plugin; existing tool endpoints remain compatible. */
@RestController
@RequestMapping("/api/v1/pdflunna")
@RequiredArgsConstructor
public class PdfLunnaController {
    private static final String METADATA = "/api/v1/misc/update-metadata";
    private static final String STAMP = "/api/v1/misc/add-stamp";
    private static final String WATERMARK = "/api/v1/security/add-watermark";
    private static final String PASSWORD = "/api/v1/security/add-password";
    private static final List<String> SUPPORTED = List.of(METADATA, STAMP, WATERMARK, PASSWORD);
    private static final Set<String> TRANSPORT_PARAMETERS = Set.of("fileInput", "fileId");
    private static final int MAX_OPERATIONS = 16;

    private final ObjectMapper objectMapper;
    private final CustomPDFDocumentFactory pdfDocumentFactory;
    private final PdfMetadataService pdfMetadataService;
    private final TempFileManager tempFileManager;
    private final EndpointConfiguration endpointConfiguration;
    private final PdfLunnaAdmission admission;

    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of(
                "protocolVersion",
                1,
                "supportedOperations",
                SUPPORTED.stream().filter(endpointConfiguration::isEndpointEnabledForUri).toList(),
                "features",
                List.of("singleDocumentPass", "imageUploads"),
                "maxOperations",
                MAX_OPERATIONS,
                "supportsRasterization",
                false,
                "pageCountEndpoint",
                "/api/v1/pdflunna/page-count");
    }

    @PostMapping(value = "/page-count", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Integer> pageCount(@ModelAttribute PDFFile request) throws IOException {
        requireUpload(request.getFileInput());
        try (var permit = admission.acquire();
                PDDocument document = pdfDocumentFactory.load(request.getFileInput(), true)) {
            return Map.of("pageCount", document.getNumberOfPages());
        }
    }

    // Deliberately synchronous: the permit covers the entire document lifetime. This endpoint
    // does not call the HTTP pipeline or AutoJob executor, so nested permit acquisition is avoided.
    @PostMapping(value = "/personalize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Resource> personalize(@ModelAttribute PdfLunnaPersonalizeRequest request)
            throws IOException {
        requireUpload(request.getFileInput());
        List<PreparedOperation> operations = prepare(request);
        try (var permit = admission.acquire();
                PDDocument document = pdfDocumentFactory.load(request.getFileInput(), true)) {
            for (PreparedOperation operation : operations) {
                if (!METADATA.equals(operation.path())) {
                    // Match the post-load behavior of the original individual tool endpoints.
                    pdfMetadataService.setDefaultMetadata(document);
                    if (document.isEncrypted()) {
                        document.setAllSecurityToBeRemoved(true);
                    }
                }
                switch (operation.path()) {
                    case METADATA ->
                            MetadataOperations.apply(
                                    document, (MetadataRequest) operation.request());
                    case STAMP ->
                            StampOperations.apply(document, (AddStampRequest) operation.request());
                    case WATERMARK ->
                            WatermarkOperations.apply(
                                    document, (AddWatermarkRequest) operation.request());
                    case PASSWORD ->
                            PasswordOperations.apply(
                                    document, (AddPasswordRequest) operation.request());
                    default -> throw new IllegalStateException("Unvalidated operation");
                }
            }
            return WebResponseUtils.pdfDocToWebResponse(
                    document,
                    GeneralUtils.generateFilename(
                            request.getFileInput().getOriginalFilename(), "_personalized.pdf"),
                    tempFileManager);
        }
    }

    @ExceptionHandler(PdfLunnaAdmission.BusyException.class)
    public ResponseEntity<Map<String, String>> busy(PdfLunnaAdmission.BusyException exception) {
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header("Retry-After", "5")
                .body(Map.of("code", "PDFLUNNA_BUSY", "error", exception.getMessage()));
    }

    private List<PreparedOperation> prepare(PdfLunnaPersonalizeRequest request) {
        PipelineConfig config;
        try {
            config = objectMapper.readValue(request.getJson(), PipelineConfig.class);
        } catch (RuntimeException e) {
            throw invalid("Invalid pipeline JSON");
        }
        if (config == null
                || config.getOperations() == null
                || config.getOperations().isEmpty()
                || config.getOperations().size() > MAX_OPERATIONS) {
            throw invalid("Pipeline must contain between 1 and " + MAX_OPERATIONS + " operations");
        }
        List<PreparedOperation> result = new ArrayList<>();
        List<PipelineOperation> operations = config.getOperations();
        for (int index = 0; index < operations.size(); index++) {
            PipelineOperation operation = operations.get(index);
            if (operation == null || !SUPPORTED.contains(operation.getOperation())) {
                throw invalid("Unsupported personalization operation");
            }
            String path = operation.getOperation();
            if (!endpointConfiguration.isEndpointEnabledForUri(path)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Operation is disabled");
            }
            if (PASSWORD.equals(path) && index != operations.size() - 1) {
                throw invalid("Password protection must be the final operation");
            }
            PDFFile parameters =
                    switch (path) {
                        case METADATA -> new MetadataRequest();
                        case STAMP -> new AddStampRequest();
                        case WATERMARK -> new AddWatermarkRequest();
                        case PASSWORD -> new AddPasswordRequest();
                        default -> throw new IllegalStateException("Unvalidated operation");
                    };
            bind(parameters, operation.getParameters());
            parameters.setFileInput(request.getFileInput());
            if (parameters instanceof AddStampRequest stamp) {
                stamp.setStampImage(request.getStampImage());
                requireMark(stamp.getStampType(), stamp.getStampText(), stamp.getStampImage());
                float effectiveSize =
                        "text".equalsIgnoreCase(stamp.getStampType())
                                        && Float.isFinite(stamp.getFontSize())
                                        && stamp.getFontSize() <= 0
                                ? 40
                                : stamp.getFontSize();
                requireGeometry(effectiveSize, stamp.getOpacity(), stamp.getRotation());
                if (stamp.getCustomMargin() == null
                        || stamp.getAlphabet() == null
                        || stamp.getCustomColor() == null
                        || stamp.getPosition() < 1
                        || stamp.getPosition() > 9) {
                    throw invalid("Stamp margin, alphabet, color and position are required");
                }
            } else if (parameters instanceof AddWatermarkRequest watermark) {
                watermark.setWatermarkImage(request.getWatermarkImage());
                requireMark(
                        watermark.getWatermarkType(),
                        watermark.getWatermarkText(),
                        watermark.getWatermarkImage());
                requireGeometry(
                        watermark.getFontSize(), watermark.getOpacity(), watermark.getRotation());
                if (watermark.getFontSize() < 1) {
                    throw invalid("Watermark font size must be at least 1");
                }
                if (Boolean.TRUE.equals(watermark.getConvertPDFToImage())) {
                    throw invalid("Rasterization is not supported by this protocol");
                }
                if (watermark.getAlphabet() == null
                        || watermark.getCustomColor() == null
                        || watermark.getWidthSpacer() < 0
                        || watermark.getHeightSpacer() < 0) {
                    throw invalid("Watermark alphabet, color and nonnegative spacers are required");
                }
            } else if (parameters instanceof AddPasswordRequest password) {
                if (!Set.of(40, 128, 256).contains(password.getKeyLength())) {
                    throw invalid("Unsupported encryption key length");
                }
            }
            result.add(new PreparedOperation(path, parameters));
        }
        return result;
    }

    private void bind(PDFFile target, Map<String, Object> source) {
        if (source == null) {
            throw invalid("Operation parameters are required");
        }
        Map<String, Object> values = new LinkedHashMap<>(source);
        TRANSPORT_PARAMETERS.forEach(values::remove);
        // Images are multipart parts, never paths or serialized file objects from JSON.
        if (values.containsKey("stampImage") || values.containsKey("watermarkImage")) {
            throw invalid("Images must be uploaded as multipart files");
        }
        if (target instanceof MetadataRequest metadata) {
            BeanWrapper bean = new BeanWrapperImpl(target);
            Map<String, String> custom = new LinkedHashMap<>();
            for (String key : new ArrayList<>(values.keySet())) {
                if (!bean.isWritableProperty(key)) {
                    Object value = values.remove(key);
                    if (value != null
                            && !(value instanceof String)
                            && !(value instanceof Number)
                            && !(value instanceof Boolean)) {
                        throw invalid("Custom metadata must be scalar");
                    }
                    custom.put(key, value == null ? null : value.toString());
                }
            }
            Object suppliedCustom = values.remove("allRequestParams");
            if (suppliedCustom instanceof Map<?, ?> map) {
                map.forEach(
                        (key, value) ->
                                custom.put(
                                        String.valueOf(key),
                                        value == null ? null : String.valueOf(value)));
            } else if (suppliedCustom != null) {
                throw invalid("allRequestParams must be an object");
            }
            metadata.setAllRequestParams(custom);
        }
        DataBinder binder = new DataBinder(target);
        binder.setAutoGrowNestedPaths(false);
        binder.setIgnoreUnknownFields(false);
        binder.setAllowedFields(
                Arrays.stream(new BeanWrapperImpl(target).getPropertyDescriptors())
                        .filter(property -> property.getWriteMethod() != null)
                        .map(property -> property.getName())
                        .toArray(String[]::new));
        try {
            binder.bind(new MutablePropertyValues(values));
        } catch (RuntimeException e) {
            throw invalid("Unsupported or invalid operation parameter");
        }
        if (binder.getBindingResult().hasErrors()
                || binder.getBindingResult().getSuppressedFields().length > 0) {
            throw invalid("Invalid operation parameter type");
        }
    }

    private static void requireUpload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("A PDF file is required");
        }
    }

    private static void requireMark(String type, String text, MultipartFile image) {
        if ("image".equalsIgnoreCase(type)) {
            if (image == null || image.isEmpty()) throw invalid("The mark image is required");
        } else if ("text".equalsIgnoreCase(type)) {
            if (text == null || text.isBlank()) throw invalid("The mark text is required");
        } else {
            throw invalid("Unsupported mark type");
        }
    }

    private static void requireGeometry(float size, float opacity, float rotation) {
        if (!Float.isFinite(size)
                || size <= 0
                || !Float.isFinite(opacity)
                || opacity < 0
                || opacity > 1
                || !Float.isFinite(rotation)) {
            throw invalid("Invalid mark size, opacity or rotation");
        }
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private record PreparedOperation(String path, PDFFile request) {}
}
