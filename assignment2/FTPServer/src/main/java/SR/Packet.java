package SR;

public class Packet {
    public static final int length = Consts.SEQ_NO_SIZE + Consts.CHK_SUM_SIZE + Consts.SIZE_SIZE + Consts.CHUNK_SIZE;

    private byte[] chunk = new byte[length];

    public Packet(byte[] chunk) {
        System.arraycopy(chunk, 0, this.chunk, 0, length);
    }

    public Packet(int seqNo, int CHKsum, int length, byte[] data) {
        chunk[0] = (byte)(seqNo & 0xFF);

        chunk[Consts.SEQ_NO_SIZE + 0] = (byte)((CHKsum >> 8) & 0xFF);
        chunk[Consts.SEQ_NO_SIZE + 1] = (byte)(CHKsum & 0xFF);

        chunk[Consts.SEQ_NO_SIZE + Consts.CHK_SUM_SIZE + 0] = (byte)((length >> 8) & 0xFF);
        chunk[Consts.SEQ_NO_SIZE + Consts.CHK_SUM_SIZE + 1] = (byte)(length & 0xFF);

        System.arraycopy(data, 0, chunk, Consts.SEQ_NO_SIZE + Consts.CHK_SUM_SIZE + Consts.SIZE_SIZE, data.length);
    }

    public Packet BitErrorPkt() {
        Packet newOne = new Packet(chunk);

        newOne.chunk[Consts.SEQ_NO_SIZE + 0] = (byte)0xFF;
        newOne.chunk[Consts.SEQ_NO_SIZE + 1] = (byte)0xFF;

        return newOne;
    }

    public int getSequenceNumber() {
        return chunk[0];
    }

    public int getCheckSum() {
        return ((chunk[Consts.SEQ_NO_SIZE + 0] & 0xFF) << 8) | (chunk[Consts.SEQ_NO_SIZE + 1] & 0xFF);
    }

    public int getLength() {
        return ((chunk[Consts.SEQ_NO_SIZE + Consts.CHK_SUM_SIZE + 0] & 0xFF) << 8) | (chunk[Consts.SEQ_NO_SIZE + Consts.CHK_SUM_SIZE + 1] & 0xFF);
    }

    public void getData(byte[] value) {
        System.arraycopy(chunk, Consts.SEQ_NO_SIZE + Consts.CHK_SUM_SIZE + Consts.SIZE_SIZE, value, 0, getLength());
    }

    public byte[] get() {
        return chunk;
    }
}
