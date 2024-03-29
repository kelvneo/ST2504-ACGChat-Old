import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;

/*
 * The server that can be run both as a console application or a GUI
 */
public class Server {
    // a unique ID for each connection
    private static int uniqueId;
    // an ArrayList to keep the list of the Client
    private ArrayList<ClientThread> al;
    // if I am in a GUI
    private ServerGUI sg;
    // to display time
    private SimpleDateFormat sdf;
    // the port number to listen for connection
    private int port;
    // the boolean that will be turned of to stop the server
    private boolean keepGoing;
    private X509Certificate certificate;
    private EncryptionUtils.RSAHelper rsaHelper;

    public static final String INFO_MESSAGE = "[INFO]";
    public static final String WARNING_MESSAGE = "[WARN]";
    public static final String ERROR_MESSAGE = "[ERROR]";

    /*
     *  server constructor that receive the port to listen to for connection as parameter
     *  in console
     */
    public Server(int port) {
        this(port, null);
    }

    public Server(int port, ServerGUI sg) {
        // GUI or not
        this.sg = sg;
        // the port
        this.port = port;
        // to display hh:mm:ss
        sdf = new SimpleDateFormat("HH:mm:ss");
        // ArrayList for the Client list
        al = new ArrayList<ClientThread>();
    }

    public void start() {

        display("Loading private key into server...");
        // Load server private key.
        try {
            rsaHelper = new EncryptionUtils.RSAHelper(EncryptionUtils.initPrivateKey());
        } catch (NoSuchPaddingException | NoSuchAlgorithmException | CertificateException | KeyStoreException | UnrecoverableEntryException | IOException e) {
            display(ERROR_MESSAGE, "Cannot load private key into server: " + e);
            e.printStackTrace();
            return;
        }
        display("Loaded private key.");

        display("Loading server certificate...");
        try {
            certificate = EncryptionUtils.loadServerCertificate();
        } catch (CertificateException | IOException e) {
            display(ERROR_MESSAGE, "Cannot load server certificate into server: " + e);
            e.printStackTrace();
            return;
        }
        display("Server certificate loaded");

        //TODO: remove later
        // display("Private Key: " + Base64.getEncoder().encodeToString(privateKey.getEncoded()));

        keepGoing = true;
        /* create socket server and wait for connection requests */
        try {
            // the socket used by the server
            ServerSocket serverSocket = new ServerSocket(port);

            // infinite loop to wait for connections
            while (keepGoing) {
                // format message saying we are waiting
                display("Server waiting for Clients on port " + port + ".");

                Socket socket = serverSocket.accept();    // accept connection
                // if I was asked to stop
                if (!keepGoing)
                    break;

                ClientThread t = new ClientThread(socket);  // make a thread of it
                if (!t.handshakeError) {
                    al.add(t); // save it in the ArrayList
                    t.start();
                }
            }
            // I was asked to stop
            try {
                serverSocket.close();
                for (int i = 0; i < al.size(); ++i) {
                    ClientThread tc = al.get(i);
                    try {
                        tc.sInput.close();
                        tc.sOutput.close();
                        tc.socket.close();
                    } catch (IOException ioE) {
                        // not much I can do
                    }
                }
            } catch (Exception e) {
                display(WARNING_MESSAGE, "Exception closing the server and clients: " + e);
            }
        }
        // something went bad
        catch (IOException e) {
            String msg = sdf.format(new Date()) + " Exception on new ServerSocket: " + e + "\n";
            display(msg);
        }
    }

    /*
     * For the GUI to stop the server
     */
    protected void stop() {
        keepGoing = false;
        // connect to myself as Client to exit statement
        // Socket socket = serverSocket.accept();
        try {
            new Socket("localhost", port);
        } catch (Exception e) {
            // nothing I can really do
        }
    }

    /*
     * Display an event (not a message) to the console or the GUI
     */
    private void display(String type, String msg) {
        String time = sdf.format(new Date()) + " - " + type + " " + msg;
        if (sg == null)
            System.out.println(time);
        else
            sg.appendEvent(time + "\n");
    }

    /*
     * Display an event (not a message) to the console or the GUI
     */
    private void display(String msg) {
        display(INFO_MESSAGE, msg);
    }

