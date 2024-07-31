package net.chetch.chetchxmppapp;

import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;

import net.chetch.appframework.GenericActivity;
import net.chetch.appframework.NotificationBar;
import net.chetch.messaging.Message;
import net.chetch.messaging.MessageType;
import net.chetch.utilities.CalendarTypeAdapater;
import net.chetch.utilities.EnumTypeAdapater;
import net.chetch.utilities.Logger;
import net.chetch.utilities.SLog;
import net.chetch.webservices.ConnectManager;
import net.chetch.webservices.WebserviceViewModel;
import net.chetch.xmpp.ChetchXMPPConnection;
import net.chetch.xmpp.ChetchXMPPViewModel;
import net.chetch.xmpp.models.GPSViewModel;
import net.chetch.xmpp.models.ADMViewModel;
import net.chetch.xmpp.models.AlarmsViewModel;

import android.widget.EditText;
import android.widget.TextView;

import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;
import com.google.gson.ToNumberStrategy;
import com.google.gson.stream.JsonReader;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

//import org.jivesoftware.smack.ConnectionListener;


public class MainActivity extends GenericActivity implements NotificationBar.INotifiable{

    static boolean connected = false;
    static boolean suppressConnectionErrors = false;
    static ConnectManager connectManager = new ConnectManager();

    Observer connectProgress  = obj -> {
        showProgress();

        if(obj instanceof WebserviceViewModel.LoadProgress) {
            WebserviceViewModel.LoadProgress progress = (WebserviceViewModel.LoadProgress) obj;
            try {
                String state = progress.startedLoading ? "Loading" : "Loaded";
                String progressInfo = state + (progress.info == null ? "" : " " + progress.info.toLowerCase());
                /*if(progress.dataLoaded != null){
                    progressInfo += " - " + progress.dataLoaded.getClass().toString();
                }*/
                setProgressInfo(progressInfo);
                Log.i("Main", "in load data progress ..." + progressInfo);

            } catch (Exception e) {
                Log.e("Main", "load progress: " + e.getMessage());
            }
        }else if(obj instanceof ChetchXMPPConnection){
            ChetchXMPPConnection cnn = (ChetchXMPPConnection)obj;
            runOnUiThread(() -> {
                if(cnn.isReadyForChat()) {
                    setProgressInfo("XMPP server authenticated!");
                } else {
                    setProgressInfo("XMPP server authenticating...");
                }
            });
        } else if(obj instanceof ConnectManager) {
            ConnectManager cm = (ConnectManager) obj;
            switch (cm.getState()) {
                case CONNECT_REQUEST:
                    if (cm.fromError()) {
                        setProgressInfo("There was an error ... retrying...");
                    } else {
                        setProgressInfo("Connecting...");
                    }
                    break;

                case RECONNECT_REQUEST:
                    setProgressInfo("Disconnected!... Attempting to reconnect...");
                    break;

                case CONNECTED:
                    hideProgress();
                    startChat();

                    Log.i("Main", "All connections made");
                    break;
            }
        }
    };

    class Test{
        Calendar LD;
    }
    //GPSViewModel model;
    //ADMViewModel model;
    AlarmsViewModel model;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        includeActionBar(SettingsActivity.class);

        if(!connectManager.isConnected()) {
            //Get models
            Logger.info("Main activity setting up model callbacks ...");

            model = new ViewModelProvider(this, new ViewModelProvider.AndroidViewModelFactory(getApplication())).get(AlarmsViewModel.class);
            model.getError().observe(this, throwable -> {
                SLog.e("Main", throwable.getMessage());
            });

            try {
                Logger.info("Main activity sstting xmpp credentials, adding models and requesting connect ...");
                model.init(getApplicationContext(),
                        "mactest",
                        "mactest");
                connectManager.addModel(model);
                connectManager.setPermissableServerTimeDifference(5 * 60);
                connectManager.requestConnect(connectProgress);

                NotificationBar.setView(findViewById(R.id.notificationbar), 100);
                NotificationBar.monitor(this, connectManager, "connection");
            } catch (Exception e) {
                showError(e);
            }
        } else {
            hideProgress();
            NotificationBar.hide();
        }

