package dev.orchard.api.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class DevServerProfileTest {

    @Test
    void devServerProfile_keepsKnotholeBesideItsH2Database() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application-devserver.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties.getProperty("spring.datasource.url"))
            .startsWith("jdbc:h2:file:${user.home}/.orchard/data/");
        assertThat(properties.getProperty("orchard.knothole.vault-path"))
            .isEqualTo("${user.home}/.orchard/data/knothole/vault");
        assertThat(properties.getProperty("orchard.knothole.master-key-path"))
            .isEqualTo("${user.home}/.orchard/data/knothole/master.key");
    }
}
