package com.platform.mcp.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;

/**
 * CR-041: STUN Binding Request로 공인 IP 주소를 탐색.
 * RFC 5389 최소 구현 — Binding Request만 사용.
 * 실패 시 localhost 주소로 폴백.
 */
public final class StunAddressResolver {

    private static final Logger log = LoggerFactory.getLogger(StunAddressResolver.class);

    private static final String DEFAULT_STUN_SERVER = "14.63.25.49";
    private static final int DEFAULT_STUN_PORT = 3478;
    private static final int STUN_TIMEOUT_MS = 3000;

    // STUN message type: Binding Request
    private static final short BINDING_REQUEST = 0x0001;
    // STUN magic cookie (RFC 5389)
    private static final int MAGIC_COOKIE = 0x2112A442;
    // STUN attribute type: XOR-MAPPED-ADDRESS
    private static final short XOR_MAPPED_ADDRESS = 0x0020;
    // STUN attribute type: MAPPED-ADDRESS (fallback)
    private static final short MAPPED_ADDRESS = 0x0001;

    private StunAddressResolver() {}

    /**
     * 기본 STUN 서버(Google)로 공인 IP 탐색.
     */
    public static String discoverPublicAddress() {
        return discoverPublicAddress(DEFAULT_STUN_SERVER, DEFAULT_STUN_PORT);
    }

    /**
     * 지정된 STUN 서버로 공인 IP 탐색.
     *
     * @param stunServer STUN 서버 호스트
     * @param stunPort   STUN 서버 포트
     * @return 공인 IP 주소 문자열
     */
    public static String discoverPublicAddress(String stunServer, int stunPort) {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(STUN_TIMEOUT_MS);

            // Build STUN Binding Request (20 bytes header)
            ByteBuffer request = ByteBuffer.allocate(20);
            request.putShort(BINDING_REQUEST);     // Message Type
            request.putShort((short) 0);           // Message Length (no attributes)
            request.putInt(MAGIC_COOKIE);           // Magic Cookie
            // Transaction ID (12 bytes random)
            for (int i = 0; i < 3; i++) {
                request.putInt((int) (Math.random() * Integer.MAX_VALUE));
            }

            InetAddress serverAddr = InetAddress.getByName(stunServer);
            DatagramPacket sendPacket = new DatagramPacket(
                    request.array(), request.capacity(),
                    new InetSocketAddress(serverAddr, stunPort));
            socket.send(sendPacket);

            // Receive response
            byte[] responseBytes = new byte[256];
            DatagramPacket receivePacket = new DatagramPacket(responseBytes, responseBytes.length);
            socket.receive(receivePacket);

            ByteBuffer response = ByteBuffer.wrap(responseBytes, 0, receivePacket.getLength());
            short messageType = response.getShort();    // Message Type
            short messageLength = response.getShort();  // Message Length
            int cookie = response.getInt();             // Magic Cookie
            response.position(response.position() + 12); // Skip Transaction ID

            // Parse attributes
            int endPos = 20 + messageLength;
            while (response.position() < endPos && response.remaining() >= 4) {
                short attrType = response.getShort();
                short attrLength = response.getShort();

                if (attrType == XOR_MAPPED_ADDRESS) {
                    response.get(); // reserved
                    byte family = response.get();
                    short xorPort = response.getShort();
                    if (family == 0x01) { // IPv4
                        int xorAddr = response.getInt();
                        int addr = xorAddr ^ MAGIC_COOKIE;
                        String ip = String.format("%d.%d.%d.%d",
                                (addr >> 24) & 0xFF, (addr >> 16) & 0xFF,
                                (addr >> 8) & 0xFF, addr & 0xFF);
                        log.info("STUN discovered public address: {} (via {}:{})", ip, stunServer, stunPort);
                        return ip;
                    }
                } else if (attrType == MAPPED_ADDRESS) {
                    response.get(); // reserved
                    byte family = response.get();
                    short port = response.getShort();
                    if (family == 0x01) { // IPv4
                        int addr = response.getInt();
                        String ip = String.format("%d.%d.%d.%d",
                                (addr >> 24) & 0xFF, (addr >> 16) & 0xFF,
                                (addr >> 8) & 0xFF, addr & 0xFF);
                        log.info("STUN discovered public address (MAPPED): {} (via {}:{})", ip, stunServer, stunPort);
                        return ip;
                    }
                } else {
                    // Skip unknown attribute (padded to 4-byte boundary)
                    int skip = attrLength + (4 - attrLength % 4) % 4;
                    if (response.remaining() >= skip) {
                        response.position(response.position() + skip);
                    } else {
                        break;
                    }
                }
            }

            log.warn("STUN response did not contain mapped address, falling back to local address");
            return fallbackAddress();

        } catch (Exception e) {
            log.warn("STUN discovery failed ({}), falling back to local address: {}", stunServer, e.getMessage());
            return fallbackAddress();
        }
    }

    private static String fallbackAddress() {
        try {
            return InetAddress.getLocalHost().getHostAddress();
        } catch (Exception e) {
            return "127.0.0.1";
        }
    }
}
