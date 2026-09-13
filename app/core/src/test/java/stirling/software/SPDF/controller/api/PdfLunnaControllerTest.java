package stirling.software.SPDF.controller.api;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.imageio.ImageIO;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.config.EndpointConfiguration;
import stirling.software.SPDF.model.api.PdfLunnaPersonalizeRequest;
import stirling.software.SPDF.service.pdflunna.PdfLunnaAdmission;
import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.model.api.PDFFile;
import stirling.software.common.service.CustomPDFDocumentFactory;
import stirling.software.common.service.PdfMetadataService;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.TempFileRegistry;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class PdfLunnaControllerTest {
    @TempDir Path directory;

    private final ObjectMapper mapper = JsonMapper.builder().build();
    private CustomPDFDocumentFactory factory;
    private TempFileManager tempFiles;
    private PdfMetadataService metadata;
    private EndpointConfiguration endpoints;
    private PdfLunnaController controller;

    @BeforeEach
    void setUp() {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getSystem().getTempFileManagement().setBaseTmpDir(directory.toString());
        metadata = spy(new PdfMetadataService(properties, "Stirling PDF", false, null));
        tempFiles = spy(new TempFileManager(new TempFileRegistry(), properties));
        factory = spy(new CustomPDFDocumentFactory(metadata, tempFiles));
        endpoints = mock(EndpointConfiguration.class);
        when(endpoints.isEndpointEnabledForUri(anyString())).thenReturn(true);
        controller =
                new PdfLunnaController(
                        mapper,
                        factory,
                        metadata,
                        tempFiles,
                        endpoints,
                        new PdfLunnaAdmission(1, 0, 1));
    }

    @Test
    void onePassPreservesLinksTextMetadataAndFinalPermissions() throws Exception {
        var input = pdf(true);
        var stamp =
                Map.<String, Object>ofEntries(
                        Map.entry("fileInput", "automated"),
                        Map.entry("fileId", "legacy-id"),
                        Map.entry("stampType", "text"),
                        Map.entry("stampText", "Licensed Alice @page"),
                        Map.entry("fontSize", 12),
                        Map.entry("opacity", 0.5),
                        Map.entry("rotation", 0),
                        Map.entry("position", 8),
                        Map.entry("overrideX", -1),
                        Map.entry("overrideY", -1),
                        Map.entry("customMargin", "small"),
                        Map.entry("customColor", "#333333"),
                        Map.entry("alphabet", "roman"),
                        Map.entry("pageNumbers", "2"),
                        Map.entry("hideOnPrint", true));
        var request =
                request(
                        input,
                        List.of(
                                operation(
                                        "/api/v1/misc/update-metadata",
                                        Map.of(
                                                "title",
                                                "Purchased book",
                                                "author",
                                                "Shop",
                                                "subject",
                                                "traceable-subject",
                                                "pdflunna_email_hash",
                                                "customer-hash")),
                                operation("/api/v1/misc/add-stamp", stamp),
                                operation(
                                        "/api/v1/security/add-password",
                                        Map.of(
                                                "ownerPassword",
                                                "new-owner",
                                                "password",
                                                "",
                                                "keyLength",
                                                256,
                                                "preventExtractContent",
                                                true,
                                                "preventModify",
                                                true,
                                                "preventPrinting",
                                                false,
                                                "preventModifyAnnotations",
                                                true))));

        byte[] output = drain(controller.personalize(request));
        verify(factory, times(1)).load(input, true);
        verify(tempFiles, times(1)).createManagedTempFile(".pdf");
        try (PDDocument document = Loader.loadPDF(output)) {
            assertEquals(3, document.getNumberOfPages());
            assertTrue(document.isEncrypted());
            assertFalse(document.getCurrentAccessPermission().canExtractContent());
            assertFalse(document.getCurrentAccessPermission().canModify());
            assertFalse(document.getCurrentAccessPermission().canModifyAnnotations());
            assertTrue(document.getCurrentAccessPermission().canPrint());
            assertEquals("Purchased book", document.getDocumentInformation().getTitle());
            assertEquals(
                    "customer-hash",
                    document.getDocumentInformation()
                            .getCustomMetadataValue("pdflunna_email_hash"));
            assertEquals("traceable-subject", document.getDocumentInformation().getSubject());
            for (int page = 0; page < 3; page++) {
                assertEquals(1, document.getPage(page).getAnnotations().size());
                var link =
                        assertInstanceOf(
                                PDAnnotationLink.class,
                                document.getPage(page).getAnnotations().get(0));
                assertEquals("https://example.com/book", ((PDActionURI) link.getAction()).getURI());
                PDFTextStripper stripper = new PDFTextStripper();
                stripper.setStartPage(page + 1);
                stripper.setEndPage(page + 1);
                String text = stripper.getText(document);
                assertTrue(text.contains("Original selectable text " + (page + 1)));
                assertEquals(page == 1, text.contains("Licensed Alice 2"));
            }
            var group = document.getDocumentCatalog().getOCProperties().getGroup("Stamp");
            assertNotNull(group);
            COSDictionary usage =
                    (COSDictionary)
                            group.getCOSObject().getDictionaryObject(COSName.getPDFName("Usage"));
            COSDictionary print =
                    (COSDictionary) usage.getDictionaryObject(COSName.getPDFName("Print"));
            assertEquals("OFF", print.getNameAsString(COSName.getPDFName("PrintState")));
        }
        try (PDDocument owner = Loader.loadPDF(output, "new-owner")) {
            assertTrue(owner.getCurrentAccessPermission().isOwnerPermission());
        }
        try (var remaining = Files.list(directory)) {
            assertEquals(0, remaining.count(), "Response closes and deletes its temporary file");
        }
    }

    @Test
    void imageIsDecodedOnceAndSharedAcrossPages() throws Exception {
        BufferedImage bitmap = new BufferedImage(24, 12, BufferedImage.TYPE_INT_RGB);
        var graphics = bitmap.createGraphics();
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 24, 12);
        graphics.dispose();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        ImageIO.write(bitmap, "png", encoded);
        var image =
                spy(
                        new MockMultipartFile(
                                "watermarkImage", "mark.png", "image/png", encoded.toByteArray()));
        var request =
                request(
                        pdf(false),
                        List.of(
                                operation(
                                        "/api/v1/security/add-watermark",
                                        Map.of(
                                                "watermarkType",
                                                "image",
                                                "fontSize",
                                                24,
                                                "opacity",
                                                0.4,
                                                "rotation",
                                                30,
                                                "widthSpacer",
                                                100,
                                                "heightSpacer",
                                                100,
                                                "alphabet",
                                                "roman",
                                                "customColor",
                                                "#333333",
                                                "hideOnPrint",
                                                true))));
        request.setWatermarkImage(image);
        byte[] output = drain(controller.personalize(request));
        verify(image, times(1)).getInputStream();
        try (PDDocument document = Loader.loadPDF(output)) {
            COSDictionary sharedImage = null;
            for (PDPage page : document.getPages()) {
                var names = page.getResources().getXObjectNames().iterator();
                assertTrue(names.hasNext());
                PDImageXObject resource =
                        (PDImageXObject) page.getResources().getXObject(names.next());
                if (sharedImage == null) sharedImage = resource.getCOSObject();
                else assertSame(sharedImage, resource.getCOSObject());
                assertFalse(names.hasNext());
            }
            assertNotNull(document.getDocumentCatalog().getOCProperties().getGroup("Watermark"));
        }
    }

    @Test
    void textFontIsSharedAndFullTextRemainsSelectable() throws Exception {
        var request =
                request(
                        pdf(false),
                        List.of(
                                operation(
                                        "/api/v1/security/add-watermark",
                                        Map.of(
                                                "watermarkType",
                                                "text",
                                                "watermarkText",
                                                "CUSTOMER",
                                                "fontSize",
                                                24,
                                                "opacity",
                                                0.4,
                                                "rotation",
                                                30,
                                                "widthSpacer",
                                                100,
                                                "heightSpacer",
                                                100,
                                                "alphabet",
                                                "roman",
                                                "customColor",
                                                "#333333"))));
        try (PDDocument document = Loader.loadPDF(drain(controller.personalize(request)))) {
            List<COSDictionary> fonts = new ArrayList<>();
            for (PDPage page : document.getPages()) {
                for (COSName name : page.getResources().getFontNames()) {
                    var font = page.getResources().getFont(name);
                    if (font.getName().contains("NotoSans")) fonts.add(font.getCOSObject());
                }
            }
            assertEquals(3, fonts.size());
            assertSame(fonts.get(0), fonts.get(1));
            assertSame(fonts.get(1), fonts.get(2));
            String text = new PDFTextStripper().getText(document);
            assertTrue(text.replaceAll("\\s+", "").contains("CUSTOMER"), text);
            assertTrue(text.contains("Original selectable text 3"));
        }
    }

    @Test
    void unsupportedOrDisabledWorkIsRejectedBeforeLoadingThePdf() throws Exception {
        var input = pdf(false);
        var request =
                request(
                        input,
                        List.of(
                                operation(
                                        "/api/v1/misc/flatten",
                                        Map.of("flattenOnlyForms", false))));
        var error =
                assertThrows(ResponseStatusException.class, () -> controller.personalize(request));
        assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        verifyNoInteractions(factory);

        request.setJson(
                mapper.writeValueAsString(
                        Map.of(
                                "pipeline",
                                List.of(
                                        operation(
                                                "/api/v1/security/add-password",
                                                Map.of("ownerPassword", "owner")),
                                        operation(
                                                "/api/v1/misc/update-metadata",
                                                Map.of("title", "after protection"))))));
        assertThrows(ResponseStatusException.class, () -> controller.personalize(request));
        verifyNoInteractions(factory);

        when(endpoints.isEndpointEnabledForUri("/api/v1/misc/update-metadata")).thenReturn(false);
        request.setJson(
                mapper.writeValueAsString(
                        Map.of(
                                "pipeline",
                                List.of(
                                        operation(
                                                "/api/v1/misc/update-metadata",
                                                Map.of("title", "disabled"))))));
        assertEquals(
                HttpStatus.FORBIDDEN,
                assertThrows(ResponseStatusException.class, () -> controller.personalize(request))
                        .getStatusCode());
        verifyNoInteractions(factory);
        assertFalse(
                ((List<?>) controller.capabilities().get("supportedOperations"))
                        .contains("/api/v1/misc/update-metadata"));
    }

    @Test
    void pageCountDoesNotUpdateMetadataOrCreateOutputFilesAndErrorsReleaseAdmission()
            throws Exception {
        PDFFile invalid = new PDFFile();
        invalid.setFileInput(
                new MockMultipartFile(
                        "fileInput", "invalid.pdf", "application/pdf", new byte[] {1, 2, 3}));
        assertThrows(IOException.class, () -> controller.pageCount(invalid));

        PDFFile request = new PDFFile();
        request.setFileInput(pdf(false));
        assertEquals(Map.of("pageCount", 3), controller.pageCount(request));
        verifyNoInteractions(metadata);
        verifyNoInteractions(tempFiles);
    }

    @Test
    void userPasswordRemainsRequiredAndTinyWatermarkIsRejectedBeforeLoad() throws Exception {
        var input = pdf(false);
        var request =
                request(
                        input,
                        List.of(
                                operation(
                                        "/api/v1/security/add-watermark",
                                        Map.of(
                                                "watermarkType",
                                                "text",
                                                "watermarkText",
                                                "A",
                                                "fontSize",
                                                1e-30,
                                                "opacity",
                                                0.4,
                                                "rotation",
                                                0,
                                                "widthSpacer",
                                                0,
                                                "heightSpacer",
                                                0,
                                                "alphabet",
                                                "roman",
                                                "customColor",
                                                "#333333"))));
        assertThrows(ResponseStatusException.class, () -> controller.personalize(request));
        verifyNoInteractions(factory);

        request.setJson(
                mapper.writeValueAsString(
                        Map.of(
                                "pipeline",
                                List.of(
                                        operation(
                                                "/api/v1/security/add-password",
                                                Map.of(
                                                        "ownerPassword",
                                                        "owner-secret",
                                                        "password",
                                                        "reader-secret",
                                                        "keyLength",
                                                        256))))));
        byte[] output = drain(controller.personalize(request));
        assertThrows(IOException.class, () -> Loader.loadPDF(output));
        try (PDDocument reader = Loader.loadPDF(output, "reader-secret")) {
            assertEquals(3, reader.getNumberOfPages());
        }
    }

    private PdfLunnaPersonalizeRequest request(
            MockMultipartFile pdf, List<Map<String, Object>> operations) {
        var request = new PdfLunnaPersonalizeRequest();
        request.setFileInput(pdf);
        request.setJson(mapper.writeValueAsString(Map.of("pipeline", operations)));
        return request;
    }

    private static Map<String, Object> operation(String path, Map<String, Object> parameters) {
        return Map.of("operation", path, "parameters", parameters);
    }

    private static MockMultipartFile pdf(boolean encrypted) throws Exception {
        try (PDDocument document = new PDDocument();
                ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            for (int index = 0; index < 3; index++) {
                PDPage page = new PDPage(PDRectangle.A4);
                document.addPage(page);
                try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                    content.beginText();
                    content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                    content.newLineAtOffset(50, 700);
                    content.showText("Original selectable text " + (index + 1));
                    content.endText();
                }
                PDAnnotationLink link = new PDAnnotationLink();
                link.setRectangle(new PDRectangle(50, 690, 180, 20));
                PDActionURI action = new PDActionURI();
                action.setURI("https://example.com/book");
                link.setAction(action);
                page.getAnnotations().add(link);
            }
            if (encrypted) {
                StandardProtectionPolicy protection =
                        new StandardProtectionPolicy("old-owner", "", new AccessPermission());
                protection.setEncryptionKeyLength(128);
                document.protect(protection);
            }
            document.save(bytes);
            return new MockMultipartFile(
                    "fileInput", "book.pdf", "application/pdf", bytes.toByteArray());
        }
    }

    private static byte[] drain(ResponseEntity<Resource> response) throws Exception {
        try (InputStream input = response.getBody().getInputStream()) {
            return input.readAllBytes();
        }
    }
}
