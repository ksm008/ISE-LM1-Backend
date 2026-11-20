package iselm1.capture;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PcapStreamParserTest {

    record Packet(long seconds, long micros, int originalLength, byte[] data) {}

    static class Recorder implements PcapStreamParser.Listener {
        int linkType = -1;
        final List<Packet> packets = new ArrayList<>();

        @Override
        public void onHeader(int linkType) {
            this.linkType = linkType;
        }

        @Override
        public void onPacket(long seconds, long micros, int originalLength, byte[] data) {
            packets.add(new Packet(seconds, micros, originalLength, data));
        }
    }

    private static byte[] pcap(ByteOrder order, int magic, int linkType, byte[]... packets) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer header = ByteBuffer.allocate(24).order(order);
        header.putInt(magic).putShort((short) 2).putShort((short) 4).putInt(0).putInt(0).putInt(65535).putInt(linkType);
        out.writeBytes(header.array());
        int i = 0;
        for (byte[] data : packets) {
            ByteBuffer record = ByteBuffer.allocate(16).order(order);
            record.putInt(1_700_000_000 + i).putInt(123_456).putInt(data.length).putInt(data.length + 10);
            out.writeBytes(record.array());
            out.writeBytes(data);
            i++;
        }
        return out.toByteArray();
    }

    @Test
    void 조각_경계와_상관없이_패킷을_나눈다() {
        byte[] a = {1, 2, 3};
        byte[] b = new byte[100];
        Arrays.fill(b, (byte) 7);
        byte[] stream = pcap(ByteOrder.LITTLE_ENDIAN, 0xa1b2c3d4, 276, a, b, new byte[0]);

        for (int chunkSize : new int[]{1, 5, 17, 24, 40, stream.length}) {
            Recorder recorder = new Recorder();
            PcapStreamParser parser = new PcapStreamParser(4096, recorder);
            for (int pos = 0; pos < stream.length; pos += chunkSize) {
                parser.feed(Arrays.copyOfRange(stream, pos, Math.min(stream.length, pos + chunkSize)));
            }
            assertThat(recorder.linkType).isEqualTo(276);
            assertThat(recorder.packets).hasSize(3);
            assertThat(recorder.packets.get(0).data()).containsExactly(a);
            assertThat(recorder.packets.get(0).seconds()).isEqualTo(1_700_000_000L);
            assertThat(recorder.packets.get(0).micros()).isEqualTo(123_456L);
            assertThat(recorder.packets.get(0).originalLength()).isEqualTo(13);
            assertThat(recorder.packets.get(1).data()).containsExactly(b);
            assertThat(recorder.packets.get(2).data()).isEmpty();
        }
    }

    @Test
    void 빅엔디언과_나노초_형식을_읽는다() {
        Recorder recorder = new Recorder();
        new PcapStreamParser(4096, recorder).feed(pcap(ByteOrder.BIG_ENDIAN, 0xa1b23c4d, 1, new byte[]{9}));

        assertThat(recorder.linkType).isEqualTo(1);
        assertThat(recorder.packets).hasSize(1);
        assertThat(recorder.packets.get(0).micros()).isEqualTo(123L); // 123456 ns → 123 µs
    }

    @Test
    void pcap_이_아니면_오류() {
        PcapStreamParser parser = new PcapStreamParser(4096, new Recorder());
        assertThatThrownBy(() -> parser.feed("tcpdump: syntax error.....".getBytes()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 패킷_길이가_너무_크면_오류() {
        byte[] stream = pcap(ByteOrder.LITTLE_ENDIAN, 0xa1b2c3d4, 1, new byte[64]);
        PcapStreamParser parser = new PcapStreamParser(32, new Recorder());
        assertThatThrownBy(() -> parser.feed(stream)).isInstanceOf(IllegalStateException.class);
    }
}
