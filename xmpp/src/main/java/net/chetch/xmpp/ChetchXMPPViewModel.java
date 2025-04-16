package net.chetch.xmpp;

import android.content.Context;
import android.os.Handler;
import android.telephony.PhoneNumberUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;

import com.google.gson.annotations.SerializedName;

import net.chetch.messaging.Message;
import net.chetch.messaging.MessageFilter;
import net.chetch.messaging.MessageType;
import net.chetch.messaging.filters.CommandResponseFilter;
import net.chetch.messaging.filters.NotificationFilter;
import net.chetch.utilities.SLog;
import net.chetch.utilities.Utils;
import net.chetch.webservices.DataStore;
import net.chetch.webservices.WebserviceViewModel;
import net.chetch.webservices.network.Service;
import net.chetch.webservices.network.ServiceToken;
import net.chetch.webservices.network.Services;
import net.chetch.xmpp.exceptions.ChetchXMPPException;
import net.chetch.xmpp.exceptions.ChetchXMPPViewModelException;

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
import java.util.TreeMap;

public class ChetchXMPPViewModel extends WebserviceViewModel implements IChetchConnectionListener, IChetchIncomingMessageListener, IChetchOutgoingMessageListener {

    //region Constants
    public static final String COMMAND_HELP = "help";
    public static final String COMMAND_ABOUT = "about";
    public static final String COMMAND_VERSION = "version";

    public static final String MESSAGE_FIELD_COMMAND = "Command";
    public static final String MESSAGE_FIELD_ARGUMENTS = "Arguments";
    public static final String MESSAGE_FIELD_SERVICE_EVENT = "ServiceEvent";
    //endregion

    //region Class defs and Enums
    enum ServiceEvent{
        @SerializedName("0")
        None (0),  //for initialising

        @SerializedName("10000")
        Disconnected (10000),

        @SerializedName("10001")
        Connected (10001),

        @SerializedName("10002")
        Disconnecting (10002),

        @SerializedName("10004")
        Stopping (10003),

        @SerializedName("10004")
        StatusUpdate (10004);

        int value;

        ServiceEvent(int val){
            value = val;
        }

        int getValue(){
            return value;
        }
    }

    static public class Status{
        public String ServiceName = null;
        public int StatusCode = 0;
        public String StatusMessage = null;
        public Map<String, Object> StatusDetails;
        public Calendar ServerTime;
        public int ServerTimeOffset = 0;

        public String getSummary(){
            String s = ServiceName == null ? "Unknown Service" : ServiceName;
            s += " (" + StatusCode + ")";
            s += " @ " + Utils.formatDate(ServerTime, "yyyy-MM-dd HH:mm:ss Z");
            return s;
        }

        public String getDetails(String lf){
            StringBuilder builder = new StringBuilder();
            for(Map.Entry<String, Object> entry : StatusDetails.entrySet()){
                builder.append(entry.getKey() + ": " + entry.getValue() + lf);
            }
            return builder.toString();
        }

        public String getDetails(){
            return getDetails("\n");
        }
    }
    //endregion

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

    //live data observables
    public MutableLiveData<Map<String,String>> help = new MutableLiveData<>();
    public MutableLiveData<Status> status = new MutableLiveData<>();
    public MutableLiveData<Object> version = new MutableLiveData<>();
    public MutableLiveData<Object> about = new MutableLiveData<>();

    //Message filtering
    List<MessageFilter> messageFilters = new ArrayList<>();

    //region Message Filters
    MessageFilter helpResponse = new CommandResponseFilter(null, COMMAND_HELP) {
        @Override
        protected void onMatched(Message message) {
            help.postValue(message.getAsClass("Help", TreeMap.class));
        }
    };

    MessageFilter versionResponse = new CommandResponseFilter(null, COMMAND_VERSION) {
        @Override
        protected void onMatched(Message message) {
            version.postValue(message.getString("Version"));
        }
    };

