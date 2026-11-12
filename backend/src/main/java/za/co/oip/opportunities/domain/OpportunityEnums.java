package za.co.oip.opportunities.domain;

/** Opportunity enumerations. Values mirror the reference_codes seed data and CHECK constraints. */
public final class OpportunityEnums {

    private OpportunityEnums() {
    }

    public enum EventType {
        TENDER, RFQ, RFP, RFI, EOI, ESD_PROGRAMME, SUPPLIER_INVITATION, PROCUREMENT_PLAN, EXPRESSION_OF_INTEREST,
        /** State land (e.g. state farms) offered for lease or allocation. */
        LAND_LEASE,
        OTHER
    }

    public enum OpportunityStatus {
        OPEN, CLOSED, AWARDED, CANCELLED, EXPIRED, UNKNOWN;

        public boolean isFinal() {
            return this == CLOSED || this == AWARDED || this == CANCELLED || this == EXPIRED;
        }
    }

    /** How bids must be submitted. NOT_SPECIFIED when the source does not say. */
    public enum SubmissionMethod { ELECTRONIC, PHYSICAL, ELECTRONIC_OR_PHYSICAL, NOT_SPECIFIED }

    /** Where a derived value came from. */
    public enum Provenance { SOURCE, SOURCE_POLICY, DOCUMENT, AI, HUMAN }

    public enum ProcurementCategory { GOODS, SERVICES, WORKS, CONSULTING_SERVICES, DISPOSAL, NOT_SPECIFIED }

    public enum DocumentType {
        TENDER_DOCUMENT, SCOPE_OF_WORK, PRICING_SCHEDULE, BILL_OF_QUANTITIES, TECHNICAL_SPECIFICATION,
        RETURNABLE_SCHEDULE, CONTRACT_CONDITIONS, SITE_BRIEFING_NOTICE, ADDENDUM, ADVERT, OTHER
    }
}
