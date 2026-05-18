package stirling.software.common.util;

import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentGroup;
import org.apache.pdfbox.pdmodel.graphics.optionalcontent.PDOptionalContentProperties;

public final class OcgUtils {

    private static final COSName USAGE = COSName.getPDFName("Usage");
    private static final COSName PRINT = COSName.getPDFName("Print");
    private static final COSName VIEW = COSName.getPDFName("View");
    private static final COSName PRINT_STATE = COSName.getPDFName("PrintState");
    private static final COSName VIEW_STATE = COSName.getPDFName("ViewState");
    private static final COSName OFF = COSName.getPDFName("OFF");
    private static final COSName ON = COSName.getPDFName("ON");
    private static final COSName AS = COSName.getPDFName("AS");
    private static final COSName EVENT = COSName.getPDFName("Event");
    private static final COSName CATEGORY = COSName.getPDFName("Category");
    private static final COSName OCGS = COSName.getPDFName("OCGs");

    private OcgUtils() {}

    /**
     * Creates an Optional Content Group registered with the document so that any content
     * wrapped in {@code beginMarkedContent(COSName.OC, ocg) ... endMarkedContent()} is
     * visible on screen but suppressed when the document is printed by compliant viewers.
     *
     * <p>Sets both the Usage dictionary (declarative print/view state) and an Automatic
     * State Adjustment entry under the default configuration so the Print event actually
     * toggles the layer off — Usage alone is informational in some readers.
     */
    public static PDOptionalContentGroup createHideOnPrintOcg(PDDocument document, String name) {
        PDOptionalContentGroup ocg = new PDOptionalContentGroup(name);

        COSDictionary usage = new COSDictionary();
        COSDictionary printDict = new COSDictionary();
        printDict.setItem(PRINT_STATE, OFF);
        usage.setItem(PRINT, printDict);
        COSDictionary viewDict = new COSDictionary();
        viewDict.setItem(VIEW_STATE, ON);
        usage.setItem(VIEW, viewDict);
        ocg.getCOSObject().setItem(USAGE, usage);

        PDOptionalContentProperties ocProps = document.getDocumentCatalog().getOCProperties();
        if (ocProps == null) {
            ocProps = new PDOptionalContentProperties();
            document.getDocumentCatalog().setOCProperties(ocProps);
        }
        ocProps.addGroup(ocg);

        attachPrintAutomaticStateAdjustment(ocProps, ocg);

        return ocg;
    }

    private static void attachPrintAutomaticStateAdjustment(
            PDOptionalContentProperties ocProps, PDOptionalContentGroup ocg) {
        COSDictionary ocPropsDict = ocProps.getCOSObject();
        COSBase dBase = ocPropsDict.getDictionaryObject(COSName.D);
        COSDictionary dDict;
        if (dBase instanceof COSDictionary existing) {
            dDict = existing;
        } else {
            dDict = new COSDictionary();
            ocPropsDict.setItem(COSName.D, dDict);
        }

        COSBase asBase = dDict.getDictionaryObject(AS);
        COSArray asArray;
        if (asBase instanceof COSArray existing) {
            asArray = existing;
        } else {
            asArray = new COSArray();
            dDict.setItem(AS, asArray);
        }

        COSDictionary printEntry = findPrintEventEntry(asArray);
        if (printEntry == null) {
            printEntry = new COSDictionary();
            printEntry.setItem(EVENT, PRINT);
            COSArray categoryArr = new COSArray();
            categoryArr.add(PRINT);
            printEntry.setItem(CATEGORY, categoryArr);
            printEntry.setItem(OCGS, new COSArray());
            asArray.add(printEntry);
        }

        COSBase ocgsBase = printEntry.getDictionaryObject(OCGS);
        COSArray ocgsArr;
        if (ocgsBase instanceof COSArray existing) {
            ocgsArr = existing;
        } else {
            ocgsArr = new COSArray();
            printEntry.setItem(OCGS, ocgsArr);
        }
        ocgsArr.add(ocg);
    }

    private static COSDictionary findPrintEventEntry(COSArray asArray) {
        for (int i = 0; i < asArray.size(); i++) {
            COSBase item = asArray.getObject(i);
            if (item instanceof COSDictionary entry) {
                COSBase event = entry.getDictionaryObject(EVENT);
                if (event instanceof COSName eventName && "Print".equals(eventName.getName())) {
                    return entry;
                }
            }
        }
        return null;
    }
}
