package stirling.software.SPDF.service.pdflunna;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class PdfLunnaAdmissionTest {
    @Test
    void limitsActiveDocumentsAndReleasesCapacityAfterCompletion() {
        PdfLunnaAdmission admission = new PdfLunnaAdmission(1, 0, 1);
        try (var first = admission.acquire()) {
            assertThrows(PdfLunnaAdmission.BusyException.class, admission::acquire);
        }
        try (var next = admission.acquire()) {
            assertNotNull(next);
        }
    }

    @Test
    void queuedRequestHasBoundedWaitAndDoesNotLeakCapacity() throws Exception {
        PdfLunnaAdmission admission = new PdfLunnaAdmission(1, 1, 1);
        try (var first = admission.acquire();
                var executor = Executors.newSingleThreadExecutor()) {
            var waiter =
                    executor.submit(
                            () ->
                                    assertThrows(
                                            PdfLunnaAdmission.BusyException.class,
                                            admission::acquire));
            assertNotNull(waiter.get(5, TimeUnit.SECONDS));
        }
        try (var next = admission.acquire()) {
            assertNotNull(next);
        }
    }
}
