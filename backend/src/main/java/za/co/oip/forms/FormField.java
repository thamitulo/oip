package za.co.oip.forms;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Company details that tender returnable forms ask for, with the label wording used on SBD 1, SBD 4, SBD 6.1 and MBD
 * forms. Patterns run against a lower-cased line of text with collapsed whitespace. The order matters: specific
 * labels are tried first and the matched text is masked, so "VAT registration number" is never also read as the
 * company registration number.
 */
public enum FormField {

    CSD_NUMBER("CSD supplier number", 0.9,
            "central supplier database(?: \\(csd\\))?(?: supplier)?(?: registration)?(?: number| no\\b\\.?)?",
            "\\bcsd (?:supplier |registration )?(?:number|no\\b\\.?)"),
    VAT_NUMBER("VAT registration number", 0.9, "\\bvat (?:registration |reg\\.? )?(?:number|no\\b\\.?)"),
    CIDB_CRS_NUMBER("CIDB CRS number", 0.85,
            "\\bcrs (?:number|no\\b\\.?)", "contractor registration (?:number|no\\b\\.?)",
            "\\bcidb (?:registration |crs )?(?:number|no\\b\\.?)"),
    CIDB_GRADING("CIDB grading", 0.8, "\\bcidb (?:grading|grade|designation|class)", "grading designation"),
    UIF_NUMBER("UIF reference number", 0.85,
            "unemployment insurance fund(?: \\(uif\\))?(?: reference| registration)?(?: number| no\\b\\.?)?",
            "\\buif (?:reference |registration |ref\\.? )?(?:number|no\\b\\.?)", "\\buif\\b"),
    COMPENSATION_FUND_NUMBER("Compensation Fund number", 0.85,
            "compensation (?:fund|commissioner)(?: \\(coida\\))?(?: registration)?(?: number| no\\b\\.?)?",
            "\\bcoida (?:registration )?(?:number|no\\b\\.?)", "workmen'?s compensation(?: number| no\\b\\.?)?"),
    INCOME_TAX_NUMBER("Income tax reference number", 0.85,
            "(?:income )?tax reference (?:number|no\\b\\.?)", "income tax (?:number|no\\b\\.?)", "\\btax (?:number|no\\b\\.?)"),
    REGISTRATION_NUMBER("Company registration number", 0.85,
            "(?:company|enterprise|cipc|business) registration (?:number|no\\b\\.?)", "\\bregistration (?:number|no\\b\\.?)"),
    CONTACT_NAME("Contact person", 0.75,
            "full name of (?:the )?(?:bidder|tenderer)(?:'?s)? (?:or|/) (?:his|her|its|their)(?: or her)? representative",
            "contact person(?:'?s name)?", "name of (?:the )?contact person", "\\bcontact name"),
    COMPANY_NAME("Company name", 0.85,
            "name of (?:the )?(?:bidder|tenderer|company|enterprise|supplier|service provider|bidding entity)",
            "(?:registered|legal|company) name", "\\bbidder'?s name", "\\bname of firm"),
    TRADING_NAME("Trading name", 0.85, "trading (?:as|name)"),
    PHYSICAL_ADDRESS("Physical address", 0.8, "(?:physical|street|business|residential) address"),
    POSTAL_ADDRESS("Postal address", 0.85, "postal address"),
    CONTACT_EMAIL("E-mail address", 0.8, "\\be-? ?mail(?: address)?"),
    CONTACT_PHONE("Telephone number", 0.75,
            "(?:telephone|tel\\.?|cell ?phone|cellular|mobile|contact) (?:number|no\\b\\.?)", "\\btel\\b"),
    BBBEE_LEVEL("B-BBEE status level", 0.8,
            "b-?bbee (?:status )?level(?: of contribution)?", "(?:status )?level of contribution"),
    BBBEE_CERTIFICATE_NUMBER("B-BBEE certificate number", 0.6, "(?:b-?bbee |verification )?certificate (?:number|no\\b\\.?)"),
    BBBEE_EXPIRY("B-BBEE certificate expiry", 0.5, "(?:certificate )?(?:expiry|expiration) date", "\\bvalid until"),
    BBBEE_AGENCY("B-BBEE verification agency", 0.7, "verification (?:agency|agent)", "\\bsanas accredited");

    private final String label;
    private final double confidence;
    private final List<Pattern> patterns;

    FormField(String label, double confidence, String... patterns) {
        this.label = label;
        this.confidence = confidence;
        this.patterns = java.util.Arrays.stream(patterns).map(Pattern::compile).toList();
    }

    public String label() {
        return label;
    }

    public double confidence() {
        return confidence;
    }

    List<Pattern> patterns() {
        return patterns;
    }
}
