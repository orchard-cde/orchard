package dev.orchard.fence;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class FenceApplication {
    public static void main(String[] args) {
        SpringApplication.run(FenceApplication.class, args);
    }
}
