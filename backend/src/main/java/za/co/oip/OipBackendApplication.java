package za.co.oip;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class OipBackendApplication {

    public static void main(String[] args) {
        SpringApplication.run(OipBackendApplication.class, args);
    }
}
