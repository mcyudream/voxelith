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

    @Test
    void canonicalQuerySortsAndKeepsEmptyValues() {
        URI uri = URI.create("http://127.0.0.1:9000/maps?prefix=demo/&list-type=2");
        assertThat(S3Signer.canonicalQuery(uri)).isEqualTo("list-type=2&prefix=demo/");
        assertThat(S3Signer.canonicalPath(uri)).isEqualTo("/maps");
    }
}
