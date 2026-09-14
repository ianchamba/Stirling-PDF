package stirling.software.SPDF.service.pdflunna;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import stirling.software.SPDF.model.api.PdfLunnaPersonalizeRequest;

/** Resolves the plugin's page selection against an already open document. */
public record PdfLunnaPageSelection(
        String pages, boolean exclude, boolean even, List<PageRange> ranges) {
    private static final int MAX_EXPRESSION_LENGTH = 8192;
    private static final int MAX_RANGES = 1000;
    private static final Pattern RANGE = Pattern.compile("[0-9]+(?:-[0-9]+)?");

    public static PdfLunnaPageSelection from(PdfLunnaPersonalizeRequest request) {
        String pages = request.getWatermarkPages();
        if (pages == null) {
            return null;
        }
        if (!Set.of("all", "first", "last", "custom", "alternate").contains(pages)) {
            throw invalid("Unsupported watermark page selection");
        }
        String mode = request.getWatermarkPagesMode();
        String alternate = request.getWatermarkPagesAlternate();
        if (mode == null || !Set.of("include", "exclude").contains(mode)) {
            throw invalid("Unsupported custom page selection mode");
        }
        if (alternate == null || !Set.of("odd", "even").contains(alternate)) {
            throw invalid("Unsupported alternate page selection");
        }
        List<PageRange> ranges = new ArrayList<>();
        if ("custom".equals(pages)) {
            String expression = request.getWatermarkPagesCustom();
            if (expression == null
                    || expression.isBlank()
                    || expression.length() > MAX_EXPRESSION_LENGTH) {
                throw invalid("Custom page selection is empty or too long");
            }
            String[] parts = expression.replaceAll("\\s+", "").split(",", -1);
            if (parts.length > MAX_RANGES) {
                throw invalid("Too many custom page ranges");
            }
            for (String part : parts) {
                if (!RANGE.matcher(part).matches()) {
                    throw invalid("Invalid custom page range");
                }
                String[] ends = part.split("-", -1);
                try {
                    int start = Integer.parseInt(ends[0]);
                    int end = ends.length == 1 ? start : Integer.parseInt(ends[1]);
                    if (start < 1 || end < start) {
                        throw invalid("Page ranges must be positive and ascending");
                    }
                    ranges.add(new PageRange(start, end));
                } catch (NumberFormatException exception) {
                    throw invalid("Page number is too large");
                }
            }
        }
        return new PdfLunnaPageSelection(
                pages, "exclude".equals(mode), "even".equals(alternate), List.copyOf(ranges));
    }

    public List<Integer> resolve(int pageCount) {
        BitSet selected = new BitSet();
        if (pageCount < 1) {
            return List.of();
        }
        switch (pages) {
            case "all" -> selected.set(0, pageCount);
            case "first" -> selected.set(0);
            case "last" -> selected.set(pageCount - 1);
            case "alternate" -> {
                for (int index = even ? 1 : 0; index < pageCount; index += 2) {
                    selected.set(index);
                }
            }
            case "custom" -> {
                for (PageRange range : ranges) {
                    if (range.start() <= pageCount) {
                        selected.set(range.start() - 1, Math.min(range.end(), pageCount));
                    }
                }
                if (exclude) {
                    selected.flip(0, pageCount);
                }
            }
            default -> throw new IllegalStateException("Unvalidated page selection");
        }
        return selected.stream().map(index -> index + 1).boxed().toList();
    }

    private static ResponseStatusException invalid(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    public record PageRange(int start, int end) {}
}