    MessageFilter aboutResponse = new CommandResponseFilter(null, COMMAND_ABOUT) {
        @Override
        protected void onMatched(Message message) {
            version.postValue(message.getString("About"));
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
            String errMsg = "ChetchXMPPViewModel::onTimer the service " + serviceName + " is not responding";
            if(lastMessageReceivedOn == null){
                long nowInMs = Calendar.getInstance().getTimeInMillis();
                long lastSentMs = (lastMessageSentOn != null) ? lastMessageSentOn.getTimeInMillis() : 0;
                if(lastSentMs > 0){
                    errMsg += " ... message sent " + (nowInMs - lastSentMs) + "ms ago but no message yet received";
                } else {
                    errMsg += " ... no message recorded as sent??? ";
                }
            }
            setError(new ChetchXMPPException(errMsg));

            Log.e("ChetchXMPPViewModel", errMsg);
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
            if(SLog.LOG)SLog.i("ChetchXMPPViewModel", "Loaded services...");
            connectToXMPPServer(observer);
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
                setError(e);
                configured = false;
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
            Calendar now = Calendar.getInstance();
            long ms = now.getTimeInMillis() - lms;
            responding = ms <= 1.5*pingInterval; //add some time for latency
            if(!responding){
                Log.e("ChetchXMPPViewModel", "who not responding");
            }
        }
        return responding;
    }

    public boolean isReadyForChat(){
        return xmppConnection != null && xmppConnection.isReadyForChat();
    }

    @Override
    protected void setError(Throwable t) {
        super.setError(t);
    }

    //endregion

