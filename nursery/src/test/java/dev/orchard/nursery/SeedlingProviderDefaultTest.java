package dev.orchard.nursery;

import dev.orchard.core.model.Seedling;
import dev.orchard.vine.CommandRunner;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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
        Method[] threeArgOverloads = Arrays.stream(SeedlingProvider.class.getDeclaredMethods())
            .filter(m -> m.getName().equals("verifyDevcontainerCli"))
            .filter(m -> m.getParameterCount() == 3)
            .toArray(Method[]::new);

        assertThat(threeArgOverloads).hasSize(1);

        Method survivor = threeArgOverloads[0];
        assertThat(Modifier.isStatic(survivor.getModifiers())).isTrue();
        assertThat(survivor.getParameterTypes())
            .containsExactly(Seedling.class, String.class, CommandRunner.class);
    }
}
