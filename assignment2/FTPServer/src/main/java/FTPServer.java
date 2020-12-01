import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

class Status {
    public static final int OK = 200;

    public static final int NOT_FOUND = 401;

    public static final int FAIL = 501;
    public static final int SYNTAX_ERR = 501;
    public static final int UNKNOWN_ERR = 501;
}

abstract class Response {
    protected abstract String getRawResponseMessage();
    protected abstract int getStatusCode();

    public String getResponseMessage() {
        return getStatusCode() + " " + getRawResponseMessage();
    }
}

class SyntaxErrResponse extends Response {
    public String getRawResponseMessage() {
        return "Syntax error";
    }

    public int getStatusCode() {
        return Status.SYNTAX_ERR;
    }
}

class MoveSuccessResponse extends Response {
    private String directory;

    public MoveSuccessResponse(String directory) {
        this.directory = directory;
    }

    public String getRawResponseMessage() {
        return directory;
    }

    public int getStatusCode() {
        return Status.OK;
    }
}

class FileNotFoundResponse extends Response {
    public String getRawResponseMessage() {
        return "No such file exists";
    }

    public int getStatusCode() {
        return Status.NOT_FOUND;
    }
}

class InvalidDirectoryResponse extends Response {
    public String getRawResponseMessage() {
        return "directory name is invalid";
    }

    public int getStatusCode() {
        return Status.FAIL;
    }
}

class UnknownErrResponse extends Response {
    public String getRawResponseMessage() {
        return "Unknown error";
    }

    public int getStatusCode() {
        return Status.UNKNOWN_ERR;
    }
}

class ListSuccessResponse extends Response {
    private int count;

    public ListSuccessResponse(int count) {
        this.count = count;
    }

    public String getRawResponseMessage() {
        return "Comprising " + count + " entires";
    }

    public int getStatusCode() {
        return Status.OK;
    }
}

class GetSuccessResponse extends Response {
    private File file;

    public GetSuccessResponse(File file) {
        this.file = file;
    }

    public String getRawResponseMessage() {
        return "Containing " + file.length() + " bytes in total";
    }

    public int getStatusCode() {
        return Status.OK;
    }
}

class ReadyToReceiveResponse extends Response {
    public String getRawResponseMessage() {
        return "Ready to receive";
    }

    public int getStatusCode() {
        return Status.OK;
    }
}

class Connection {
    // Control Channel
    private Socket ctlSocket;
    private PrintWriter ctlWriter;
    private BufferedReader ctlReader;

    private List<Long> drop = new ArrayList<Long>();
    private List<Long> timeout = new ArrayList<Long>();
    private List<Long> biterror = new ArrayList<Long>();

    // Data Channel
    private int dataPort;

    // File System
    private String currentDir;

    private static final int SEQ_NO_SIZE = 1;
    private static final int CHK_SUM_SIZE = 2;
    private static final int SIZE_SIZE = 2;
    private static final int CHUNK_SIZE = 1000;

    public Connection(Socket ctl, int dataPort) {
        ctlSocket = ctl;

        this.dataPort = dataPort;
    }

    public void run() throws IOException {
        try {
            // setting control channel
            ctlWriter = new PrintWriter(ctlSocket.getOutputStream(), true);
            ctlReader = new BufferedReader(new InputStreamReader(ctlSocket.getInputStream()));

            // setting file system
            currentDir = System.getProperty("user.dir");

            while (true) {
                String line = ctlReader.readLine();

                if (line == null) {
                    break;
                }

                execute(line);
            }
        } catch (SocketException e) {
            System.err.println("<EXCEPTION> " + e.getMessage());
        } finally {
                if (ctlWriter != null) ctlWriter.close();
                if (ctlReader != null) ctlReader.close();

                if (ctlSocket != null) ctlSocket.close();
        }
    }

