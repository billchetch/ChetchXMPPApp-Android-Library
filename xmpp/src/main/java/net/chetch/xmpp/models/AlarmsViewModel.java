package net.chetch.xmpp.models;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.annotations.SerializedName;

import net.chetch.messaging.Message;
import net.chetch.messaging.MessageFilter;
import net.chetch.messaging.filters.AlertFilter;
import net.chetch.messaging.filters.CommandResponseFilter;
import net.chetch.messaging.filters.NotificationFilter;

import org.jivesoftware.smack.XMPPConnection;
import org.jivesoftware.smack.chat2.Chat;
import org.jxmpp.jid.EntityBareJid;

import java.util.Calendar;
import java.util.List;
import java.util.Map;

public class AlarmsViewModel extends ADMViewModel{

    //region Constants
    public static final String ALARMS_SERVICE_NAME = "Alarms XMPP Service";
    public static final String COMMAND_LIST_ALARMS = "list-alarms";
    public static final String MESSAGE_FIELD_ALARMS_LIST = "Alarms";
    public static final String MESSAGE_FIELD_ALARM = "Alarm";
    public static final String MESSAGE_FIELD_BUZZER_ON = "Buzzer";
    public static final String MESSAGE_FIELD_PILOT_ON = "Pilot";
    public static final String MESSAGE_FIELD_TEST = "Test";
    public static final int REQUEST_ALARMS_LIST_INTERVAL = 30*1000;
    public static final String COMMAND_TEST_ALARM = "test-alarm";
    public static final String COMMAND_TEST_BUZZER = "test-buzzer";
    public static final String COMMAND_TEST_PILOT = "test-pilot";
    public static final String COMMAND_SILENCE = "silence";
    public static final String COMMAND_UNSILENCE = "unsilence";
    public static final int DEFAULT_TEST_DURATION = 5; //in seconds
    public static final int DEFAULT_SILENCE_DURATION = 1*60; //in seconds
    //endregion

    //region Class and enums
    public enum AlarmState
    {
        @SerializedName("0")
        DISABLED,
        @SerializedName("1")
        DISCONNECTED,
        @SerializedName("2")
        LOWERED,
        @SerializedName("3")
        MINOR,
        @SerializedName("4")
        MODERATE,
        @SerializedName("5")
        SEVERE,
        @SerializedName("6")
        CRITICAL,
    }

    static public class Alarm{
        public String ID;
        public AlarmState State;
        public String Name;
        public String Message;
        public Calendar LastRaised;
        public Calendar LastLowered;
        public Calendar LastLDisabled;
        boolean Testing;

        public Alarm(){}
    }

    public enum Test{
        @SerializedName("0")
        NONE,
        @SerializedName("1")
        ALARM,
        @SerializedName("2")
        BUZZER,
        @SerializedName("3")
        PILOT,
    }

    //endregion

    //live data observables
    public MutableLiveData<Alarm> alertedAlarm = new MutableLiveData<>();
    public MutableLiveData<List<Alarm>> alarms = new MutableLiveData<>();
    public MutableLiveData<Test> test = new MutableLiveData<>();

    //region Message filter
    MessageFilter alarmsListResponse = new CommandResponseFilter(null, COMMAND_LIST_ALARMS) {
        @Override
        protected void onMatched(Message message){
            List<Alarm> alarmsList = message.getList(MESSAGE_FIELD_ALARMS_LIST, Alarm.class);
            alarms.postValue(alarmsList);
        }
    };

    MessageFilter alert = new AlertFilter(null) {
        @Override
        protected void onMatched(Message message) {
            Alarm alarm = message.getAsClass(MESSAGE_FIELD_ALARM, Alarm.class);
            buzzerOn = message.getBoolean(MESSAGE_FIELD_BUZZER_ON);
            pilotOn = message.getBoolean(MESSAGE_FIELD_PILOT_ON);
            alertedAlarm.postValue(alarm);
        }
    };

    MessageFilter testing = new NotificationFilter(null, MESSAGE_FIELD_TEST) {
        @Override
        protected void onMatched(Message message) {
            currentTest = message.getAsClass(MESSAGE_FIELD_TEST, Test.class);
            test.postValue(currentTest);
        }
    };
    //endregion

    //Fields
    Calendar alarsListRequestLastSent = null;
    boolean buzzerOn = false;
    boolean pilotOn = false;
    Test currentTest = Test.NONE;

    //region Initialise stuf
    @Override
    public void init(Context context, String username, String password) {
        serviceName = ALARMS_SERVICE_NAME;
        super.init(context, username, password);

        addMessageFilter(alarmsListResponse);
        addMessageFilter(alert);
        addMessageFilter(testing);
    }
    //endregion


    @Override
    protected long onTimer() {
        if(alarsListRequestLastSent != null && Calendar.getInstance().getTimeInMillis() - alarsListRequestLastSent.getTimeInMillis() > REQUEST_ALARMS_LIST_INTERVAL){
            requestAlarmsList();
        }

        return super.onTimer();
    }

    @Override
    public void authenticated(XMPPConnection arg0, boolean arg1) {
        super.authenticated(arg0, arg1);

        requestAlarmsList();
    }


    //region Sending messages and commands etc.
    public void requestAlarmsList(){
        try {
            sendCommand(COMMAND_LIST_ALARMS);
            alarsListRequestLastSent = Calendar.getInstance();
        } catch (Exception e){
            setError(e);
            e.printStackTrace();
        }
    }

    public boolean isBuzzerOn(){ return buzzerOn; }
    public boolean isPilotOn(){ return pilotOn; }
    public boolean isTesting(){ return currentTest != Test.NONE; }

    public void testAlarm(String alarmID, int duration) throws Exception{
        sendCommand(COMMAND_TEST_ALARM, alarmID, duration);
    }

    public void testBuzzer(int duration) throws Exception{
        sendCommand(COMMAND_TEST_BUZZER, duration);
    }

    public void testPilot(int duration) throws Exception{
        sendCommand(COMMAND_TEST_PILOT, duration);
    }

    public void silence(int duration) throws Exception{
        sendCommand(COMMAND_SILENCE, duration);
    }

    public void unsilence() throws Exception{
        sendCommand(COMMAND_UNSILENCE);
    }
    //endregion
}
