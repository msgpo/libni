package net.ulno.libni.receiver;

import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.yaml.snakeyaml.Yaml;

import java.io.BufferedReader;
import java.io.Reader;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.DatagramChannel;
import java.util.*;

/**
 * Created by ulno on 22.02.16.
 */
public class NetworkMultiplexer extends LibniReceiver implements LibniReceiverListener {
    public static final int MAX_BUFFER_SIZE = 256;
    public static final int BUFFER_HEADER_SIZE=16;
    public static final int MAX_NETWORK_CONTROLLERS=128;
    private Hashtable<NetworkReceiverID,NetworkReceiver> networkControllers = new Hashtable<>();
    private ArrayList<NetworkReceiverID> networkReceiverIDList = new ArrayList<>();
    private Random random = new Random();

    private DatagramChannel channel = null;
    private Thread receiverThread;

    private ByteBuffer messageBuffer = ByteBuffer.allocateDirect(MAX_BUFFER_SIZE);
    private byte messageSaved[] = new byte[MAX_BUFFER_SIZE];
    private int messageSavedSize;

    private DatagramSocket socket = null;
    //private DatagramPacket incoming = new DatagramPacket(messageBuffer, messageBuffer.length);
    private boolean finished = false;
    private MqttClient mqttClient = null;

    public NetworkMultiplexer(Reader reader) {
        // init with contents of an external file
        int port = -1;
        String host;
        String topic;

        Yaml yaml = new Yaml();
        Map<String, String> configMap = (Map<String, String>)  yaml.load(reader);
        if(configMap.containsKey("type")) {
            switch(configMap.get("type")) {
                case "udp":
                    try {
                        port = Integer.valueOf(configMap.get("port"));
                    } catch (Exception e) {
                        port = 19877; // assume default port
                    }
                    initUDP(port);
                    break;
                case "tcp":
                    try {
                        port = Integer.valueOf(configMap.get("port"));
                    } catch (Exception e) {
                        port = 19877; // assume default port
                    }
                    initTCP(port);
                    break;
                case "mqtt":
                    try {
                        port = Integer.valueOf(configMap.get("port"));
                    } catch (Exception e) {
                        port = 1883; // assume default port
                    }
                    try {
                        host = configMap.get("host");
                        topic = configMap.get("topic");
                    } catch (Exception e) {
                        host = null;
                        topic = null;
                        System.out.println("Libni Config File Reader: Problem reading config file: cannnot read host or topic.");
                    }
                    if(host!=null) {
                        initMQTT(host,port,topic);
                    }
                    break;
                default:
                    System.out.println("Libni Config File Reader: Problem reading config file: protocol " + configMap.get("type") + " unknown.");
                    break;
            }
        }
    }

    void initUDP( int port ) {
        if (port > 0) {
            try {
                //socket = new DatagramSocket(port);
                //socket.setSoTimeout(10);
                channel = DatagramChannel.open();
                channel.configureBlocking(false); // TODO: necessary?
                socket = channel.socket();
                socket.bind(new InetSocketAddress(port));
            } catch (Exception e) {
                System.out.println("NetworkReceiver:" + "Can't open socket." + e.toString());
            }


            receiverThread = new Thread() {
                @Override
                public void run() {
                    if (channel != null) {
                        while (!finished) {
                            try {
                                //System.out.println("Trying to receive...");
                                //socket.receive(incoming);
                                messageBuffer.clear();
                                if (channel.receive(messageBuffer) != null) {
                                    messageBuffer.flip();
                                    //System.out.println("Received sth. Length: " + messageBuffer.remaining());
                                    messageSavedSize = messageBuffer.remaining();
                                    if(messageSavedSize <= MAX_BUFFER_SIZE)
                                        messageBuffer.get(messageSaved, 0, messageSavedSize);
                                }
                            } catch (Exception e) {
                                //System.out.println("...receive... failed");
                                //e.printStackTrace(); // might just have timed out
                            }
                            try {
                                Thread.sleep(10); // prevent busy wait and race
                            } catch (InterruptedException e) {


                            }
                        }
                    }

                }
            };
            receiverThread.start();

        }

    }

    void initTCP( int port ) {

    }

