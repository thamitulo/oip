package za.co.oip.opportunities;

import org.springframework.stereotype.Component;
import za.co.oip.crawling.adapter.NormalisedOpportunity;
import za.co.oip.procurement.domain.ProcurementEnums.OrganisationType;
import za.co.oip.procurement.domain.ProcurementEnums.Sector;
import za.co.oip.procurement.domain.ProcurementOrganisation;
import za.co.oip.procurement.domain.ProcurementOrganisationRepository;
import za.co.oip.sources.domain.Source;
import za.co.oip.sources.domain.SourceEnums.AdapterType;

import java.util.Locale;
import java.util.UUID;

/**
 * Determines which procuring organisation an opportunity belongs to. Single-organisation sources (a
 * mining company's tender page) use the source's organisation; multi-organisation sources such as eTenders
 * name the procuring entity per record, which is matched or created here.
 */
@Component
public class OrganisationResolver {

    private final ProcurementOrganisationRepository repository;

    public OrganisationResolver(ProcurementOrganisationRepository repository) {
        this.repository = repository;
    }

    public UUID resolve(Source source, NormalisedOpportunity item) {
        String name = item.organisationName();
        boolean perRecordOrganisation = source.getAdapterType() == AdapterType.ETENDERS_OCDS
                || source.getAdapterType() == AdapterType.ETENDERS_PORTAL;
        if (!perRecordOrganisation || name == null || name.isBlank()) {
            return source.getOrganisationId();
        }
        String externalSource = source.getAdapterType().name();
        if (item.organisationExternalId() != null) {
            var byExternal = repository.findByExternalSourceAndExternalId(externalSource, item.organisationExternalId());
            if (byExternal.isPresent()) {
                return byExternal.get().getId();
            }
        }
        var byName = repository.findByNameIgnoreCase(name.trim());
        if (byName.isPresent()) {
            return byName.get().getId();
        }
        ProcurementOrganisation org = new ProcurementOrganisation();
        org.setName(name.trim());
        org.setSector(source.getSector() == Sector.AGGREGATOR ? Sector.GOVERNMENT : source.getSector());
        org.setOrganisationType(inferType(name));
        org.setExternalSource(item.organisationExternalId() == null ? null : externalSource);
        org.setExternalId(item.organisationExternalId());
        org.setNotes("Created automatically from " + source.getName()
                + ". Organisation type inferred from the name; verify before relying on it.");
        return repository.save(org).getId();
    }

    /** Keyword-based guess; labelled as inferred in the organisation notes. */
    static OrganisationType inferType(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.contains("municipality") || n.contains("metropolitan") || n.contains("metro ")) return OrganisationType.MUNICIPALITY;
        if (n.contains("university") || n.contains("college") || n.contains("tvet")) return OrganisationType.EDUCATIONAL_INSTITUTION;
        if (n.contains("water board") || n.contains("water utility") || n.endsWith(" water")) return OrganisationType.WATER_BOARD;
        if (n.contains("hospital") || n.contains("health")) return OrganisationType.HEALTHCARE_INSTITUTION;
        if (n.contains("(soc)") || n.contains("soc ltd") || n.contains("(pty) ltd")) return OrganisationType.STATE_OWNED_COMPANY;
        if (n.contains("department") || n.startsWith("dept")) return OrganisationType.NATIONAL_DEPARTMENT;
        return OrganisationType.OTHER;
    }
}
