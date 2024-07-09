package net.chetch.xmpp;

import net.chetch.xmpp.exceptions.ChetchXMPPException;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.NonNull;

import org.jivesoftware.smack.ConnectionConfiguration;
import org.jivesoftware.smack.ReconnectionListener;
import org.jivesoftware.smack.chat2.Chat;
import org.jivesoftware.smack.chat2.ChatManager;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChetchXMPPConnection implements ConnectionListener, ReconnectionListener {

    static private ChetchXMPPConnection instance;
    static public ChetchXMPPConnection getInstance(Context context){
        if(instance == null){
            instance = new ChetchXMPPConnection(context);
        }
        return instance;
    }

    private boolean connecting = false;
    private boolean connected = false;
    private boolean authenticating = false;
    private XMPPTCPConnection connection = null;

    private ChetchXMPPConnection(Context context){
        AndroidSmackInitializer.initialize(context);
    }

    public void reset() throws Exception{
        if (connecting) {
            throw new ChetchXMPPException("ChetchXMPPConnection::disconnect: Connection in progress");
        }

        if (connection != null && connection.isConnected()) {
           throw new Exception("ChetchXMPPConnection::disconnect: Connection in progress");
        }
        connecting = false;
        connection = null;
        authenticating = false;
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

    public void connect(String hostAddress, String xmppDomain, ConnectionListener connectionListener, ReconnectionListener reconnectionListener) throws Exception{
        if(connecting) {
            throw new ChetchXMPPException("ChetchXMPPConnection::connect: Connection in progress");
        }
        if(connection != null){
            throw new ChetchXMPPException("ChetchXMPPConnection::connect: Connection object already exists");
        }

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
        if(connectionListener != null){
            connection.addConnectionListener(connectionListener);
        }
        connection.addConnectionListener(this);

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
            } catch(Exception e){
                e.printStackTrace();
                throw new RuntimeException(e);
            } finally {
                connecting = false;
            }
        }); //end executor.execute
    }

    public void connect(String hostAddress, String xmppDomain) throws Exception{
        connect(hostAddress, xmppDomain, null, null);
    }

    public void connect(String hostAddress, String xmppDomain, ConnectionListener connectionListener) throws Exception{
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

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            try {
                authenticating = true;
                connection.login(username, password);
            } catch (Exception e) {
                e.printStackTrace();
                throw new RuntimeException(e);
            } finally {
                authenticating = false;
            }
        }); //end executor.execute
    }

    /*
    Connection listeer hooks
     */
    @Override
    public void connected(final XMPPConnection connection) {

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
}