    void initMQTT( String host, int port, String topic ) {
        if(host != null) {
            MemoryPersistence persistence = new MemoryPersistence();

            class mqttSubscriber implements MqttCallback {

                @Override
                public void connectionLost(Throwable cause) {

                }

                @Override
                public void messageArrived(String topic, MqttMessage message) throws Exception {
                    byte[] payload = message.getPayload();
                    messageSavedSize = payload.length;
                    for(int i=messageSavedSize-1; i>=0; i--) { // just copy message for asynchrounous main thread
                        messageSaved[i]=payload[i];
                    }
                }

                @Override
                public void deliveryComplete(IMqttDeliveryToken token) {

                }
            };

            try {
                mqttClient = new MqttClient("tcp://"+host+":"+port, MqttClient.generateClientId(), persistence);
                MqttConnectOptions connOpts = new MqttConnectOptions();
                connOpts.setCleanSession(true);
                mqttClient.connect(connOpts);
                mqttClient.setCallback(new mqttSubscriber());
                mqttClient.subscribe(topic);
            } catch(MqttException me) {
                System.out.println("reason "+me.getReasonCode());
                System.out.println("msg "+me.getMessage());
                System.out.println("loc "+me.getLocalizedMessage());
                System.out.println("cause "+me.getCause());
                System.out.println("excep "+me);
                me.printStackTrace();
            }
        }
        // TODO: think about cleanup
    }

    /**
     * This needs to be called from render to prevent threading issues
     */
    NetworkReceiverID evaluateID = new NetworkReceiverID(); // declare here to prevent allocating memory all the time
    public void evaluate() {
        // Look at last received message
        // First analyze header -> and get id from there
        if(messageSavedSize > BUFFER_HEADER_SIZE && messageSaved[0]=='L' && messageSaved[1]=='B' && messageSaved[2] == 'N' && messageSaved[3] == 'I') { // Magic header correct
            long sessionID = messageSaved[8];
            sessionID = (sessionID<<8) + messageSaved[9];
            sessionID = (sessionID<<8) + messageSaved[10];
            sessionID = (sessionID<<8) + messageSaved[11];
            evaluateID.sessionID = sessionID;

            long clientID = messageSaved[12];
            clientID = (clientID<<8) + messageSaved[13];
            clientID = (clientID<<8) + messageSaved[14];
            clientID = (clientID<<8) + messageSaved[15];
            evaluateID.clientID = clientID;

            if(!networkControllers.containsKey(evaluateID)) { // creating new controller
                int index = networkReceiverIDList.size();
                if(  index >= MAX_NETWORK_CONTROLLERS ) { // discard randomly one
                    index = random.nextInt(MAX_NETWORK_CONTROLLERS);
                    // delete element in hashmap
                    networkControllers.get(networkReceiverIDList.get(index)).dispose();
                    networkControllers.remove(networkReceiverIDList.get(index));
                }
                NetworkReceiverID newID = new NetworkReceiverID(sessionID, clientID);
                NetworkReceiver controller = new NetworkReceiver(newID);
                controller.setLibniControllerListener(this);
                if (networkReceiverIDList.size() >= index) {
                    networkReceiverIDList.add(newID);
                } else { // is in there
                    networkReceiverIDList.set(index, newID);
                }
                networkControllers.put(newID, controller);
            }
            networkControllers.get(evaluateID).evaluateNetworkPackage(messageSaved, messageSavedSize);
        }
    }

    @Override
    protected boolean unmappedButtonPressed(int unmappedButtonNr) {
        return getButton(unmappedButtonNr);
    }

    @Override
    protected long unmappedAnalog(int unmappedAnalogNr) {
        return getAnalog(unmappedAnalogNr);
    }

    @Override
    public void dispose() {
        finished = true; //stop thread
        try {
            channel.close();
        } catch (Exception e) {
            //e.printStackTrace();
        }
        //System.out.println("Disconnected");
        super.dispose();
    }

    @Override
    public boolean getButton(int button) {
        for(int i = networkReceiverIDList.size()-1; i>=0; i--) {
            if(networkControllers.get(networkReceiverIDList.get(i)).getButton(button))
                return true;
        }
        return false;
    }

    @Override
    public long getAnalog(int analogNr) {
        long analog=0;
        long analogAbs=0;
        for(int i = networkReceiverIDList.size()-1; i>=0; i--) {
            NetworkReceiver c = networkControllers.get(networkReceiverIDList.get(i));
            long newAnalog = c.getAnalog(analogNr);
            long newAnalogAbs = Math.abs(newAnalog);
            if(newAnalogAbs > analogAbs) {
                analogAbs = newAnalogAbs;
                analog = newAnalog;
            }
        }
        return analog;
    }

    @Override
    public void buttonUpdated(int buttonNr, boolean pressed) {
        // just propagate down even if it might not be true
        if(libniControllerListener != null)
            libniControllerListener.buttonUpdated(buttonNr, pressed);
    }

    @Override
    public void analogUpdated(int analogNr, long value) {
        // just propagate down even if it might not be true
        if(libniControllerListener != null)
            libniControllerListener.analogUpdated(analogNr,value);
    }
}