    //region XMPP connection stuff
    /*
    XMPP connection
     */
    private void connectToXMPPServer(Observer connectionObserver){
        xmppConnectionObserver = connectionObserver;

        stopTimer();
        lastMessageReceivedOn = null;
        lastMessageSentOn = null;

        try{
            if(xmppConnection != null){
                if(xmppConnection.isReadyForChat()) {
                    if (SLog.LOG) SLog.i("ChetchXMPPViewModel", "::connectToXMPPServer Ready for chat so subscribing and starting timer...");
                    subscribe(); //subscribes to the service (providing it's on of course)
                    startTimer(timerDelay);
                    notifyObserver(connectionObserver, xmppConnection);
                    return;
                } else if(xmppConnection.isConnected() || xmppConnection.isConnecting()) {
                    //we wait
                    if (SLog.LOG) SLog.i("ChetchXMPPViewModel", "::connectToXMPPServer Waiting for connection process to complete...");
                    notifyObserver(connectionObserver, xmppConnection);
                    return;
                }
            }

            if(SLog.LOG)SLog.i("ChetchXMPPViewModel", "Connecting to XMPP server..");
            xmppConnection.reset();
            xmppConnection.connect(chetchXMPPService.getLanIP(), xmppServiceJid.getDomain().toString(), this);
            notifyObserver(connectionObserver, xmppConnection);
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

    //final stage of a successful logon process
    //IMPORTANT:  Do NOT send a message from within this method as it cuases things to hang
    @Override
    public void authenticated(XMPPConnection arg0, boolean arg1) {
        if(SLog.LOG)SLog.i("ChetchXMPPViewModel", "Authenticated!");

        //we now check if the service (client) is online
        String chatPartner = chetchXMPPService.getEndpoint();
        try {
            chat = xmppConnection.createChat(chatPartner, this, this);
            //add this person to the roster
            if(SLog.LOG)SLog.i("ChetchXMPPViewModel", "Authentication successful so subscribing and starting timer with delay " + timerDelay);
            subscribe(); //subscribes to the service (providing it's on of course)
            startTimer(timerDelay);
        } catch(Exception e){
            setError(e);
            e.printStackTrace();
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

    public void requestStatus() throws Exception{
        Message statusRequest = new Message();
        statusRequest.Type = MessageType.STATUS_REQUEST;
        sendMessage(statusRequest);
    }

    public void sendCommand(String commandAndArgs, Object ... args) throws Exception{
        if(commandAndArgs == null || commandAndArgs.trim().isEmpty()){
            throw new ChetchXMPPException("ChetchXMPPViewModel::sendCommand command cannot be null or empty");
        }

        List<Object> argList = new ArrayList<>();
        String[] parts = commandAndArgs.split(" ");
        String command = parts[0].toLowerCase().trim();
        for(int i = 1; i < parts.length; i++){
            if(!parts[i].isEmpty()) {
                argList.add(parts[i].toLowerCase().trim());
            }
        }
        for(Object arg : args){
            if(arg != null) {
                argList.add(arg);
            }
        }
        sendCommand(command, argList);
    }

    private void sendCommand(String command, List<Object> args) throws Exception{
        Message cmd = new Message();
        cmd.Type = MessageType.COMMAND;
        cmd.addValue(MESSAGE_FIELD_COMMAND, command.toLowerCase().trim());

        cmd.addValue(MESSAGE_FIELD_ARGUMENTS, args);
        sendMessage(cmd);
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
                    if(message.hasValue(MESSAGE_FIELD_SERVICE_EVENT)){
                        ServiceEvent serviceEvent = message.getAsClass(MESSAGE_FIELD_SERVICE_EVENT, ServiceEvent.class);
                        switch (serviceEvent) {
                            case Stopping:
                            case Disconnecting:
                                lastMessageReceivedOn = null;
                                lastMessageSentOn = null;
                                throw new ChetchXMPPException("Service " + serviceName + " is not available");

                            case Connected:
                                break;

                            case StatusUpdate:
                                onStatusUpdateReceived(message);
                                break;
                        }
                    } else {
                        allowFiltering = true;
                    }
                } catch(ChetchXMPPException ex){
                    setError(ex);
                } catch (Exception e){
                    //not sure about this
                }
                break;

            case SUBSCRIBE_RESPONSE:
                onSubscribeResponseReceived(message);
                break;

            case PING_RESPONSE:
                Log.i("ChetchXMPPViewModel", "Ping response received!");
                break;

            case STATUS_RESPONSE:
                onStatusUpdateReceived(message);
                Log.i("ChetchXMPPViewModel", "Status response received!");
                break;

            case ERROR:
                onErrorReceived(message);
                Log.e("ChetchXMPPViewModel", "Error! ");
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

    //centtralised and hook subscription received
    protected void onSubscribeResponseReceived(Message message) {
        try {
            requestStatus(); //immediate request status
            Log.i("ChetchXMPPViewModel", "Subscribe response received!");
        } catch (Exception e) {
            Log.e("ChetchXMPPViewModel", e.getMessage());
            e.printStackTrace();
        }
    }

    //centralised and a hook as well as status updates can be from status request resposnses as well as notifictions
    protected void onStatusUpdateReceived(Message message) {
        Status newStatus = message.getAsClass(Status.class);
        status.postValue(newStatus);
    }

    protected void onErrorReceived(Message message){
        ChetchXMPPViewModelException xmppException = new ChetchXMPPViewModelException(message);
        setError(xmppException);
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

    public void addMessageFilters(MessageFilter ... messageFilters){
        for(MessageFilter mf : messageFilters){
            addMessageFilter(mf);
        }
    }
    public List<MessageFilter> getMessageFiltersForMessage(Message message){
        List<MessageFilter> filters = new ArrayList<>();
        for (MessageFilter mf : messageFilters) {
            if(mf.matches(message)){
                filters.add(mf);
            }
        }
        return filters;
    }

    public boolean hasFilterForMessage(Message message){
        return !getMessageFiltersForMessage(message).isEmpty();
    }

    public String getCommandFromMessage(Message message) throws Exception{
        if(message.hasValue(MESSAGE_FIELD_COMMAND)){
            String command = message.getString(MESSAGE_FIELD_COMMAND);
            if(command == null || command.isEmpty()){
                throw new Exception("ChetchXMPPViewModel::getCommandFromMessage command is empty");
            }
            return command.toLowerCase().trim();
        } else {
            throw new Exception("ChetchXMPPViewModel::getCommandFromMessage message does not have a command field");
        }
    }
    //endregion
}
