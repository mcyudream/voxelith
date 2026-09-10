package online.yudream.voxelith.lod.infrastructure.heightfield;

import online.yudream.voxelith.lod.application.HeightfieldStore;
import online.yudream.voxelith.lod.domain.heightfield.Heightfield;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Optional;

/**
 * 高度场二进制落盘：{mapDir}/heightfield.bin（HF01 magic）。
 */
public final class FileHeightfieldStore implements HeightfieldStore {

    private static final byte[] MAGIC = {'H', 'F', '0', '1'};

    private final Path file;

    public FileHeightfieldStore(Path file) {
        this.file = file;
    }

    @Override
    public Optional<Heightfield> load() {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            byte[] magic = in.readNBytes(4);
            if (!Arrays.equals(magic, MAGIC)) {
                throw new IllegalStateException("高度场文件魔数不匹配: " + file);
            }
            int footprint = in.readInt();
            int originX = in.readInt();
            int originZ = in.readInt();
            int width = in.readInt();
            int depth = in.readInt();
            float floorY = in.readFloat();
            int n = Math.multiplyExact(width, depth);
            float[] topY = new float[n];
            for (int i = 0; i < n; i++) {
                topY[i] = in.readFloat();
            }
            int[] rgb = new int[n];
            for (int i = 0; i < n; i++) {
                rgb[i] = in.readInt();
            }
            return Optional.of(Heightfield.restore(footprint, originX, originZ, width, depth, topY, rgb, floorY));
        } catch (IOException e) {
            throw new UncheckedIOException("读取高度场失败: " + file, e);
        }
    }

    @Override
    public void save(Heightfield field) {
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
                out.write(MAGIC);
                out.writeInt(field.footprint());
                out.writeInt(field.originX());
                out.writeInt(field.originZ());
                out.writeInt(field.width());
                out.writeInt(field.depth());
                out.writeFloat(field.floorY());
                float[] topY = field.copyTopY();
                for (float y : topY) {
                    out.writeFloat(y);
                }
                int[] rgb = field.copyRgb();
                for (int color : rgb) {
                    out.writeInt(color);
                }
            }
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("写高度场失败: " + file, e);
        }
    }
}