    /*
     *  to broadcast a message to all Clients
     */
    private synchronized void broadcast(String message) {
        // add HH:mm:ss and \n to the message
        String time = sdf.format(new Date());
        String messageLf = time + " - " + message + "\n";
        // display message on console or GUI
        if (sg == null)
            System.out.print(messageLf);
        else
            sg.appendRoom(messageLf);     // append in the room window

        // we loop in reverse order in case we would have to remove a Client
        // because it has disconnected
        for (int i = al.size(); --i >= 0; ) {
            ClientThread ct = al.get(i);
            // try to write to the Client if it fails remove it from the list
            if (!ct.writeMsg(messageLf)) {
                al.remove(i);
                display("Disconnected Client " + ct.username + " removed from list.");
            }
        }
    }

    // for a client who logoff using the LOGOUT message
    synchronized void remove(int id) {
        // scan the array list until we found the Id
        for (int i = 0; i < al.size(); ++i) {
            ClientThread ct = al.get(i);
            // found it
            if (ct.id == id) {
                al.remove(i);
                return;
            }
        }
    }

    /*
     *  To run as a console application just open a console window and:
     * > java Server
     * > java Server portNumber
     * If the port number is not specified 1500 is used
     */
    public static void main(String[] args) {
        // start server on port 1500 unless a PortNumber is specified
        int portNumber = 1500;
        switch (args.length) {
            case 1:
                try {
                    portNumber = Integer.parseInt(args[0]);
                } catch (Exception e) {
                    System.out.println("Invalid port number.");
                    System.out.println("Usage is: > java Server [portNumber]");
                    return;
                }
            case 0:
                break;
            default:
                System.out.println("Usage is: > java Server [portNumber]");
                return;

        }
        // create a server object and start it
        Server server = new Server(portNumber);
        server.start();
    }

    /**
     * One instance of this thread will run for each client
     */
    class ClientThread extends Thread {
        // the socket where to listen/talk
        Socket socket;
        ObjectInputStream sInput;
        ObjectOutputStream sOutput;
        // my unique id (easier for disconnection)
        int id;
        // the Username of the Client
        String username;
        // the only type of message a will receive
        ChatMessage cm;
        // the date I connect
        String date;
        public boolean handshakeError = false;
        EncryptionUtils.AESHelper aesHelper;

        // Constructore
        ClientThread(Socket socket) {
            // a unique id
            id = ++uniqueId;
            this.socket = socket;
			/* Creating both Data Stream */
            System.out.println("Thread trying to create Object Input/Output Streams");
            try {
                // create output first
                sOutput = new ObjectOutputStream(socket.getOutputStream());
                sInput = new ObjectInputStream(socket.getInputStream());

                /////////////////////////////////////////////////////
                // START ENCRYPTED CONNECTION HANDSHAKE WITH HELLO //
                /////////////////////////////////////////////////////
                if (!sInput.readObject().equals("HELLO")) {
                    display("Invalid starting handshake");
                    handshakeError = true;
                    close();
                    return;
                }
                //Send "Hello" and Server's Certificate back to client
                sOutput.writeObject("HELLO");
                sOutput.writeObject(certificate);
                //Send "HELLODONE" to tell the client that everything has been sent
                sOutput.writeObject("HELLODONE");

                //////////////////////////////////
                // RECEIVING IV AND SESSION KEY //
                //////////////////////////////////

                // Decrypt the client Initialization Vector to sync with the client.
                byte[] decryptedIV = rsaHelper.decrypt((byte[]) sInput.readObject());
                //Received encrypted key from client
                byte[] encryptedKey = (byte[]) sInput.readObject();
                //Decrypt the client session key for use later
                byte[] decryptedKey = rsaHelper.decrypt(encryptedKey);
                //Store the decrypted key for use later
                aesHelper = new EncryptionUtils.AESHelper(new SecretKeySpec(decryptedKey, "AES"), decryptedIV);

                //////////////////////////////////////////
                // RECEIVING ENCRYPTED DONE FROM CLIENT //
                //////////////////////////////////////////
                byte[] clientEncDone = (byte[]) sInput.readObject();
                //Decrypt the "DONE" message sent by the client using the session key the client sent before
                byte[] clientDecDone = aesHelper.decrypt(clientEncDone);
                if (!new String(clientDecDone).equals("DONE")) {
                    display("Invalid starting handshake");
                    handshakeError = true;
                    close();
                    return;
                }

                ///////////////////////////////////
                // SEND ENCRYPTED DONE TO CLIENT //
                ///////////////////////////////////
                sOutput.writeObject(aesHelper.encrypt("DONE".getBytes()));


                //////////////////////////////////
                // RECEIVE USERNAME FROM CLIENT //
                //////////////////////////////////
                byte[] encUsername = (byte[]) sInput.readObject();
                // Decrypt username
                username = new String(aesHelper.decrypt(encUsername));
                display(username + " (" + socket.getRemoteSocketAddress().toString() + ") just connected.");

            } catch (IOException e) {
                e.printStackTrace();
                display("Exception creating new Input/output Streams: " + e);
                return;
            }
            // have to catch ClassNotFoundException
            // but I read a String, I am sure it will work
            catch (ClassNotFoundException e) {
            } catch (NoSuchPaddingException | NoSuchAlgorithmException | InvalidKeyException | BadPaddingException | IllegalBlockSizeException | InvalidAlgorithmParameterException e) {
                e.printStackTrace();
            }
            date = new Date().toString() + "\n";
        }

