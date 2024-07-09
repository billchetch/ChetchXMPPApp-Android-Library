package net.chetch.chetchxmppapp;

import android.content.SharedPreferences;
import android.os.Handler;
import android.preference.PreferenceManager;
import android.util.Log;

import net.chetch.appframework.ChetchApplication;
import net.chetch.utilities.Logger;
import net.chetch.utilities.Utils;
import net.chetch.webservices.network.NetworkRepository;

import java.util.Calendar;

public class XMPPApplication extends ChetchApplication {

    static private final int TIMER_DELAY_IN_MILLIS = 5* Utils.MINUTE_IN_MILLIS;


    Handler timerHandler = new Handler();
    Runnable timerRunnable = new Runnable() {
        @Override
        public void run() {
            onTimer();

            timerHandler.postDelayed(this, TIMER_DELAY_IN_MILLIS);
        }
    };

    private Calendar appStarted;
    private int restartAfter = 0;

    @Override
    public void onCreate() {
        super.onCreate();

        PreferenceManager.setDefaultValues(this, R.xml.preferences, true);
        SharedPreferences sharedPref = PreferenceManager.getDefaultSharedPreferences(this);
        try{
            //String apiBaseURL = sharedPref.getString("api_base_url", null);
            //String apiBaseURL = "http://192.168.2.188:8001/api";
            //String apiBaseURL = "http://192.168.1.106:8001/api";
            //String apiBaseURL = "http://192.168.1.100:8001/api";
            //String apiBaseURL = "http://192.168.4.102:8001/api";
            String apiBaseURL = "http://192.168.2.88:8001/api";
            NetworkRepository.getInstance().setAPIBaseURL(apiBaseURL);

            MainActivity.suppressConnectionErrors = sharedPref.getBoolean("suppress_connection_errors", true);

            //Some kind of bug here if we try to use getInt so yeah getString then parseInt
            restartAfter = Integer.parseInt(sharedPref.getString("restart_after", "12"));

            //fire up timer
            timerHandler.postDelayed(timerRunnable, TIMER_DELAY_IN_MILLIS);
            appStarted = Calendar.getInstance();
        } catch (Exception e){
            Log.e("ERApplication", e.getMessage());
        }
    }

    protected void onTimer(){
        //check how long we've been running for and restart if more than a given time

        if(restartAfter <= 0)return;
        long h = Utils.hoursDiff(Calendar.getInstance(), appStarted);
        if(h >= restartAfter){
            Logger.info("Application has been running for " + h + " hours so restarting");
            restartApp(2);
        }

    }

    public void setRestartAfter(int restartAfter){
        this.restartAfter = restartAfter;
    }

    public long getUpTime(){
        return Calendar.getInstance().getTimeInMillis() - appStarted.getTimeInMillis();
    }
}