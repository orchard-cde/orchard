package dev.orchard.canopy.config;

import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.SeedlingProvider;
import dev.orchard.nursery.qemu.QemuConfig;
import dev.orchard.nursery.qemu.QemuSeedlingProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

@Configuration
public class CanopyConfig {

    @Bean
    @ConfigurationProperties(prefix = "orchard.qemu")
    public CanopyQemuProperties canopyQemuProperties() {
        return new CanopyQemuProperties();
    }

    @Bean
    public QemuConfig qemuConfig(CanopyQemuProperties props) {
        return QemuConfig.builder()
            .qemuBinary(Path.of(props.getQemuBinary()))
            .qemuImgBinary(Path.of(props.getQemuImgBinary()))
            .baseImagePath(Path.of(props.getBaseImagePath()))
            .vmStoragePath(Path.of(props.getVmStoragePath()))
            .cloudInitTemplatePath(Path.of(props.getCloudInitTemplatePath()))
            .sshPortRangeStart(props.getSshPortRangeStart())
            .sshPortRangeEnd(props.getSshPortRangeEnd())
            .enableKvm(props.isEnableKvm())
            .sshPublicKey(props.getSshPublicKey())
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

    public static class CanopyQemuProperties {
        private String qemuBinary = "/usr/bin/qemu-system-x86_64";
        private String qemuImgBinary = "/usr/bin/qemu-img";
        private String baseImagePath = "/var/lib/orchard/images/base.qcow2";
        private String vmStoragePath = "/var/lib/orchard/vms";
        private String cloudInitTemplatePath = "/etc/orchard/cloud-init";
        private int sshPortRangeStart = 10022;
        private int sshPortRangeEnd = 10122;
        private boolean enableKvm = true;
        private String sshPublicKey = "";

        public String getQemuBinary() { return qemuBinary; }
        public void setQemuBinary(String v) { this.qemuBinary = v; }
        public String getQemuImgBinary() { return qemuImgBinary; }
        public void setQemuImgBinary(String v) { this.qemuImgBinary = v; }
        public String getBaseImagePath() { return baseImagePath; }
        public void setBaseImagePath(String v) { this.baseImagePath = v; }
        public String getVmStoragePath() { return vmStoragePath; }
        public void setVmStoragePath(String v) { this.vmStoragePath = v; }
        public String getCloudInitTemplatePath() { return cloudInitTemplatePath; }
        public void setCloudInitTemplatePath(String v) { this.cloudInitTemplatePath = v; }
        public int getSshPortRangeStart() { return sshPortRangeStart; }
        public void setSshPortRangeStart(int v) { this.sshPortRangeStart = v; }
        public int getSshPortRangeEnd() { return sshPortRangeEnd; }
        public void setSshPortRangeEnd(int v) { this.sshPortRangeEnd = v; }
        public boolean isEnableKvm() { return enableKvm; }
        public void setEnableKvm(boolean v) { this.enableKvm = v; }
        public String getSshPublicKey() { return sshPublicKey; }
        public void setSshPublicKey(String v) { this.sshPublicKey = v; }
    }
}
