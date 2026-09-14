package stirling.software.SPDF.service.pdflunna;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.model.api.PdfLunnaPersonalizeRequest;

class PdfLunnaPageSelectionTest {
    @Test
    void resolvesEveryModeWithoutAnotherPdfLoad() {
        assertEquals(List.of(1, 2, 3, 4, 5), select("all", null, "include", "odd", 5));
        assertEquals(List.of(1), select("first", null, "include", "odd", 5));
        assertEquals(List.of(5), select("last", null, "include", "odd", 5));
        assertEquals(List.of(1, 3, 5), select("alternate", null, "include", "odd", 5));
        assertEquals(List.of(2, 4), select("alternate", null, "include", "even", 5));
        assertEquals(List.of(1, 3, 4, 5), select("custom", " 1,3-5,3 ", "include", "odd", 5));
        assertEquals(List.of(2), select("custom", "1,3-5", "exclude", "odd", 5));
        assertNull(PdfLunnaPageSelection.from(new PdfLunnaPersonalizeRequest()));
    }

    @Test
    void emptySelectionNeverExpandsToAllPagesAndLargeRangesAreClipped() {
        assertEquals(List.of(), select("alternate", null, "include", "even", 1));
        assertEquals(List.of(), select("custom", "1-5", "exclude", "odd", 5));
        assertEquals(List.of(), select("custom", "20-30", "include", "odd", 5));
        assertEquals(List.of(), select("last", null, "include", "odd", 0));
        assertTimeout(
                Duration.ofSeconds(1),
                () ->
                        assertEquals(
                                List.of(1, 2, 3),
                                select("custom", "1-2147483647", "include", "odd", 3)));
    }

    @Test
    void invalidSelectionIsRejectedInsteadOfWatermarkingDifferentPages() {
        for (String expression :
                List.of("", "0", "3-1", "1,,2", "1,", "-3", "x", "2147483648", "1-")) {
            var error =
                    assertThrows(
                            ResponseStatusException.class,
                            () -> select("custom", expression, "include", "odd", 5));
            assertEquals(HttpStatus.BAD_REQUEST, error.getStatusCode());
        }
        assertThrows(
                ResponseStatusException.class, () -> select("unknown", null, "include", "odd", 5));
        assertThrows(
                ResponseStatusException.class, () -> select("custom", "1", "unknown", "odd", 5));
        assertThrows(
                ResponseStatusException.class,
                () -> select("alternate", null, "include", "unknown", 5));
        assertThrows(
                ResponseStatusException.class,
                () -> select("custom", "1,".repeat(1000) + "1", "include", "odd", 5));
        assertThrows(
                ResponseStatusException.class,
                () -> select("custom", "1".repeat(8193), "include", "odd", 5));
    }

    private static List<Integer> select(
            String pages, String custom, String mode, String alternate, int count) {
        var request = new PdfLunnaPersonalizeRequest();
        request.setWatermarkPages(pages);
        request.setWatermarkPagesCustom(custom);
        request.setWatermarkPagesMode(mode);
        request.setWatermarkPagesAlternate(alternate);
        return PdfLunnaPageSelection.from(request).resolve(count);
    }
}
