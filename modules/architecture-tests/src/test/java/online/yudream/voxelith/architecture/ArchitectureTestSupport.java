package online.yudream.voxelith.architecture;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

/**
 * 架构测试共享的类导入与上下文清单。
 */
final class ArchitectureTestSupport {

    /** 全部限界上下文的根包（shared-kernel 不属于上下文，不受四层约束）。 */
    static final java.util.List<String> CONTEXTS = java.util.List.of(
            "resource", "world", "runtime", "bake", "tile",
            "lod", "orchestration", "marker", "maps"
    );

    private ArchitectureTestSupport() {
    }

    static JavaClasses importAll() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("online.yudream.voxelith..");
    }

    /** 该上下文当前是否已有任何类（决定是否对其评估规则，规避空集评估的 ArchUnit 内部 NPE）。 */
    static boolean hasAnyClass(JavaClasses classes, String context) {
        return classes.stream()
                .anyMatch(c -> c.getPackageName().startsWith("online.yudream.voxelith." + context + "."));
    }
}
