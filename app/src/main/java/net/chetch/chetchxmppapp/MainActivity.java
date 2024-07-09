package net.chetch.chetchxmppapp;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import net.chetch.appframework.GenericActivity;
import net.chetch.xmpp.ChetchXMPPConnection;
import android.os.StrictMode;

import org.jivesoftware.smack.ConnectionListener;
import org.jivesoftware.smack.XMPPConnection;

//import org.jivesoftware.smack.ConnectionListener;


public class MainActivity extends GenericActivity {

    static boolean connected = false;
    static boolean suppressConnectionErrors = false;




    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);

        /*int SDK_INT = android.os.Build.VERSION.SDK_INT;
        if (SDK_INT > 8)
        {
            StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder()
                    .permitAll().build();
            StrictMode.setThreadPolicy(policy);
            //your codes here

        }*/

        Button connectButton = findViewById(R.id.btnConnect);
        connectButton.setText("Connecting...");
        connectButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                login();
            }
        });
        connectButton.setEnabled(false);
        connect();

        Log.d("Main", "Created yewwww");
    }

    private void connect(){
        Log.d("Connect", "Connecting");

        ChetchXMPPConnection cnn = ChetchXMPPConnection.getInstance(getApplicationContext());
        try {
            cnn.connect("bb.lan", "openfire.bb.lan", new ConnectionListener() {
                @Override
                public void connected(XMPPConnection connection) {
                    ConnectionListener.super.connected(connection);
                    runOnUiThread(() -> {
                        Button connectButton = findViewById(R.id.btnConnect);
                        connectButton.setEnabled(true);
                        connectButton.setText("Click to Login");
                    });
                }

                @Override
                public void authenticated(XMPPConnection connection, boolean resumed) {
                    ConnectionListener.super.authenticated(connection, resumed);
                    runOnUiThread(() -> {
                        showWarningDialog("Hey you've logged in");
                    });
                }
            });
        } catch(Exception e){
            e.printStackTrace();
        }
    }

    private void login(){
        Log.d("Login", "Authenticating");

        ChetchXMPPConnection cnn = ChetchXMPPConnection.getInstance(getApplicationContext());
        try {
            cnn.login("test", "test");
        } catch(Exception e){
            e.printStackTrace();
        }
    }

}