    private void execute(String rcvMsg) {
        if (rcvMsg.length() == 0)
            return;

        String[] tokens = null;

        {
            String[] tmp = rcvMsg.strip().split(" ");

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

        System.out.println("Request: " + rcvMsg);

        switch (tokens[0].toUpperCase()) {
            case "CD":
                cmd_CD(tokens);
                break;

            case "LIST":
                cmd_LIST(tokens);
                break;

            case "GET":
                cmd_GET(tokens);
                break;

            case "PUT":
                cmd_PUT(tokens);
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
                sendResponse(new SyntaxErrResponse());
                break;
        }
    }

    private String getAbsPath(String path) throws IOException {
        if (Path.of(path).isAbsolute()) {
            return path;
        }

        return new File(currentDir + "/" + path).getCanonicalPath();
    }

    private void sendResponse(Response resp) {
        String respMsg = resp.getResponseMessage();

        System.out.println("Response: " + respMsg);
        ctlWriter.println(respMsg);
    }


    private void cmd_CD(String[] tokens) {
        if (tokens.length == 1) { // no argument
            sendResponse(new MoveSuccessResponse(currentDir));
            return;
        } else if (tokens.length == 2) {
            String tmpDir;
            try {
                tmpDir = getAbsPath(tokens[1]);
            } catch (IOException e) {
                sendResponse(new InvalidDirectoryResponse());
                return;
            }

            File tmpFile = new File(tmpDir);

            if (!tmpFile.exists() || !tmpFile.isDirectory()) {
                sendResponse(new InvalidDirectoryResponse());
                return;
            }

            currentDir = tmpDir;
            sendResponse(new MoveSuccessResponse(currentDir));
            return;
        }

        sendResponse(new SyntaxErrResponse());
    }

    private void cmd_LIST(String[] tokens) {
        if (tokens.length != 2) {
            sendResponse(new SyntaxErrResponse());
            return;
        }

        File tmpFile;
        try {
            tmpFile = new File(getAbsPath(tokens[1]));
        } catch (IOException e) {
            sendResponse(new InvalidDirectoryResponse());
            return;
        }

        if (!tmpFile.exists() || !tmpFile.isDirectory()) {
            sendResponse(new InvalidDirectoryResponse());
            return;
        }

        int count = 0;
        StringBuilder builder = new StringBuilder();
        for (File f : tmpFile.listFiles()) {
            if (builder.length() > 0) {
                builder.append('\n');
            }

            builder.append(f.getName() + ",");
            if (f.isDirectory()) {
                builder.append('-');
            } else {
                builder.append(f.length());
            }

            ++count;
        }
        sendResponse(new ListSuccessResponse(count));

        ctlWriter.println(builder.toString());
    }

    private void cmd_GET(String[] tokens) {
        if (tokens.length != 2) {
            sendResponse(new SyntaxErrResponse());
            return;
        }

        ServerSocket dataChannel = null;
        Socket dataSocket = null;

        try {
            dataChannel = new ServerSocket(dataPort);
            dataSocket = dataChannel.accept();

            String tmpDir = getAbsPath(tokens[1]);
            FileInputStream inp = new FileInputStream(tmpDir);
            File file = new File(tmpDir);

            final long length = file.length();
            sendResponse(new GetSuccessResponse(file));

            SR.Sender sender = new SR.Sender(dataSocket, drop, timeout, biterror);
            sender.send(inp, length);

            drop.clear();
            timeout.clear();
            biterror.clear();
        } catch (FileNotFoundException e) {
            sendResponse(new FileNotFoundResponse());
        } catch (Exception e) {
            sendResponse(new UnknownErrResponse());
        } finally {
            try {
                if (dataSocket != null) dataSocket.close();
                if (dataChannel != null) dataChannel.close();
            } catch (Exception e) {
                sendResponse(new UnknownErrResponse());
            }
        }
    }

    private void cmd_PUT(String[] tokens) {
        if (tokens.length != 2) {
            sendResponse(new SyntaxErrResponse());
            return;
        }

        ServerSocket dataChannel = null;
        Socket dataSocket = null;

        try {
            dataChannel = new ServerSocket(dataPort);
            dataSocket = dataChannel.accept();

            final long totalLength = Long.parseLong(ctlReader.readLine());

            sendResponse(new ReadyToReceiveResponse());

            final String filename = Path.of(tokens[1]).getFileName().toString();

            FileOutputStream oup = new FileOutputStream(currentDir + "/" + filename);

            SR.Receiver receiver = new SR.Receiver(dataSocket);
            receiver.recv(oup, totalLength);

            if (oup != null) oup.close();
        } catch (Exception e) {
            sendResponse(new UnknownErrResponse());
        } finally {
            try {
                if (dataSocket != null) dataSocket.close();
                if (dataChannel != null) dataChannel.close();
            } catch (Exception e) {
                sendResponse(new UnknownErrResponse());
            }
        }
    }

    // DROP, TIMEOUT, BITERROR command must be sent by program.
    // So, we don't need to check syntax error.
    private void cmd_DROP(String[] tokens) {
        for (String token : tokens[1].split(",")) {
            drop.add(Long.parseLong(token));
        }
    }

    private void cmd_TIMEOUT(String[] tokens) {
        for (String token : tokens[1].split(",")) {
            timeout.add(Long.parseLong(token));
        }
    }

    private void cmd_BITERROR(String[] tokens) {
        for (String token : tokens[1].split(",")) {
            biterror.add(Long.parseLong(token));
        }
    }
}

class FTPServer {
    private ServerSocket ctlSocket;
    private boolean isRunning;

    public FTPServer(int ctlPort, int dataPort) throws IOException {
        ctlSocket = new ServerSocket(ctlPort);

        isRunning = true;
        while (isRunning) {
            Socket ctlClientSocket = ctlSocket.accept();

            Connection conn = new Connection(ctlClientSocket, dataPort);

            conn.run();
        }

        if (ctlSocket != null) {
            ctlSocket.close();
        }
    }

    public void stop() {
        isRunning = false;
    }

    public static void main(String[] args) {
        int ctlPort = 2020, dataPort = 2121;

        if (args.length != 0) {
            ctlPort = Integer.parseInt(args[0]);

            if (args.length == 2) {
                dataPort = Integer.parseInt(args[1]);
            } else if (args.length > 2) {
                System.err.println("<ERROR> invalid arguments");
                System.err.println("usage: java FTPServer <control port> <data port>");

                System.exit(-1);
            }
        }

        try {
            new FTPServer(ctlPort, dataPort);
        } catch (Exception e) {
            System.err.println("<EXCEPTION> " + e.toString());
            System.exit(-1);
        }
    }
}
