import javax.print.DocFlavor;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Scanner;

class Connection {
    private String host;
    private int dataPort;

    private Socket ctlSocket;
    private PrintWriter ctlWriter;
    private BufferedReader ctlReader;

    private static final int SEQ_NO_SIZE = 1;
    private static final int CHK_SUM_SIZE = 2;
    private static final int SIZE_SIZE = 2;
    private static final int CHUNK_SIZE = 1000;

    public Connection(String host, int ctlPort, int dataPort) throws IOException {
        this.host = host;
        this.dataPort = dataPort;

        ctlSocket = new Socket(host, ctlPort);
        ctlWriter = new PrintWriter(ctlSocket.getOutputStream(), true);
        ctlReader = new BufferedReader(new InputStreamReader(ctlSocket.getInputStream()));
    }

    public void sendControlMessage(String msg) {
        ctlWriter.println(msg);
    }

    public String recvControlMessage() throws IOException {
        return ctlReader.readLine().strip();
    }

    public static boolean isSuccess(String msg) {
        int statusCode = Integer.parseInt(msg.split(" ")[0]);

        return statusCode / 100 == 2;
    }

    public static String parsePhrase(String msg) {
        int splitPivot = msg.indexOf(" ");
        if (splitPivot != -1) {
            return msg.substring(splitPivot + 1);
        }

        return msg;
    }

    public static void printRecvControlMessage(String resp) {
        if (resp == null || resp.length() == 0)
            return;

        if (!isSuccess(resp)) {
            System.out.print("Failed - ");
        }

        System.out.println(parsePhrase(resp));
    }

    public void printRecvControlMessage() throws IOException {
        printRecvControlMessage(recvControlMessage());
    }

    public void sendData(InputStream inp, String name, long length) {
        Socket socket = null;
        try {
            while (true) {
                socket = null;

                try {
                    socket = new Socket(host, dataPort);
                    break;
                } catch (Exception e) {
                    // ignore exception
                }
            }

            sendControlMessage(Long.toString(length));

            String respMsg = recvControlMessage();
            if (!Connection.isSuccess(respMsg)) {
                Connection.printRecvControlMessage(respMsg);
                return;
            }

            InputStream inStream = socket.getInputStream();
            OutputStream outStream = socket.getOutputStream();

            System.out.println(name + " transferred  / " + length + " bytes");

            long chunkCount = length / CHUNK_SIZE + (length % CHUNK_SIZE > 0 ? 1 : 0);
            for (long chunkID = 1; chunkID <= chunkCount; ++chunkID) {
                byte[] chunk = new byte[SEQ_NO_SIZE + CHK_SUM_SIZE + SIZE_SIZE + CHUNK_SIZE];
                int chunkSize = 0;

                // make SeqNo
                {
                    chunk[0] = (byte) (chunkID % 16);
                }

                // make CHKsum
                {
                    chunk[SEQ_NO_SIZE + 0] = 0;
                    chunk[SEQ_NO_SIZE + 1] = 0;
                }

                // make Size
                {
                    final int BASE_IDX = SEQ_NO_SIZE + CHK_SUM_SIZE;

                    if (length >= CHUNK_SIZE * chunkID) {
                        chunkSize = CHUNK_SIZE;
                    } else {
                        chunkSize = (int) (length - CHUNK_SIZE * (chunkID - 1));
                    }

                    chunk[BASE_IDX + 0] = (byte)((chunkSize >> 8) & 0xFF);
                    chunk[BASE_IDX + 1] = (byte)(chunkSize & 0xFF);
                }

                // make Data
                {
                    final int BASE_IDX = SEQ_NO_SIZE + CHK_SUM_SIZE + SIZE_SIZE;

                    inp.read(chunk, BASE_IDX, chunkSize);
                }

                outStream.write(chunk);

                byte[] resp = new byte[SEQ_NO_SIZE + CHK_SUM_SIZE];
                inStream.read(resp);

                final int SeqNo = resp[0];
                final int CHKsum = (resp[SEQ_NO_SIZE + 0] << 8) | resp[SEQ_NO_SIZE + 1];

                if (CHKsum == 0xFF) {
                    // bit error occurs
                }

                System.out.print("#");
            }

            if (inStream != null) inStream.close();
            if (outStream != null) outStream.close();

            System.out.println("  Completed...");
        } catch (IOException e) {
            System.out.println("send data failed : " + e.getMessage());
        } finally {
            try {
                if (socket != null) socket.close();
            } catch (IOException e) {
                System.out.print("socket cannot be closed");
            }
        }
    }

