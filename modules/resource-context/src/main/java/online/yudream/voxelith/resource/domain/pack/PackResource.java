package online.yudream.voxelith.resource.domain.pack;

/**
 * 资源包中读取到的单条资源原始字节。
 */
public record PackResource(byte[] bytes) {

    public String asUtf8() {
        return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
    }
}
