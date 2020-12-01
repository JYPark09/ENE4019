package SR;

public class AckPacket {
    public static final int length = Consts.SEQ_NO_SIZE + Consts.CHK_SUM_SIZE;

    private byte[] chunk = new byte[length];

    public AckPacket(byte[] chunk) {
        System.arraycopy(chunk, 0, this.chunk, 0, length);
    }

    public AckPacket(int seqNo, int CHKsum) {
        chunk[0] = (byte)(seqNo);

        chunk[Consts.SEQ_NO_SIZE + 0] = (byte)((CHKsum >> 8) & 0xFF);
        chunk[Consts.SEQ_NO_SIZE + 1] = (byte)(CHKsum & 0xFF);
    }

    public int getSequenceNumber() {
        return chunk[0];
    }

    public int getCheckSum() {
        return ((chunk[Consts.SEQ_NO_SIZE + 0] & 0xFF) << 8) | (chunk[Consts.SEQ_NO_SIZE + 1] & 0xFF);
    }

    public byte[] get() {
        return chunk;
    }
}
