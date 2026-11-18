package za.co.oip.companies.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface CompanyDirectorRepository extends JpaRepository<CompanyDirector, UUID> {

    List<CompanyDirector> findByCompanyProfileIdOrderByCreatedAtAsc(UUID companyProfileId);
}