    public void recvData(String name) {
        Socket socket = null;
        try {
            while (true) {
                socket = null;

                try {
                    socket = new Socket(host, dataPort);
                    break;
                } catch (Exception e) {
                    // ignore exception
                }
            }

            InputStream inStream = socket.getInputStream();
            OutputStream outStream = socket.getOutputStream();

            String respMsg = recvControlMessage();
            if (!Connection.isSuccess(respMsg)) {
                Connection.printRecvControlMessage(respMsg);
                return;
            }

            final long length = Long.parseLong(Connection.parsePhrase(respMsg).replaceAll("[^0-9]", ""));

            System.out.println("Received " + name + " / " + length + " bytes");

            FileOutputStream oup = new FileOutputStream(name);

            byte[] buffer = new byte[SEQ_NO_SIZE + CHK_SUM_SIZE + SIZE_SIZE + CHUNK_SIZE];
            while (inStream.read(buffer) != -1) {
                final int SeqNo = buffer[0];
                final int CHKsum = ((buffer[1] & 0xFF) << 8) | (buffer[2] & 0xFF);
                final int Size = ((buffer[3] & 0xFF) << 8) | (buffer[4] & 0xFF);

                if (CHKsum == 0xFFFF) {
                    // bit error occurs
                }

                oup.write(buffer, SEQ_NO_SIZE + CHK_SUM_SIZE + SIZE_SIZE, Size);

                {
                    byte[] resp = new byte[SEQ_NO_SIZE + CHK_SUM_SIZE];

                    // make SeqNo
                    {
                        resp[0] = buffer[0];
                    }

                    // make CHKsum
                    {
                        resp[SEQ_NO_SIZE + 0] = 0;
                        resp[SEQ_NO_SIZE + 1] = 0;
                    }

                    outStream.write(resp);
                }

                System.out.print("#");
            }

            if (oup != null) oup.close();
            if (inStream != null) inStream.close();
            if (outStream != null) outStream.close();

            System.out.println("  Completed...");
        } catch (IOException e) {
            System.out.println("recv data failed : " + e.getMessage());
        } finally {
            try {
                if (socket != null) socket.close();
            } catch (IOException e) {
                System.out.print("socket cannot be closed");
            }
        }
    }
}

class FTPClient {
    private Connection conn;
    private boolean isRunning;

    public FTPClient(String host, int ctlPort, int dataPort) throws IOException {
        conn = new Connection(host, ctlPort, dataPort);

        Scanner reader = new Scanner(System.in);

        isRunning = true;
        while (isRunning && reader.hasNext()) {
            execute(reader.nextLine().strip());
        }
    }

    private void execute(String recvMsg) throws IOException {
        String[] tokens = null;

        {
            String[] tmp = recvMsg.strip().split(" ");

            if (tmp.length == 1) {
                tokens = new String[1];

                tokens[0] = tmp[0];
            } else if (tmp.length > 1) {
                tokens = new String[2];

                tokens[0] = tmp[0];

                StringBuilder builder = new StringBuilder();

                for (int i = 1; i < tmp.length; ++i) {
                    if (i != 1) {
                        builder.append(' ');
                    }

                    builder.append(tmp[i]);
                }

                tokens[1] = builder.toString();
            }
        }

        if (tokens == null) {
            return;
        }

        switch (tokens[0].toUpperCase()) {
            case "LIST":
                conn.sendControlMessage(recvMsg);
                cmd_LIST(tokens);
                break;

            case "GET":
                conn.sendControlMessage(recvMsg);
                cmd_GET(tokens);
                break;

            case "PUT":
                conn.sendControlMessage(recvMsg);
                cmd_PUT(tokens);
                break;

            case "QUIT":
                isRunning = false;
                break;

            default:
                conn.sendControlMessage(recvMsg);
                conn.printRecvControlMessage();
                break;
        }
    }

    public void cmd_LIST(String[] tokens) throws IOException {
        String resp = conn.recvControlMessage();

        if (!Connection.isSuccess(resp)) {
            Connection.printRecvControlMessage(resp);
            return;
        }

        int entryCnt = Integer.parseInt(Connection.parsePhrase(resp).replaceAll("[^0-9]", ""));

        for (int i = 0; i < entryCnt; ++i) {
            System.out.println(conn.recvControlMessage());
        }
    }

    public void cmd_GET(String[] tokens) {
        if (tokens.length != 2) {
            System.out.println("syntax error");
            return;
        }

        final String name = Path.of(tokens[1]).getFileName().toString();

        conn.recvData(name);
    }

    public void cmd_PUT(String[] tokens) throws IOException {
        if (tokens.length != 2) {
            System.out.println("syntax error");
            return;
        }

        try {
            FileInputStream inp = new FileInputStream(tokens[1]);
            File file = new File(tokens[1]);

            conn.sendData(inp, file.getName(), file.length());
        } catch (FileNotFoundException e) {
            System.out.println("file not exists");
        }
    }

    public static void main(String[] args) {
        String host = "127.0.0.1";
        int ctlPort = 2020, dataPort = 2121;

        if (args.length != 0) {
            host = args[0];

            if (args.length > 1) {
                ctlPort = Integer.parseInt(args[1]);

                if (args.length == 3) {
                   dataPort = Integer.parseInt(args[2]);
                } else if (args.length > 3) {
                    System.err.println("<ERROR> invalid arguments");
                    System.err.println("usage: java FTPClient <host> <control port> <data port>");

                    System.exit(-1);
                }
            }
        }

        try {
            new FTPClient(host, ctlPort, dataPort);
        } catch (Exception e) {
            System.err.println("<EXCEPTION> " + e.toString());
            System.exit(-1);
        }
    }
}