        // what will run forever
        public void run() {
            // to loop until LOGOUT
            boolean keepGoing = true;
            while (keepGoing) {
                // read a String (which is an object)
                try {
                    byte[] encText = (byte[]) sInput.readObject();
                    cm = (ChatMessage) Serializer.deserialize(aesHelper.decrypt(encText));
                } catch (IOException | InvalidKeyException | IllegalBlockSizeException | BadPaddingException | InvalidAlgorithmParameterException e) {
                    display(WARNING_MESSAGE, username + " (" + socket.getRemoteSocketAddress().toString() + ") " + " Exception reading Streams: " + e);
                    e.printStackTrace();
                    break;
                } catch (ClassNotFoundException e2) {
                    break;
                }
                // the messaage part of the ChatMessage
                String message = cm.getMessage();

                // Switch on the type of message receive
                switch (cm.getType()) {

                    case ChatMessage.MESSAGE:
                        broadcast(username + ": " + message);
                        break;
                    case ChatMessage.LOGOUT:
                        display(username + " (" + socket.getRemoteSocketAddress().toString() + ") " + " disconnected with a LOGOUT message.");
                        keepGoing = false;
                        break;
                    case ChatMessage.WHOISIN:
                        writeMsg("List of the users connected at " + sdf.format(new Date()) + "\n");
                        // scan al the users connected
                        for (int i = 0; i < al.size(); ++i) {
                            ClientThread ct = al.get(i);
                            writeMsg((i + 1) + ") " + ct.username + " since " + ct.date);
                        }
                        break;
                }
            }
            // remove myself from the arrayList containing the list of the
            // connected Clients
            remove(id);
            close();
        }

        // try to close everything
        private void close() {
            // try to close the connection
            try {
                if (sOutput != null) sOutput.close();
            } catch (Exception e) {
            }
            try {
                if (sInput != null) sInput.close();
            } catch (Exception e) {
            }
            ;
            try {
                if (socket != null) socket.close();
            } catch (Exception e) {
            }
        }

        /*
         * Write a String to the Client output stream
         */
        private boolean writeMsg(String msg) {
            // if Client is still connected send the message to it
            if (!socket.isConnected()) {
                close();
                return false;
            }
            // write the message to the stream
            try {
                sOutput.writeObject(aesHelper.encrypt(msg.getBytes()));
            }
            // if an error occurs, do not abort just inform the user
            catch (IOException | InvalidKeyException | IllegalBlockSizeException | BadPaddingException | InvalidAlgorithmParameterException e) {
                display(ERROR_MESSAGE, "Error sending message to " + username + " (" + socket.getRemoteSocketAddress().toString() + ") ");
                display(e.toString());
                e.printStackTrace();
            }
            return true;
        }
    }
}
