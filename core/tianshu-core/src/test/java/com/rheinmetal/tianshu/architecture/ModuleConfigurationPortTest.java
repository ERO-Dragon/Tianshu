package com.rheinmetal.tianshu.architecture;

import com.rheinmetal.tianshu.function.intake.settings.IntakeConfiguration;
import com.rheinmetal.tianshu.function.auxilium.storage.AuxiliumStorageConfiguration;
import com.rheinmetal.tianshu.function.weaving.settings.WeavingConfiguration;
import com.rheinmetal.tianshu.function.echo.settings.EchoConfiguration;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ModuleConfigurationPortTest {
    @Test
    void configurationPortsExposeNoMutationOrDiscoveryOperations() {
        for (Class<?> port : List.of(
                IntakeConfiguration.class,
                WeavingConfiguration.class,
                EchoConfiguration.class,
                AuxiliumStorageConfiguration.class
        )) {
            assertTrue(port.isInterface(), () -> port.getName() + " must be an interface");
            assertTrue(Arrays.stream(port.getMethods()).noneMatch(method ->
                            method.getName().startsWith("set")
                                    || method.getName().equals("save")
                                    || method.getName().startsWith("find")
                                    || method.getName().startsWith("scan")),
                    () -> port.getName() + " must remain a read-only value/path port");
        }
    }
}
