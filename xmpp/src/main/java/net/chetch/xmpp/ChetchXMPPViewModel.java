package net.chetch.xmpp;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import net.chetch.messaging.Message;
import net.chetch.messaging.MessageFilter;
import net.chetch.messaging.MessageType;
import net.chetch.messaging.filters.CommandResponseFilter;
import net.chetch.utilities.SLog;
import net.chetch.webservices.DataStore;
import net.chetch.webservices.WebserviceViewModel;
import net.chetch.webservices.network.Service;
import net.chetch.webservices.network.ServiceToken;
import net.chetch.webservices.network.Services;
import net.chetch.xmpp.exceptions.ChetchXMPPException;

import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.chat2.Chat;
import org.jivesoftware.smack.chat2.IncomingChatMessageListener;
import org.jivesoftware.smack.chat2.OutgoingChatMessageListener;
import org.jivesoftware.smack.packet.MessageBuilder;
import org.jivesoftware.smack.roster.Roster;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ChetchXMPPViewModel extends WebserviceViewModel implements IChetchConnectionListener, IChetchIncomingMessageListener, IChetchOutgoingMessageListener {

    public static final String CHETCH_XMPP_DOMAIN = "openfire.bb.lan";

    public static final String COMMAND_HELP = "help";
    public static final String COMMAND_ABOUT = "about";
    public static final String COMMAND_VERSION = "version";

    enum ServiceEvent{
        None,  //for initialising
        Connected,
        Disconnecting,
        Stopping,
    }

    //Chetch network service stuff
    String serviceName = null; //NOTE: this is the name as written in the network table of services
    Service chetchXMPPService = null; //The service object as per the network table
    //ServiceToken serviceToken = null; //Used to store data per client for this service e.g. a token

    //XMPP related stuff
    String username = null; //login in to xmpp service with this and password
    String password = null;
    EntityBareJid xmppServiceJid = null; //This is extracted from the service object
    Chat chat = null; //the chat between this client and the service named by serviceName
    ChetchXMPPConnection xmppConnection = null;
    Observer xmppConnectionObserver = null;

    //messaging stuff
    Calendar lastMessageReceivedOn = null;
    Calendar lastMessageSentOn = null;
    long pingInterval = 10000; //ping interval in ms

    //timer stuff
    long timerDelay = 2000; //IN MILLIS!
    Calendar timerStartedOn = null;
    Handler timerHandler = new Handler();
    Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            long nextTimer = onTimer();
            if(nextTimer > 0) {
                timerHandler.postDelayed(this, timerDelay);
            }
        }
    };

    //Message filtering
    List<MessageFilter> messageFilters = new ArrayList<>();

    //region Message Filters
    public MutableLiveData<Map<String,String>> help = new MutableLiveData<>();
    MessageFilter helpResponse = new CommandResponseFilter(null, COMMAND_HELP) {
        @Override
        protected void onMatched(Message message) {
            Map<String, String> helpMap = new HashMap<>();
            for(Map.Entry<String, Object> kv : message.getBody().entrySet()){
                helpMap.put(kv.getKey(), kv.getValue().toString());
            }
            help.postValue(helpMap);
        }
    };

    public MutableLiveData<Object> version = new MutableLiveData<>();
    MessageFilter versionResponse = new CommandResponseFilter(null, COMMAND_HELP) {
        @Override
        protected void onMatched(Message message) {
            version.postValue(message.getValue("Version"));
        }
    };

    public MutableLiveData<Object> about = new MutableLiveData<>();
    MessageFilter aboutResponse = new CommandResponseFilter(null, COMMAND_HELP) {
        @Override
        protected void onMatched(Message message) {
            version.postValue(message.getValue("About"));
        }
    };
    //endregion

    //region Initiailsing
    public void init(Context context){
        try {
            if(pingInterval < timerDelay + 2000){
                throw new ChetchXMPPException("Ping interval must be greater than timer interval plus latency");
            }
            xmppConnection = ChetchXMPPConnection.create(context);

            //filters
            addMessageFilter(helpResponse);
            addMessageFilter(aboutResponse);
            addMessageFilter(versionResponse);
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    public void init(Context context, String username, String password, String serviceName){
        init(context);
        setCredentials(username, password);
        this.serviceName = serviceName;
    }

    public void setCredentials(String username, String password){
        this.username = username;
        this.password = password;
    }
    //endregion

    //region Timer methods
    protected void startTimer(long timerDelay, long postDelay){
        if(timerStartedOn != null)return;
        this.timerDelay = timerDelay;

        timerHandler.postDelayed(timerRunnable, postDelay);
        timerStartedOn = Calendar.getInstance();
    }

    protected void startTimer(long timerDelay){
        startTimer(timerDelay, timerDelay);
    }

    protected void startTimer(){
        startTimer(timerDelay);
    }

    protected void stopTimer(){
        timerHandler.removeCallbacks(timerRunnable);
        timerStartedOn = null;
    }

    protected long onTimer(){
        long nextTimerOn = timerDelay;

        //timer based monitoring of server
        if(xmppConnection != null && !xmppConnection.isConnecting() && !isServiceResponding()){
            setError(new ChetchXMPPException("ChetchXMPPViewModel::onTimer the service " + serviceName + " is not responding"));
            Log.e("ChetchXMPPViewModel", "ChetchXMPPViewModel::onTimer the service " + serviceName + " is not responding");
        }
        //determine if ping reuqired and if so then send
        if(lastMessageSentOn != null){
            long nowInMs = Calendar.getInstance().getTimeInMillis();
            long useTime = (lastMessageReceivedOn == null ? lastMessageSentOn : lastMessageReceivedOn).getTimeInMillis();
            if(nowInMs - useTime > pingInterval){
                try {
                    sendPing();
                    Log.d("ChetchXMPPViewModel", "Sending ping");
                } catch (Exception e){
                    e.printStackTrace();
                }
            }
        }

        //in case we wrongly set the nextTimerOn value
        if(nextTimerOn > pingInterval){
            nextTimerOn = timerDelay;
        }
        return nextTimerOn;
    }
    //endregion

    //region Webservice Viewmodel functionality
    public DataStore loadData(Observer observer) throws Exception {
        //in the case of being called after we've already loaded
        if(isReadyForChat()){
            if(SLog.LOG)SLog.w("ChetchXMPPViewModel", "XMPP connection already established");
        }

        if(xmppConnection == null){
            throw new Exception("Service must have connection object");
        }

        //check we have a client
        if(username == null){
            throw new Exception("Service must have a username");
        }
        if(password == null){
            throw new Exception("Service must have a password");
        }

        if(SLog.LOG)SLog.i("ChetchXMPPViewModel", "Loading data for xmpp user " + username);
        DataStore<?> dataStore = super.loadData(observer);
        dataStore.observe(services-> {
            connectToXMPPServer(observer);
            if(SLog.LOG)SLog.i("ChetchXMPPViewModel", "Loaded services...");
        });
        return dataStore;
    }

    @Override
    protected boolean configureServices(Services services) {
        boolean configured = super.configureServices(services);
        if(configured && services.hasService(serviceName)){
            chetchXMPPService = services.getService(serviceName);
            try {
                //create a bare JID from the endpoint and update message filters
                xmppServiceJid = JidCreate.entityBareFrom(chetchXMPPService.getEndpoint());
                for (MessageFilter mf : messageFilters) {
                    if (xmppServiceJid != null && (mf.Sender == null || mf.Sender.isEmpty())) {
                        mf.Sender = xmppServiceJid.toString();
                    }
                }

            } catch (Exception e){
                setError(e)
;                configured = false;
            }
        }
        return configured;
    }

    public boolean isReady() {
        return super.isReady() && isServiceResponding();
    }

    public boolean isServiceResponding(){
        if(xmppConnection == null || !xmppConnection.isReadyForChat()){
            return false;
        }

        boolean responding = false;
        if(lastMessageReceivedOn != null){
            long lms = lastMessageReceivedOn.getTimeInMillis();
            long ms = Calendar.getInstance().getTimeInMillis() - lms;
            responding = ms <= pingInterval + 2000; //add some time for latency
        }
        return responding;
    }

    public boolean isReadyForChat(){
        return xmppConnection != null && xmppConnection.isReadyForChat();
    }
    //endregion

    //region XMPP connection stuff
    /*
    XMPP connection
     */
    private void connectToXMPPServer(Observer connectionObserver){
        xmppConnectionObserver = connectionObserver;
        try{
            xmppConnection.reset();

            xmppConnection.connect(chetchXMPPService.getLanIP(), xmppServiceJid.getDomain().toString(), this);

        } catch (Exception e){
            if(SLog.LOG)SLog.e("ChetchXMPPViewModel", e.getMessage());
            e.printStackTrace();
            setError(e);
        }
    }

    public ChetchXMPPConnection getConnection(){
        return xmppConnection;
    }

    @Override
    public void connectFailed(Exception e){
        if(SLog.LOG)SLog.e("ChetchXMPPViewModel", e.getMessage());
        e.printStackTrace();
        setError(e);
    }

    @Override
    public void connected(XMPPConnection connection) {

        if(xmppConnectionObserver != null){
            notifyObserver(xmppConnectionObserver, xmppConnection);
        }
        try {
            xmppConnection.login(username, password);
        } catch(Exception e){
            if(SLog.LOG)SLog.e("ChetchXMPPViewModel", e.getMessage());
            e.printStackTrace();
            setError(e);
        }
    }

    @Override
    public void connectionClosedOnError(Exception e){
        if(SLog.LOG)SLog.e("ChetchXMPPViewModel", e.getMessage());
        e.printStackTrace();
        setError(e);
    }

    @Override
    public void authenticationFailed(Exception e){
        if(SLog.LOG)SLog.e("ChetchXMPPViewModel::authenticationFailed", e.getMessage());
        e.printStackTrace();
        setError(e);
    }

    //fial stage of a successful logon process
    @Override
    public void authenticated(XMPPConnection arg0, boolean arg1) {
        if(SLog.LOG)SLog.i("ChetchXMPPViewModel", "Authenticated!");

        //we now check if the service (client) is online
        String chatPartner = chetchXMPPService.getEndpoint();
        try {
            chat = xmppConnection.createChat(chatPartner, this, this);
            //add this person to the roster
            subscribe(); //subscribes to the service (providing it's on of course)
            startTimer(timerDelay);
        } catch(Exception e){
            setError(e);
        }

    }
    //endregion

    //region Sending messages
    public void sendMessage(Message message) throws Exception{
        if(message.Tag == null || message.Tag.isEmpty()){
            message.Tag = "MS:" + Calendar.getInstance().getTimeInMillis();
        }
        xmppConnection.sendMessage(chat, message);
    }

    public void subscribe() throws Exception {
        Message subscribe = new Message();
        subscribe.Type = MessageType.SUBSCRIBE;
        sendMessage(subscribe);
        Log.d("ChetchXMPPViewModel", "Subscribing to service...");
    }

    public void sendPing() throws Exception{
        Message ping = new Message();
        ping.Type = MessageType.PING;
        sendMessage(ping);
    }

    public void sendCommand(String command, List<Object> args) throws Exception{
        if(command == null || command.trim().isEmpty()){
            throw new ChetchXMPPException("ChetchXMPPViewModel::sendCommand command cannot be null or empty");
        }

        Message cmd = new Message();
        cmd.Type = MessageType.COMMAND;
        cmd.addValue("Command", command.toLowerCase().trim());

        List<Object> arguments = new ArrayList<>();
        cmd.addValue("Arguments", arguments);
        sendMessage(cmd);
    }

    public void sendCommand(String commandAndArgs) throws Exception{
        if(commandAndArgs == null || commandAndArgs.trim().isEmpty()){
            throw new ChetchXMPPException("ChetchXMPPViewModel::sendCommand command and args cannot be null or empty");
        }

        List<Object> args = new ArrayList();
        String sanitised = commandAndArgs.toLowerCase().trim();
        String[] split = sanitised.split(" ");
        String cmd = split[0];
        for(int i = 1; i < split.length; i++){
            args.add(split[i]);
        }

        sendCommand(cmd, args);
    }

    @Override
    public void onOutgoingMessage(EntityBareJid from, MessageBuilder builder, Chat chat) {
        lastMessageSentOn = Calendar.getInstance();
    }
    //endregion

    //region Recevving messages
    @Override
    public void onIncomingMessage(EntityBareJid from, @NonNull Message message, org.jivesoftware.smack.packet.Message originalMessage, Chat chat) {
        lastMessageReceivedOn = Calendar.getInstance();
        boolean allowFiltering = false; //set to false if message shouldn't be run against message filters
        switch(message.Type){
            case NOTIFICATION:
                try {
                    ServiceEvent serviceEvent = ServiceEvent.values()[message.SubType];
                    switch (serviceEvent) {
                        case Stopping:
                        case Disconnecting:
                            lastMessageReceivedOn = null;
                            lastMessageSentOn = null;
                            throw new ChetchXMPPException("Service " + serviceName + " is not available");

                        case Connected:
                            break;
                    }
                } catch(ChetchXMPPException ex){
                    setError(ex);
                } catch (Exception e){
                    //not sure about this
                }
                break;

            case SUBSCRIBE_RESPONSE:
                Log.i("ChetchXMPPViewModel", "Subscribe response received!");
                break;

            case PING_RESPONSE:
                Log.i("ChetchXMPPViewModel", "Ping response received!");
                break;

            default:
                allowFiltering = true;
                break;
        }

        //message filters
        if(allowFiltering) {
            for (MessageFilter mf : messageFilters) {
                mf.onMessageReceived(message);
            }
        }
    }

    public void addMessageListener(IChetchIncomingMessageListener listener){
        xmppConnection.addMessageListener(listener);
    }

    public void addMessageFilter(MessageFilter messageFilter){
        if(!messageFilters.contains(messageFilter)){
            if(xmppServiceJid != null && (messageFilter.Sender == null || messageFilter.Sender.isEmpty())){
                messageFilter.Sender = xmppServiceJid.toString();
            }

            messageFilters.add(messageFilter);
        }
    }
    //endregion
}
