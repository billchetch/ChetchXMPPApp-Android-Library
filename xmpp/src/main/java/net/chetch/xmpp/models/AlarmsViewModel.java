package net.chetch.xmpp.models;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.annotations.SerializedName;

import net.chetch.messaging.filters.DataFilter;
import net.chetch.utilities.Utils;
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
import java.util.concurrent.TimeUnit;

public class AlarmsViewModel extends ADMViewModel{

    //region Constants
    public static final String ALARMS_SERVICE_NAME = "Alarms XMPP Service";
    public static final String COMMAND_LIST_ALARMS = "list-alarms";
    public static final String MESSAGE_FIELD_ALARMS_LIST = "Alarms";
    public static final String MESSAGE_FIELD_ALARM = "Alarm";
    public static final String MESSAGE_FIELD_TEST = "Test";
    public static final int REQUEST_ALARMS_LIST_INTERVAL = 30*1000;
    public static final String COMMAND_TEST_ALARM = "test-alarm";
    public static final String COMMAND_TEST_BUZZER = "test-buzzer";
    public static final String COMMAND_TEST_PILOT = "test-pilot";
    public static final String COMMAND_SILENCE = "silence";
    public static final String COMMAND_UNSILENCE = "unsilence";
    public static final String COMMAND_ENABLE_ALARM = "enable";
    public static final String COMMAND_DISABLE_ALARM = "disable";
    public static final int DEFAULT_TEST_DURATION = 5; //in seconds
    public static final int DEFAULT_SILENCE_DURATION = 1*60; //in seconds
    public static final String PILOT_LIGHT_ID = "pilot";
    public static final String BUZZER_ID = "buzzer";
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

        public boolean isDisabled(){
            return State == AlarmState.DISABLED;
        }

        public boolean isConnected() {
            return State != AlarmState.DISCONNECTED;
        }

        public boolean isRaised(){
            return isConnected() && !isDisabled() && State != AlarmState.LOWERED;
        }

        public long getLastRaisedFor(){
            Calendar lr = LastRaised;
            if(lr == null)return -1;

            Calendar ll = LastLowered;
            if(ll == null)ll = Calendar.getInstance();

            return Utils.dateDiff(ll, lr, TimeUnit.SECONDS);
        }

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
    public MutableLiveData<Boolean> pilotLight = new MutableLiveData<>();
    public MutableLiveData<Boolean> buzzer = new MutableLiveData<>();

    //region Message filter
    MessageFilter alarmsListResponseFilter = new CommandResponseFilter(null, COMMAND_LIST_ALARMS) {
        @Override
        protected void onMatched(Message message){
            List<Alarm> alarmsList = message.getList(MESSAGE_FIELD_ALARMS_LIST, Alarm.class);
            alarms.postValue(alarmsList);
        }
    };

    MessageFilter alertFilter = new AlertFilter(null) {
        @Override
        protected void onMatched(Message message) {
            Alarm alarm = message.getAsClass(MESSAGE_FIELD_ALARM, Alarm.class);
            alertedAlarm.postValue(alarm);
        }
    };

    MessageFilter testingFilter = new NotificationFilter(null, MESSAGE_FIELD_TEST) {
        @Override
        protected void onMatched(Message message) {
            currentTest = message.getAsClass(MESSAGE_FIELD_TEST, Test.class);
            test.postValue(currentTest);
        }
    };

    MessageFilter pilotFilter = new DataFilter(null, "ID", PILOT_LIGHT_ID){
        @Override
        protected void onMatched(Message message) {
            pilotOn = message.getBoolean("SwitchPosition");
            pilotLight.postValue(pilotOn);
        }
    };

    MessageFilter buzzerFilter = new DataFilter(null, "ID", PILOT_LIGHT_ID){
        @Override
        protected void onMatched(Message message) {
            boolean buzzerOn = message.getBoolean("SwitchPosition");
            buzzerSilenced = message.getBoolean("Silenced");
            buzzer.postValue(buzzerOn);
        }
    };
    //endregion

    //Fields
    Calendar alarsListRequestLastSent = null;
    boolean buzzerOn = false;
    boolean buzzerSilenced = false;
    boolean pilotOn = false;
    Test currentTest = Test.NONE;

    //region Initialise stuf
    @Override
    public void init(Context context, String username, String password) {
        serviceName = ALARMS_SERVICE_NAME;
        super.init(context, username, password);

        addMessageFilter(alarmsListResponseFilter);
        addMessageFilter(alertFilter);
        addMessageFilter(testingFilter);
        addMessageFilter(pilotFilter);
        addMessageFilter(buzzerFilter);
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
    public boolean isBuzzerSilenced(){ return buzzerSilenced; }
    public boolean isPilotOn(){ return pilotOn; }
    public boolean isTesting(){ return currentTest != Test.NONE; }

    public void testAlarm(String alarmID, int duration) throws Exception{
        sendCommand(COMMAND_TEST_ALARM, alarmID, duration);
    }

    public void testAlarm(String alarmID) throws Exception{
        sendCommand(COMMAND_TEST_ALARM, alarmID, DEFAULT_TEST_DURATION);
    }

    public void testBuzzer(int duration) throws Exception{
        sendCommand(COMMAND_TEST_BUZZER, duration);
    }

    public void testBuzzer() throws Exception{
        testBuzzer(DEFAULT_TEST_DURATION);
    }

    public void testPilot(int duration) throws Exception{
        sendCommand(COMMAND_TEST_PILOT, duration);
    }

    public void testPilot() throws Exception{
        testPilot(DEFAULT_TEST_DURATION);
    }

    public void silence(int duration) throws Exception{
        sendCommand(COMMAND_SILENCE, duration);
    }

    public void unsilence() throws Exception{
        sendCommand(COMMAND_UNSILENCE);
    }

    public void enableAlarm(String alarmID) throws Exception{
        sendCommand(COMMAND_ENABLE_ALARM, alarmID);
    }

    public void disableAlarm(String alarmID) throws Exception{
        sendCommand(COMMAND_DISABLE_ALARM, alarmID);
    }
    //endregion
}
