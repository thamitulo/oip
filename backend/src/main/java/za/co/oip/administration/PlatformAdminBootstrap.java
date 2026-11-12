package za.co.oip.administration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import za.co.oip.configuration.OipProperties;
import za.co.oip.identity.User;
import za.co.oip.identity.UserRepository;
import za.co.oip.tenancy.TenantService;

/**
 * Creates the first platform administrator from {@code OIP_BOOTSTRAP_ADMIN_*} environment variables,
 * only when no platform administrator exists yet. There are no default credentials.
 */
@Component
public class PlatformAdminBootstrap implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(PlatformAdminBootstrap.class);

    private final OipProperties properties;
    private final UserRepository userRepository;
    private final TenantService tenantService;
    private final PasswordEncoder passwordEncoder;

    public PlatformAdminBootstrap(OipProperties properties, UserRepository userRepository,
                                  TenantService tenantService, PasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.tenantService = tenantService;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        OipProperties.Bootstrap cfg = properties.bootstrap();
        if (cfg == null || isBlank(cfg.adminEmail()) || isBlank(cfg.adminPassword())) {
            return;
        }
        if (userRepository.existsByPlatformAdminTrue()) {
            return;
        }
        if (cfg.adminPassword().length() < 12) {
            log.error("OIP_BOOTSTRAP_ADMIN_PASSWORD must be at least 12 characters; platform admin not created");
            return;
        }
        User admin = userRepository.findByEmailIgnoreCase(cfg.adminEmail()).orElseGet(() -> {
            User u = new User();
            u.setEmail(cfg.adminEmail().trim().toLowerCase());
            u.setFullName("Platform Administrator");
            u.setPasswordHash(passwordEncoder.encode(cfg.adminPassword()));
            return userRepository.save(u);
        });
        admin.setPlatformAdmin(true);
        userRepository.save(admin);
        tenantService.createTenant(isBlank(cfg.adminTenantName()) ? "Platform Operations" : cfg.adminTenantName(),
                admin.getId());
        log.info("Bootstrap platform administrator created for {}", admin.getEmail());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
