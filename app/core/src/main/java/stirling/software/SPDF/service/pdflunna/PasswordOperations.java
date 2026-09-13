package stirling.software.SPDF.service.pdflunna;

import java.io.IOException;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.encryption.AccessPermission;
import org.apache.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import stirling.software.SPDF.model.api.security.AddPasswordRequest;

@lombok.experimental.UtilityClass
public class PasswordOperations {

    public void apply(PDDocument document, AddPasswordRequest request) throws IOException {
        String ownerPassword = request.getOwnerPassword();
        String password = request.getPassword();
        int keyLength = request.getKeyLength();
        boolean preventAssembly = Boolean.TRUE.equals(request.getPreventAssembly());
        boolean preventExtractContent = Boolean.TRUE.equals(request.getPreventExtractContent());
        boolean preventExtractForAccessibility =
                Boolean.TRUE.equals(request.getPreventExtractForAccessibility());
        boolean preventFillInForm = Boolean.TRUE.equals(request.getPreventFillInForm());
        boolean preventModify = Boolean.TRUE.equals(request.getPreventModify());
        boolean preventModifyAnnotations =
                Boolean.TRUE.equals(request.getPreventModifyAnnotations());
        boolean preventPrinting = Boolean.TRUE.equals(request.getPreventPrinting());
        boolean preventPrintingFaithful = Boolean.TRUE.equals(request.getPreventPrintingFaithful());

        AccessPermission ap = new AccessPermission();
        ap.setCanAssembleDocument(!preventAssembly);
        ap.setCanExtractContent(!preventExtractContent);
        ap.setCanExtractForAccessibility(!preventExtractForAccessibility);
        ap.setCanFillInForm(!preventFillInForm);
        ap.setCanModify(!preventModify);
        ap.setCanModifyAnnotations(!preventModifyAnnotations);
        ap.setCanPrint(!preventPrinting);
        ap.setCanPrintFaithful(!preventPrintingFaithful);
        StandardProtectionPolicy spp = new StandardProtectionPolicy(ownerPassword, password, ap);

        if ((ownerPassword != null && ownerPassword.length() > 0)
                || (password != null && password.length() > 0)) {
            spp.setEncryptionKeyLength(keyLength);
        }
        spp.setPermissions(ap);
        document.protect(spp);
    }
}
