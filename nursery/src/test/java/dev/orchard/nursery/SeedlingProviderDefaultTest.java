package dev.orchard.nursery;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class SeedlingProviderDefaultTest {

    @Test
    void seedlingProvider_noLongerHandsOutAnSshBackedDefault() {
        // A provider must not be born SSH-shaped: reaching a substrate is the GroveProvider's
        // business now, via Vine. See issues #86 and #215.
        boolean hasTwoArgDefault = Arrays.stream(SeedlingProvider.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("verifyDevcontainerCli"))
            .anyMatch(m -> m.getParameterCount() == 2 && m.isDefault());

        assertThat(hasTwoArgDefault).isFalse();
    }

    @Test
    void theExplicitThreeArgOverloadSurvives() {
        boolean hasStaticThreeArg = Arrays.stream(SeedlingProvider.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("verifyDevcontainerCli"))
            .anyMatch(m -> m.getParameterCount() == 3);

        assertThat(hasStaticThreeArg).isTrue();
    }
}
