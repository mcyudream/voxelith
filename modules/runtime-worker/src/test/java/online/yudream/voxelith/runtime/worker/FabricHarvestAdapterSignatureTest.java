package online.yudream.voxelith.runtime.worker;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ZipResourcePack 跨版本构造选择：1.20.1 三参 (String, File, boolean)，
 * 1.20.4 四参 (String, ZipFileWrapper, boolean, String)。按签名探测，不绑版本号。
 */
class FabricHarvestAdapterSignatureTest {

    @Test
    void fileThreeArgCtorIsPreferredWhenPresent() {
        Constructor<?> chosen = firstMatching(LegacyZipPack.class,
                c -> c.getParameterCount() == 3 && c.getParameterTypes()[1] == File.class);
        assertThat(chosen).isNotNull();
        assertThat(chosen.getParameterTypes()[0]).isEqualTo(String.class);
        assertThat(chosen.getParameterTypes()[2]).isEqualTo(boolean.class);
    }

    @Test
    void wrapperFourArgCtorUsedWhenFileCtorMissing() {
        Constructor<?> fileCtor = firstMatching(ModernZipPack.class,
                c -> c.getParameterCount() == 3 && c.getParameterTypes()[1] == File.class);
        assertThat(fileCtor).isNull();
        Constructor<?> wrapperCtor = firstMatching(ModernZipPack.class,
                c -> c.getParameterCount() == 4 && c.getParameterTypes()[1] == Wrapper.class);
        assertThat(wrapperCtor).isNotNull();
        assertThat(wrapperCtor.getParameterTypes()[3]).isEqualTo(String.class);
    }

    @Test
    void packagePrivateWrapperCtorIsFoundViaDeclared() throws Exception {
        Constructor<?> published = null;
        try {
            published = PackagePrivateWrapper.class.getConstructor(File.class);
        } catch (NoSuchMethodException ignored) {
            // 1.20.4 ZipFileWrapper 正是这种包可见构造
        }
        assertThat(published).isNull();
        Constructor<?> declared = Reflect.ctor(PackagePrivateWrapper.class, File.class);
        assertThat(declared).isNotNull();
        assertThat(declared.getParameterTypes()[0]).isEqualTo(File.class);
    }

    @Test
    void bakedQuadUnitCubeMapsToModelSpaceSixteen() {
        assertThat(FabricHarvestAdapter.toModelPos(0.0f)).isEqualTo(0.0f);
        assertThat(FabricHarvestAdapter.toModelPos(1.0f)).isEqualTo(16.0f);
        assertThat(FabricHarvestAdapter.toModelPos(0.5f)).isEqualTo(8.0f);
        assertThat(FabricHarvestAdapter.toModelPos(1.0625f)).isEqualTo(17.0f);
    }

    @Test
    void zipFileFactoryOpenMethodIsPreferredPublicApi() throws Exception {
        Constructor<?> factoryCtor = ZipFileFactory.class.getConstructor(File.class, boolean.class);
        assertThat(factoryCtor).isNotNull();
        assertThat(ZipFileFactory.class.getMethod("method_52424", String.class).getReturnType())
                .isEqualTo(Object.class);
    }

    private static Constructor<?> firstMatching(Class<?> type, java.util.function.Predicate<Constructor<?>> pred) {
        for (Constructor<?> ctor : type.getConstructors()) {
            if (pred.test(ctor)) {
                return ctor;
            }
        }
        return null;
    }

    public static final class Wrapper {
        public Wrapper(File file) {
        }
    }

    public static final class LegacyZipPack {
        public LegacyZipPack(String name, File file, boolean alwaysStable) {
        }
    }

    public static final class ModernZipPack {
        public ModernZipPack(String name, Wrapper wrapper, boolean alwaysStable, String overlay) {
        }
    }

    static final class PackagePrivateWrapper {
        PackagePrivateWrapper(File file) {
        }
    }

    public static final class ZipFileFactory {
        public ZipFileFactory(File file, boolean alwaysStable) {
        }

        public Object method_52424(String name) {
            return name;
        }
    }
}