        Log.d("Main", "Created yewwww");
    }

    String lastCommandAndArgs = "";
    private void startChat(){
        try {
            TextView messages = findViewById(R.id.messages);
            TextView alertArea = findViewById(R.id.alertArea);
            View chatWindow = findViewById(R.id.chat);
            chatWindow.setVisibility(View.VISIBLE);
            Button sendBtn = findViewById(R.id.sendButton);
            EditText compose = findViewById(R.id.compose);
            sendBtn.setOnClickListener(view -> {
                try {
                    String messageBody = compose.getText().toString().trim();
                    if(!messageBody.isEmpty()) {
                        Message message = new Message();
                        message.Type = MessageType.INFO;
                        message.addValue("Comment", messageBody);
                        message.addValue("Count", 24);
                        model.sendMessage(message);
                        compose.setText("");
                        String messagesSoFar = messages.getText().toString();
                        messages.setText(messagesSoFar + "\n---> " + messageBody);
                    }
                } catch (Exception e){
                    e.printStackTrace();
                }
            });

            Button pingBtn = findViewById(R.id.sendPingButton);
            pingBtn.setOnClickListener(view -> {
                try{
                    model.sendPing();
                    compose.setText("");
                } catch(Exception e){
                    e.printStackTrace();
                }
            });

            Button commandBtn = findViewById(R.id.sendCommandButton);
            commandBtn.setOnClickListener(view -> {
                try{
                    String commandAndArgs = compose.getText().toString().trim().toLowerCase();
                    if(commandAndArgs.isEmpty() && !lastCommandAndArgs.isEmpty()){
                        compose.setText(lastCommandAndArgs);
                    } else {
                        model.sendCommand(commandAndArgs);
                        lastCommandAndArgs = commandAndArgs;
                        compose.setText("");
                    }
                } catch(Exception e){
                    e.printStackTrace();
                }
            });

            Button errorBtn = findViewById(R.id.sendErrorTestButton);
            errorBtn.setOnClickListener(view -> {
                try{
                    //compose.setText("");
                } catch(Exception e){
                    e.printStackTrace();
                }
            });

            //finally add a message listener
            model.addMessageListener((from, message, originalMessage, chat)->{
                if(message.Type != MessageType.COMMAND_RESPONSE) {
                    messages.setText(message.toString());
                }
            });

            model.help.observe(this, h ->{
                String lf = System.lineSeparator();
                String s = "HELP:" + lf + lf;

                for(Map.Entry<String, String> entry : h.entrySet()){
                    s += entry.getKey() + ": " + entry.getValue() + lf;
                }
                messages.setText(s);
            });

            model.version.observe(this, v->{
                messages.setText("Version: " + v);
            });

            model.about.observe(this, v->{
                messages.setText("About: " + v);
            });

            model.status.observe(this, s->{
                alertArea.setText("Status..." + s.toString());
            });

            model.alertedAlarm.observe(this, alarm->{
                //alertArea.setText(alarm.ID + " - " + alarm.State + " - " + alarm.Message);
            });

            model.test.observe(this, test ->{
                alertArea.setText("Test " + test + " is doing it's thing");
            });

        } catch(Exception e){
            e.printStackTrace();
        }
    }

    @Override
    public void handleNotification(Object notifier, String tag, Object data) {
        if(notifier instanceof ConnectManager){
            ConnectManager cm = (ConnectManager)notifier;
            switch(cm.getState()){
                case CONNECTED:
                    NotificationBar.show(NotificationBar.NotificationType.INFO, "Connected and ready to use.", null,5);
                    break;

                case ERROR:
                    NotificationBar.show(NotificationBar.NotificationType.ERROR, "Service unavailable.");
                    break;

                case RECONNECT_REQUEST:
                    NotificationBar.show(NotificationBar.NotificationType.WARNING, "Attempting to reconnect...");
                    break;
            }
        }
    }
}