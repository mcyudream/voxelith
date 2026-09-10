package online.yudream.voxelith.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * 分层守护：每个限界上下文内部严格四层，依赖只许向内。
 * interfaces / infrastructure → application → domain；domain 不依赖任何外层。
 */
class LayeredArchitectureTest {

    static JavaClasses classes;

    @BeforeAll
    static void importAll() {
        classes = ArchitectureTestSupport.importAll();
    }

    @Test
    @DisplayName("domain 层不得依赖同上下文的外层（application/interfaces/infrastructure）")
    void domainMustNotDependOnOuterLayers() {
        for (String ctx : ArchitectureTestSupport.CONTEXTS) {
            if (!ArchitectureTestSupport.hasAnyClass(classes, ctx)) {
                continue;
            }
            String root = "online.yudream.voxelith." + ctx;
            ArchRule rule = noClasses()
                    .that().resideInAPackage(root + ".domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            root + ".application..",
                            root + ".interfaces..",
                            root + ".infrastructure..")
                    .as("[" + ctx + "] domain 不得依赖外层");
            rule.check(classes);
        }
    }

    @Test
    @DisplayName("application 层不得依赖 interfaces/infrastructure")
    void applicationMustNotDependOnInterfacesOrInfrastructure() {
        for (String ctx : ArchitectureTestSupport.CONTEXTS) {
            if (!ArchitectureTestSupport.hasAnyClass(classes, ctx)) {
                continue;
            }
            String root = "online.yudream.voxelith." + ctx;
            ArchRule rule = noClasses()
                    .that().resideInAPackage(root + ".application..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            root + ".interfaces..",
                            root + ".infrastructure..")
                    .as("[" + ctx + "] application 不得依赖 interfaces/infrastructure");
            rule.check(classes);
        }
    }

    @Test
    @DisplayName("interfaces 层不得依赖 infrastructure 层")
    void interfacesMustNotDependOnInfrastructure() {
        for (String ctx : ArchitectureTestSupport.CONTEXTS) {
            if (!ArchitectureTestSupport.hasAnyClass(classes, ctx)) {
                continue;
            }
            String root = "online.yudream.voxelith." + ctx;
            ArchRule rule = noClasses()
                    .that().resideInAPackage(root + ".interfaces..")
                    .should().dependOnClassesThat()
                    .resideInAPackage(root + ".infrastructure..")
                    .as("[" + ctx + "] interfaces 不得依赖 infrastructure");
            rule.check(classes);
        }
    }

    @Test
    @DisplayName("domain 层零框架依赖（Spring/Jakarta/Gson/SQL 一律禁止）")
    void domainMustBeFrameworkFree() {
        for (String ctx : ArchitectureTestSupport.CONTEXTS) {
            if (!ArchitectureTestSupport.hasAnyClass(classes, ctx)) {
                continue;
            }
            String root = "online.yudream.voxelith." + ctx;
            ArchRule rule = noClasses()
                    .that().resideInAPackage(root + ".domain..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.springframework..",
                            "jakarta..",
                            "com.google.gson..",
                            "java.sql..",
                            "javax.sql..")
                    .as("[" + ctx + "] domain 零框架依赖");
            rule.check(classes);
        }
    }
}
