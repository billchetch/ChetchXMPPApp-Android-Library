package net.chetch.xmpp;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.Observer;

import net.chetch.messaging.Message;
import net.chetch.messaging.MessageType;
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
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

public class ChetchXMPPViewModel extends WebserviceViewModel implements IChetchConnectionListener, IChetchIncomingMessageListener, IChetchOutgoingMessageListener {

    public static final String CHETCH_XMPP_SERVICE = "Chetch XMPP";
    public static final String CHETCH_XMPP_DOMAIN = "openfire.bb.lan";

    String username = null;
    String password = null;
    String serviceName = null;
    Service chetchXMPPService = null;
    ServiceToken serviceToken = null;
    Chat chat = null; //the chat between this client and the service named by serviceName
    ChetchXMPPConnection xmppConnection = null;
    Observer xmppConnectionObserver = null;

    Calendar lastMessageReceived = null;
    long pingInterval = 30000; //ping interval in ms

    public void init(Context context){
        try {
            xmppConnection = ChetchXMPPConnection.create(context);
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    public void init(Context context, String username, String password, String serviceName){
        init(context);
        setCredentials(username, password);
        this.serviceName = serviceName;
    }

    public ChetchXMPPConnection getConnection(){
        return xmppConnection;
    }

    public void setCredentials(String username, String password){
        this.username = username;
        this.password = password;
    }

    public DataStore loadData(Observer observer) throws Exception {
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
        if(lastMessageReceived != null){
            long ms = Calendar.getInstance().getTimeInMillis() - lastMessageReceived.getTimeInMillis();
            responding = ms < 2*pingInterval;
        }
        return responding;
    }

    /*
    XMPP connection
     */
    private void connectToXMPPServer(Observer connectionObserver){
        xmppConnectionObserver = connectionObserver;
        try{
            xmppConnection.connect(chetchXMPPService.getLanIP(), CHETCH_XMPP_DOMAIN, this);
        } catch (Exception e){
            if(SLog.LOG)SLog.e("ChetchXMPPViewModel", e.getMessage());
            e.printStackTrace();
            setError(e);
        }
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
        String chatPartner =chetchXMPPService.getEndpoint();
        try {
            chat = xmppConnection.createChat(chatPartner, this, this);
            sendPing();
        } catch(Exception e){
            setError(e);
        }

    }

    public void sendMessage(Message message) throws Exception{
        xmppConnection.sendMessage(chat, message);
    }

    public void sendPing() throws Exception{
        Message ping = new Message();
        ping.Type = MessageType.PING;
        ping.Tag = "MS:" + Calendar.getInstance().getTimeInMillis();
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
    public void onIncomingMessage(EntityBareJid from, Message message, org.jivesoftware.smack.packet.Message originalMessage, Chat chat) {
        lastMessageReceived = Calendar.getInstance();
    }

    @Override
    public void onOutgoingMessage(EntityBareJid from, MessageBuilder builder, Chat chat) {

    }

    public void addMessageListener(IChetchIncomingMessageListener listener){
        xmppConnection.addMessageListener(listener);
    }
}
