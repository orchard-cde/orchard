package dev.orchard.nursery.aws;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class Ec2ConfigTest {

    private Ec2Config newConfig(Map<Integer, String> mapping, Ec2Config.IpMode mode) {
        return new Ec2Config(
            "us-east-1",
            "ami-test",
            "orchard-key",
            "sg-test",
            "subnet-test",
            mapping,
            mode,
            Path.of("/tmp/orchard-key")
        );
    }

    @Test
    void resolveInstanceType_returnsMappedValue() {
        Ec2Config config = newConfig(Map.of(2, "t3.small", 4, "t3.medium"), Ec2Config.IpMode.AUTO);

        assertThat(config.resolveInstanceType(2)).isEqualTo("t3.small");
        assertThat(config.resolveInstanceType(4)).isEqualTo("t3.medium");
    }

    @Test
    void resolveInstanceType_unknownCoreCount_fallsBackToT3Small() {
        Ec2Config config = newConfig(Map.of(2, "t3.small"), Ec2Config.IpMode.AUTO);

        assertThat(config.resolveInstanceType(99)).isEqualTo("t3.small");
    }

    @Test
    void ipMode_hasThreeValues() {
        assertThat(Ec2Config.IpMode.values())
            .containsExactlyInAnyOrder(
                Ec2Config.IpMode.AUTO,
                Ec2Config.IpMode.PUBLIC,
                Ec2Config.IpMode.PRIVATE);
    }

    @Test
    void ipMode_valueOf_caseInsensitiveHelperParses() {
        assertThat(Ec2Config.IpMode.parse("auto")).isEqualTo(Ec2Config.IpMode.AUTO);
        assertThat(Ec2Config.IpMode.parse("Public")).isEqualTo(Ec2Config.IpMode.PUBLIC);
        assertThat(Ec2Config.IpMode.parse("PRIVATE")).isEqualTo(Ec2Config.IpMode.PRIVATE);
    }

    @Test
    void ipMode_parse_nullOrBlank_defaultsToAuto() {
        assertThat(Ec2Config.IpMode.parse(null)).isEqualTo(Ec2Config.IpMode.AUTO);
        assertThat(Ec2Config.IpMode.parse("")).isEqualTo(Ec2Config.IpMode.AUTO);
        assertThat(Ec2Config.IpMode.parse("   ")).isEqualTo(Ec2Config.IpMode.AUTO);
    }
}
