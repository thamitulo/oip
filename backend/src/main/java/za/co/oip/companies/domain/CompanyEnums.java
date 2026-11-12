package za.co.oip.companies.domain;

/** Enumerations used by the company profile. */
public final class CompanyEnums {

    private CompanyEnums() {
    }

    public enum BusinessSize { MICRO, SMALL, MEDIUM, LARGE }

    public enum BbbeeLevel { LEVEL_1, LEVEL_2, LEVEL_3, LEVEL_4, LEVEL_5, LEVEL_6, LEVEL_7, LEVEL_8, NON_COMPLIANT, UNKNOWN }

    public enum EnterpriseClassification { EME, QSE, GENERIC, UNKNOWN }

    /**
     * How far a company claim has been substantiated. Users can only set SELF_DECLARED or
     * DOCUMENT_PROVIDED; VERIFIED is reserved for a platform verification process.
     */
    public enum VerificationStatus { SELF_DECLARED, DOCUMENT_PROVIDED, VERIFIED, EXPIRED }

    public enum RegistrationType { CSD, CIDB, SUPPLIER_PORTAL, PROFESSIONAL_BODY, TAX, OTHER }

    public enum ReferenceSector { GOVERNMENT, MINING, PRIVATE, OTHER }
}
