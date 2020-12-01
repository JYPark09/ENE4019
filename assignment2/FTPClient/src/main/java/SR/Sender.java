package SR;

import java.beans.beancontext.BeanContextServiceAvailableEvent;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.util.HashMap;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class Sender {
    public static final int TIME_OUT = 1000;

    private class Frame {
        public Timer timer = new Timer();
        public boolean acked = false;
        public boolean processing = false;
    }

    private class Window {
        private long baseIndex = 0;
        private long totalIndex;

        private HashMap<Integer, Frame> frames = new HashMap<Integer, Frame>();

        public Window(long totalIndex) {
            this.totalIndex = totalIndex;
        }

        public synchronized boolean isRun() {
            return baseIndex < totalIndex;
        }

        public synchronized void add(int seqNo) {
            Frame frame = new Frame();
            frames.put(seqNo, frame);
        }

        public synchronized void start(int seqNo) {
            Frame frame = frames.get(seqNo);

            frame.processing = true;
            frame.timer.schedule(new TimeoutHandler(frame), Sender.TIME_OUT);
        }

        public synchronized void stop(int seqNo) {
            Frame frame = frames.get(seqNo);

            frame.acked = true;
            frame.processing = false;
            frame.timer.cancel();
        }

        public synchronized void update() {
            final int baseSeqNo = (int)(baseIndex % Consts.SEQ_NO_INTERVAL + 1);

            frames.remove(baseSeqNo);
            ++baseIndex;
        }

        public long getBaseIndex() {
            return baseIndex;
        }

        public long getTotalIndex() {
            return totalIndex;
        }

        public synchronized boolean hasSeqNo(int seqNo) {
            return frames.containsKey(seqNo);
        }

        public synchronized boolean isProcessing(int seqNo) {
            return frames.get(seqNo).processing;
        }

        public synchronized boolean isAcked(int seqNo) {
            return frames.get(seqNo).acked;
        }
    }

    private class SendHandler implements Runnable {
        private Window window;
        private OutputStream outStream;
        private long length;
        private InputStream file;

        private List<Long> drop, timeout, biterr;

        public SendHandler(Window window, OutputStream outStream, InputStream file, long length, List<Long> drop, List<Long> timeout, List<Long> biterr) {
            this.window = window;
            this.outStream = outStream;
            this.length = length;
            this.file = file;

            this.drop = drop;
            this.timeout = timeout;
            this.biterr = biterr;
        }

        @Override
        public void run() {
            HashMap<Integer, Packet> packets = new HashMap<Integer, Packet>();

            while (window.isRun()) {
                final long base = window.getBaseIndex();
                final int baseSeqNo = (int)(base % Consts.SEQ_NO_INTERVAL) + 1;

                for (long index = base; index < window.getTotalIndex() && index < base + Consts.WINDOW_SIZE; ++index) {
                    try {
                        final int seq = (int)(index % Consts.SEQ_NO_INTERVAL) + 1;

                        if (!window.hasSeqNo(seq)) {
                            byte[] data = new byte[Consts.CHUNK_SIZE];
                            int chunkSize;
                            if (length >= Consts.CHUNK_SIZE * (index + 1)) {
                                chunkSize = Consts.CHUNK_SIZE;
                            } else {
                                chunkSize = (int)(length - Consts.CHUNK_SIZE * index);
                            }

                            file.read(data, 0, chunkSize);

                            System.out.print(seq + " ");
                            packets.put(seq, new Packet(seq, 0, chunkSize, data));
                            window.add(seq);
                        }

                        if (!window.isProcessing(seq) && !window.isAcked(seq)) {
                            try {
                                int dropIdx = drop.indexOf(index + 1);
                                int timeoutIndex = timeout.indexOf(index + 1);
                                int biterrIndex = biterr.indexOf(index + 1);

                                window.start(seq);

                                if (dropIdx != -1) {
                                    drop.set(dropIdx, -1l);
                                } else if (timeoutIndex != -1) {
                                    timeout.set(timeoutIndex, -1l);
                                    new Thread(new Runnable() {
                                        @Override
                                        public void run() {
                                            try {
                                                Thread.sleep(TIME_OUT * 2l);
                                                outStream.write(packets.get(seq).get());
                                            } catch (Exception e) {
                                                // Do nothing;
                                            }
                                        }
                                    }).start();
                                } else if (biterrIndex != -1) {
                                    biterr.set(biterrIndex, -1l);
                                    outStream.write(packets.get(seq).BitErrorPkt().get());
                                } else {
                                    outStream.write(packets.get(seq).get());
                                }
                            } catch (Exception e) {
                                // Do nothing
                            }
                        }
                    } catch (Exception e) {
                        // Do nothing
                    }
                }

                if (window.isAcked(baseSeqNo)) {
                    window.update();
                    packets.remove(baseSeqNo);
                }
            }
        }
    }

    private class AckHandler implements Runnable {
        private Window window;
        private InputStream inStream;

        public AckHandler(Window window, InputStream inStream) {
            this.window = window;
            this.inStream = inStream;
        }

        @Override
        public void run() {
            while (window.isRun()) {
                try {
                    byte[] resp = new byte[AckPacket.length];
                    if (inStream.read(resp) != -1) {
                        AckPacket packet = new AckPacket(resp);

                        System.out.println(packet.getSequenceNumber() + " acked ");

                        if (packet.getCheckSum() == 0) {
                            window.stop(packet.getSequenceNumber());
                        }
                    }
                } catch (Exception e) {
                    // Do nothing
                }
            }
        }
    }

    private class TimeoutHandler extends TimerTask {
        private Frame frame;

        public TimeoutHandler(Frame frame) {
            this.frame = frame;
        }

        @Override
        public void run() {
            frame.processing = false;
        }
    }

    private InputStream inStream;
    private OutputStream outStream;

    private List<Long> drop, timeout, biterr;

    public Sender(Socket socket, List<Long> drop, List<Long> timeout, List<Long> biterr) throws IOException {
        inStream = socket.getInputStream();
        outStream = socket.getOutputStream();

        this.drop = drop;
        this.timeout = timeout;
        this.biterr = biterr;
    }

    public void send(InputStream data, long length) throws InterruptedException {
        final long chunkCount = length / Consts.CHUNK_SIZE + (length % Consts.CHUNK_SIZE > 0 ? 1 : 0);

        Window window = new Window(chunkCount);

        Thread sendWorker = new Thread(new SendHandler(window, outStream, data, length, drop, timeout, biterr));
        Thread ackWorker = new Thread(new AckHandler(window, inStream));

        sendWorker.start();
        ackWorker.start();

        sendWorker.join();
        ackWorker.join();

        System.out.println();
    }
}
