package dev.orchard.server.config;

import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.SeedlingProvider;
import dev.orchard.nursery.qemu.QemuConfig;
import dev.orchard.nursery.qemu.QemuSeedlingProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class NurseryConfig {

    @Bean
    @ConfigurationProperties(prefix = "orchard.qemu")
    public QemuConfigProperties qemuConfigProperties() {
        return new QemuConfigProperties();
    }

    @Bean
    public QemuConfig qemuConfig(QemuConfigProperties props) {
        return QemuConfig.builder()
            .qemuBinary(Path.of(props.getQemuBinary()))
            .qemuImgBinary(Path.of(props.getQemuImgBinary()))
            .baseImagePath(Path.of(props.getBaseImagePath()))
            .vmStoragePath(Path.of(props.getVmStoragePath()))
            .cloudInitTemplatePath(Path.of(props.getCloudInitTemplatePath()))
            .sshPortRangeStart(props.getSshPortRangeStart())
            .sshPortRangeEnd(props.getSshPortRangeEnd())
            .enableKvm(props.isEnableKvm())
            .build();
    }

    @Bean
    public SeedlingProvider seedlingProvider(QemuConfig config) {
        return new QemuSeedlingProvider(config);
    }

    @Bean
    public FruitGrower fruitGrower() {
        return new FruitGrower();
    }

    public static class QemuConfigProperties {
        private String qemuBinary = "/usr/bin/qemu-system-x86_64";
        private String qemuImgBinary = "/usr/bin/qemu-img";
        private String baseImagePath = "/var/lib/orchard/images/base.qcow2";
        private String vmStoragePath = "/var/lib/orchard/vms";
        private String cloudInitTemplatePath = "/etc/orchard/cloud-init";
        private int sshPortRangeStart = 10022;
        private int sshPortRangeEnd = 10122;
        private boolean enableKvm = true;

        public String getQemuBinary() { return qemuBinary; }
        public void setQemuBinary(String qemuBinary) { this.qemuBinary = qemuBinary; }

        public String getQemuImgBinary() { return qemuImgBinary; }
        public void setQemuImgBinary(String qemuImgBinary) { this.qemuImgBinary = qemuImgBinary; }

        public String getBaseImagePath() { return baseImagePath; }
        public void setBaseImagePath(String baseImagePath) { this.baseImagePath = baseImagePath; }

        public String getVmStoragePath() { return vmStoragePath; }
        public void setVmStoragePath(String vmStoragePath) { this.vmStoragePath = vmStoragePath; }

        public String getCloudInitTemplatePath() { return cloudInitTemplatePath; }
        public void setCloudInitTemplatePath(String cloudInitTemplatePath) { this.cloudInitTemplatePath = cloudInitTemplatePath; }

        public int getSshPortRangeStart() { return sshPortRangeStart; }
        public void setSshPortRangeStart(int sshPortRangeStart) { this.sshPortRangeStart = sshPortRangeStart; }

        public int getSshPortRangeEnd() { return sshPortRangeEnd; }
        public void setSshPortRangeEnd(int sshPortRangeEnd) { this.sshPortRangeEnd = sshPortRangeEnd; }

        public boolean isEnableKvm() { return enableKvm; }
        public void setEnableKvm(boolean enableKvm) { this.enableKvm = enableKvm; }
    }
}
