package online.yudream.voxelith.server.config;

import online.yudream.voxelith.maps.domain.ObjectStore;
import online.yudream.voxelith.orchestration.domain.RegionObjectSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 组合根适配器：把发布侧的 {@link ObjectStore}（maps context）接成编排域要的
 * {@link RegionObjectSource}。
 *
 * <p>为什么这层适配必须待在 apps：跨上下文只允许访问对方的 application 层，
 * 让 maps 直接实现 orchestration.domain 的接口、或让 orchestration 直接引用
 * maps.domain 的 ObjectStore 都会破坏架构约束（ArchUnit 会拦）。组合根不属于任何
 * 限界上下文，正是放这类转接的地方。</p>
 *
 * <p>同一个 ObjectStore 既能指向本地目录（FileObjectStore）也能指向 S3，
 * 所以「本地目录也走轮询」是免费的——排查时很有用。</p>
 */
final class ObjectStoreRegionSource implements RegionObjectSource {

    private final ObjectStore store;

    ObjectStoreRegionSource(ObjectStore store) {
        this.store = store;
    }

    @Override
    public List<RegionObject> list(String prefix) {
        List<RegionObject> objects = new ArrayList<>();
        for (ObjectStore.ObjectMeta meta : store.listMeta(prefix)) {
            objects.add(new RegionObject(meta.key(), meta.version(), meta.size(),
                    meta.lastModifiedEpochMs()));
        }
        return List.copyOf(objects);
    }

    @Override
    public Optional<byte[]> get(String key) {
        return store.get(key);
    }
}
