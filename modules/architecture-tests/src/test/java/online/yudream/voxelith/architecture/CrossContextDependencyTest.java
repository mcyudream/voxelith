package online.yudream.voxelith.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 跨限界上下文守护：上下文之间只允许访问对方的 application 层（用例）与 shared-kernel，
 * 禁止直连对方的 domain / interfaces / infrastructure。
 */
class CrossContextDependencyTest {

    static JavaClasses classes;

    @BeforeAll
    static void importAll() {
        classes = ArchitectureTestSupport.importAll();
    }

    @Test
    @DisplayName("任何上下文不得依赖其他上下文的 domain/interfaces/infrastructure")
    void contextsMustOnlyUseOtherContextsApplicationLayer() {
        for (String consumer : ArchitectureTestSupport.CONTEXTS) {
            if (!ArchitectureTestSupport.hasAnyClass(classes, consumer)) {
                continue;
            }
            for (String provider : ArchitectureTestSupport.CONTEXTS) {
                if (consumer.equals(provider)) {
                    continue;
                }
                String providerRoot = "online.yudream.voxelith." + provider;
                ArchRule rule = noClasses()
                        .that().resideInAPackage("online.yudream.voxelith." + consumer + "..")
                        .should().dependOnClassesThat()
                        .resideInAnyPackage(
                                providerRoot + ".domain..",
                                providerRoot + ".interfaces..",
                                providerRoot + ".infrastructure..")
                        .as("[" + consumer + "] 不得直连 [" + provider + "] 的 domain/interfaces/infrastructure");
                rule.check(classes);
            }
        }
    }
}
