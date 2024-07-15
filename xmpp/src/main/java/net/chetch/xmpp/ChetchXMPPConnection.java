package net.chetch.xmpp;

import net.chetch.messaging.MessageType;
import net.chetch.xmpp.exceptions.ChetchXMPPException;

import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ReconnectionListener;
import org.jivesoftware.smack.chat2.Chat;
import org.jivesoftware.smack.chat2.ChatManager;
import org.jivesoftware.smack.chat2.IncomingChatMessageListener;
import org.jivesoftware.smack.chat2.OutgoingChatMessageListener;
import org.jivesoftware.smack.packet.Message;
import org.jivesoftware.smack.packet.MessageBuilder;
import org.jivesoftware.smack.tcp.XMPPTCPConnection;
import org.jivesoftware.smack.tcp.XMPPTCPConnectionConfiguration;
import org.jivesoftware.smack.android.AndroidSmackInitializer;

import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.ReconnectionManager;
import org.jivesoftware.smack.SmackException;
import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.XMPPException;
import org.jxmpp.jid.BareJid;
import org.jxmpp.jid.DomainBareJid;
import org.jxmpp.jid.DomainFullJid;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.EntityFullJid;
import org.jxmpp.jid.EntityJid;
import org.jxmpp.jid.FullJid;
import org.jxmpp.jid.Jid;
import org.jxmpp.jid.impl.JidCreate;
import org.jxmpp.jid.parts.Domainpart;
import org.jxmpp.jid.parts.Localpart;
import org.jxmpp.jid.parts.Resourcepart;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChetchXMPPConnection implements IChetchConnectionListener, ReconnectionListener, IncomingChatMessageListener, OutgoingChatMessageListener {
    public static final String CHETCH_MESSAGE_SUBJECT = "chetch.message";

    static private boolean initialised = false;
    static public void initialise(Context context){
        AndroidSmackInitializer.initialize(context);
        initialised = true;
    }

    static public ChetchXMPPConnection create(Context context) throws Exception{
        initialise(context);
        return create();
    }

    static public ChetchXMPPConnection create() throws Exception{
        if(!initialised){
            throw new ChetchXMPPException("ChetchXMPPConnection::create cannot create connection as not yet initialised");
        }

        return new ChetchXMPPConnection();
    }

    private class ChatData{
        Chat chat = null;
        int messagesSent = 0;
        int messagesReceived = 0;

        ChatData(Chat chat){
            this.chat = chat;
        }
    }

    private boolean connecting = false;
    private boolean connected = false;
    private boolean authenticating = false;
    private XMPPTCPConnection connection = null;
    IChetchConnectionListener connectionListener = null;
    private ChatManager chatManager = null;
    final private Map<EntityBareJid, ChatData> chats = new HashMap<>();
    final private List<IChetchIncomingMessageListener> incomingMessageListeners = new ArrayList<>();
    final private List<IChetchOutgoingMessageListener> outgoingMessageListeners = new ArrayList<>();

    private ChetchXMPPConnection(){

    }

    private void reset() throws Exception{
        if (connecting) {
            throw new ChetchXMPPException("ChetchXMPPConnection::disconnect: Connection in progress");
        }

        if (connection != null && connection.isConnected()) {
           throw new Exception("ChetchXMPPConnection::disconnect: Connection in progress");
        }
        connecting = false;
        connection = null;
        connectionListener = null;
        authenticating = false;
        chatManager = null;
        chats.clear();
        incomingMessageListeners.clear();
        outgoingMessageListeners.clear();
    }

    public void disconnect() throws Exception{
        if(connecting) {
            throw new ChetchXMPPException("ChetchXMPPConnection::disconnect: Connection in progress");
        }

        if(connection != null && connection.isConnected()){
            connection.disconnect();
        }
        reset();
    }

    public void connect(String hostAddress, String xmppDomain, IChetchConnectionListener connectionListener, ReconnectionListener reconnectionListener) throws Exception{
        if(connecting) {
            throw new ChetchXMPPException("ChetchXMPPConnection::connect: Connection in progress");
        }
        if(connection != null && connection.isConnected()){
           return;
        }

        //configure the connection
        XMPPTCPConnectionConfiguration conf = null;
        try {
            conf = XMPPTCPConnectionConfiguration.builder()
                    .setSecurityMode(ConnectionConfiguration.SecurityMode.disabled)
                    .setXmppDomain(xmppDomain)
                    .setHost(hostAddress)
                    //.setUsernameAndPassword("test", "test")
                    .setConnectTimeout(100000)
                    .build();
        } catch (Exception e){
            e.printStackTrace();
            throw e;
        }
        XMPPTCPConnection.setUseStreamManagementDefault(true);
        connection = new XMPPTCPConnection(conf);
        connection.setReplyTimeout(5000);
        connection.addConnectionListener(this);
        this.connectionListener = connectionListener;
        if(connectionListener != null){
            connection.addConnectionListener(connectionListener);
        }

        //now execute connection
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try{
                connecting = true;
                connection.connect();

                ReconnectionManager reconnectionManager = ReconnectionManager.getInstanceFor(connection);
                reconnectionManager.setEnabledPerDefault(false);
                reconnectionManager.enableAutomaticReconnection();
                reconnectionManager.addReconnectionListener(this);
                if(reconnectionListener != null){
                    reconnectionManager.addReconnectionListener(reconnectionListener);
                }
                /*PingManager pingManager =
                PingManager.getInstanceFor(connection);
                pingManager.setPingInterval(300);*/

                chatManager = ChatManager.getInstanceFor(connection);
            } catch(Exception e){
                e.printStackTrace();
                connectFailed(e);
                if(connectionListener != null){
                    connectionListener.connectFailed(e);
                }
            } finally {
                connecting = false;
            }

            //store a chatmanager for this connection

        }); //end executor.execute
    }

    public void connect(String hostAddress, String xmppDomain) throws Exception{
        connect(hostAddress, xmppDomain, null, null);
    }

    public void connect(String hostAddress, String xmppDomain, IChetchConnectionListener connectionListener) throws Exception{
        connect(hostAddress, xmppDomain, connectionListener, null);
    }

    public void login(String username, String password) throws Exception{
        if(connecting) {
            throw new ChetchXMPPException("ChetchXMPPConnection::login: Connection in progress");
        }
        if(connection == null){
            throw new ChetchXMPPException("ChetchXMPPConnection::login: Connection object does not exist");
        }
        if(!connection.isConnected()){
            throw new ChetchXMPPException("ChetchXMPPConnection::login: not connected");
        }
        if(connection.isAuthenticated()){
            return;
        }

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                authenticating = true;
                connection.login(username, password);
            } catch (Exception e) {
                e.printStackTrace();
                authenticationFailed(e);
                if(connectionListener != null){
                    connectionListener.authenticationFailed(e);
                }
            } finally {
                authenticating = false;
            }
        }); //end executor.execute
    }

    /*
    Connection listeer hooks
     */
    @Override
    public void connectFailed(Exception arg0){
        try {
            connecting = false;
            reset();
        } catch (Exception e){
            e.printStackTrace();
        }
        Log.e("xmpp", "Connect failed: " + arg0.getMessage());
    }

    @Override
    public void connected(final XMPPConnection connection) {
        connecting = false;
        Log.d("xmpp", "Connected!");

    }

    @Override
    public void connectionClosed() {
        Log.d("xmpp", "ConnectionCLosed!");
        try {
            reset();
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    @Override
    public void connectionClosedOnError(Exception arg0) {
        Log.e("xmpp", "ConnectionClosedOn Error!");
        try {
            reset();
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    @Override
    public void authenticationFailed(Exception arg0) {
        authenticating = false;
        Log.e("xmpp", "Authentication failuer!: " + arg0.getMessage());
    }

    @Override
    public void authenticated(XMPPConnection arg0, boolean arg1) {
        Log.d("xmpp", "Authenticated!");
    }

    /*
    Reconnction listeer hooks
     */

    @Override
    public void reconnectingIn(int arg0) {

        Log.d("xmpp", "Reconnectingin " + arg0);
    }

    @Override
    public void reconnectionFailed(Exception arg0) {
        Log.e("xmpp", "ReconnectionFailed!");
    }

    /*
    Messaging
    */
    public boolean isReadyForChat(){
        return connection != null && connection.isConnected() && connection.isAuthenticated();
    }

    private String sanitizeEntityID(String entityID){
        DomainBareJid domain = connection.getXMPPServiceDomain();
        if(!entityID.contains("@" + domain)){
            entityID += "@" + domain.getDomain();
        }
        return entityID;
    }

    public Chat createChat(String entityID, IChetchIncomingMessageListener incomingListener, IChetchOutgoingMessageListener outgoingListener) throws Exception{
        if(connection == null){
            throw new ChetchXMPPException("ChetchXMPPConnection::createChat no connection object");
        }

        //santisie the entityID
        EntityBareJid jid = JidCreate.entityBareFrom(sanitizeEntityID(entityID));

        //Do some checking
        if(chats.containsKey(jid)){
            throw new ChetchXMPPException("ChetchXMPPConnection::createChat A chat already exists for " + entityID);
        }
        if(chatManager == null){
            throw new ChetchXMPPException("ChetchXMPPConnection::createChat no chat manager exists");
        }

        //create the chat and add listeners
        Chat chat = chatManager.chatWith(jid);
        ChatData chatData = new ChatData(chat);
        chats.put(jid, chatData);

        //general xmpp listeners (required to then call listeners for chetch messages)
        chatManager.addIncomingListener(this);
        chatManager.addOutgoingListener(this);

        //chetch message listeners
        addMessageListener(incomingListener);
        addMessageListener(outgoingListener);

        return chat;
    }

    public Chat createChat(String entityID, IChetchIncomingMessageListener incomingListener) throws Exception{
        return createChat(entityID, incomingListener, null);
    }

    public Chat createChat(String entityID) throws Exception{
        return createChat(entityID, null, null);
    }

    public boolean isChetchMessage(org.jivesoftware.smack.packet.Message message){
        return message.getType() == org.jivesoftware.smack.packet.Message.Type.normal && CHETCH_MESSAGE_SUBJECT.equals(message.getSubject());
        //return false;
    }

    public void addMessageListener(IChetchIncomingMessageListener listener){
        if(listener != null && !incomingMessageListeners.contains(listener)){
            incomingMessageListeners.add(listener);
        }
    }

    public void addMessageListener(IChetchOutgoingMessageListener listener){
        if(listener != null && !outgoingMessageListeners.contains(listener)){
            outgoingMessageListeners.add(listener);
        }
    }

    @Override
    public void newIncomingMessage(EntityBareJid from, org.jivesoftware.smack.packet.Message message, Chat chat) {
        ChatData chatData = chats.get(from);
        chatData.messagesReceived++;

        if(isChetchMessage(message)) {
            try {
                net.chetch.messaging.Message chetchMessage = net.chetch.messaging.Message.deserialize(message.getBody());
                for (IChetchIncomingMessageListener listener : incomingMessageListeners) {
                    listener.onIncomingMessage(from, chetchMessage, message, chat);
                }
            } catch (Exception e){
                e.printStackTrace();
            }

        }
    }

    @Override
    public void newOutgoingMessage(EntityBareJid to, MessageBuilder messageBuilder, Chat chat) {
        ChatData chatData = chats.get(to);
        chatData.messagesSent++;

        for(IChetchOutgoingMessageListener listener : outgoingMessageListeners){
            listener.onOutgoingMessage(to, messageBuilder, chat);
        }
    }

    public Message sendMessage(Chat chat, net.chetch.messaging.Message chetchMessage) throws Exception{
        //Add this c
        if(chetchMessage.Sender == null || chetchMessage.Sender.trim().isEmpty()){
            chetchMessage.Sender = connection.getUser().toString();
        }

        String messageBody = chetchMessage.serialize();

        Message xmppMessage = MessageBuilder.buildMessage()
                .ofType(org.jivesoftware.smack.packet.Message.Type.normal)
                .setSubject(CHETCH_MESSAGE_SUBJECT)
                .setBody(messageBody)
                .build();

        chat.send(xmppMessage);
        return xmppMessage;
    }
}
