package net.chetch.xmpp;

import android.content.Context;
import android.util.Log;

import androidx.lifecycle.Observer;

import net.chetch.messaging.Message;
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
import org.jivesoftware.smack.packet.MessageBuilder;
import org.jxmpp.jid.EntityBareJid;
import org.jxmpp.jid.impl.JidCreate;

public class ChetchXMPPViewModel extends WebserviceViewModel implements IChetchConnectionListener{

    public static final String CHETCH_XMPP_SERVICE = "Chetch XMPP";
    public static final String CHETCH_XMPP_DOMAIN = "openfire.bb.lan";
    public static final String CHETCH_MESSAGE_SUBJECT = "chetch.message";

    String username = null;
    String password = null;
    Service chetchXMPPService = null;
    ServiceToken serviceToken = null;
    ChetchXMPPConnection xmppConnection = null;
    Observer xmppConnectionObserver = null;

    public void init(Context context){
        try {
            xmppConnection = ChetchXMPPConnection.create(context);
        } catch (Exception e){
            e.printStackTrace();
        }
    }

    public void init(Context context, String username, String password){
        init(context);
        setCredentials(username, password);
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
        if(configured && services.hasService(CHETCH_XMPP_SERVICE)){
            chetchXMPPService = services.getService(CHETCH_XMPP_SERVICE);
        }
        return configured;
    }

    public boolean isReady() {
        return super.isReady() && xmppConnection.isReadyForChat();
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

    @Override
    public void authenticated(XMPPConnection arg0, boolean arg1) {

        if(SLog.LOG)SLog.i("ChetchXMPPViewModel", "Authenticated!");
    }

    //Chetch Messages
    public boolean isChetchMessage(org.jivesoftware.smack.packet.Message message){
        //return message.getType() == org.jivesoftware.smack.packet.Message.Type.normal && CHETCH_MESSAGE_SUBJECT.equals(message.getSubject());
        return true;
    }

    public org.jivesoftware.smack.packet.Message processOutgoingMessage(Message message){
        String messageBody = message.serialize();

        org.jivesoftware.smack.packet.Message xmppMessage = MessageBuilder.buildMessage()
                .ofType(org.jivesoftware.smack.packet.Message.Type.normal)
                .setSubject(CHETCH_MESSAGE_SUBJECT)
                .setBody(messageBody)
                .build();

        return xmppMessage;
    }

    public Message processIncomingMessage(org.jivesoftware.smack.packet.Message incomingMessage){
        if(isChetchMessage(incomingMessage)){
            Message message = Message.deserialize(incomingMessage.getBody());
            return message;
        } else {
            return null;
        }
    }
}