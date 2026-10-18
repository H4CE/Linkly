package io.linkly.url_shortener.safety;

import io.linkly.url_shortener.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

@Component
public class PublicUrlPolicy {
    public record Target(URI uri, InetAddress[] addresses) {}

    public Target resolve(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        int expectedPort = "https".equals(scheme) ? 443 : 80;
        if (!("http".equals(scheme) || "https".equals(scheme)) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getHost().contains("%")
                || (uri.getPort() != -1 && uri.getPort() != expectedPort)) {
            throw blocked();
        }
        try {
            InetAddress[] addresses = resolveHost(uri.getHost());
            if (addresses.length == 0) throw new UnknownHostException();
            // Reject the entire DNS answer if ANY address is internal/special-use.
            // The fetcher will connect only to these validated addresses, avoiding DNS rebinding.
            for (InetAddress address : addresses) if (!isPublicAddress(address)) throw blocked();
            // Strip the fragment without re-encoding escaped paths or query parameters.
            return new Target(URI.create(uri.toString().split("#", 2)[0]), addresses);
        } catch (UnknownHostException ex) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Destination hostname could not be resolved");
        }
    }

    protected InetAddress[] resolveHost(String host) throws UnknownHostException {
        return InetAddress.getAllByName(host);
    }

    public boolean isPublicAddress(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int a = bytes[0] & 255, b = bytes[1] & 255;
        if (bytes.length == 4) {
            int c = bytes[2] & 255;
            return !(a == 0 || a == 10 || a == 127 || a >= 224
                    || (a == 100 && b >= 64 && b <= 127)
                    || (a == 169 && b == 254)
                    || (a == 172 && b >= 16 && b <= 31)
                    || (a == 192 && b == 168)
                    || (a == 192 && b == 0 && (c == 0 || c == 2))
                    || (a == 192 && b == 88 && c == 99)
                    || (a == 198 && (b == 18 || b == 19))
                    || (a == 198 && b == 51 && c == 100)
                    || (a == 203 && b == 0 && c == 113));
        }
        // Accept global unicast IPv6 only; exclude tunnelling and documentation ranges.
        int c = bytes[2] & 255, d = bytes[3] & 255;
        return (a & 0xe0) == 0x20
                && !(a == 0x20 && b == 0x01 && c <= 1)
                && !(a == 0x20 && b == 0x01 && c == 0x0d && d == 0xb8)
                && !(a == 0x20 && b == 0x02)
                && !(a == 0x3f && b == 0xff && (c & 0xf0) == 0);
    }

    private ApiException blocked() {
        return new ApiException(HttpStatus.BAD_REQUEST,
                "Content checks require a public HTTP(S) destination on its standard port");
    }
}
