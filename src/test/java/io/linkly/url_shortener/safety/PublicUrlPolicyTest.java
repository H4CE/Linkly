package io.linkly.url_shortener.safety;

import io.linkly.url_shortener.exception.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import static org.junit.jupiter.api.Assertions.*;

class PublicUrlPolicyTest {
    private final PublicUrlPolicy policy = new PublicUrlPolicy();

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "0.0.0.0", "10.1.2.3", "172.16.2.3", "192.168.1.1",
            "169.254.169.254", "100.64.0.1", "198.18.0.1", "192.0.2.1", "198.51.100.2", "203.0.113.3",
            "224.0.0.1", "255.255.255.255", "::", "::1", "fc00::1", "fe80::1", "ff02::1",
            "2001:db8::1", "2002:7f00:1::", "64:ff9b::7f00:1", "::ffff:127.0.0.1", "3fff::1"})
    void internalAndSpecialUseAddressesAreRejected(String ip) throws Exception {
        assertFalse(policy.isPublicAddress(InetAddress.getByName(ip)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.1.1.1", "8.8.8.8", "93.184.216.34", "2606:4700:4700::1111", "2001:4860:4860::8888"})
    void globalAddressesAreAllowed(String ip) throws Exception {
        assertTrue(policy.isPublicAddress(InetAddress.getByName(ip)));
    }

    @ParameterizedTest
    @ValueSource(strings = {"file:///etc/passwd", "ftp://example.com/file", "http://user:pass@example.com",
            "http://example.com:8080", "https://example.com:80", "http://example.com:443", "http:///no-host",
            "https://[fe80::1%25eth0]/"})
    void unsupportedTargetsAreRejectedBeforeDns(String url) {
        PublicUrlPolicy noDns = new PublicUrlPolicy() {
            protected InetAddress[] resolveHost(String host) { throw new AssertionError("DNS must not run"); }
        };
        assertEquals(400, assertThrows(ApiException.class, () -> noDns.resolve(URI.create(url))).getStatus().value());
    }

    @Test void mixedPublicAndPrivateDnsAnswersAreRejected() {
        PublicUrlPolicy mixed = new PublicUrlPolicy() {
            protected InetAddress[] resolveHost(String host) throws UnknownHostException {
                return new InetAddress[]{InetAddress.getByName("1.1.1.1"), InetAddress.getByName("127.0.0.1")};
            }
        };
        assertThrows(ApiException.class, () -> mixed.resolve(URI.create("https://mixed.test/page")));
    }

    @Test void encodedPathAndQueryArePreservedWhenRemovingFragment() {
        PublicUrlPolicy fixed = new PublicUrlPolicy() {
            protected InetAddress[] resolveHost(String host) throws UnknownHostException {
                return new InetAddress[]{InetAddress.getByName("1.1.1.1")};
            }
        };
        var result = fixed.resolve(URI.create("https://public.test/a%2Fb?q=x%26y#section"));
        assertEquals("https://public.test/a%2Fb?q=x%26y", result.uri().toString());
    }

    @Test void dnsFailureIsNotTreatedAsASuccessfulCheck() {
        PublicUrlPolicy failing = new PublicUrlPolicy() {
            protected InetAddress[] resolveHost(String host) throws UnknownHostException { throw new UnknownHostException(); }
        };
        assertEquals(422, assertThrows(ApiException.class, () -> failing.resolve(URI.create("https://missing.test"))).getStatus().value());
    }
}
