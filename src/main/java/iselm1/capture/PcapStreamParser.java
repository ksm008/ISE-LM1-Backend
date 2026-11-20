package iselm1.capture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * tcpdump {@code -w -} 출력(pcap 형식)을 받은 조각 그대로 넣으면 패킷 단위로 나눠 준다.
 * 조각 경계는 아무 곳이나 될 수 있다. 한 스레드에서만 호출한다.
 */
public class PcapStreamParser {

    public interface Listener {
        /** 전역 헤더를 읽었을 때 한 번 호출된다. */
        void onHeader(int linkType);

        /** @param micros 초 아래 마이크로초 (나노초 pcap 이면 변환) */
        void onPacket(long seconds, long micros, int originalLength, byte[] data);
    }

    private static final int GLOBAL_HEADER_LENGTH = 24;
    private static final int RECORD_HEADER_LENGTH = 16;

    private final int maxPacketBytes;
    private final Listener listener;

    private byte[] buffer = new byte[64 * 1024];
    private int length;
    private boolean headerRead;
    private ByteOrder order;
    private boolean nanoseconds;

    /** @param maxPacketBytes 한 패킷의 최대 캡처 길이. 이보다 크면 형식 오류로 본다. */
    public PcapStreamParser(int maxPacketBytes, Listener listener) {
        this.maxPacketBytes = maxPacketBytes;
        this.listener = listener;
    }

    /** @throws IllegalStateException pcap 형식이 아닐 때 */
    public void feed(byte[] chunk) {
        append(chunk);
        int pos = 0;

        if (!headerRead) {
            if (length < GLOBAL_HEADER_LENGTH) {
                return;
            }
            readGlobalHeader();
            headerRead = true;
            pos = GLOBAL_HEADER_LENGTH;
        }

        while (length - pos >= RECORD_HEADER_LENGTH) {
            long seconds = Integer.toUnsignedLong(readInt(pos));
            long fraction = Integer.toUnsignedLong(readInt(pos + 4));
            int capturedLength = readInt(pos + 8);
            int originalLength = readInt(pos + 12);
            if (capturedLength < 0 || capturedLength > maxPacketBytes) {
                throw new IllegalStateException("잘못된 패킷 길이: " + capturedLength);
            }
            if (length - pos - RECORD_HEADER_LENGTH < capturedLength) {
                break; // 나머지는 다음 조각에 있다
            }
            int start = pos + RECORD_HEADER_LENGTH;
            byte[] data = Arrays.copyOfRange(buffer, start, start + capturedLength);
            listener.onPacket(seconds, nanoseconds ? fraction / 1000 : fraction, originalLength, data);
            pos = start + capturedLength;
        }

        // 처리한 부분을 버린다
        System.arraycopy(buffer, pos, buffer, 0, length - pos);
        length -= pos;
    }

    private void readGlobalHeader() {
        int magic = ByteBuffer.wrap(buffer, 0, 4).order(ByteOrder.BIG_ENDIAN).getInt();
        switch (magic) {
            case 0xa1b2c3d4 -> { order = ByteOrder.BIG_ENDIAN; nanoseconds = false; }
            case 0xd4c3b2a1 -> { order = ByteOrder.LITTLE_ENDIAN; nanoseconds = false; }
            case 0xa1b23c4d -> { order = ByteOrder.BIG_ENDIAN; nanoseconds = true; }
            case 0x4d3cb2a1 -> { order = ByteOrder.LITTLE_ENDIAN; nanoseconds = true; }
            default -> throw new IllegalStateException(String.format("pcap 형식이 아닙니다 (magic=0x%08x)", magic));
        }
        // 상위 비트는 FCS 정보이므로 링크 타입만 남긴다
        listener.onHeader(readInt(20) & 0x0FFFFFFF);
    }

    private int readInt(int offset) {
        return ByteBuffer.wrap(buffer, offset, 4).order(order).getInt();
    }

    private void append(byte[] chunk) {
        if (length + chunk.length > buffer.length) {
            buffer = Arrays.copyOf(buffer, Math.max(buffer.length * 2, length + chunk.length));
        }
        System.arraycopy(chunk, 0, buffer, length, chunk.length);
        length += chunk.length;
    }
}
