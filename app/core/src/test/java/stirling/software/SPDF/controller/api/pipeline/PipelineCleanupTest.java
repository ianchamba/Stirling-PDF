package stirling.software.SPDF.controller.api.pipeline;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.util.MultiValueMap;
import org.springframework.web.multipart.MultipartFile;

import stirling.software.SPDF.model.api.HandleDataRequest;
import stirling.software.SPDF.service.ApiDocService;
import stirling.software.common.model.ApplicationProperties;
import stirling.software.common.service.InternalApiClient;
import stirling.software.common.service.PostHogService;
import stirling.software.common.util.TempFile;
import stirling.software.common.util.TempFileManager;
import stirling.software.common.util.TempFileRegistry;

import tools.jackson.databind.json.JsonMapper;

class PipelineCleanupTest {
    @TempDir Path directory;
    private InternalApiClient client;
    private PipelineController controller;
    private TempFileManager tempFiles;

    @BeforeEach
    void setUp() {
        ApplicationProperties properties = new ApplicationProperties();
        properties.getSystem().getTempFileManagement().setBaseTmpDir(directory.toString());
        tempFiles = new TempFileManager(new TempFileRegistry(), properties);
        ApiDocService apiDocs = mock(ApiDocService.class);
        when(apiDocs.getExtensionTypes(eq(false), anyString())).thenReturn(List.of("ALL"));
        when(apiDocs.isValidOperation(anyString(), anyMap())).thenReturn(true);
        client = mock(InternalApiClient.class);
        controller =
                new PipelineController(
                        new PipelineProcessor(apiDocs, client, tempFiles),
                        JsonMapper.builder().build(),
                        mock(PostHogService.class),
                        tempFiles);
    }

    @Test
    void intermediatesAreClosedButFinalResponseLivesUntilConsumed() throws Exception {
        when(client.post(anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            MultiValueMap<String, Object> body = invocation.getArgument(1);
                            Resource input = (Resource) body.getFirst("fileInput");
                            try (InputStream stream = input.getInputStream()) {
                                return output(
                                        new String(stream.readAllBytes(), StandardCharsets.UTF_8)
                                                + "-step");
                            }
                        });
        var response = controller.handleData(request(1));
        assertNotNull(response);
        assertEquals(1, filesRemaining());
        try (InputStream input = response.getBody().getInputStream()) {
            assertEquals(
                    "%PDF-test-step-step",
                    new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals(0, filesRemaining());
    }

    @Test
    void failureDuringLaterStepCleansEarlierOutputsAndUpload() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        when(client.post(anyString(), any()))
                .thenAnswer(
                        invocation -> {
                            if (calls.incrementAndGet() == 2)
                                throw new IllegalStateException("operation failed");
                            return output("%PDF-intermediate");
                        });
        assertNull(controller.handleData(request(1)));
        assertEquals(0, filesRemaining());
    }

    @Test
    void zipOwnsItsCopyAfterIntermediateFilesAreClosed() throws Exception {
        when(client.post(anyString(), any())).thenAnswer(invocation -> output("%PDF-output"));
        var response = controller.handleData(request(2));
        assertNotNull(response);
        assertEquals(1, filesRemaining());
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(response.getBody().getInputStream())) {
            while (zip.getNextEntry() != null) {
                assertEquals("%PDF-output", new String(zip.readAllBytes(), StandardCharsets.UTF_8));
                entries++;
                zip.closeEntry();
            }
        }
        assertEquals(2, entries);
        assertEquals(0, filesRemaining());
    }

    private ResponseEntity<Resource> output(String contents) throws Exception {
        TempFile file = tempFiles.createManagedTempFile(".pdf");
        Files.writeString(file.getPath(), contents);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"book_metadata.pdf\"")
                .body(new InternalApiClient.TempFileResource(file, "book_metadata.pdf"));
    }

    private HandleDataRequest request(int count) {
        HandleDataRequest request = new HandleDataRequest();
        MultipartFile[] uploads = new MultipartFile[count];
        for (int index = 0; index < count; index++) {
            uploads[index] =
                    new MockMultipartFile(
                            "fileInput",
                            "book.pdf",
                            "application/pdf",
                            "%PDF-test".getBytes(StandardCharsets.UTF_8));
        }
        request.setFileInput(uploads);
        request.setJson(
                "{\"pipeline\":[{\"operation\":\"/api/v1/misc/update-metadata\",\"parameters\":{}},{\"operation\":\"/api/v1/security/add-watermark\",\"parameters\":{}}]}");
        return request;
    }

    private long filesRemaining() throws Exception {
        try (var files = Files.list(directory)) {
            return files.count();
        }
    }
}
