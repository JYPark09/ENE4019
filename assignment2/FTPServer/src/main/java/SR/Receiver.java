package SR;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;

public class Receiver {
    private InputStream inStream;
    private OutputStream outStream;

    public Receiver(Socket socket) throws IOException {
        inStream = socket.getInputStream();
        outStream = socket.getOutputStream();
    }

    private void sendAck(int seqNo) throws IOException {
        AckPacket packet = new AckPacket(seqNo, 0);

        outStream.write(packet.get());
    }

    public void recv(OutputStream data, long length) {
        final long chunkCount = length / Consts.CHUNK_SIZE + (length % Consts.CHUNK_SIZE > 0 ? 1 : 0);

        long baseIndex = 0;
        HashMap<Integer, Packet> packets = new HashMap<Integer, Packet>();

        while (baseIndex < chunkCount) {
            try {
                final int baseSeqNo = (int)(baseIndex % Consts.SEQ_NO_INTERVAL) + 1;

                byte[] chunk = new byte[Packet.length];
                if (inStream.read(chunk) != -1) {
                    Packet packet = new Packet(chunk);

                    if (packet.getCheckSum() != 0x0) {
                        // bit-error is occurred
                        continue;
                    }

                    final int seqNo = packet.getSequenceNumber();
                    sendAck(seqNo);

                    // we need to store ONLY in range packet.
                    if ((baseSeqNo <= seqNo && seqNo <= Math.min(Consts.SEQ_NO_INTERVAL, baseSeqNo + Consts.WINDOW_SIZE)) ||
                            (seqNo <= (baseSeqNo + Consts.WINDOW_SIZE - Consts.SEQ_NO_INTERVAL + 1))) {
                        packets.put(seqNo, packet);
                    }

                    for (long i = baseIndex; i < baseIndex + Consts.WINDOW_SIZE && i < chunkCount; ++i) {
                        final int seq = (int)(i % Consts.SEQ_NO_INTERVAL) + 1;

                        if (!packets.containsKey(seq)) {
                            break;
                        }

                        Packet pkt = packets.get(seq);

                        final int len = pkt.getLength();
                        byte[] dataToWrite = new byte[len];
                        pkt.getData(dataToWrite);

                        data.write(dataToWrite);
                        packets.remove(seq);
                        ++baseIndex;
                    }
                }
            } catch (Exception e) {
                // Do nothing
            }
        }

        System.out.println("DONE");
    }
}
