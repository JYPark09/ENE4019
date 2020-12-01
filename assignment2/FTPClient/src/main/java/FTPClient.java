import SR.Sender;

import javax.print.DocFlavor;
import java.io.*;
import java.net.Socket;
import java.net.SocketException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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

    private static final int WINDOW_SIZE = 5;
    private static final int SEQ_NO_INTERVAL = 15;

    public List<Long> ReceiveDrop = new ArrayList<Long>();
    public List<Long> ReceiveTimeout = new ArrayList<Long>();
    public List<Long> ReceiveBitErr = new ArrayList<Long>();

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

    private void send(int seqNo, int CHKsum, int length, byte[] data, boolean timeout, OutputStream out) throws IOException {
        byte[] chunk = new byte[SEQ_NO_SIZE + CHK_SUM_SIZE + SIZE_SIZE + CHUNK_SIZE];

        // make SeqNo
        chunk[0] = (byte)(seqNo);

        // make CHKSum
        chunk[SEQ_NO_SIZE + 0] = (byte)((CHKsum >> 8) & 0xFF);
        chunk[SEQ_NO_SIZE + 1] = (byte)(CHKsum & 0xFF);

        // make Size
        chunk[SEQ_NO_SIZE + CHK_SUM_SIZE + 0] = (byte)((length >> 8) & 0xFF);
        chunk[SEQ_NO_SIZE + CHK_SUM_SIZE + 1] = (byte)(length & 0xFF);

        // make Data
        System.arraycopy(data, 0, chunk, SEQ_NO_SIZE + CHK_SUM_SIZE + SIZE_SIZE, length);

        out.write(chunk);
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

            System.out.println(name + " transferred  / " + length + " bytes");

            SR.Sender sender = new Sender(socket, ReceiveDrop, ReceiveTimeout, ReceiveBitErr);
            sender.send(inp, length);

            System.out.println("  Completed...");

            ReceiveDrop.clear();
            ReceiveTimeout.clear();
            ReceiveBitErr.clear();
        } catch (Exception e) {
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

            String respMsg = recvControlMessage();
            if (!Connection.isSuccess(respMsg)) {
                Connection.printRecvControlMessage(respMsg);
                return;
            }

            final long length = Long.parseLong(Connection.parsePhrase(respMsg).replaceAll("[^0-9]", ""));

            System.out.println("Received " + name + " / " + length + " bytes");

            FileOutputStream oup = new FileOutputStream(name);

            SR.Receiver receiver = new SR.Receiver(socket);
            receiver.recv(oup, length);

            if (oup != null) oup.close();

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
                cmd_PUT(tokens, recvMsg);
                break;

            case "QUIT":
                isRunning = false;
                break;

            case "DROP":
                cmd_DROP(tokens);
                break;

            case "TIMEOUT":
                cmd_TIMEOUT(tokens);
                break;

            case "BITERROR":
                cmd_BITERROR(tokens);
                break;

            default:
                conn.sendControlMessage(recvMsg);
                conn.printRecvControlMessage();
                break;
        }
    }

    private void cmd_LIST(String[] tokens) throws IOException {
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

    private void cmd_GET(String[] tokens) {
        if (tokens.length != 2) {
            System.out.println("syntax error");
            return;
        }

        final String name = Path.of(tokens[1]).getFileName().toString();

        conn.recvData(name);
    }

    private void cmd_PUT(String[] tokens, String recvMsg) throws IOException {
        if (tokens.length != 2) {
            System.out.println("syntax error");
            return;
        }

        try {
            FileInputStream inp = new FileInputStream(tokens[1]);
            File file = new File(tokens[1]);

            conn.sendControlMessage(recvMsg);

            conn.sendData(inp, file.getName(), file.length());
        } catch (FileNotFoundException e) {
            System.out.println("file not exists");
        }
    }

    private void cmd_DROP(String[] tokens) {
        if (tokens.length != 2) {
            System.out.println("syntax error");
            return;
        }

        String[] argTokens = tokens[1].split(",");
        List<Long> toSend = new ArrayList<Long>();
        for (String token : argTokens) {
            if (token.length() < 2)
                continue;

            if (token.charAt(0) == 'R') {
                conn.ReceiveDrop.add(Long.parseLong(token.substring(1)));
            } else if (token.toUpperCase().charAt(0) == 'S') {
                toSend.add(Long.parseLong(token.substring(1)));
            }
        }

        if (!toSend.isEmpty()) {
            StringBuilder builder = new StringBuilder();

            builder.append("DROP ");

            boolean first = true;
            for (Long v : toSend) {
                if (!first)
                    builder.append(',');

                builder.append(v);
                first = false;
            }
        }
    }

    private void cmd_TIMEOUT(String[] tokens) {
        if (tokens.length != 2) {
            System.out.println("syntax error");
            return;
        }

        String[] argTokens = tokens[1].split(",");
        List<Long> toSend = new ArrayList<Long>();
        for (String token : argTokens) {
            if (token.length() < 2)
                continue;

            if (token.charAt(0) == 'R') {
                conn.ReceiveTimeout.add(Long.parseLong(token.substring(1)));
            } else if (token.toUpperCase().charAt(0) == 'S') {
                toSend.add(Long.parseLong(token.substring(1)));
            }
        }

        if (!toSend.isEmpty()) {
            StringBuilder builder = new StringBuilder();

            builder.append("TIMEOUT ");

            boolean first = true;
            for (Long v : toSend) {
                if (!first)
                    builder.append(',');

                builder.append(v);
                first = false;
            }
        }
    }

    private void cmd_BITERROR(String[] tokens) {
        if (tokens.length != 2) {
            System.out.println("syntax error");
            return;
        }

        String[] argTokens = tokens[1].split(",");
        List<Long> toSend = new ArrayList<Long>();
        for (String token : argTokens) {
            if (token.length() < 2)
                continue;

            if (token.toUpperCase().charAt(0) == 'R') {
                conn.ReceiveBitErr.add(Long.parseLong(token.substring(1)));
            } else if (token.toUpperCase().charAt(0) == 'S') {
                toSend.add(Long.parseLong(token.substring(1)));
            }
        }

        if (!toSend.isEmpty()) {
            StringBuilder builder = new StringBuilder();

            builder.append("BITERROR ");

            boolean first = true;
            for (Long v : toSend) {
                if (!first)
                    builder.append(',');

                builder.append(v);
                first = false;
            }
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
