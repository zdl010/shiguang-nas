package com.shiguang.nas.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 枚举本机在局域网中的可访问地址（需求第 3 条）。
 * 手机浏览器要访问，就得知道往哪个 IP 打。
 */
@Service
public class LanAddressService {

    private static final Logger log = LoggerFactory.getLogger(LanAddressService.class);

    private final int port;
    private final boolean tlsEnabled;

    public LanAddressService(@Value("${server.port:18888}") int port,
                             @Value("${shiguang.tls.enabled:false}") boolean tlsEnabled) {
        this.port = port;
        this.tlsEnabled = tlsEnabled;
    }

    public int port() {
        return port;
    }

    /**
     * 协议前缀跟着 TLS 开关走。
     *
     * <p>写死 http:// 的后果是：开了 TLS 之后，控制台打印的地址、二维码里的地址、
     * 前端拿到的地址全是错的，用户点开只会看到一个连不上的页面。
     */
    private String scheme() {
        return tlsEnabled ? "https" : "http";
    }

    /** 形如 http://192.168.1.24:18888 的地址列表，按可用性排序。 */
    public List<String> lanUrls() {
        List<String> urls = new ArrayList<>();
        for (String ip : lanAddresses()) {
            urls.add(scheme() + "://" + ip + ":" + port);
        }
        return urls;
    }

    public String primaryUrl() {
        List<String> urls = lanUrls();
        return urls.isEmpty() ? localUrl() : urls.get(0);
    }

    public String localUrl() {
        return scheme() + "://127.0.0.1:" + port;
    }

    private List<String> lanAddresses() {
        List<String> addresses = new ArrayList<>();
        try {
            for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                // 跳过回环、未启用、以及 docker/vmware 之类的虚拟网卡
                if (nic.isLoopback() || !nic.isUp() || nic.isVirtual()) {
                    continue;
                }
                for (var address : Collections.list(nic.getInetAddresses())) {
                    // isSiteLocalAddress() 覆盖 10.x / 172.16-31.x / 192.168.x，正好是家用局域网段
                    if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                        addresses.add(address.getHostAddress());
                    }
                }
            }
        } catch (SocketException e) {
            log.warn("枚举网络接口失败: {}", e.getMessage());
        }
        return addresses;
    }
}
