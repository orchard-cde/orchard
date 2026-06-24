package dev.orchard.trellis.config;

import dev.orchard.nursery.DevcontainerCli;
import dev.orchard.nursery.DevcontainerCliConfig;
import dev.orchard.nursery.FruitGrower;
import dev.orchard.nursery.ProviderRegistry;
import dev.orchard.nursery.aws.DefaultEc2Operations;
import dev.orchard.nursery.aws.Ec2Config;
import dev.orchard.nursery.aws.Ec2InstanceWaiter;
import dev.orchard.nursery.aws.Ec2Operations;
import dev.orchard.nursery.aws.Ec2SeedlingProvider;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import dev.orchard.nursery.azure.AzureConfig;
import dev.orchard.nursery.azure.AzureVmSeedlingProvider;
import dev.orchard.nursery.gcp.ComputeConfig;
import dev.orchard.nursery.gcp.ComputeSeedlingProvider;
import dev.orchard.nursery.qemu.QemuConfig;
import dev.orchard.nursery.qemu.QemuPlatformDefaults;
import dev.orchard.nursery.qemu.QemuSeedlingProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

@Configuration
public class NurseryConfig {

    private static final Logger log = LoggerFactory.getLogger(NurseryConfig.class);

    // --- QEMU (always available) ---

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
            .sshPublicKey(props.getSshPublicKey())
            .serialOutput(props.getSerialOutput())
            .autoProvision(props.isAutoProvision())
            .sshKeyPath(Path.of(props.getSshKeyPath()))
            .build();
    }

    // --- AWS EC2 ---

    @Bean
    @ConditionalOnProperty(prefix = "orchard.nursery.aws", name = "region")
    @ConfigurationProperties(prefix = "orchard.nursery.aws")
    public AwsConfigProperties awsConfigProperties() {
        return new AwsConfigProperties();
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchard.nursery.aws", name = "region")
    public Ec2Config ec2Config(AwsConfigProperties props) {
        return new Ec2Config(
            props.getRegion(),
            props.getAmiId(),
            props.getKeyPairName(),
            props.getSecurityGroupId(),
            props.getSubnetId(),
            props.getInstanceTypeMapping(),
            Ec2Config.IpMode.parse(props.getIpMode()),
            resolveSshKeyPath(props.getSshKeyPath())
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchard.nursery.aws", name = "region")
    public Ec2Client ec2Client(Ec2Config config) {
        return Ec2Client.builder()
            .region(Region.of(config.region()))
            .httpClient(UrlConnectionHttpClient.create())
            .build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchard.nursery.aws", name = "region")
    public Ec2Operations ec2Operations(Ec2Client ec2Client) {
        return new DefaultEc2Operations(ec2Client);
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchard.nursery.aws", name = "region")
    public Ec2InstanceWaiter ec2InstanceWaiter(Ec2Operations operations, Ec2Config config) {
        return new Ec2InstanceWaiter(
            operations,
            Duration.ofMinutes(3),    // running timeout
            Duration.ofSeconds(5),    // running poll interval
            Duration.ofMinutes(3),    // ssh timeout
            Duration.ofSeconds(5),    // ssh poll interval
            Ec2InstanceWaiter.shellSshProbe(config.sshKeyPath())
        );
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchard.nursery.aws", name = "region")
    public Ec2SeedlingProvider ec2SeedlingProvider(
            Ec2Config config, Ec2Operations operations, Ec2InstanceWaiter waiter,
            DevcontainerCliConfig devcontainerCliConfig) {
        return new Ec2SeedlingProvider(config, operations, waiter, devcontainerCliConfig);
    }

    // --- GCP Compute ---

    @Bean
    @ConditionalOnProperty(prefix = "orchard.nursery.gcp", name = "project")
    @ConfigurationProperties(prefix = "orchard.nursery.gcp")
    public GcpConfigProperties gcpConfigProperties() {
        return new GcpConfigProperties();
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchard.nursery.gcp", name = "project")
    public ComputeConfig computeConfig(GcpConfigProperties props) {
        return new ComputeConfig(
            props.getProject(),
            props.getZone(),
            props.getMachineTypeMapping(),
            props.getImageFamily(),
            props.getImageProject()
        );
    }

    // --- Azure VM ---

    @Bean
    @ConditionalOnProperty(prefix = "orchard.nursery.azure", name = "subscription-id")
    @ConfigurationProperties(prefix = "orchard.nursery.azure")
    public AzureConfigProperties azureConfigProperties() {
        return new AzureConfigProperties();
    }

    @Bean
    @ConditionalOnProperty(prefix = "orchard.nursery.azure", name = "subscription-id")
    public AzureConfig azureConfig(AzureConfigProperties props) {
        return new AzureConfig(
            props.getSubscriptionId(),
            props.getResourceGroup(),
            props.getLocation(),
            props.getVmSizeMapping()
        );
    }

    // --- Provider Registry ---

    @Bean
    public ProviderRegistry providerRegistry(
            @Value("${orchard.nursery.provider:qemu}") String defaultProvider,
            QemuConfig qemuConfig,
            DevcontainerCliConfig devcontainerCliConfig,
            org.springframework.beans.factory.ObjectProvider<Ec2SeedlingProvider> ec2SeedlingProvider,
            org.springframework.beans.factory.ObjectProvider<ComputeConfig> computeConfig,
            org.springframework.beans.factory.ObjectProvider<AzureConfig> azureConfig) {

        ProviderRegistry registry = new ProviderRegistry();

        // Always register QEMU; reattach any VMs that survived the previous server run
        QemuSeedlingProvider qemuProvider = new QemuSeedlingProvider(qemuConfig, devcontainerCliConfig);
        qemuProvider.reattachSurvivingVms();
        registry.register(qemuProvider);
        log.info("Registered seedling provider: {}", qemuProvider.getProviderId());

        // Conditionally register AWS EC2 — Spring instantiates and manages the bean lifecycle.
        ec2SeedlingProvider.ifAvailable(provider -> {
            registry.register(provider);
            log.info("Registered seedling provider: {}", provider.getProviderId());
        });

        // Conditionally register GCP Compute
        computeConfig.ifAvailable(config -> {
            ComputeSeedlingProvider gcpProvider = new ComputeSeedlingProvider(config);
            registry.register(gcpProvider);
            log.info("Registered seedling provider: {}", gcpProvider.getProviderId());
        });

        // Conditionally register Azure VM
        azureConfig.ifAvailable(config -> {
            AzureVmSeedlingProvider azureProvider = new AzureVmSeedlingProvider(config);
            registry.register(azureProvider);
            log.info("Registered seedling provider: {}", azureProvider.getProviderId());
        });

        // Set the default provider based on configuration
        String resolvedDefault = resolveProviderId(defaultProvider);
        if (registry.hasProvider(resolvedDefault)) {
            registry.setDefault(resolvedDefault);
            log.info("Default seedling provider: {}", resolvedDefault);
        } else {
            log.warn("Configured default provider '{}' not available, falling back to qemu-local", defaultProvider);
        }

        log.info("Provider registry initialized with {} providers: {}",
            registry.getProviderIds().size(), registry.getProviderIds());
        return registry;
    }

    @Bean
    public DevcontainerCli devcontainerCli(DevcontainerCliConfig config) {
        return new DevcontainerCli(config);
    }

    @Bean
    public FruitGrower fruitGrower(
            DevcontainerCli devcontainerCli,
            @Value("${orchard.nursery.use-devcontainer-cli:true}") boolean useDevcontainerCli,
            org.springframework.context.ApplicationEventPublisher events) {
        return new FruitGrower(devcontainerCli, useDevcontainerCli, events);
    }

    private static java.nio.file.Path resolveSshKeyPath(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String expanded = raw.startsWith("~/") || raw.equals("~")
            ? System.getProperty("user.home") + raw.substring(1)
            : raw;
        return java.nio.file.Path.of(expanded);
    }

    /**
     * Maps short provider names (used in config) to provider IDs.
     */
    private String resolveProviderId(String shortName) {
        return switch (shortName) {
            case "qemu" -> "qemu-local";
            case "aws", "ec2", "aws-ec2" -> "aws-ec2";
            case "gcp", "gce", "gcp-compute" -> "gcp-compute";
            case "azure", "azure-vm" -> "azure-vm";
            default -> shortName;
        };
    }

    // --- Config Properties Classes ---

    public static class QemuConfigProperties {
        private String qemuBinary = QemuPlatformDefaults.defaultQemuBinary().toString();
        private String qemuImgBinary = QemuPlatformDefaults.defaultQemuImgBinary().toString();
        private String baseImagePath = QemuPlatformDefaults.defaultBaseImagePath().toString();
        private String vmStoragePath = QemuPlatformDefaults.defaultVmStoragePath().toString();
        private String cloudInitTemplatePath = "/etc/orchard/cloud-init";
        private int sshPortRangeStart = 49152;
        private int sshPortRangeEnd = 49999;
        private boolean enableKvm = QemuPlatformDefaults.defaultEnableKvm();
        private String sshPublicKey = "";
        private String serialOutput = QemuPlatformDefaults.defaultSerialOutput();
        private boolean autoProvision = true;
        private String sshKeyPath = QemuPlatformDefaults.defaultSshKeyPath().toString();

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

        public String getSshPublicKey() { return sshPublicKey; }
        public void setSshPublicKey(String sshPublicKey) { this.sshPublicKey = sshPublicKey; }

        public String getSerialOutput() { return serialOutput; }
        public void setSerialOutput(String serialOutput) { this.serialOutput = serialOutput; }

        public boolean isAutoProvision() { return autoProvision; }
        public void setAutoProvision(boolean autoProvision) { this.autoProvision = autoProvision; }

        public String getSshKeyPath() { return sshKeyPath; }
        public void setSshKeyPath(String sshKeyPath) { this.sshKeyPath = sshKeyPath; }
    }

    public static class AwsConfigProperties {
        private String region = "us-east-1";
        private String amiId;
        private String keyPairName;
        private String securityGroupId;
        private String subnetId;
        private Map<Integer, String> instanceTypeMapping = Map.of(
            2, "t3.small",
            4, "t3.medium",
            8, "t3.xlarge"
        );
        private String ipMode = "AUTO";
        private String sshKeyPath;

        public String getRegion() { return region; }
        public void setRegion(String region) { this.region = region; }

        public String getAmiId() { return amiId; }
        public void setAmiId(String amiId) { this.amiId = amiId; }

        public String getKeyPairName() { return keyPairName; }
        public void setKeyPairName(String keyPairName) { this.keyPairName = keyPairName; }

        public String getSecurityGroupId() { return securityGroupId; }
        public void setSecurityGroupId(String securityGroupId) { this.securityGroupId = securityGroupId; }

        public String getSubnetId() { return subnetId; }
        public void setSubnetId(String subnetId) { this.subnetId = subnetId; }

        public Map<Integer, String> getInstanceTypeMapping() { return instanceTypeMapping; }
        public void setInstanceTypeMapping(Map<Integer, String> instanceTypeMapping) { this.instanceTypeMapping = instanceTypeMapping; }

        public String getIpMode() { return ipMode; }
        public void setIpMode(String ipMode) { this.ipMode = ipMode; }

        public String getSshKeyPath() { return sshKeyPath; }
        public void setSshKeyPath(String sshKeyPath) { this.sshKeyPath = sshKeyPath; }
    }

    public static class GcpConfigProperties {
        private String project;
        private String zone = "us-central1-a";
        private Map<Integer, String> machineTypeMapping = Map.of(
            2, "e2-standard-2",
            4, "e2-standard-4",
            8, "e2-standard-8"
        );
        private String imageFamily = "ubuntu-2204-lts";
        private String imageProject = "ubuntu-os-cloud";

        public String getProject() { return project; }
        public void setProject(String project) { this.project = project; }

        public String getZone() { return zone; }
        public void setZone(String zone) { this.zone = zone; }

        public Map<Integer, String> getMachineTypeMapping() { return machineTypeMapping; }
        public void setMachineTypeMapping(Map<Integer, String> machineTypeMapping) { this.machineTypeMapping = machineTypeMapping; }

        public String getImageFamily() { return imageFamily; }
        public void setImageFamily(String imageFamily) { this.imageFamily = imageFamily; }

        public String getImageProject() { return imageProject; }
        public void setImageProject(String imageProject) { this.imageProject = imageProject; }
    }

    public static class AzureConfigProperties {
        private String subscriptionId;
        private String resourceGroup;
        private String location = "eastus";
        private Map<Integer, String> vmSizeMapping = Map.of(
            2, "Standard_B2s",
            4, "Standard_B4ms",
            8, "Standard_D8s_v3"
        );

        public String getSubscriptionId() { return subscriptionId; }
        public void setSubscriptionId(String subscriptionId) { this.subscriptionId = subscriptionId; }

        public String getResourceGroup() { return resourceGroup; }
        public void setResourceGroup(String resourceGroup) { this.resourceGroup = resourceGroup; }

        public String getLocation() { return location; }
        public void setLocation(String location) { this.location = location; }

        public Map<Integer, String> getVmSizeMapping() { return vmSizeMapping; }
        public void setVmSizeMapping(Map<Integer, String> vmSizeMapping) { this.vmSizeMapping = vmSizeMapping; }
    }
}
