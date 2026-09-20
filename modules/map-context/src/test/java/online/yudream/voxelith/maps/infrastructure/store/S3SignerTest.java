package online.yudream.voxelith.maps.infrastructure.store;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class S3SignerTest {

    @Test
    void authorizationIsDeterministicForFixedClockAndEmptyPayload() {
        Instant now = Instant.parse("2013-05-24T00:00:00Z");
        URI uri = URI.create("https://examplebucket.s3.amazonaws.com/test.txt");
        String auth = S3Signer.authorization(
                "GET", uri, now, new byte[0], null,
                "us-east-1", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY");
        assertThat(auth).startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20130524/us-east-1/s3/aws4_request, ");
        assertThat(auth).contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date");
        assertThat(auth).contains("Signature=");
        // 同一输入两次签名必须一致
        assertThat(S3Signer.authorization(
                "GET", uri, now, new byte[0], null,
                "us-east-1", "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY"))
                .isEqualTo(auth);
    }

    @Test
    void putWithContentTypeIncludesContentTypeInSignedHeaders() {
        Instant now = Instant.parse("2013-05-24T00:00:00Z");
        URI uri = URI.create("http://127.0.0.1:9000/maps/demo/atlas.png");
        byte[] body = "png".getBytes(StandardCharsets.UTF_8);
        String auth = S3Signer.authorization(
                "PUT", uri, now, body, "image/png",
                "us-east-1", "minio", "minio123");
        assertThat(auth).contains("SignedHeaders=content-type;host;x-amz-content-sha256;x-amz-date");
        assertThat(S3Signer.hostHeader(uri)).isEqualTo("127.0.0.1:9000");
        assertThat(S3Signer.sha256Hex(body)).hasSize(64);
    }

    @Test
    void parseListKeysExtractsAllKeys() {
        String xml = """
                <?xml version="1.0"?>
                <ListBucketResult>
                  <Contents><Key>demo/manifest.json</Key></Contents>
                  <Contents><Key>demo/tiles/hires/0/0.glb</Key></Contents>
                </ListBucketResult>
                """;
        assertThat(S3Signer.parseListKeys(xml)).containsExactly(
                "demo/manifest.json", "demo/tiles/hires/0/0.glb");
        assertThat(S3Signer.parseListKeys("<ListBucketResult/>")).isEqualTo(List.of());
    }

    /**
     * S3 侧 Watch 等价物的数据来源：ListObjectsV2 的 ETag/大小/修改时间。
     * 轮询比对全靠这几个字段，解析错一个字段就会「永远检测不到变更」或「每次都当变更」。
     */
    @Test
    void parseListEntriesExtractsMetadata() {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ListBucketResult>
                  <Name>voxelith</Name>
                  <Contents>
                    <Key>region/r.0.0.mca</Key>
                    <LastModified>2026-09-20T03:14:15.000Z</LastModified>
                    <ETag>&quot;5d41402abc4b2a76b9719d911017c592&quot;</ETag>
                    <Size>8192</Size>
                  </Contents>
                  <Contents>
                    <Key>region/r.-1.2.mca</Key>
                    <LastModified>2026-09-20T04:00:00.000Z</LastModified>
                    <ETag>"d41d8cd98f00b204e9800998ecf8427e"</ETag>
                    <Size>0</Size>
                  </Contents>
                </ListBucketResult>
                """;

        var entries = S3Signer.parseListEntries(xml);
        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).key()).isEqualTo("region/r.0.0.mca");
        assertThat(entries.get(0).size()).isEqualTo(8192);
        assertThat(entries.get(0).version()).isEqualTo("5d41402abc4b2a76b9719d911017c592");
        assertThat(entries.get(0).lastModifiedEpochMs())
                .isEqualTo(Instant.parse("2026-09-20T03:14:15.000Z").toEpochMilli());
        assertThat(entries.get(1).key()).isEqualTo("region/r.-1.2.mca");
        assertThat(entries.get(1).size()).isZero();

        assertThat(S3Signer.parseListEntries("<ListBucketResult/>")).isEmpty();
        assertThat(S3Signer.parseListEntries("<Contents><Key>k</Key></Contents>").get(0).version())
                .isEmpty();
    }

    @Test
    void canonicalQuerySortsAndKeepsEmptyValues() {
        URI uri = URI.create("http://127.0.0.1:9000/maps?prefix=demo/&list-type=2");
        assertThat(S3Signer.canonicalQuery(uri)).isEqualTo("list-type=2&prefix=demo/");
        assertThat(S3Signer.canonicalPath(uri)).isEqualTo("/maps");
    }
}
