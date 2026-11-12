package za.co.oip.procurement.domain;

/** Enumerations shared by the procurement organisation model. Values mirror database CHECK constraints. */
public final class ProcurementEnums {

    private ProcurementEnums() {
    }

    public enum OrganisationType {
        NATIONAL_DEPARTMENT, PROVINCIAL_DEPARTMENT, MUNICIPALITY, MUNICIPAL_ENTITY, PUBLIC_ENTITY,
        STATE_OWNED_COMPANY, EDUCATIONAL_INSTITUTION, WATER_BOARD, ENERGY_ENTITY, TRANSPORT_ENTITY,
        HEALTHCARE_INSTITUTION, MINING_COMPANY, PRIVATE_COMPANY, AGGREGATOR, OTHER
    }

    public enum Sector { GOVERNMENT, MINING, PRIVATE, AGGREGATOR }

    public enum WebsiteVerification { UNVERIFIED, VERIFIED, UNREACHABLE, BLOCKED }

    public enum ChannelType {
        PUBLIC_TENDER_LISTING, EOI_ESD_PAGE, SUPPLIER_REGISTRATION, SUPPLIER_PLATFORM,
        LOGIN_RFQ_SYSTEM, PROCUREMENT_CONTACT, PUBLIC_API, NO_PUBLIC_FEED_FOUND
    }

    public enum ChannelVerification { PENDING_VERIFICATION, VERIFIED, UNREACHABLE, BLOCKED }

    public enum PlatformType {
        GOVERNMENT_REGISTRY, GOVERNMENT_PORTAL, SAP_ARIBA, COUPA, B1SA, SALESFORCE_PORTAL,
        FABRIC_WORKFLOWS, DYNAMICS_365, CUSTOM_PORTAL, OTHER
    }
}
